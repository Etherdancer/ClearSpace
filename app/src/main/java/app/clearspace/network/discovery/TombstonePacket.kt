package app.clearspace.network.discovery

import kotlinx.serialization.Serializable

@Serializable
data class TombstonePacket(
    val contentHash: String,
    val spaceId: String,
    val moderatorPubKey: String, // Ed25519 public key base64
    val signatureBase64: String, // signature over (contentHash + spaceId)
    val timestamp: Long
)
