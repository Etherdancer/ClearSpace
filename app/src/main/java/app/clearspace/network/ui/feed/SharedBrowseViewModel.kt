package app.clearspace.network.ui.feed

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.combine
import kotlinx.serialization.encodeToString
import app.clearspace.network.crypto.GroupEncryption
import app.clearspace.network.crypto.KeyManager
import app.clearspace.network.discovery.ContentHeader
import app.clearspace.network.discovery.PeerRepository
import app.clearspace.network.discovery.P2PClient
import app.clearspace.network.discovery.DiscoveryManager
import app.clearspace.network.reputation.ReputationAgent
import app.clearspace.network.storage.db.AetherDatabase
import app.clearspace.network.storage.db.entity.ContentUnit
import app.clearspace.network.storage.db.entity.TrustLevel
import app.clearspace.network.storage.db.entity.Visibility
import app.clearspace.network.storage.db.entity.BlockedAuthor
import app.clearspace.network.ui.components.FlagConstants.FlagType

class SharedBrowseViewModel(application: Application) : AndroidViewModel(application) {
    private val _sharedHeaders = MutableStateFlow<List<Pair<String, ContentHeader>>>(emptyList()) // Pair of IP/Port to Header
    val sharedHeaders: StateFlow<List<Pair<String, ContentHeader>>> = _sharedHeaders.asStateFlow()
    
    private val _loadingHeader = MutableStateFlow(false)
    val loadingHeader: StateFlow<Boolean> = _loadingHeader.asStateFlow()

    private val _viewingContent = MutableStateFlow<ContentUnit?>(null)
    val viewingContent: StateFlow<ContentUnit?> = _viewingContent.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    enum class SourceFilter { ALL, ACQUAINTANCES, TRUSTED }
    private val _sourceFilter = MutableStateFlow(SourceFilter.ALL)
    val sourceFilter: StateFlow<SourceFilter> = _sourceFilter.asStateFlow()

    private val db = AetherDatabase.getPrivateDatabase()
    private val sharedDb = AetherDatabase.getSharedDatabase()

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun updateSourceFilter(filter: SourceFilter) {
        _sourceFilter.value = filter
    }

    init {
        viewModelScope.launch {
            val peersFlow = db.peerDao().getAllPeers()
            val blockedFlow = db.blockedAuthorDao().getAllBlocked()
            val localSharedFlow = sharedDb.contentDao().getSharedContentFlow()
            
            val flow1 = combine(
                PeerRepository.discoveredPeers,
                PeerRepository.globalWebRtcHeaders,
                _sourceFilter
            ) { a, b, c -> Triple(a, b, c) }
            
            val flow2 = combine(
                peersFlow,
                localSharedFlow,
                blockedFlow
            ) { a, b, c -> Triple(a, b, c) }

            combine(flow1, flow2) { t1, t2 ->
                val discoveredPeers = t1.first as List<app.clearspace.network.discovery.PresencePacket>
                val globalWebRtcHeaders = t1.second as List<Pair<String, ContentHeader>>
                val filter = t1.third as SourceFilter
                val knownPeers = t2.first as List<app.clearspace.network.storage.db.entity.KnownPeer>
                val localSharedContent = t2.second as List<app.clearspace.network.storage.db.entity.ContentUnit>
                val blockedAuthors = t2.third as List<app.clearspace.network.storage.db.entity.BlockedAuthor>
                
                val blockedIds = blockedAuthors.map { it.authorId }.toSet()
                val newHeaders = mutableListOf<Pair<String, ContentHeader>>()
                
                // Add local shared content
                for (local in localSharedContent) {
                    if (blockedIds.contains(local.authorAlias)) continue
                    val isAcquaintance = knownPeers.any { it.alias == local.authorAlias }
                    val isTrusted = knownPeers.any { it.alias == local.authorAlias && it.trustLevel != TrustLevel.ACQUAINTANCE }
                    
                    val include = when (filter) {
                        SourceFilter.ALL -> true
                        SourceFilter.ACQUAINTANCES -> isAcquaintance
                        SourceFilter.TRUSTED -> isTrusted
                    }
                    if (include) {
                        val header = ContentHeader(
                            contentHash = local.contentHash,
                            title = local.title,
                            authorAlias = local.authorAlias,
                            timestamp = local.timestamp,
                            thumbnailBase64 = local.thumbnailBase64,
                            categoryFlags = local.categoryFlags,
                            emotionFlags = local.emotionFlags,
                            reputationScore = local.likeTokens.size - local.dislikeTokens.size,
                            spaceId = local.spaceId
                        )
                        newHeaders.add(Pair("local:0", header))
                    }
                }
                
                for (peer in discoveredPeers) {
                    val ip = peer.ip
                    if (ip != null && peer.tcpPort > 0) {
                        try {
                            val indexes = P2PClient.fetchIndex(ip, peer.tcpPort)
                            if (indexes != null) {
                                for (header in indexes) {
                                    if (blockedIds.contains(header.authorAlias)) continue
                                    val isAcquaintance = knownPeers.any { it.alias == header.authorAlias }
                                    val isTrusted = knownPeers.any { it.alias == header.authorAlias && it.trustLevel != TrustLevel.ACQUAINTANCE }
                                    
                                    val include = when (filter) {
                                        SourceFilter.ALL -> true
                                        SourceFilter.ACQUAINTANCES -> isAcquaintance
                                        SourceFilter.TRUSTED -> isTrusted
                                    }
                                    if (include) {
                                        newHeaders.add(Pair("$ip:${peer.tcpPort}", header))
                                    }
                                }
                            }
                        } catch(e: Exception) {
                            // Ignored or log
                        }
                    }
                }
                
                for (pair in globalWebRtcHeaders) {
                    val header = pair.second
                    if (blockedIds.contains(header.authorAlias)) continue
                    val isAcquaintance = knownPeers.any { it.alias == header.authorAlias }
                    val isTrusted = knownPeers.any { it.alias == header.authorAlias && it.trustLevel != TrustLevel.ACQUAINTANCE }
                    
                    val include = when (filter) {
                        SourceFilter.ALL -> true
                        SourceFilter.ACQUAINTANCES -> isAcquaintance
                        SourceFilter.TRUSTED -> isTrusted
                    }
                    if (include) {
                        newHeaders.add(pair)
                    }
                }
                newHeaders
            }.collect { filtered ->
                _sharedHeaders.value = filtered
            }
        }
    }
    
