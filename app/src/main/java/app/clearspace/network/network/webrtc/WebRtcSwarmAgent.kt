package app.clearspace.network.network.webrtc

import android.content.Context
import android.util.Log
import app.clearspace.network.crypto.KeyManager
import app.clearspace.network.discovery.GlobalDiscoveryAgent
import app.clearspace.network.discovery.GlobalProfile
import app.clearspace.network.network.WebRtcManager
import app.clearspace.network.persona.PersonaAgent
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import java.util.UUID

class WebRtcSwarmAgent(private val context: Context) {
    private val firestore = FirebaseFirestore.getInstance()
    private val personaAgent = PersonaAgent(context, KeyManager(context))

    suspend fun syncWithRandomGlobalPeer() {
        try {
            val pubKey = personaAgent.publicKeyBase64
            if (pubKey.isEmpty()) return

            val snapshot = firestore.collection(GlobalDiscoveryAgent.COLLECTION_DIRECTORY)
                .limit(50)
                .get()
                .await()

            val profiles = snapshot.toObjects(GlobalProfile::class.java).filter { it.id != pubKey }
            if (profiles.isEmpty()) return

            val randomPeer = profiles.random()
            val roomId = UUID.randomUUID().toString()

            Log.d("WebRtcSwarmAgent", "Initiating WebRTC sync with ${randomPeer.alias} in room $roomId")

            // Send WEBRTC_SYNC_REQUEST ping
            val myProfile = personaAgent.getProfile() ?: return
            val pingData = mapOf(
                "senderId" to pubKey,
                "senderAlias" to myProfile.alias,
                "type" to "WEBRTC_SYNC_REQUEST",
                "roomId" to roomId,
                "timestamp" to System.currentTimeMillis()
            )

            firestore.collection(GlobalDiscoveryAgent.COLLECTION_PINGS)
                .document(randomPeer.id)
                .collection("incoming")
                .add(pingData)
                .await()

            // Start the WebRTC call
            val webRtcManager = WebRtcManager.getInstance(context)
            webRtcManager.onDataChannelOpened = {
                Log.d("WebRtcSwarmAgent", "DataChannel opened, requesting index")
                webRtcManager.sendData("GET_INDEX")
            }
            webRtcManager.startCall(roomId, isCaller = true)

        } catch (e: Exception) {
            Log.e("WebRtcSwarmAgent", "Failed to start sync", e)
        }
    }
}
