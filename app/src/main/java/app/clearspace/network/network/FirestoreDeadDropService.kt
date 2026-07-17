package app.clearspace.network.network

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import app.clearspace.network.storage.db.entity.ContentUnit
import app.clearspace.network.storage.db.entity.Visibility
import app.clearspace.network.crypto.GroupEncryption
import app.clearspace.network.storage.db.AetherDatabase
import app.clearspace.network.storage.db.entity.TrustLevel
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.tasks.await

/**
 * Service responsible for acting as the "Dead Drop" for TRUSTED and GROUP content.
 * Content is fully End-to-End Encrypted (E2E) on device before being uploaded.
 * 
 * Flow:
 * 1. Upload E2E Encrypted Blob.
 * 2. Contacts download blob.
 * 3. Firebase auto-deletes the blob after 7 days (via TTL policy) or we explicitly delete.
 */
class FirestoreDeadDropService : Service() {

    private val db = FirebaseFirestore.getInstance()
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_UPLOAD) {
            val contentJson = intent.getStringExtra(EXTRA_CONTENT_JSON) ?: return START_NOT_STICKY
            serviceScope.launch {
                uploadContent(contentJson)
            }
        } else if (action == ACTION_GOSSIP_TOP_RATED) {
            val contentJson = intent.getStringExtra(EXTRA_CONTENT_JSON) ?: return START_NOT_STICKY
            serviceScope.launch {
                uploadGossipContent(contentJson)
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun uploadContent(contentJson: String) {
        try {
            val contentUnit = Json.decodeFromString<ContentUnit>(contentJson)
            
            // Safety Check: We only upload TRUSTED or GROUP content.
            // PUBLIC stays on local P2P. PRIVATE stays local.
            if (contentUnit.visibility == Visibility.PUBLIC || contentUnit.visibility == Visibility.PRIVATE) {
                Log.w("FirestoreDeadDrop", "Prevented upload of non-global content.")
                return
            }

            if (contentUnit.imagePath != null || contentUnit.videoPath != null) {
                Log.d("FirestoreDeadDrop", "Media detected. WebRTC stream will be established later...")
                // TODO: Implement WebRTC signaling for media
            }

            val privateDb = AetherDatabase.getPrivateDatabase()
            val recipientMap = mutableMapOf<String, String>()

            if (contentUnit.visibility == Visibility.TRUSTED) {
                val trustedPeers = privateDb.peerDao().getByTrustLevel(TrustLevel.TRUSTED_REMOTE_VERIFIED) +
                                   privateDb.peerDao().getByTrustLevel(TrustLevel.TRUSTED_IN_PERSON)
                trustedPeers.forEach { peer ->
                    peer.encryptionPublicKeyBase64?.let {
                        recipientMap[peer.publicKeyBase64] = it
                    }
                }
            } else if (contentUnit.visibility == Visibility.GROUP) {
                contentUnit.recipientKeyMapJson?.let { json ->
                    try {
                        val wrappedKeys = Json.decodeFromString<Map<String, String>>(json)
                        wrappedKeys.keys.forEach { ed25519Pub ->
                            val peer = privateDb.peerDao().getByPublicKey(ed25519Pub)
                            peer?.encryptionPublicKeyBase64?.let {
                                recipientMap[ed25519Pub] = it
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("FirestoreDeadDrop", "Failed to parse group recipients", e)
                    }
                }
            }

            if (recipientMap.isEmpty()) {
                Log.e("FirestoreDeadDrop", "Cannot upload: no recipients with encryption keys found. Aborting.")
                return
            }

            val encryptedPayload = GroupEncryption.encryptPayloadForRecipients(
                plaintextBytes = contentJson.toByteArray(Charsets.UTF_8),
                recipientPublicKeys = recipientMap
            )

            val auth = FirebaseAuth.getInstance()
            if (auth.currentUser == null) {
                try {
                    auth.signInAnonymously().await()
                } catch (e: Exception) {
                    Log.e("FirestoreDeadDrop", "Failed anonymous sign-in, aborting upload.", e)
                    return
                }
            }
            val uid = auth.currentUser?.uid ?: ""

            val documentData = hashMapOf(
                "hash" to contentUnit.contentHash,
                "payload" to encryptedPayload,
                "timestamp" to System.currentTimeMillis(),
                "senderUid" to uid
            )

            val targetCollection = if (contentUnit.visibility == Visibility.GROUP) "group_drops" else "trusted_drops"

            db.collection(targetCollection)
                .document(contentUnit.contentHash)
                .set(documentData)
                .addOnSuccessListener {
                    Log.d("FirestoreDeadDrop", "Successfully dropped E2E blob to Firestore.")
                }
                .addOnFailureListener { e ->
                    Log.e("FirestoreDeadDrop", "Failed to upload to Firestore", e)
                }
        } catch (e: Exception) {
            Log.e("FirestoreDeadDrop", "Error processing upload", e)
        }
    }

    private suspend fun uploadGossipContent(contentJson: String) {
        try {
            val contentUnit = Json.decodeFromString<ContentUnit>(contentJson)
            
            if (contentUnit.visibility != Visibility.PUBLIC) {
                Log.w("FirestoreDeadDrop", "Gossip prevented: Content is not PUBLIC.")
                return
            }

            if (contentUnit.imagePath != null || contentUnit.videoPath != null) {
                Log.d("FirestoreDeadDrop", "Media detected in Gossip. WebRTC stream will be established later...")
                // TODO: Implement WebRTC signaling for media
            }

            val privateDb = AetherDatabase.getPrivateDatabase()
            val recipientMap = mutableMapOf<String, String>()

            val trustedPeers = privateDb.peerDao().getByTrustLevel(TrustLevel.TRUSTED_REMOTE_VERIFIED) +
                               privateDb.peerDao().getByTrustLevel(TrustLevel.TRUSTED_IN_PERSON)
            trustedPeers.forEach { peer ->
                peer.encryptionPublicKeyBase64?.let {
                    recipientMap[peer.publicKeyBase64] = it
                }
            }

            if (recipientMap.isEmpty()) {
                Log.d("FirestoreDeadDrop", "Gossip aborted: no trusted peers to gossip to.")
                return
            }

            val encryptedPayload = GroupEncryption.encryptPayloadForRecipients(
                plaintextBytes = contentJson.toByteArray(Charsets.UTF_8),
                recipientPublicKeys = recipientMap
            )

            val auth = FirebaseAuth.getInstance()
            if (auth.currentUser == null) {
                try {
                    auth.signInAnonymously().await()
                } catch (e: Exception) {
                    return
                }
            }
            val uid = auth.currentUser?.uid ?: ""

            val documentData = hashMapOf(
                "hash" to contentUnit.contentHash,
                "payload" to encryptedPayload,
                "timestamp" to System.currentTimeMillis(),
                "senderUid" to uid,
                "isGossip" to true
            )

            db.collection("trusted_drops")
                .document("gossip_${contentUnit.contentHash}")
                .set(documentData)
                .addOnSuccessListener {
                    Log.d("FirestoreDeadDrop", "Successfully gossiped top-rated content to trusted peers.")
                }
        } catch (e: Exception) {
            Log.e("FirestoreDeadDrop", "Error gossiping content", e)
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    companion object {
        const val ACTION_UPLOAD = "app.clearspace.network.UPLOAD_DEAD_DROP"
        const val ACTION_GOSSIP_TOP_RATED = "app.clearspace.network.GOSSIP_TOP_RATED"
        const val EXTRA_CONTENT_JSON = "extra_content_json"
    }
}
