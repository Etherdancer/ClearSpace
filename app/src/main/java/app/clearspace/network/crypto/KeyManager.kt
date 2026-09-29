package app.clearspace.network.crypto

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.agreement.X25519Agreement
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class KeyManager(context: Context) {

    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val MASTER_KEY_ALIAS = "aether_master_key"
        private const val PREFS_NAME = "aether_crypto_prefs"
        private const val KEY_DB_SECRET = "db_secret_encrypted"
        private const val KEY_ED25519_PRIV = "ed25519_priv_encrypted"
        private const val KEY_ED25519_PUB = "ed25519_pub"
        private const val KEY_X25519_PRIV = "x25519_priv_encrypted"
        private const val KEY_X25519_PUB = "x25519_pub"
        private const val KEY_VOTER_SECRET = "voter_secret_encrypted"
        private const val KEY_BIOMETRIC_PAYLOAD = "biometric_payload"
        private const val BIOMETRIC_KEY_ALIAS = "aether_biometric_key"
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_LENGTH = 128
        private const val KEY_RECOVERY_SQ = "recovery_sq_payload"
        private const val SQ_V2_PREFIX = "v2:"
    }

    init {
        ensureMasterKeyExists()
    }

    private fun ensureMasterKeyExists() {
        if (!keyStore.containsAlias(MASTER_KEY_ALIAS)) {
            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            val builder = KeyGenParameterSpec.Builder(
                MASTER_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            
            // Try StrongBox on Android P+
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    builder.setIsStrongBoxBacked(true)
                    keyGenerator.init(builder.build())
                    keyGenerator.generateKey()
                    return
                }
            } catch (e: Exception) {
                // Fallback to TEE
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    builder.setIsStrongBoxBacked(false)
                }
            }
            keyGenerator.init(builder.build())
            keyGenerator.generateKey()
        }
    }

    private fun getMasterKey(): SecretKey {
        return keyStore.getKey(MASTER_KEY_ALIAS, null) as SecretKey
    }

    fun encryptData(data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getMasterKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(data)
        return iv + encrypted
    }

    fun decryptData(data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = data.copyOfRange(0, GCM_IV_LENGTH)
        val encrypted = data.copyOfRange(GCM_IV_LENGTH, data.size)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.DECRYPT_MODE, getMasterKey(), spec)
        return cipher.doFinal(encrypted)
    }

    private fun getDbSecret(): ByteArray {
        val encryptedStr = prefs.getString(KEY_DB_SECRET, null)
        if (encryptedStr != null) {
            return decryptData(Base64.decode(encryptedStr, Base64.DEFAULT))
        }
        val secret = ByteArray(32).apply { SecureRandom().nextBytes(this) }
        val enc = encryptData(secret)
        prefs.edit().putString(KEY_DB_SECRET, Base64.encodeToString(enc, Base64.DEFAULT)).apply()
        return secret
    }

    /**
     * Derives the SQLCipher key by XORing the Argon2id hash with a hardware-protected random secret.
     */
    fun deriveSqlCipherKey(argon2Hash: ByteArray): ByteArray {
        require(argon2Hash.size == 32) { "Argon2 hash must be 32 bytes" }
        val dbSecret = getDbSecret()
        val result = ByteArray(32)
        for (i in 0 until 32) {
            result[i] = (argon2Hash[i].toInt() xor dbSecret[i].toInt()).toByte()
        }
        java.util.Arrays.fill(dbSecret, 0.toByte())
        return result
    }

    fun getOrGenerateIdentity(): Pair<ByteArray, ByteArray> {
        val privEncStr = prefs.getString(KEY_ED25519_PRIV, null)
        val pubStr = prefs.getString(KEY_ED25519_PUB, null)
        if (privEncStr != null && pubStr != null) {
            val priv = decryptData(Base64.decode(privEncStr, Base64.DEFAULT))
            val pubBytes = Base64.decode(pubStr, Base64.DEFAULT)
            return Pair(priv, pubBytes)
        }
        
        val gen = Ed25519KeyPairGenerator()
        gen.init(Ed25519KeyGenerationParameters(SecureRandom()))
        val pair: AsymmetricCipherKeyPair = gen.generateKeyPair()
        val privParams = pair.private as Ed25519PrivateKeyParameters
        val pubParams = pair.public as Ed25519PublicKeyParameters
        
        val privBytes = privParams.encoded
        val pubBytes = pubParams.encoded
        
        val encPriv = encryptData(privBytes)
        prefs.edit()
            .putString(KEY_ED25519_PRIV, Base64.encodeToString(encPriv, Base64.DEFAULT))
            .putString(KEY_ED25519_PUB, Base64.encodeToString(pubBytes, Base64.DEFAULT))
            .apply()
            
        return Pair(privBytes, pubBytes)
    }

    fun regenerateIdentities() {
        prefs.edit()
            .remove(KEY_ED25519_PRIV)
            .remove(KEY_ED25519_PUB)
            .remove(KEY_X25519_PRIV)
            .remove(KEY_X25519_PUB)
            .apply()
    }

    fun getOrGenerateEncryptionIdentity(): Pair<ByteArray, ByteArray> {
        val privEncStr = prefs.getString(KEY_X25519_PRIV, null)
        val pubStr = prefs.getString(KEY_X25519_PUB, null)
        if (privEncStr != null && pubStr != null) {
            val priv = decryptData(Base64.decode(privEncStr, Base64.DEFAULT))
            val pubBytes = Base64.decode(pubStr, Base64.DEFAULT)
            return Pair(priv, pubBytes)
        }
        
        val gen = X25519KeyPairGenerator()
        gen.init(X25519KeyGenerationParameters(SecureRandom()))
        val pair: AsymmetricCipherKeyPair = gen.generateKeyPair()
        val privParams = pair.private as X25519PrivateKeyParameters
        val pubParams = pair.public as X25519PublicKeyParameters
        
        val privBytes = privParams.encoded
        val pubBytes = pubParams.encoded
        
        val encPriv = encryptData(privBytes)
        prefs.edit()
            .putString(KEY_X25519_PRIV, Base64.encodeToString(encPriv, Base64.DEFAULT))
            .putString(KEY_X25519_PUB, Base64.encodeToString(pubBytes, Base64.DEFAULT))
            .apply()
            
        return Pair(privBytes, pubBytes)
    }

    fun storePasswordHash(hash: ByteArray) {
        val enc = encryptData(hash)
        prefs.edit().putString("password_hash_encrypted", Base64.encodeToString(enc, Base64.DEFAULT)).apply()
    }

    fun getStoredPasswordHash(): ByteArray? {
        val encStr = prefs.getString("password_hash_encrypted", null) ?: return null
        return decryptData(Base64.decode(encStr, Base64.DEFAULT))
    }

    fun getOrGenerateVoterSecret(): ByteArray {
        val encStr = prefs.getString(KEY_VOTER_SECRET, null)
        if (encStr != null) {
            return decryptData(Base64.decode(encStr, Base64.DEFAULT))
        }
        val secret = ByteArray(32).apply { SecureRandom().nextBytes(this) }
        val encSecret = encryptData(secret)
        prefs.edit().putString(KEY_VOTER_SECRET, Base64.encodeToString(encSecret, Base64.DEFAULT)).apply()
        return secret
    }

    fun wipeAllKeys() {
        try {
            keyStore.deleteEntry(MASTER_KEY_ALIAS)
        } catch (e: Exception) {
            // Ignore
        }
        prefs.edit().clear().apply()
    }

    fun signData(data: ByteArray): ByteArray {
        val privEncStr = prefs.getString(KEY_ED25519_PRIV, null) ?: throw IllegalStateException("No identity key found")
        val privBytes = decryptData(Base64.decode(privEncStr, Base64.DEFAULT))
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(privBytes, 0))
        signer.update(data, 0, data.size)
        val signature = signer.generateSignature()
        java.util.Arrays.fill(privBytes, 0.toByte())
        return signature
    }

    fun verifySignature(data: ByteArray, signature: ByteArray, pubKey: ByteArray): Boolean {
        val signer = Ed25519Signer()
        signer.init(false, Ed25519PublicKeyParameters(pubKey, 0))
        signer.update(data, 0, data.size)
        return signer.verifySignature(signature)
    }

    fun getEd25519Fingerprint(): String {
        val pub = getOrGenerateIdentity().second
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(pub)
        return Base64.encodeToString(digest, Base64.NO_WRAP)
    }

    fun computeECDHSharedSecret(peerPublicKey: ByteArray): ByteArray {
        val myPrivEncStr = prefs.getString(KEY_X25519_PRIV, null) ?: throw IllegalStateException("No X25519 identity key found")
        val myPrivBytes = decryptData(Base64.decode(myPrivEncStr, Base64.DEFAULT))

        val agreement = X25519Agreement()
        agreement.init(X25519PrivateKeyParameters(myPrivBytes, 0))
        val sharedSecret = ByteArray(agreement.agreementSize)
        agreement.calculateAgreement(X25519PublicKeyParameters(peerPublicKey, 0), sharedSecret, 0)
        
        java.util.Arrays.fill(myPrivBytes, 0.toByte())
        return sharedSecret
    }

    fun encryptPayloadE2EE(payload: ByteArray, sharedSecret: ByteArray): ByteArray {
        val iv = ByteArray(GCM_IV_LENGTH).apply { SecureRandom().nextBytes(this) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keyBytes = java.security.MessageDigest.getInstance("SHA-256").digest(sharedSecret)
        val keySpec = javax.crypto.spec.SecretKeySpec(keyBytes, "AES")
        
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        val encrypted = cipher.doFinal(payload)
        java.util.Arrays.fill(keyBytes, 0.toByte())
        return iv + encrypted
    }

    fun decryptPayloadE2EE(data: ByteArray, sharedSecret: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keyBytes = java.security.MessageDigest.getInstance("SHA-256").digest(sharedSecret)
        val keySpec = javax.crypto.spec.SecretKeySpec(keyBytes, "AES")
        
        val iv = data.copyOfRange(0, GCM_IV_LENGTH)
        val encrypted = data.copyOfRange(GCM_IV_LENGTH, data.size)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        
        val decrypted = cipher.doFinal(encrypted)
        java.util.Arrays.fill(keyBytes, 0.toByte())
        return decrypted
    }

    // --- Biometric Authentication Support ---

    fun hasBiometricKey(): Boolean {
        return prefs.contains(KEY_BIOMETRIC_PAYLOAD) && keyStore.containsAlias(BIOMETRIC_KEY_ALIAS)
    }

    fun getBiometricCipher(mode: Int): Cipher {
        if (!keyStore.containsAlias(BIOMETRIC_KEY_ALIAS)) {
            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            val builder = KeyGenParameterSpec.Builder(
                BIOMETRIC_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
            } else {
                @Suppress("DEPRECATION")
                builder.setUserAuthenticationValidityDurationSeconds(-1)
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                builder.setInvalidatedByBiometricEnrollment(true)
            }

            // Depending on API level, set StrongBox or other auth parameters if needed
            keyGenerator.init(builder.build())
            keyGenerator.generateKey()
        }
        val key = keyStore.getKey(BIOMETRIC_KEY_ALIAS, null) as SecretKey
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        
        if (mode == Cipher.ENCRYPT_MODE) {
            cipher.init(Cipher.ENCRYPT_MODE, key)
        } else {
            val payloadStr = prefs.getString(KEY_BIOMETRIC_PAYLOAD, null) ?: throw IllegalStateException("No biometric payload found")
            val payload = Base64.decode(payloadStr, Base64.DEFAULT)
            val iv = payload.copyOfRange(0, GCM_IV_LENGTH)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, key, spec)
        }
        
        return cipher
    }

    fun storeBiometricPayload(cipher: Cipher, hash: ByteArray) {
        val encrypted = cipher.doFinal(hash)
        val payload = cipher.iv + encrypted
        prefs.edit().putString(KEY_BIOMETRIC_PAYLOAD, Base64.encodeToString(payload, Base64.DEFAULT)).apply()
    }

    fun getBiometricPayload(cipher: Cipher): ByteArray {
        val payloadStr = prefs.getString(KEY_BIOMETRIC_PAYLOAD, null) ?: throw IllegalStateException("No biometric payload found")
        val payload = Base64.decode(payloadStr, Base64.DEFAULT)
        val encrypted = payload.copyOfRange(GCM_IV_LENGTH, payload.size)
        return cipher.doFinal(encrypted)
    }

    // --- Password Recovery Support ---
    
    fun resetPassword(newArgon2Hash: ByteArray, sqlCipherKey: ByteArray) {
        require(newArgon2Hash.size == 32) { "Argon2 hash must be 32 bytes" }
        require(sqlCipherKey.size == 32) { "SQLCipher key must be 32 bytes" }
        
        val newDbSecret = ByteArray(32)
        for (i in 0 until 32) {
            newDbSecret[i] = (sqlCipherKey[i].toInt() xor newArgon2Hash[i].toInt()).toByte()
        }
        val enc = encryptData(newDbSecret)
        prefs.edit().putString(KEY_DB_SECRET, android.util.Base64.encodeToString(enc, android.util.Base64.DEFAULT)).apply()
    }

    /**
     * Normalises answers so recovery isn't defeated by case or stray whitespace
     * ("  Zagreb " == "zagreb").
     */
    private fun normalizeAnswers(answers: List<String>): CharArray =
        answers.joinToString("|") { it.trim().lowercase(java.util.Locale.ROOT).replace(Regex("\\s+"), " ") }.toCharArray()

    /**
     * Stores the SQLCipher key encrypted under an Argon2id key derived from the answers.
     * Format (v2): "v2:" + Base64(salt[16] || iv || ciphertext).
     *
     * v1 hashed the answers with a random salt that was never stored, so recovery could
     * never derive the same key again — every v1 payload is unrecoverable.
     */
    fun setupSecurityQuestions(answers: List<String>, sqlCipherKey: ByteArray) {
        val salt = PasswordHasher.generateSalt()
        val (_, recoveryKey) = PasswordHasher.hash(normalizeAnswers(answers), salt, clearPassword = true)
        val encryptedSqlCipherKey = encryptPayloadE2EE(sqlCipherKey, recoveryKey)
        java.util.Arrays.fill(recoveryKey, 0.toByte())
        prefs.edit()
            .putString(KEY_RECOVERY_SQ, SQ_V2_PREFIX + Base64.encodeToString(salt + encryptedSqlCipherKey, Base64.NO_WRAP))
            .apply()
    }

    /** True if security-question recovery was set up with the broken v1 format (must be redone). */
    fun hasLegacySecurityQuestions(): Boolean =
        prefs.getString(KEY_RECOVERY_SQ, null)?.startsWith(SQ_V2_PREFIX) == false

    fun hasSecurityQuestions(): Boolean = prefs.getString(KEY_RECOVERY_SQ, null)?.startsWith(SQ_V2_PREFIX) == true

    fun recoverWithSecurityQuestions(answers: List<String>): ByteArray? {
        val stored = prefs.getString(KEY_RECOVERY_SQ, null) ?: return null
        if (!stored.startsWith(SQ_V2_PREFIX)) return null // v1: salt was never stored
        val blob = Base64.decode(stored.removePrefix(SQ_V2_PREFIX), Base64.DEFAULT)
        if (blob.size <= PasswordHasher.SALT_LENGTH + GCM_IV_LENGTH) return null
        val salt = blob.copyOfRange(0, PasswordHasher.SALT_LENGTH)
        val payload = blob.copyOfRange(PasswordHasher.SALT_LENGTH, blob.size)
        val (_, recoveryKey) = PasswordHasher.hash(normalizeAnswers(answers), salt, clearPassword = true)
        return try {
            decryptPayloadE2EE(payload, recoveryKey)
        } catch (e: Exception) {
            null
        } finally {
            java.util.Arrays.fill(recoveryKey, 0.toByte())
        }
    }

    fun setupEmergencyKey(emergencyKey: ByteArray, sqlCipherKey: ByteArray) {
        val encryptedSqlCipherKey = encryptPayloadE2EE(sqlCipherKey, emergencyKey)
        prefs.edit().putString("recovery_ek_payload", android.util.Base64.encodeToString(encryptedSqlCipherKey, android.util.Base64.DEFAULT)).apply()
    }

    fun recoverWithEmergencyKey(emergencyKey: ByteArray): ByteArray? {
        val payloadStr = prefs.getString("recovery_ek_payload", null) ?: return null
        val payload = android.util.Base64.decode(payloadStr, android.util.Base64.DEFAULT)
        return try {
            decryptPayloadE2EE(payload, emergencyKey)
        } catch (e: Exception) {
            null
        }
    }
}
