package app.clearspace.network.storage.db.entity

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
enum class Visibility {
    PRIVATE, TRUSTED, PUBLIC, GROUP
}

@Serializable
@Entity(tableName = "content_units")
data class ContentUnit(
    @PrimaryKey val contentHash: String,
    val title: String,
    val body: String,
    val categoryFlags: String,
    val emotionFlags: String,
    val visibility: Visibility,
    val authorAlias: String,
    val timestamp: Long,
    val importCount: Int,
    val powNonce: Long,
    val likeTokens: Set<String>,
    val dislikeTokens: Set<String>,
    val reportTokens: Set<String> = emptySet(),
    val categoryTokens: Map<String, Set<String>> = emptyMap(),
    val emotionTokens: Map<String, Set<String>> = emptyMap(),
    val imagePath: String? = null,
    val videoPath: String? = null,
    val thumbnailPath: String? = null,
    val thumbnailBase64: String? = null,
    val recipientKeyMapJson: String? = null,
    // FIX C3: Ed25519 authorship signature. Null = legacy unsigned content (treated as valid).
    val authorSignatureBase64: String? = null,
    val authorPublicKeyBase64: String? = null,
    val spaceId: String? = null
)


@Fts4(contentEntity = ContentUnit::class)
@Entity(tableName = "content_units_fts")
data class ContentFtsEntity(
    val title: String,
    val body: String,
    val categoryFlags: String,
    val authorAlias: String
)
