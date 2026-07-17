package app.clearspace.network.network.push

import android.content.Intent
import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlinx.coroutines.tasks.await
import app.clearspace.network.storage.db.entity.ContentUnit
import app.clearspace.network.crypto.GroupEncryption
import app.clearspace.network.storage.db.AetherDatabase
import app.clearspace.network.storage.db.entity.KnownPeer
import java.io.File

class AetherMessagingService : FirebaseMessagingService() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        Log.d("AetherMessagingService", "Received a wake-up ping from Firebase!")
        
        // 1. Kickstart local discovery
        val startIntent = Intent(this, app.clearspace.network.discovery.DiscoveryForegroundService::class.java)
        startIntent.action = "WAKE_UP_PING"
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(startIntent)
            } else {
                startService(startIntent)
            }
        } catch (e: Exception) {
            Log.e("AetherMessagingService", "Could not start local discovery: ${e.message}")
        }

        // 2. Poll remote Dead Drops on Firestore
        serviceScope.launch {
            pollDeadDrops()
        }
    }

    private suspend fun pollDeadDrops() {
        Log.d("AetherMessagingService", "Polling Firestore for new dead drops...")
        val db = FirebaseFirestore.getInstance()
        val privateDb = AetherDatabase.getPrivateDatabase()
        
        try {
            val snapshot = db.collection("trusted_drops")
                .orderBy("timestamp", com.google.firebase.firestore.Query.Direction.DESCENDING)
                .limit(20)
                .get()
                .await()
                
            for (document in snapshot.documents) {
                try {
                    val hash = document.getString("hash") ?: continue
                    if (privateDb.contentDao().getByHashSync(hash) != null) {
                        continue // Already have it
                    }

                    val encryptedPayloadStr = document.getString("payload") ?: continue
                    
                    val keyManager = app.clearspace.network.crypto.KeyManager(applicationContext)
                    val identity = keyManager.getOrGenerateIdentity()
                    val myEd25519PubBase64 = android.util.Base64.encodeToString(identity.second, android.util.Base64.NO_WRAP)
                    val myX25519PrivBytes = keyManager.getOrGenerateEncryptionIdentity().first

                    // Decrypt the payload
                    val decryptedBytes = GroupEncryption.decryptPayloadForMe(
                        encryptedPayloadStr,
                        myEd25519PubBase64,
                        myX25519PrivBytes
                    ) ?: continue
                    
                    val contentJson = String(decryptedBytes, Charsets.UTF_8)
                    val contentUnit = Json.decodeFromString<ContentUnit>(contentJson)
                    
                    // Save to local database
                    privateDb.contentDao().insert(contentUnit)
                    Log.d("AetherMessagingService", "Saved new remote content: ${contentUnit.contentHash}")
                    
                    // Check if we need to download media
                    if (contentUnit.imagePath != null || contentUnit.videoPath != null) {
                        val expectedPath = contentUnit.videoPath ?: contentUnit.imagePath!!
                        if (!File(expectedPath).exists()) {
                            Log.d("AetherMessagingService", "Media missing locally. WebRTC signaling will handle download later.")
                            // TODO: Trigger WebRTC signaling download
                        }
                    }
                    
                } catch (e: Exception) {
                    Log.e("AetherMessagingService", "Failed to process drop document", e)
                }
            }
        } catch (e: Exception) {
            Log.e("AetherMessagingService", "Error polling dead drops", e)
        }
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d("AetherMessagingService", "New FCM Token: $token")
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
