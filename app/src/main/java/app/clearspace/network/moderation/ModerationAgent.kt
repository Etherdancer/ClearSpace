package app.clearspace.network.moderation

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import app.clearspace.network.storage.db.AetherDatabase
import app.clearspace.network.storage.db.entity.BlacklistEntry
import com.google.firebase.firestore.FirebaseFirestore
import androidx.room.withTransaction

class ModerationAgent(private val context: Context) {
    private val db = FirebaseFirestore.getInstance()

    private val prefs = context.getSharedPreferences("moderation_prefs", Context.MODE_PRIVATE)

    suspend fun syncBlacklist() {
        withContext(Dispatchers.IO) {
            try {
                // Client-Side Consensus Moderation
                // Fetch only NEW report tokens from Firebase globally
                val lastSync = prefs.getLong("last_sync_time", 0)
                val result = db.collectionGroup("reports")
                    .whereGreaterThan("timestamp", lastSync)
                    .get().await()
                
                val newTokensByHash = mutableMapOf<String, MutableSet<String>>()
                for (doc in result.documents) {
                    val hash = doc.reference.parent.parent?.id ?: continue
                    val token = doc.getString("reportToken") ?: continue
                    newTokensByHash.getOrPut(hash) { mutableSetOf() }.add(token)
                }

                if (newTokensByHash.isNotEmpty()) {
                    val sharedDb = AetherDatabase.getSharedDatabase()
                    val privateDb = AetherDatabase.getPrivateDatabase()
                    val newlyBlacklisted = mutableListOf<String>()

                    sharedDb.withTransaction {
                        for ((hash, newTokens) in newTokensByHash) {
                            val localUnit = sharedDb.contentDao().getByHash(hash)
                            if (localUnit != null) {
                                // Merge new report tokens
                                val updatedReports = (localUnit.reportTokens + newTokens).toMutableSet()
                                
                                // Cap at 10,000
                                if (updatedReports.size > 10000) {
                                    val toDrop = updatedReports.size - 10000
                                    updatedReports.removeAll(updatedReports.take(toDrop).toSet())
                                }

                                val updatedUnit = localUnit.copy(reportTokens = updatedReports)
                                
                                // Evaluate Dynamic Threshold: 5% of interactions, floor of 10
                                val totalInteractions = updatedUnit.likeTokens.size + updatedUnit.dislikeTokens.size
                                val dynamicThreshold = maxOf(10, (totalInteractions * 0.05).toInt())

                                if (updatedUnit.reportTokens.size >= dynamicThreshold) {
                                    // Blacklist threshold met!
                                    newlyBlacklisted.add(hash)
                                    privateDb.blacklistDao().insertAll(listOf(BlacklistEntry(hash, System.currentTimeMillis())))
                                    sharedDb.contentDao().delete(hash)
                                } else {
                                    // Just update the unit with the new reports
                                    sharedDb.contentDao().insert(updatedUnit)
                                }
                            }
                        }
                    }
                }
                
                // Update sync time
                prefs.edit().putLong("last_sync_time", System.currentTimeMillis()).apply()

            } catch (e: Exception) {
                // Fail CLOSED: apply cached blacklist even without network
                try {
                    val privateDb = AetherDatabase.getPrivateDatabase()
                    val cached = privateDb.blacklistDao().getAll().map { it.hash }
                    if (cached.isNotEmpty()) applyBlacklist(cached)
                } catch (dbEx: Exception) {
                    dbEx.printStackTrace()
                }
            }
        }
    }

    private suspend fun applyBlacklist(bannedHashes: List<String>) {
        val sharedDb = AetherDatabase.getSharedDatabase()
        bannedHashes.forEach { hash ->
            sharedDb.contentDao().delete(hash)
        }
    }

    suspend fun syncTombstones() {
        withContext(Dispatchers.IO) {
            try {
                val lastSync = prefs.getLong("last_tombstone_sync", 0)
                val result = db.collectionGroup("tombstones")
                    .whereGreaterThan("timestamp", lastSync)
                    .get().await()

                if (result.documents.isEmpty()) return@withContext

                val privateDb = AetherDatabase.getPrivateDatabase()
                val sharedDb = AetherDatabase.getSharedDatabase()

                sharedDb.withTransaction {
                    for (doc in result.documents) {
                        val contentHash = doc.getString("contentHash") ?: continue
                        val spaceId = doc.getString("spaceId") ?: continue
                        val moderatorPubKey = doc.getString("moderatorPubKey") ?: continue
                        val signatureBase64 = doc.getString("signatureBase64") ?: continue
                        
                        // 1. Check if moderatorPubKey is actually a moderator for this space
                        val moderators = privateDb.spaceDao().getModeratorsForSpace(spaceId)
                        val isModerator = moderators.any { it.moderatorPubKey == moderatorPubKey }
                        
                        if (isModerator) {
                            // 2. Verify Cryptographic Signature
                            val dataToSign = (contentHash + spaceId).toByteArray(Charsets.UTF_8)
                            val pubKeyBytes = android.util.Base64.decode(moderatorPubKey, android.util.Base64.DEFAULT)
                            val sigBytes = android.util.Base64.decode(signatureBase64, android.util.Base64.DEFAULT)
                            
                            val keyManager = app.clearspace.network.crypto.KeyManager(context)
                            val isValid = try {
                                keyManager.verifySignature(dataToSign, sigBytes, pubKeyBytes)
                            } catch (e: Exception) { false }

                            if (isValid) {
                                // 3. Delete the content
                                sharedDb.contentDao().delete(contentHash)
                                privateDb.blacklistDao().insertAll(listOf(BlacklistEntry(contentHash, System.currentTimeMillis())))
                            }
                        }
                    }
                }
                
                prefs.edit().putLong("last_tombstone_sync", System.currentTimeMillis()).apply()

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
