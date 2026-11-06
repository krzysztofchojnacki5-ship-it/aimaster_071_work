package pl.aimasterpro.app.core

import java.security.KeyStore
import java.security.SecureRandom
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import java.net.Socket
import java.net.InetSocketAddress
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509KeyManager
import javax.net.ssl.X509TrustManager

/**
 * Platform-provider TLS setup for the future Companion transport.
 *
 * This class deliberately accepts caller-provisioned KeyStores. It does not create certificates,
 * persist secrets, open sockets, or weaken certificate/hostname validation. Both peers must trust
 * the issuing CA(s), and the identity store must contain a private-key entry with a certificate
 * chain. The client socket additionally performs HTTPS endpoint identification (SAN/hostname).
 */
object CompanionTlsContext {
    private val allowedProtocols = setOf("TLSv1.3", "TLSv1.2")

    /**
     * Creates an mTLS context that requires both normal platform PKIX validation and an explicit
     * SPKI SHA-256 pin match for the peer leaf certificate. Multiple pins allow a controlled
     * current/next-key overlap during a future rotation. No empty-pin or trust-all mode is allowed.
     */
    fun createPinnedMutualTlsContext(
        identityKeyStore: KeyStore,
        keyPassword: CharArray?,
        trustStore: KeyStore,
        peerPins: Set<CompanionSpkiSha256Pin>
    ): SSLContext {
        require(peerPins.isNotEmpty()) { "Lista pinów SPKI peer nie może być pusta." }
        require(hasPrivateKeyIdentity(identityKeyStore, keyPassword)) {
            "Magazyn tożsamości musi zawierać klucz prywatny i łańcuch certyfikatów."
        }
        require(hasTrustedCertificates(trustStore)) {
            "Magazyn zaufania nie zawiera certyfikatów CA/tożsamości."
        }

        val keyFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        keyFactory.init(identityKeyStore, keyPassword)
        require(keyFactory.keyManagers.any { it is X509KeyManager }) {
            "Dostawca TLS nie udostępnił menedżera kluczy X.509."
        }

        val trustFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        trustFactory.init(trustStore)
        val delegate = trustFactory.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
            ?: throw IllegalStateException("Dostawca TLS nie udostępnił menedżera zaufania X.509.")
        val pinnedTrustManager = PinnedPeerTrustManager(delegate, peerPins.toSet())

        return SSLContext.getInstance("TLS").apply {
            init(keyFactory.keyManagers, arrayOf(pinnedTrustManager), SecureRandom())
        }
    }

    private class PinnedPeerTrustManager(
        private val delegate: X509TrustManager,
        pins: Set<CompanionSpkiSha256Pin>
    ) : X509ExtendedTrustManager() {
        private val peerPins = pins.toSet()
        private val extended = delegate as? X509ExtendedTrustManager

        override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {
            delegate.checkClientTrusted(chain, authType)
            requirePinned(chain)
        }

        override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {
            delegate.checkServerTrusted(chain, authType)
            requirePinned(chain)
        }

        override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String, socket: Socket) {
            if (extended != null) extended.checkClientTrusted(chain, authType, socket)
            else delegate.checkClientTrusted(chain, authType)
            requirePinned(chain)
        }

