package app.clearspace.network.discovery

import android.content.Context
import android.util.Log
import app.clearspace.network.crypto.KeyManager
import app.clearspace.network.persona.PersonaAgent
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class GlobalProfile(
    val id: String = "",
    val alias: String = "",
    val fcmToken: String = "",
    val avatarId: Int = 0,
    val bioSnippet: String = "",
    val timestamp: Long = 0L
)

class GlobalDiscoveryAgent(private val context: Context) {
    private val firestore = FirebaseFirestore.getInstance()
    private val personaAgent = PersonaAgent(context, KeyManager(context))
    private val prefs = context.getSharedPreferences("aether_settings", Context.MODE_PRIVATE)

    companion object {
        const val TAG = "GlobalDiscovery"
        const val COLLECTION_DIRECTORY = "global_directory"
        const val COLLECTION_PINGS = "pings"
    }

    suspend fun updateDiscoverability() {
        val isDiscoverable = prefs.getBoolean("global_discoverable", false)
        val profile = personaAgent.getProfile() ?: return
        val pubKey = personaAgent.publicKeyBase64

        if (isDiscoverable) {
            try {
                val token = FirebaseMessaging.getInstance().token.await()
                val bioSnippet = profile.about?.value?.take(50) ?: ""
                
                val globalProfile = GlobalProfile(
                    id = pubKey,
                    alias = profile.alias,
                    fcmToken = token,
                    avatarId = profile.avatarId,
                    bioSnippet = bioSnippet,
                    timestamp = System.currentTimeMillis()
                )
                
                firestore.collection(COLLECTION_DIRECTORY)
                    .document(pubKey)
                    .set(globalProfile)
                    .await()
                Log.d(TAG, "Successfully registered in global directory.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register in global directory", e)
            }
        } else {
            try {
                firestore.collection(COLLECTION_DIRECTORY).document(pubKey).delete().await()
                Log.d(TAG, "Successfully removed from global directory.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove from global directory", e)
            }
        }
    }

    suspend fun searchByAlias(aliasQuery: String): List<GlobalProfile> {
        return try {
            val snapshot = firestore.collection(COLLECTION_DIRECTORY)
                .whereEqualTo("alias", aliasQuery)
                .limit(20)
                .get()
                .await()
            
            snapshot.toObjects(GlobalProfile::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "Search failed", e)
            emptyList()
        }
    }

    suspend fun pingUser(targetPubKey: String, isAccepting: Boolean = false) {
        val myProfile = personaAgent.getProfile() ?: return
        try {
            val pingData = mapOf(
                "senderId" to personaAgent.publicKeyBase64,
                "senderAlias" to myProfile.alias,
                "type" to if (isAccepting) "CONNECTION_ACCEPTED" else "CONNECTION_REQUEST",
                "timestamp" to System.currentTimeMillis()
            )
            firestore.collection(COLLECTION_PINGS)
                .document(targetPubKey)
                .collection("incoming")
                .add(pingData)
                .await()
            Log.d(TAG, "Ping sent to $targetPubKey (isAccepting=$isAccepting)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send ping", e)
        }
    }

    private val _incomingPings = MutableStateFlow<List<Map<String, Any>>>(emptyList())
    val incomingPings: StateFlow<List<Map<String, Any>>> = _incomingPings

    fun startListeningForPings() {
        val pubKey = personaAgent.publicKeyBase64
        if (pubKey.isEmpty()) return

        firestore.collection(COLLECTION_PINGS)
            .document(pubKey)
            .collection("incoming")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(TAG, "Listen failed.", error)
                    return@addSnapshotListener
                }

                if (snapshot != null) {
                    val pings = snapshot.documents.mapNotNull { doc ->
                        doc.data?.plus("docId" to doc.id)
                    }
                    // Filter out accepted pings that we automatically process
                    val userFacingPings = mutableListOf<Map<String, Any>>()
                    
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                        for (ping in pings) {
                            val type = ping["type"] as? String
                            val senderId = ping["senderId"] as? String ?: continue
                            val senderAlias = ping["senderAlias"] as? String ?: "Unknown"
                            val docId = ping["docId"] as? String ?: continue
                            
                            if (type == "CONNECTION_ACCEPTED") {
                                // Automatically save them and delete the ping
                                savePeerToDb(senderId, senderAlias)
                                firestore.collection(COLLECTION_PINGS).document(pubKey)
                                    .collection("incoming").document(docId).delete()
                            } else if (type == "WEBRTC_SYNC_REQUEST") {
                                val roomId = ping["roomId"] as? String
                                if (roomId != null) {
                                    Log.d(TAG, "Received WEBRTC_SYNC_REQUEST for room $roomId")
                                    app.clearspace.network.network.WebRtcManager.getInstance(context).startCall(roomId, isCaller = false)
                                    firestore.collection(COLLECTION_PINGS).document(pubKey)
                                        .collection("incoming").document(docId).delete()
                                }
                            } else {
                                userFacingPings.add(ping)
                            }
                        }
                        _incomingPings.value = userFacingPings
                    }
                }
            }
    }

    private suspend fun savePeerToDb(senderId: String, senderAlias: String) {
        val db = app.clearspace.network.storage.db.AetherDatabase.getPrivateDatabase()
        val newPeer = app.clearspace.network.storage.db.entity.KnownPeer(
            publicKeyBase64 = senderId,
            alias = senderAlias,
            avatarIndex = 0,
            trustLevel = app.clearspace.network.storage.db.entity.TrustLevel.ACQUAINTANCE,
            onionAddress = null
        )
        db.peerDao().insert(newPeer)
    }

    suspend fun acceptPing(docId: String, senderId: String, senderAlias: String) {
        val pubKey = personaAgent.publicKeyBase64
        try {
            firestore.collection(COLLECTION_PINGS)
                .document(pubKey)
                .collection("incoming")
                .document(docId)
                .delete()
                .await()
            
            savePeerToDb(senderId, senderAlias)
            pingUser(senderId, isAccepting = true)
            Log.d(TAG, "Accepted ping from $senderId and saved to DB")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to accept ping", e)
        }
    }

    suspend fun rejectPing(docId: String) {
        val pubKey = personaAgent.publicKeyBase64
        try {
            firestore.collection(COLLECTION_PINGS)
                .document(pubKey)
                .collection("incoming")
                .document(docId)
                .delete()
                .await()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reject ping", e)
        }
    }
}
