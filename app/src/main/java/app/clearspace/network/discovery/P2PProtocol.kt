package app.clearspace.network.discovery

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import app.clearspace.network.IAetherIpc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import app.clearspace.network.crypto.KeyManager
import app.clearspace.network.persona.PersonaAgent
import app.clearspace.network.security.guard.SecureP2PManager
import app.clearspace.network.storage.db.AetherDatabase
import app.clearspace.network.storage.db.entity.ContentUnit
import app.clearspace.network.storage.db.entity.RelayPacket
import app.clearspace.network.storage.db.entity.SecurityLogEntry
import app.clearspace.network.storage.db.entity.Visibility
import java.io.InputStream
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

@Serializable
data class ContentHeader(
    val contentHash: String,
    val title: String,
    val authorAlias: String,
    val timestamp: Long,
    val thumbnailBase64: String?,
    val categoryFlags: String,
    val emotionFlags: String,
    val reputationScore: Int,
    val spaceId: String? = null
)

class P2PServer(
    private val context: Context,
    private val ipcService: IAetherIpc
) {
    private var serverSocket: ServerSocket? = null
    private val isRunning = AtomicBoolean(false)
    private val executor = Executors.newFixedThreadPool(10) // Limit concurrency to prevent DoS
    private val activeConnections = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val activeTotalConnections = java.util.concurrent.atomic.AtomicInteger(0)
    private val tofuPrefs: SharedPreferences =
        context.getSharedPreferences("tofu_certs", Context.MODE_PRIVATE)

    companion object {
        private const val MAX_CONNECTIONS_PER_IP = 3
    }

    /**
     * FIX H3: Returns true only if the connecting peer's TLS certificate fingerprint
     * matches a previously TOFU-pinned entry for this IP address.
     * Untrusted peers (new or fingerprint-changed) are denied access to PROFILE and relay data.
     */
    private fun isTrustedPeer(socket: Socket): Boolean {
        val sslSocket = socket as? SSLSocket ?: return false
        val chain = try { sslSocket.session?.peerCertificates } catch (e: Exception) { return false }
        if (chain.isNullOrEmpty()) return false
        val cert = chain[0] as? java.security.cert.X509Certificate ?: return false
        val md = MessageDigest.getInstance("SHA-256")
        val fingerprint = Base64.encodeToString(md.digest(cert.encoded), Base64.NO_WRAP)
        val ip = socket.inetAddress?.hostAddress ?: return false
        return tofuPrefs.getString("cert_$ip", null) == fingerprint
    }
    
    var localPort: Int = 0
        private set

    fun start(): Int {
        if (isRunning.get()) return localPort
        
        // Requests client certificates so isTrustedPeer() can recognise pinned peers.
        // (Without this the peer chain was always empty and trusted-only commands always failed.)
        serverSocket = SecureP2PManager.createServerSocket(0)
        localPort = serverSocket!!.localPort
        isRunning.set(true)

        thread(start = true, name = "P2PServer-Thread") {
            try {
                while (isRunning.get()) {
                    val client = serverSocket?.accept() ?: break
                    client.soTimeout = 3000 // Prevent connection hanging DoS
                    executor.submit { handleClient(client) }
                }
            } catch (e: Exception) {
                // Socket closed or error
            }
        }
        return localPort
    }

    fun stop() {
        isRunning.set(false)
        try {
            serverSocket?.close()
        } catch (e: Exception) {}
        serverSocket = null
        executor.shutdownNow()
        activeConnections.clear()
    }

    private fun handleClient(socket: Socket) {
        val ip = socket.inetAddress?.hostAddress ?: "unknown"
        // Atomic check-and-increment: a get-then-put let simultaneous connections exceed the cap.
        var admitted = false
        activeConnections.compute(ip) { _, current ->
            val c = current ?: 0
            if (c >= MAX_CONNECTIONS_PER_IP) c else { admitted = true; c + 1 }
        }
        if (!admitted) {
            P2PClient.logSecurityEvent("RATE_LIMIT_EXCEEDED", "peer=$ip")
            try { socket.close() } catch (e: Exception) {}
            return
        }

        val totalCount = activeTotalConnections.incrementAndGet()

        try {
            socket.use { s ->
                val input = java.io.BufferedInputStream(s.getInputStream())
                val writer = PrintWriter(java.io.OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8), true)

                if (totalCount > 8) {
                    writer.println("ERROR: BUSY")
                    return@use
                }

                val request = LineIO.readLine(input, 4096) ?: return@use
                val parts = request.split(" ")
                var command = parts[0]

                if (command.endsWith("_v1")) {
                    command = command.removeSuffix("_v1")
                }

                val timestampStr = parts.lastOrNull()
                val timestamp = timestampStr?.toLongOrNull()
                if (timestamp == null || Math.abs(System.currentTimeMillis() - timestamp) > 300000) {
                    writer.println("ERROR: INVALID_TIMESTAMP_OR_EXPIRED")
                    return@use
                }

                when (command) {
                    "INDEX" -> {
                        val response = ipcService.index
                        if (response.isNotEmpty()) writer.println(response) else writer.println("[]")
                    }
                    "GET" -> {
                        if (parts.size > 1) {
                            val hash = parts[1]
                            val response = ipcService.getContent(hash)
                            if (response.isNotEmpty()) {
                                writer.println(response)
                            } else {
                                writer.println("ERROR: NOT_FOUND_OR_PRIVATE")
                            }
                        }
                    }
                    "PROFILE" -> {
                        // FIX H3: Only TOFU-pinned peers may request profiles
                        if (!isTrustedPeer(s)) {
                            writer.println("ERROR: NOT_TRUSTED")
                            return@use
                        }
                        val response = ipcService.profile
                        if (response.isNotEmpty()) {
                            writer.println(response)
                        } else {
                            writer.println("ERROR: PROFILE_PRIVATE_OR_NO_PROFILE")
                        }
                    }
                    "RELAY_INDEX" -> {
                        // FIX H3: Only TOFU-pinned peers may request relay index
                        if (!isTrustedPeer(s)) {
                            writer.println("ERROR: NOT_TRUSTED")
                            return@use
                        }
                        val currentTime = System.currentTimeMillis()
                        val response = ipcService.getRelayIndex(currentTime)
                        if (response.isNotEmpty()) writer.println(response) else writer.println("[]")
                    }
                    "GET_RELAY" -> {
                        // FIX H3: Only TOFU-pinned peers may request relay packets
                        if (!isTrustedPeer(s)) {
                            writer.println("ERROR: NOT_TRUSTED")
                            return@use
                        }
                        if (parts.size > 1) {
                            val packetId = parts[1]
                            val currentTime = System.currentTimeMillis()
                            val response = ipcService.getRelayPacket(currentTime, packetId)
                            if (response.isNotEmpty()) {
                                writer.println(response)
                            } else {
                                writer.println("ERROR: NOT_FOUND")
                            }
                        }
                    }
                    else -> {
                        writer.println("ERROR: UNKNOWN_COMMAND")
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            activeConnections.computeIfPresent(ip) { _, c -> if (c <= 1) null else c - 1 }
            activeTotalConnections.decrementAndGet()
            try { socket.close() } catch (e: Exception) {}
        }
    }

}

object P2PClient {
    // Context needed for KeyManager (signature verification) and SecurityLogDao
    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    // FIX L3: Write security events to SecurityLogDao instead of suppressing them
    internal fun logSecurityEvent(eventType: String, detail: String) {
        try {
            val db = AetherDatabase.getSharedDatabase()
            CoroutineScope(Dispatchers.IO).launch {
                db.securityLogDao().insert(
                    SecurityLogEntry(
                        eventType = eventType,
                        timestamp = System.currentTimeMillis(),
                        detail = detail
                    )
                )
            }
        } catch (e: Exception) {
            // If DB is unavailable, fall back to logcat — never suppress security events silently
            android.util.Log.e("ClearSpaceSecurity", "$eventType: $detail")
        }
    }

    // Shared bounded read (5 MB limit for large responses); UTF-8 safe, buffered.
    private suspend fun readBoundedResponse(input: InputStream, maxLength: Int = 5_000_000): String? {
        val response = LineIO.readLine(java.io.BufferedInputStream(input), maxLength)
        if (response != null && response.startsWith("ERROR: BUSY")) {
            kotlinx.coroutines.delay((1000L..3000L).random())
            return null
        }
        return response
    }

    suspend fun fetchIndex(ip: String, port: Int): List<ContentHeader>? = withContext(Dispatchers.IO) {
        try {
            val socket = SecureP2PManager.getSocketFactory().createSocket()
            socket.use { s ->
                s.connect(java.net.InetSocketAddress(ip, port), 2000)
                s.soTimeout = 5000
                val writer = java.io.PrintWriter(java.io.OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8), true)
                writer.println("INDEX_v1 ${System.currentTimeMillis()}")
                
                val response = readBoundedResponse(socket.getInputStream())
                if (response != null && response.startsWith("[")) {
                    return@withContext Json.decodeFromString<List<ContentHeader>>(response)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext null
    }

    suspend fun fetchContent(ip: String, port: Int, hash: String): ContentUnit? = withContext(Dispatchers.IO) {
        try {
            val socket = SecureP2PManager.getSocketFactory().createSocket()
            socket.use { s ->
                s.connect(java.net.InetSocketAddress(ip, port), 2000)
                s.soTimeout = 10000
                val writer = java.io.PrintWriter(java.io.OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8), true)
                writer.println("GET_v1 $hash ${System.currentTimeMillis()}")
                
                val response = readBoundedResponse(socket.getInputStream())
                if (response != null && response.startsWith("{")) {
                    val unit = Json.decodeFromString<ContentUnit>(response)
                    
                    // Verify data integrity against spoofing/MITM injection
                    val dataToHash = unit.title + unit.body
                    val md = MessageDigest.getInstance("SHA-256")
                    val computedHashBytes = md.digest(dataToHash.toByteArray(Charsets.UTF_8))
                    val computedHash = computedHashBytes.joinToString("") { "%02x".format(it) }
                    
                    if (computedHash == unit.contentHash && computedHash == hash) {
                        if (!app.clearspace.network.reputation.ReputationAgent.verifyProofOfWork(unit.contentHash, unit.powNonce)) {
                            // FIX L3: Log PoW rejection to SecurityLogDao
                            logSecurityEvent("CONTENT_REJECTED_POW_MISMATCH", "peer=$ip hash=$hash")
                            return@withContext null
                        }
                        // FIX C3: Verify Ed25519 authorship signature
                        val keyManager = app.clearspace.network.crypto.KeyManager(appContext)
                        // Network content must be signed (unsigned = could be anyone).
                        if (!app.clearspace.network.crypto.ContentSigner.verifyStrict(unit, keyManager)) {
                            logSecurityEvent("CONTENT_REJECTED_SIGNATURE_INVALID", "peer=$ip hash=$hash alias=${unit.authorAlias}")
                            return@withContext null
                        }
                        return@withContext unit
                    } else {
                        // FIX L3: Log hash mismatch to SecurityLogDao
                        logSecurityEvent("CONTENT_REJECTED_HASH_MISMATCH", "peer=$ip expected=$hash computed=$computedHash")
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext null
    }


    suspend fun fetchProfile(ip: String, port: Int): app.clearspace.network.persona.Profile? = withContext(Dispatchers.IO) {
        try {
            SecureP2PManager.getSocketFactory().createSocket(ip, port).use { socket ->
                socket.soTimeout = 5000
                val writer = PrintWriter(java.io.OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8), true)
                writer.println("PROFILE_v1 ${System.currentTimeMillis()}")
                
                val response = readBoundedResponse(socket.getInputStream(), 1024 * 500) // 500 KB limit for profile
                if (response != null && response.startsWith("{")) {
                    return@withContext Json.decodeFromString<app.clearspace.network.persona.Profile>(response)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext null
    }

    suspend fun fetchRelayIndex(ip: String, port: Int): List<String>? = withContext(Dispatchers.IO) {
        try {
            SecureP2PManager.getSocketFactory().createSocket(ip, port).use { socket ->
                socket.soTimeout = 5000
                val writer = PrintWriter(java.io.OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8), true)
                writer.println("RELAY_INDEX_v1 ${System.currentTimeMillis()}")
                
                val response = readBoundedResponse(socket.getInputStream(), 1024 * 1024)
                if (response != null && response.startsWith("[")) {
                    return@withContext Json.decodeFromString<List<String>>(response)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext null
    }

    suspend fun fetchRelayPacket(ip: String, port: Int, packetId: String): RelayPacket? = withContext(Dispatchers.IO) {
        try {
            SecureP2PManager.getSocketFactory().createSocket(ip, port).use { socket ->
                socket.soTimeout = 10000
                val writer = PrintWriter(java.io.OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8), true)
                writer.println("GET_RELAY_v1 $packetId ${System.currentTimeMillis()}")
                
                val response = readBoundedResponse(socket.getInputStream(), 5_000_000)
                if (response != null && response.startsWith("{")) {
                    return@withContext Json.decodeFromString<RelayPacket>(response)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext null
    }
}

