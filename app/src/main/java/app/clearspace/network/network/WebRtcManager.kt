package app.clearspace.network.network

import android.content.Context
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import org.webrtc.*
import java.nio.ByteBuffer
import java.util.UUID

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class WebRtcManager private constructor(private val context: Context) {

    private var peerConnectionFactory: PeerConnectionFactory? = null
    var peerConnection: PeerConnection? = null
    var dataChannel: DataChannel? = null

    var onDataReceived: ((String) -> Unit)? = null
    var onDataChannelOpened: (() -> Unit)? = null

    private val _connectionState = MutableStateFlow(PeerConnection.IceConnectionState.NEW)
    val connectionState: StateFlow<PeerConnection.IceConnectionState> = _connectionState.asStateFlow()

    private val db = FirebaseFirestore.getInstance()

    init {
        initializeWebrtc()
    }

    private fun initializeWebrtc() {
        val initializationOptions = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(true)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initializationOptions)

        val options = PeerConnectionFactory.Options()
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(options)
            .createPeerConnectionFactory()
    }

    fun startCall(roomId: String, isCaller: Boolean) {
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer()
        )

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers)
        
        peerConnection = peerConnectionFactory?.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                state?.let { 
                    _connectionState.value = it
                    Log.d("WebRtcManager", "ICE Connection State: $it")
                }
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) {}

            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}

            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate?.let {
                    val candidateMap = hashMapOf(
                        "sdpMid" to it.sdpMid,
                        "sdpMLineIndex" to it.sdpMLineIndex,
                        "sdpCandidate" to it.sdp
                    )
                    val collection = if (isCaller) "callerCandidates" else "calleeCandidates"
                    db.collection("webrtc_rooms").document(roomId)
                        .collection(collection).add(candidateMap)
                }
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}

            override fun onAddStream(stream: MediaStream?) {}

            override fun onRemoveStream(stream: MediaStream?) {}

            override fun onDataChannel(channel: DataChannel?) {
                channel?.let { setupDataChannel(it) }
            }

            override fun onRenegotiationNeeded() {}

            override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {}
        })

        if (isCaller) {
            val dcInit = DataChannel.Init()
            dataChannel = peerConnection?.createDataChannel("secureDataChannel", dcInit)
            dataChannel?.let { setupDataChannel(it) }

            createOffer(roomId)
            listenForAnswer(roomId)
        } else {
            listenForOfferAndCreateAnswer(roomId)
        }

        listenForIceCandidates(roomId, isCaller)
    }

    private fun setupDataChannel(channel: DataChannel) {
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}

            override fun onStateChange() {
                Log.d("WebRtcManager", "DataChannel State: ${channel.state()}")
                if (channel.state() == DataChannel.State.OPEN) {
                    onDataChannelOpened?.invoke()
                }
            }

            override fun onMessage(buffer: DataChannel.Buffer?) {
                buffer?.let {
                    val data = it.data
                    val bytes = ByteArray(data.remaining())
                    data.get(bytes)
                    val message = String(bytes, Charsets.UTF_8)
                    onDataReceived?.invoke(message)
                }
            }
        })
        dataChannel = channel
    }

    fun sendData(message: String) {
        val buffer = ByteBuffer.wrap(message.toByteArray(Charsets.UTF_8))
        dataChannel?.send(DataChannel.Buffer(buffer, false))
    }

    private fun createOffer(roomId: String) {
        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sessionDescription: SessionDescription?) {
                sessionDescription?.let { sdp ->
                    peerConnection?.setLocalDescription(this, sdp)
                    val offerMap = hashMapOf(
                        "type" to sdp.type.canonicalForm(),
                        "sdp" to sdp.description
                    )
                    db.collection("webrtc_rooms").document(roomId).set(hashMapOf("offer" to offerMap))
                }
            }

            override fun onSetSuccess() {}
            override fun onCreateFailure(s: String?) {}
            override fun onSetFailure(s: String?) {}
        }, MediaConstraints())
    }

    private fun listenForAnswer(roomId: String) {
        db.collection("webrtc_rooms").document(roomId)
            .addSnapshotListener { snapshot, _ ->
                if (snapshot != null && snapshot.contains("answer")) {
                    val answerMap = snapshot.get("answer") as? Map<String, String>
                    val sdp = answerMap?.get("sdp")
                    if (sdp != null && peerConnection?.remoteDescription == null) {
                        handleAnswer(sdp)
                    }
                }
            }
    }

    private fun listenForOfferAndCreateAnswer(roomId: String) {
        db.collection("webrtc_rooms").document(roomId)
            .addSnapshotListener { snapshot, _ ->
                if (snapshot != null && snapshot.contains("offer")) {
                    val offerMap = snapshot.get("offer") as? Map<String, String>
                    val sdp = offerMap?.get("sdp")
                    if (sdp != null && peerConnection?.remoteDescription == null) {
                        handleOfferAndCreateAnswer(roomId, sdp)
                    }
                }
            }
    }

    fun handleOfferAndCreateAnswer(roomId: String, sdpDescription: String) {
        val sessionDescription = SessionDescription(SessionDescription.Type.OFFER, sdpDescription)
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onSetSuccess() {
                peerConnection?.createAnswer(object : SdpObserver {
                    override fun onCreateSuccess(sessionDescription: SessionDescription?) {
                        sessionDescription?.let { sdp ->
                            peerConnection?.setLocalDescription(this, sdp)
                            val answerMap = hashMapOf(
                                "type" to sdp.type.canonicalForm(),
                                "sdp" to sdp.description
                            )
                            db.collection("webrtc_rooms").document(roomId).update("answer", answerMap)
                        }
                    }

                    override fun onSetSuccess() {}
                    override fun onCreateFailure(s: String?) {}
                    override fun onSetFailure(s: String?) {}
                }, MediaConstraints())
            }

            override fun onCreateFailure(s: String?) {}
            override fun onSetFailure(s: String?) {}
        }, sessionDescription)
    }

    fun handleAnswer(sdpDescription: String) {
        val sessionDescription = SessionDescription(SessionDescription.Type.ANSWER, sdpDescription)
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onSetSuccess() {}
            override fun onCreateFailure(s: String?) {}
            override fun onSetFailure(s: String?) {}
        }, sessionDescription)
    }

    private fun listenForIceCandidates(roomId: String, isCaller: Boolean) {
        val collection = if (isCaller) "calleeCandidates" else "callerCandidates"
        db.collection("webrtc_rooms").document(roomId).collection(collection)
            .addSnapshotListener { snapshot, _ ->
                snapshot?.documentChanges?.forEach { change ->
                    if (change.type == com.google.firebase.firestore.DocumentChange.Type.ADDED) {
                        val data = change.document.data
                        val candidate = IceCandidate(
                            data["sdpMid"] as String,
                            (data["sdpMLineIndex"] as Long).toInt(),
                            data["sdpCandidate"] as String
                        )
                        peerConnection?.addIceCandidate(candidate)
                    }
                }
            }
    }

    fun close() {
        dataChannel?.close()
        peerConnection?.close()
        peerConnectionFactory?.dispose()
        _connectionState.value = PeerConnection.IceConnectionState.CLOSED
    }

    companion object {
        @Volatile
        private var instance: WebRtcManager? = null

        fun getInstance(context: Context): WebRtcManager {
            return instance ?: synchronized(this) {
                instance ?: WebRtcManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