        override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String, socket: Socket) {
            if (extended != null) extended.checkServerTrusted(chain, authType, socket)
            else delegate.checkServerTrusted(chain, authType)
            requirePinned(chain)
        }

        override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String, engine: SSLEngine) {
            if (extended != null) extended.checkClientTrusted(chain, authType, engine)
            else delegate.checkClientTrusted(chain, authType)
            requirePinned(chain)
        }

        override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String, engine: SSLEngine) {
            if (extended != null) extended.checkServerTrusted(chain, authType, engine)
            else delegate.checkServerTrusted(chain, authType)
            requirePinned(chain)
        }

        override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = delegate.acceptedIssuers

        private fun requirePinned(chain: Array<java.security.cert.X509Certificate>) {
            val leaf = chain.firstOrNull()
                ?: throw java.security.cert.CertificateException("Brak certyfikatu peer do sprawdzenia SPKI.")
            if (peerPins.none { it.matches(leaf) }) {
                throw java.security.cert.CertificateException("Certyfikat peer nie pasuje do żadnego dozwolonego pinu SPKI.")
            }
        }
    }

    /** Creates an mTLS context using the platform's standard key and trust manager factories. */
    fun createMutualTlsContext(
        identityKeyStore: KeyStore,
        keyPassword: CharArray?,
        trustStore: KeyStore
    ): SSLContext {
        require(hasPrivateKeyIdentity(identityKeyStore, keyPassword)) {
            "Magazyn tożsamości musi zawierać klucz prywatny i łańcuch certyfikatów."
        }
        require(hasTrustedCertificates(trustStore)) {
            "Magazyn zaufania nie zawiera certyfikatów CA/tożsamości."
        }

        val keyFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        keyFactory.init(identityKeyStore, keyPassword)
        require(keyFactory.keyManagers.any { it is X509KeyManager }) {
            "Dostawca TLS nie udostępnił menedżera kluczy X.509."
        }

        val trustFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        trustFactory.init(trustStore)
        require(trustFactory.trustManagers.any { it is X509TrustManager }) {
            "Dostawca TLS nie udostępnił menedżera zaufania X.509."
        }

        return SSLContext.getInstance("TLS").apply {
            init(keyFactory.keyManagers, trustFactory.trustManagers, SecureRandom())
        }
    }

    /**
     * Opens a TCP-connected TLS client socket whose peer host is bound by the platform socket
     * factory. The TLS handshake is not started here; the caller must call startHandshake() before
     * exchanging application data. HTTPS endpoint identification validates the peer SAN/hostname.
     */
    fun openClientSocket(
        context: SSLContext,
        expectedPeerHost: String,
        port: Int,
        connectTimeoutMs: Int = 10_000
    ): SSLSocket {
        requireValidPeerHost(expectedPeerHost)
        require(port in 1..65535) { "Nieprawidłowy port Companion." }
        require(connectTimeoutMs in 1..60_000) { "Nieprawidłowy limit czasu połączenia Companion." }
        val socket = context.socketFactory.createSocket() as? SSLSocket
            ?: throw IllegalStateException("Dostawca TLS nie utworzył SSLSocket.")
        try {
            socket.useClientMode = true
            val parameters = secureParameters(socket.supportedProtocols)
            parameters.endpointIdentificationAlgorithm = "HTTPS"
            socket.sslParameters = parameters
            socket.connect(InetSocketAddress(expectedPeerHost, port), connectTimeoutMs)
            return socket
        } catch (error: Exception) {
            try { socket.close() } catch (_: Exception) { /* best-effort cleanup */ }
            throw error
        }
    }

    /** Configures a server socket to require and validate a client certificate (mTLS). */
    fun configureMutualTlsServerSocket(socket: SSLServerSocket) {
        socket.useClientMode = false
        val parameters = secureParameters(socket.supportedProtocols)
        parameters.needClientAuth = true
        socket.sslParameters = parameters
    }

    private fun secureParameters(supported: Array<String>): SSLParameters {
        val protocols = supported.filter { it in allowedProtocols }
        require("TLSv1.2" in protocols) {
            "Platformowy dostawca TLS nie obsługuje wymaganego TLS 1.2."
        }
        return SSLParameters().apply { this.protocols = protocols.toTypedArray() }
    }

    private fun hasPrivateKeyIdentity(store: KeyStore, password: CharArray?): Boolean {
        val aliases = store.aliases()
        while (aliases.hasMoreElements()) {
            val alias = aliases.nextElement()
            if (!store.isKeyEntry(alias)) continue
            val chain = store.getCertificateChain(alias)
            if (chain.isNullOrEmpty()) continue
            val key = try {
                store.getKey(alias, password)
            } catch (_: Exception) {
                null
            }
            if (key != null && key.algorithm.isNotBlank()) return true
        }
        return false
    }

    private fun hasTrustedCertificates(store: KeyStore): Boolean {
        val aliases = store.aliases()
        while (aliases.hasMoreElements()) {
            if (store.getCertificate(aliases.nextElement()) != null) return true
        }
        return false
    }

    private fun requireValidPeerHost(host: String) {
        require(host.isNotBlank() && host == host.trim() &&
            host.none { it.isWhitespace() || it == '/' || it == '@' || it == '*' || it == '\\' }) {
            "Nieprawidłowa nazwa hosta peer."
        }
    }
}
