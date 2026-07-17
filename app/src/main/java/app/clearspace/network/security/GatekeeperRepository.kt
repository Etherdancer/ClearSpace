package app.clearspace.network.security

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import app.clearspace.network.crypto.KeyManager
import app.clearspace.network.crypto.PasswordHasher

class GatekeeperRepository(private val context: Context, private val keyManager: KeyManager) {

    private val prefs: SharedPreferences = context.getSharedPreferences("gatekeeper_prefs", Context.MODE_PRIVATE)

    private val _authState = MutableStateFlow<AuthState>(AuthState.Unauthenticated)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    fun hasCompletedOnboarding(): Boolean {
        return prefs.getBoolean(PREF_ONBOARDING_COMPLETE, false)
    }

    fun completeOnboarding(password: CharArray): ByteArray {
        val (salt, hash) = PasswordHasher.hash(password, clearPassword = false)
        val fullHash = salt + hash
        keyManager.storePasswordHash(fullHash)
        prefs.edit().putBoolean(PREF_ONBOARDING_COMPLETE, true).apply()
        return keyManager.deriveSqlCipherKey(hash)
    }

    fun isLockedOut(): Boolean {
        val lockoutUntil = getLockoutUntil()
        return System.currentTimeMillis() < lockoutUntil
    }

    fun getRemainingLockoutSeconds(): Long {
        val lockoutUntil = getLockoutUntil()
        val remaining = lockoutUntil - System.currentTimeMillis()
        return if (remaining > 0) remaining / 1000 else 0
    }

    private fun getFailedAttempts(): Int {
        val encStr = try {
            prefs.getString(PREF_FAILED_ATTEMPTS, null)
        } catch (e: ClassCastException) {
            prefs.edit().remove(PREF_FAILED_ATTEMPTS).apply()
            null
        } ?: return 0
        return try {
            val bytes = keyManager.decryptData(android.util.Base64.decode(encStr, android.util.Base64.DEFAULT))
            java.nio.ByteBuffer.wrap(bytes).int
        } catch (e: Exception) { 0 }
    }

    private fun setFailedAttempts(attempts: Int, editor: SharedPreferences.Editor) {
        val bytes = java.nio.ByteBuffer.allocate(4).putInt(attempts).array()
        val enc = keyManager.encryptData(bytes)
        editor.putString(PREF_FAILED_ATTEMPTS, android.util.Base64.encodeToString(enc, android.util.Base64.DEFAULT))
    }

    private fun getLockoutUntil(): Long {
        val encStr = try {
            prefs.getString(PREF_LOCKOUT_UNTIL, null)
        } catch (e: ClassCastException) {
            prefs.edit().remove(PREF_LOCKOUT_UNTIL).apply()
            null
        } ?: return 0L
        return try {
            val bytes = keyManager.decryptData(android.util.Base64.decode(encStr, android.util.Base64.DEFAULT))
            java.nio.ByteBuffer.wrap(bytes).long
        } catch (e: Exception) { 0L }
    }

    private fun setLockoutUntil(time: Long, editor: SharedPreferences.Editor) {
        val bytes = java.nio.ByteBuffer.allocate(8).putLong(time).array()
        val enc = keyManager.encryptData(bytes)
        editor.putString(PREF_LOCKOUT_UNTIL, android.util.Base64.encodeToString(enc, android.util.Base64.DEFAULT))
    }

    fun authenticate(password: CharArray): ByteArray? {
        if (isLockedOut()) return null

        val storedHash = keyManager.getStoredPasswordHash() ?: return null
        val computedHash = PasswordHasher.computeHashForDbKey(password, storedHash)

        if (computedHash != null) {
            resetFailedAttempts()
            _authState.value = AuthState.Authenticated
            return keyManager.deriveSqlCipherKey(computedHash)
        } else {
            recordFailedAttempt()
            return null
        }
    }

    fun authenticateWithBiometric(cipher: javax.crypto.Cipher): ByteArray? {
        if (isLockedOut()) return null
        return try {
            val hash = keyManager.getBiometricPayload(cipher)
            resetFailedAttempts()
            _authState.value = AuthState.Authenticated
            keyManager.deriveSqlCipherKey(hash)
        } catch (e: Exception) {
            recordFailedAttempt()
            null
        }
    }

    fun canUseBiometric(): Boolean {
        return keyManager.hasBiometricKey()
    }

