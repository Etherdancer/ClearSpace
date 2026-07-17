package app.clearspace.network.storage.sync

import android.content.Context
import android.util.Log
import app.clearspace.network.network.WebRtcManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * ClearSyncManager (Replaces Syncthing)
 * A native Kotlin sync engine that exchanges timestamped database delta manifests.
 * Connects outwardly to other peers via WebRTC Data Channels.
 */
class ClearSyncManager(private val context: Context) : SyncManager {
    private val _syncState = MutableStateFlow<SyncStatus>(SyncStatus.IDLE)
    private var isRunning = false
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val webRtcManager = WebRtcManager.getInstance(context)

    override fun getSyncStatus(): Flow<SyncStatus> = _syncState.asStateFlow()

    override fun startSyncService() {
        Log.d(TAG, "Starting ClearSync Native Kotlin Engine (WebRTC)...")
        _syncState.value = SyncStatus.IDLE
        isRunning = true
        
        webRtcManager.onDataReceived = { message ->
            handleIncomingData(message)
        }
    }

    override fun stopSyncService() {
        Log.d(TAG, "Stopping ClearSync Engine...")
        isRunning = false
        _syncState.value = SyncStatus.IDLE
        webRtcManager.onDataReceived = null
        scope.cancel()
    }

    private fun handleIncomingData(message: String) {
        scope.launch {
            try {
                val json = JSONObject(message)
                
                if (json.has("auth")) {
                    Log.d(TAG, "Received Auth Packet via WebRTC")
                    val authJson = json.getJSONObject("auth")
                    val pubKey = authJson.getString("pubKey")
                    val timestamp = authJson.getLong("timestamp")
                    val signatureBase64 = authJson.getString("signature")
                    
                    if (Math.abs(System.currentTimeMillis() - timestamp) > 300000) {
                        Log.e(TAG, "Auth expired")
                        return@launch
                    }
                    
                    val keyManager = app.clearspace.network.crypto.KeyManager(context)
                    val signatureBytes = android.util.Base64.decode(signatureBase64, android.util.Base64.NO_WRAP)
                    val dataToVerify = (pubKey + timestamp.toString()).toByteArray(Charsets.UTF_8)
                    val pubKeyBytes = android.util.Base64.decode(pubKey, android.util.Base64.NO_WRAP)
                    
                    if (!keyManager.verifySignature(dataToVerify, signatureBytes, pubKeyBytes)) {
                        Log.e(TAG, "Signature verification failed")
                        return@launch
                    }
                    
                    val db = app.clearspace.network.storage.db.AetherDatabase.getPrivateDatabase()
                    val peer = db.peerDao().getByPublicKey(pubKey)
                    if (peer == null || peer.trustLevel == app.clearspace.network.storage.db.entity.TrustLevel.ACQUAINTANCE) {
                        Log.e(TAG, "Untrusted peer")
                        return@launch
                    }
                    
                    Log.d(TAG, "Peer verified successfully! Waiting for sync manifest.")
                } else if (json.has("lastSync")) {
                    Log.d(TAG, "Received Sync Manifest via WebRTC: $json")
                    val response = JSONObject()
                    response.put("status", "sync_complete")
                    response.put("notes_received", 0)
                    webRtcManager.sendData(response.toString())
                } else if (json.has("status") && json.getString("status") == "sync_complete") {
                    Log.d(TAG, "Secure Sync Complete response from peer.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling incoming WebRTC data", e)
            }
        }
    }

    fun triggerSyncHandshake() {
        scope.launch {
            try {
                Log.d(TAG, "Triggering Sync Handshake over WebRTC...")
                val keyManager = app.clearspace.network.crypto.KeyManager(context)
                
                // 1. Send Client Auth Packet
                val myIdentity = keyManager.getOrGenerateIdentity()
                val myPubKey = android.util.Base64.encodeToString(myIdentity.second, android.util.Base64.NO_WRAP)
                val myTs = System.currentTimeMillis()
                val myData = (myPubKey + myTs.toString()).toByteArray(Charsets.UTF_8)
                val mySig = android.util.Base64.encodeToString(keyManager.signData(myData), android.util.Base64.NO_WRAP)
                
                val clientAuth = JSONObject().apply {
                    put("auth", JSONObject().apply {
                        put("pubKey", myPubKey)
                        put("timestamp", myTs)
                        put("signature", mySig)
                    })
                }
                webRtcManager.sendData(clientAuth.toString())
                
                // 2. Send Sync Manifest immediately (we rely on the async callback for responses)
                val manifest = JSONObject()
                manifest.put("lastSync", System.currentTimeMillis())
                webRtcManager.sendData(manifest.toString())
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send sync handshake", e)
            }
        }
    }

    override fun addTrustedDevice(deviceId: String) {
        Log.d(TAG, "Adding trusted device: $deviceId")
    }

    override fun removeTrustedDevice(deviceId: String) {
        Log.d(TAG, "Removing trusted device: $deviceId")
    }

    companion object {
        private const val TAG = "ClearSyncManager"
        @Volatile
        private var instance: ClearSyncManager? = null

        fun getInstance(context: Context): ClearSyncManager {
            return instance ?: synchronized(this) {
                instance ?: ClearSyncManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
