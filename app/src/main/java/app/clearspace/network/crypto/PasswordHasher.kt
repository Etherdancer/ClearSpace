package app.clearspace.network.crypto

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Arrays

object PasswordHasher {
    const val SALT_LENGTH = 16
    private const val HASH_LENGTH = 32
    
    // 64 MB = 64 * 1024 KB
    private const val MEMORY = 64 * 1024
    private const val ITERATIONS = 3
    private const val PARALLELISM = 1

    /**
     * Hashes a password using Argon2id.
     * @param password The password as CharArray (will be cleared after use if `clearPassword` is true).
     * @param salt Optional salt, generates a new one if not provided.
     * @return Pair of (Salt, Hash).
     */
    fun hash(password: CharArray, salt: ByteArray = generateSalt(), clearPassword: Boolean = true): Pair<ByteArray, ByteArray> {
        val parameters = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withIterations(ITERATIONS)
            .withMemoryAsKB(MEMORY)
            .withParallelism(PARALLELISM)
            .withSalt(salt)
            .build()
            
        val generator = Argon2BytesGenerator()
        generator.init(parameters)
        
        val result = ByteArray(HASH_LENGTH)
        val passwordBytes = toBytes(password)
        
        try {
            generator.generateBytes(passwordBytes, result, 0, result.size)
            return Pair(salt, result)
        } finally {
            // Zero out password bytes
            Arrays.fill(passwordBytes, 0.toByte())
            if (clearPassword) {
                Arrays.fill(password, '\u0000')
            }
        }
    }

    /**
     * Verifies a password against an expected salt and hash.
     */
    fun verify(password: CharArray, expectedSalt: ByteArray, expectedHash: ByteArray, clearPassword: Boolean = true): Boolean {
        val (_, computedHash) = hash(password, expectedSalt, clearPassword)
        // Constant time comparison
        return MessageDigest.isEqual(expectedHash, computedHash)
    }

    fun generateSalt(): ByteArray {
        val salt = ByteArray(SALT_LENGTH)
        SecureRandom().nextBytes(salt)
        return salt
    }

    private fun toBytes(chars: CharArray): ByteArray {
        val charBuffer = CharBuffer.wrap(chars)
        val byteBuffer: ByteBuffer = StandardCharsets.UTF_8.encode(charBuffer)
        val bytes = ByteArray(byteBuffer.limit())
        byteBuffer.get(bytes)
        
        // Clear buffers
        Arrays.fill(byteBuffer.array(), 0.toByte())
        
        return bytes
    }

    fun hashPassword(password: CharArray): ByteArray {
        val (salt, hash) = hash(password, clearPassword = true)
        return salt + hash
    }

    fun verifyPassword(password: CharArray, storedSaltHash: ByteArray): Boolean {
        if (storedSaltHash.size != SALT_LENGTH + HASH_LENGTH) return false
        val salt = storedSaltHash.copyOfRange(0, SALT_LENGTH)
        val expectedHash = storedSaltHash.copyOfRange(SALT_LENGTH, storedSaltHash.size)
        return verify(password, salt, expectedHash, clearPassword = true)
    }

    fun computeHashForDbKey(password: CharArray, storedSaltHash: ByteArray): ByteArray? {
        if (storedSaltHash.size != SALT_LENGTH + HASH_LENGTH) return null
        val salt = storedSaltHash.copyOfRange(0, SALT_LENGTH)
        val expectedHash = storedSaltHash.copyOfRange(SALT_LENGTH, storedSaltHash.size)
        val (_, computedHash) = hash(password, salt, clearPassword = true)
        if (MessageDigest.isEqual(expectedHash, computedHash)) {
            return computedHash
        }
        return null
    }
}
