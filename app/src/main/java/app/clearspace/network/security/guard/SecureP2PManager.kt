package app.clearspace.network.security.guard

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date
import java.util.UUID
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocketFactory
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.cert.CertificateException
import javax.security.auth.x500.X500Principal
import android.content.Context
import android.content.SharedPreferences
import java.net.Socket
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.SSLEngine
import java.security.MessageDigest
import android.util.Base64
object SecureP2PManager {

    private const val TLS_KEY_ALIAS = "aether_p2p_tls_key"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"

    private var sslContext: SSLContext? = null
    private var serverSslContext: SSLContext? = null

    fun init(context: Context) {
        if (sslContext == null) {
            sslContext = initSSLContext(context)
        }
    }

    private fun initSSLContext(context: Context): SSLContext {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        
        if (!keyStore.containsAlias(TLS_KEY_ALIAS)) {
            val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, ANDROID_KEYSTORE)
            val randomSerial = BigInteger(64, SecureRandom())
            val randomId = UUID.randomUUID().toString().take(8)
            val parameterSpec = KeyGenParameterSpec.Builder(
                TLS_KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setCertificateSubject(X500Principal("CN=AetherNode_$randomId"))
                .setCertificateSerialNumber(randomSerial)
                .setCertificateNotBefore(Date())
                .setCertificateNotAfter(Date(System.currentTimeMillis() + 10L * 365 * 24 * 60 * 60 * 1000))
                .build()
            kpg.initialize(parameterSpec)
            kpg.generateKeyPair()
        }

        val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        // In AndroidKeyStore, passing null as the password gets the key
        keyManagerFactory.init(keyStore, null)

        val prefs = context.getSharedPreferences("tofu_certs", Context.MODE_PRIVATE)
        val trustAllCerts = arrayOf<TrustManager>(TofuTrustManager(prefs))

        val context = SSLContext.getInstance("TLSv1.3")
        context.init(keyManagerFactory.keyManagers, trustAllCerts, SecureRandom())

        // Server side: ask every client for its certificate but don't prompt the user for
        // each stranger that browses our public INDEX. Access to trusted-only commands is
        // decided afterwards by P2PServer.isTrustedPeer (fingerprint must be TOFU-pinned).
        serverSslContext = SSLContext.getInstance("TLSv1.3").apply {
            init(keyManagerFactory.keyManagers, arrayOf<TrustManager>(ClientCertificateCollector), SecureRandom())
        }
        return context
    }

    /** Server socket that requests (but doesn't require) a client certificate. */
    fun createServerSocket(port: Int): javax.net.ssl.SSLServerSocket {
        val server = serverSslContext!!.serverSocketFactory.createServerSocket(port) as javax.net.ssl.SSLServerSocket
        server.wantClientAuth = true
        return server
    }

    fun getServerSocketFactory(): SSLServerSocketFactory {
        return serverSslContext!!.serverSocketFactory
    }

    fun getSocketFactory(): SSLSocketFactory {
        return sslContext!!.socketFactory
    }
}

class TofuTrustManager(private val prefs: SharedPreferences) : X509ExtendedTrustManager() {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("Connection blocked: checkClientTrusted without socket or engine is not permitted.")
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("Connection blocked: checkServerTrusted without socket or engine is not permitted.")
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) {
        val ip = socket?.inetAddress?.hostAddress ?: throw CertificateException("Connection blocked: socket has no address.")
        checkTrusted(chain, ip)
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) {
        val ip = socket?.inetAddress?.hostAddress ?: throw CertificateException("Connection blocked: socket has no address.")
        checkTrusted(chain, ip)
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) {
        val ip = engine?.peerHost ?: throw CertificateException("Connection blocked: engine has no peer host.")
        checkTrusted(chain, ip)
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) {
        val ip = engine?.peerHost ?: throw CertificateException("Connection blocked: engine has no peer host.")
        checkTrusted(chain, ip)
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()

    private fun checkTrusted(chain: Array<out X509Certificate>?, ip: String) {
        val cert = chain?.get(0) ?: throw CertificateException("No certificate provided")

        val md = MessageDigest.getInstance("SHA-256")
        val fingerprintBytes = md.digest(cert.encoded)
        val fingerprint = Base64.encodeToString(fingerprintBytes, Base64.NO_WRAP)

        val knownFingerprint = prefs.getString("cert_$ip", null)
        when {
            knownFingerprint == null -> {
                // FIX C2: New peer — request explicit user confirmation before TOFU pinning.
                // We runBlocking here because checkTrusted is called on a background IO thread
                // (inside the TLS handshake), not on the main thread. The connection will hang
                // until the user responds or the socket's soTimeout fires.
                val deferred = TofuPendingManager.requestConfirmation(ip, fingerprint)
                val accepted = kotlinx.coroutines.runBlocking { deferred.await() }
                if (!accepted) {
                    throw CertificateException("User rejected certificate for $ip")
                }
                prefs.edit().putString("cert_$ip", fingerprint).apply()
            }
            knownFingerprint != fingerprint -> {
                // FIX C2: Certificate change after TOFU pin — re-prompt to handle dynamic IPs
                // but never silently accept.
                app.clearspace.network.discovery.P2PClient.logSecurityEvent(
                    "MITM_CERTIFICATE_CHANGE_DETECTED",
                    "peer=$ip known_cert=$knownFingerprint new_cert=$fingerprint"
                )
                val deferred = TofuPendingManager.requestConfirmation(ip, fingerprint)
                val accepted = kotlinx.coroutines.runBlocking { deferred.await() }
                if (!accepted) {
                    throw CertificateException("SECURITY ALERT: Certificate for $ip has changed! Possible MITM attack.")
                }
                prefs.edit().putString("cert_$ip", fingerprint).apply()
            }
            // else: known fingerprint matches — allow silently
        }
    }

}

/**
 * Server-side trust manager: accepts any client certificate so it can be inspected later.
 * Never used for outgoing connections (those use [TofuTrustManager]).
 */
object ClientCertificateCollector : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        if (chain.isNullOrEmpty()) throw CertificateException("Empty client certificate chain")
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("Server certificates are not validated by the collector")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
}
