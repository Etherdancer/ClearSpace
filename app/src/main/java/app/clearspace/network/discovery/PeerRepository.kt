package app.clearspace.network.discovery

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Singleton repository holding the live state of nearby discovered peers.
 * Updated by DiscoveryManager/ForegroundService, observed by the UI (SocialScreen).
 */
object PeerRepository {
    private val _discoveredPeers = MutableStateFlow<List<PresencePacket>>(emptyList())
    val discoveredPeers: StateFlow<List<PresencePacket>> = _discoveredPeers.asStateFlow()

    private val _globalWebRtcHeaders = MutableStateFlow<List<Pair<String, ContentHeader>>>(emptyList())
    val globalWebRtcHeaders: StateFlow<List<Pair<String, ContentHeader>>> = _globalWebRtcHeaders.asStateFlow()

    fun updatePeers(peers: List<PresencePacket>) {
        _discoveredPeers.value = peers
    }

    fun addGlobalWebRtcHeaders(peerId: String, headers: List<ContentHeader>) {
        val current = _globalWebRtcHeaders.value.toMutableList()
        current.removeAll { it.first == peerId }
        current.addAll(headers.map { Pair(peerId, it) })
        _globalWebRtcHeaders.value = current
    }
}
