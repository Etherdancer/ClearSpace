package app.clearspace.network.crypto

import android.util.Base64
import app.clearspace.network.storage.db.entity.ContentUnit

/**
 * Signs and verifies ContentUnit authorship using the local Ed25519 identity key.
 *
 * Security: Without signatures, any peer on the network can forge a ContentUnit
 * claiming any authorAlias. The Ed25519 keypair already exists in KeyManager but
 * was previously unused for content signing. (FIX C3 from security audit.)
 *
 * Backwards compatibility: Old content with no signature has null authorSignatureBase64.
 * [verify] treats null signatures as valid to avoid breaking existing content.
 * New content created after this fix will always carry a signature.
 */
object ContentSigner {

    /**
     * Returns the canonical bytes that are signed.
     * Binds contentHash (derived from title+body), authorAlias, and timestamp.
     * Mutable fields (like reputation tokens) are deliberately excluded so that
     * token accumulation does not break signature verification.
     */
    fun getSignableBytes(unit: ContentUnit): ByteArray =
        "${unit.contentHash}|${unit.authorAlias}|${unit.timestamp}".toByteArray(Charsets.UTF_8)

    /**
     * Returns a copy of [unit] with [authorSignatureBase64] and [authorPublicKeyBase64] set.
     * Call this immediately before persisting new content to the database.
     */
    fun sign(unit: ContentUnit, keyManager: KeyManager): ContentUnit {
        val bytes  = getSignableBytes(unit)
        val sig    = keyManager.signData(bytes)
        val pubKey = keyManager.getOrGenerateIdentity().second
        return unit.copy(
            authorSignatureBase64  = Base64.encodeToString(sig, Base64.NO_WRAP),
            authorPublicKeyBase64  = Base64.encodeToString(pubKey, Base64.NO_WRAP)
        )
    }

    /**
     * Returns true if the signature on [unit] is valid, OR if the unit has no signature
     * (legacy unsigned content — backwards-compatible pass-through).
     *
     * Returns false only when a signature IS present but fails verification — indicating
     * deliberate tampering or author impersonation.
     */
    /**
     * Strict check for content received from the network: a signature is REQUIRED.
     * Accepting unsigned content would let any peer publish under any alias simply by
     * leaving the signature fields out.
     */
    fun verifyStrict(unit: ContentUnit, keyManager: KeyManager): Boolean {
        if (unit.authorSignatureBase64 == null || unit.authorPublicKeyBase64 == null) return false
        return verify(unit, keyManager)
    }

    fun verify(unit: ContentUnit, keyManager: KeyManager): Boolean {
        val sigB64 = unit.authorSignatureBase64 ?: return true   // Legacy: no signature = accept
        val pubB64 = unit.authorPublicKeyBase64 ?: return true   // Legacy: no public key = accept
        return try {
            val sig = Base64.decode(sigB64, Base64.DEFAULT)
            val pub = Base64.decode(pubB64, Base64.DEFAULT)
            keyManager.verifySignature(getSignableBytes(unit), sig, pub)
        } catch (e: Exception) {
            false // Malformed Base64 or key format = reject
        }
    }
}