    fun getBiometricCipher(mode: Int): javax.crypto.Cipher {
        return keyManager.getBiometricCipher(mode)
    }

    fun enrollBiometric(cipher: javax.crypto.Cipher, password: CharArray): Boolean {
        val storedHash = keyManager.getStoredPasswordHash() ?: return false
        val computedHash = PasswordHasher.computeHashForDbKey(password, storedHash) ?: return false
        return try {
            keyManager.storeBiometricPayload(cipher, computedHash)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun recordFailedAttempt() {
        val attempts = getFailedAttempts() + 1
        val editor = prefs.edit()
        setFailedAttempts(attempts, editor)

        if (attempts >= 20) {
            // Self-destruct condition: Wipe keys
            keyManager.wipeAllKeys()
            // In a real app we'd also clear the DB files here, but since the keys are gone, the DB is cryptographically shredded.
            setLockoutUntil(Long.MAX_VALUE, editor) // Permanent lockout
        } else if (attempts >= 5) {
            val lockoutMinutes = when {
                attempts >= 15 -> 15.0
                attempts >= 10 -> 5.0
                attempts >= 7 -> 1.0
                else -> 0.5 // 30 seconds
            }
            val lockoutUntil = System.currentTimeMillis() + (lockoutMinutes * 60.0 * 1000.0).toLong()
            setLockoutUntil(lockoutUntil, editor)
        }
        editor.apply()
    }

    private fun resetFailedAttempts() {
        val editor = prefs.edit()
        setFailedAttempts(0, editor)
        setLockoutUntil(0L, editor)
        editor.apply()
    }

    fun isRecoverySqEnabled(): Boolean = prefs.getBoolean(PREF_RECOVERY_SQ, false)
    fun isRecoveryEkEnabled(): Boolean = prefs.getBoolean(PREF_RECOVERY_EK, false)
    fun isRecoveryBioEnabled(): Boolean = prefs.getBoolean(PREF_RECOVERY_BIO, false)

    fun setupSecurityQuestions(answers: List<String>, sqlCipherKey: ByteArray) {
        keyManager.setupSecurityQuestions(answers, sqlCipherKey)
        prefs.edit().putBoolean(PREF_RECOVERY_SQ, true).apply()
    }

    fun setupEmergencyKey(emergencyKey: ByteArray, sqlCipherKey: ByteArray) {
        keyManager.setupEmergencyKey(emergencyKey, sqlCipherKey)
        prefs.edit().putBoolean(PREF_RECOVERY_EK, true).apply()
    }

    fun enableBiometricRecovery() {
        prefs.edit().putBoolean(PREF_RECOVERY_BIO, true).apply()
    }

    fun recoverWithSecurityQuestions(answers: List<String>): ByteArray? {
        if (isLockedOut()) return null
        val key = keyManager.recoverWithSecurityQuestions(answers)
        if (key != null) {
            resetFailedAttempts()
        } else {
            recordFailedAttempt()
        }
        return key
    }

    fun recoverWithEmergencyKey(emergencyKey: ByteArray): ByteArray? {
        if (isLockedOut()) return null
        val key = keyManager.recoverWithEmergencyKey(emergencyKey)
        if (key != null) {
            resetFailedAttempts()
        } else {
            recordFailedAttempt()
        }
        return key
    }

    fun resetPassword(newPassword: CharArray, sqlCipherKey: ByteArray): ByteArray {
        val (salt, hash) = PasswordHasher.hash(newPassword, clearPassword = false)
        val fullHash = salt + hash
        keyManager.resetPassword(hash, sqlCipherKey)
        keyManager.storePasswordHash(fullHash)
        _authState.value = AuthState.Authenticated
        return keyManager.deriveSqlCipherKey(hash)
    }

    companion object {
        private const val PREF_ONBOARDING_COMPLETE = "onboarding_complete"
        private const val PREF_FAILED_ATTEMPTS = "failed_attempts"
        private const val PREF_LOCKOUT_UNTIL = "lockout_until"
        private const val PREF_RECOVERY_SQ = "recovery_sq_enabled"
        private const val PREF_RECOVERY_EK = "recovery_ek_enabled"
        private const val PREF_RECOVERY_BIO = "recovery_bio_enabled"
    }
}

sealed class AuthState {
    object Unauthenticated : AuthState()
    object Authenticated : AuthState()
}