    fun clearViewingContent() {
        _viewingContent.value = null
    }

    private var openContentJob: kotlinx.coroutines.Job? = null

    fun cancelLoading() {
        openContentJob?.cancel()
        _loadingHeader.value = false
    }

    fun openContent(ipPort: String, hash: String) {
        openContentJob?.cancel()
        openContentJob = viewModelScope.launch {
            _loadingHeader.value = true
            try {
                val parts = ipPort.split(":")
                var content: ContentUnit? = null
                
                if (parts.size == 2) {
                    val ip = parts[0]
                    val port = parts[1].toIntOrNull() ?: 0
                    try {
                        content = kotlinx.coroutines.withTimeoutOrNull(5000) {
                            P2PClient.fetchContent(ip, port, hash)
                        }
                    } catch(e: Exception) {}
                }
                
                if (content == null) {
                    content = sharedDb.contentDao().getByHash(hash)
                    if (content == null) {
                        content = db.contentDao().getByHash(hash)
                    }
                }
                
                if (content != null && content.visibility == Visibility.GROUP && content.recipientKeyMapJson != null) {
                    val keyManager = KeyManager(getApplication())
                    val myX25519Priv = keyManager.getOrGenerateEncryptionIdentity().first
                    val unwrappedAesKey = GroupEncryption.unwrapKey(content.recipientKeyMapJson, myX25519Priv)
                    
                    if (unwrappedAesKey != null) {
                        try {
                            val encryptedPayload = android.util.Base64.decode(content.body, android.util.Base64.DEFAULT)
                            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
                            val iv = encryptedPayload.copyOfRange(0, 12)
                            val ciphertext = encryptedPayload.copyOfRange(12, encryptedPayload.size)
                            val keySpec = javax.crypto.spec.SecretKeySpec(unwrappedAesKey, "AES")
                            val gcmSpec = javax.crypto.spec.GCMParameterSpec(128, iv)
                            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, keySpec, gcmSpec)
                            val decryptedBody = cipher.doFinal(ciphertext)
                            content = content.copy(body = String(decryptedBody))
                        } catch (e: Exception) {
                            content = content.copy(body = "[Decryption Failed: Invalid Payload]")
                        }
                    } else {
                        content = content.copy(body = "[Decryption Failed: You are not in this group or key unwrapping failed]")
                    }
                }
                
                if (content != null) {
                    val localStub = sharedDb.contentDao().getByHash(hash)
                    if (localStub != null && content !== localStub) {
                        val repAgent = ReputationAgent(getApplication(), KeyManager(getApplication()))
                        content = repAgent.mergeReputation(localStub, content)
                        
                        val totalInteractions = content.likeTokens.size + content.dislikeTokens.size
                        val dynamicThreshold = maxOf(10, (totalInteractions * 0.05).toInt())
                        
                        if (content.reportTokens.size >= dynamicThreshold) {
                            sharedDb.contentDao().delete(hash)
                            db.blacklistDao().insertAll(listOf(app.clearspace.network.storage.db.entity.BlacklistEntry(hash, System.currentTimeMillis())))
                            content = null
                        } else {
                            sharedDb.contentDao().insert(content)
                        }
                    }
                }
                _viewingContent.value = content
            } catch(e: Exception) {
                // Log
            } finally {
                _loadingHeader.value = false
            }
        }
    }
    
    fun saveToPrivateLibrary(unit: ContentUnit, targetVisibility: Visibility = Visibility.PRIVATE) {
        viewModelScope.launch {
            val privateUnit = unit.copy(visibility = targetVisibility)
            AetherDatabase.getPrivateDatabase().contentDao().insert(privateUnit)
            
            if (targetVisibility == Visibility.TRUSTED) {
                val intent = android.content.Intent(getApplication<android.app.Application>(), app.clearspace.network.network.FirestoreDeadDropService::class.java).apply {
                    action = app.clearspace.network.network.FirestoreDeadDropService.ACTION_UPLOAD
                    putExtra(app.clearspace.network.network.FirestoreDeadDropService.EXTRA_CONTENT_JSON, kotlinx.serialization.json.Json.encodeToString(privateUnit))
                }
                getApplication<android.app.Application>().startService(intent)
            }
        }
    }

    fun vote(unit: ContentUnit, isLike: Boolean) {
        viewModelScope.launch {
            val repAgent = ReputationAgent(getApplication(), KeyManager(getApplication()))
            if (!repAgent.canVote()) return@launch

            val token = repAgent.generateVoteToken(unit.contentHash)
            
            // For remote content, save a stub in Shared DB so the vote can be gossiped
            val updatedUnit = if (isLike) {
                unit.copy(likeTokens = unit.likeTokens + token)
            } else {
                unit.copy(dislikeTokens = unit.dislikeTokens + token)
            }
            
            sharedDb.contentDao().insert(updatedUnit.copy(importCount = 0)) // Save as stub if not exists
            app.clearspace.network.storage.StorageQuotaManager.enforcePublicQuota(getApplication())
            
            val likes = updatedUnit.likeTokens.size
            val dislikes = updatedUnit.dislikeTokens.size
            if (likes > 5 && (likes.toDouble() / kotlin.math.max(1, dislikes)) >= 2.0 && updatedUnit.visibility == Visibility.PUBLIC) {
                val intent = android.content.Intent(getApplication<android.app.Application>(), app.clearspace.network.network.FirestoreDeadDropService::class.java)
                intent.action = app.clearspace.network.network.FirestoreDeadDropService.ACTION_GOSSIP_TOP_RATED
                intent.putExtra(app.clearspace.network.network.FirestoreDeadDropService.EXTRA_CONTENT_JSON, kotlinx.serialization.json.Json.encodeToString(updatedUnit))
                getApplication<android.app.Application>().startService(intent)
            }
            
            // Update viewing content
            if (_viewingContent.value?.contentHash == unit.contentHash) {
                _viewingContent.value = updatedUnit
            }
        }
    }

    fun voteFlag(unit: ContentUnit, flagType: FlagType, flagName: String) {
        viewModelScope.launch {
            val repAgent = ReputationAgent(getApplication(), KeyManager(getApplication()))
            if (!repAgent.canVote()) return@launch

            val token = repAgent.generateFlagToken(unit.contentHash, flagName)

            val updatedUnit = when (flagType) {
                FlagType.CATEGORY -> {
                    val currentTokens = unit.categoryTokens.toMutableMap()
                    val flagSet = currentTokens[flagName]?.toMutableSet() ?: mutableSetOf()
                    flagSet.add(token)
                    currentTokens[flagName] = flagSet
                    unit.copy(categoryTokens = currentTokens)
                }
                FlagType.EMOTION -> {
                    val currentTokens = unit.emotionTokens.toMutableMap()
                    val flagSet = currentTokens[flagName]?.toMutableSet() ?: mutableSetOf()
                    flagSet.add(token)
                    currentTokens[flagName] = flagSet
                    unit.copy(emotionTokens = currentTokens)
                }
            }

            sharedDb.contentDao().insert(updatedUnit.copy(importCount = 0))
            app.clearspace.network.storage.StorageQuotaManager.enforcePublicQuota(getApplication())

            if (_viewingContent.value?.contentHash == unit.contentHash) {
                _viewingContent.value = updatedUnit
            }
        }
    }

    fun reportContent(unit: ContentUnit, reason: String) {
        viewModelScope.launch {
            // Delete locally so it's not rendered or shared further
            sharedDb.contentDao().delete(unit.contentHash)
            if (_viewingContent.value?.contentHash == unit.contentHash) {
                _viewingContent.value = null
            }
            
            // Upload the report to the moderation database (Firestore) and register in global blacklist
            try {
                val context = getApplication<Application>()
                app.clearspace.network.moderation.ContentReporter(context).reportContent(unit.contentHash, reason)
                
                val repAgent = ReputationAgent(context, KeyManager(context))
                repAgent.submitReport(unit.contentHash)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun blockAuthor(authorId: String) {
        viewModelScope.launch {
            db.blockedAuthorDao().insert(BlockedAuthor(authorId = authorId))
            // Clear current view if we are viewing their content
            if (_viewingContent.value?.authorAlias == authorId) {
                _viewingContent.value = null
            }
        }
    }
}
