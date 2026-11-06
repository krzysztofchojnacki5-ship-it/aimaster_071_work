package pl.aimasterpro.app.core

import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.concurrent.ExecutionException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import org.junit.Assert.*
import org.junit.Test

class CompanionTlsContextTest {
    @Test
    fun clientSocketBindsPeerHostAndRequiresHttpsEndpointIdentification() {
        ServerSocket(0).use { listener ->
            val socket = CompanionTlsContext.openClientSocket(
                SSLContext.getDefault(), "localhost", listener.localPort
            )
            try {
                assertTrue(socket.isConnected)
                assertTrue(socket.useClientMode)
                assertEquals("HTTPS", socket.sslParameters.endpointIdentificationAlgorithm)
                assertTrue(socket.enabledProtocols.contains("TLSv1.2"))
                assertTrue(socket.enabledProtocols.all { it == "TLSv1.2" || it == "TLSv1.3" })
            } finally {
                socket.close()
            }
        }
    }

    @Test
    fun clientSocketRejectsMalformedPeerHost() {
        assertThrows(IllegalArgumentException::class.java) {
            CompanionTlsContext.openClientSocket(SSLContext.getDefault(), "https://companion.example", 443)
        }
    }

    @Test
    fun serverSocketRequiresClientCertificateAndModernProtocols() {
        val server = SSLContext.getDefault().serverSocketFactory.createServerSocket() as SSLServerSocket
        try {
            CompanionTlsContext.configureMutualTlsServerSocket(server)
            assertFalse(server.useClientMode)
            assertTrue(server.needClientAuth)
            assertTrue(server.enabledProtocols.contains("TLSv1.2"))
            assertTrue(server.enabledProtocols.all { it == "TLSv1.2" || it == "TLSv1.3" })
        } finally {
            server.close()
        }
    }

    @Test
    fun contextRejectsIdentityStoreWithoutPrivateKey() {
        val identity = emptyKeyStore()
        val trust = emptyKeyStore()
        assertThrows(IllegalArgumentException::class.java) {
            CompanionTlsContext.createMutualTlsContext(identity, null, trust)
        }
    }

    @Test
    fun mutualTlsHandshakeSucceedsWithTrustedServerAndClientCertificates() {
        val serverContext = tlsContext("server", "root-ca")
        val clientContext = tlsContext("client", "root-ca")
        withTlsServer(serverContext) { port, serverResult ->
            CompanionTlsContext.openClientSocket(clientContext, "localhost", port).use { client ->
                client.soTimeout = 4_000
                client.startHandshake()
                assertTrue(client.session.isValid)
                assertTrue(client.session.protocol == "TLSv1.3" || client.session.protocol == "TLSv1.2")
                assertTrue(client.session.peerCertificates.isNotEmpty())
            }
            assertTrue(serverResult.get(5, TimeUnit.SECONDS).contains("CN=client"))
        }
    }

    @Test
    fun mutualTlsHandshakeSucceedsWhenBothPeersAreRestrictedToTls12() {
        val serverContext = tlsContext("server", "root-ca")
        val clientContext = tlsContext("client", "root-ca")
        withTlsServer(serverContext, forceTls12 = true) { port, serverResult ->
            CompanionTlsContext.openClientSocket(clientContext, "localhost", port).use { client ->
                client.enabledProtocols = arrayOf("TLSv1.2")
                client.soTimeout = 4_000
                client.startHandshake()
                assertEquals("TLSv1.2", client.session.protocol)
            }
            assertTrue(serverResult.get(5, TimeUnit.SECONDS).contains("CN=client"))
        }
    }

    @Test
    fun serverRejectsClientWithoutCertificate() {
        val serverContext = tlsContext("server", "root-ca")
        val clientContext = trustOnlyContext("root-ca")
        withTlsServer(serverContext) { port, serverResult ->
            CompanionTlsContext.openClientSocket(clientContext, "localhost", port).use { client ->
                client.soTimeout = 4_000
                try {
                    client.startHandshake()
                } catch (_: SSLException) {
                    // The server-side handshake result is authoritative for missing client identity.
                }
            }
            assertServerHandshakeFailed(serverResult)
        }
    }

    @Test
    fun serverRejectsClientCertificateSignedByUntrustedAuthority() {
        val serverContext = tlsContext("server", "root-ca")
        val rogueClientContext = tlsContext("rogue-client", "root-ca")
        withTlsServer(serverContext) { port, serverResult ->
            CompanionTlsContext.openClientSocket(rogueClientContext, "localhost", port).use { client ->
                client.soTimeout = 4_000
                try {
                    client.startHandshake()
                } catch (_: SSLException) {
                    // The authoritative assertion is the server rejecting the untrusted client chain.
                }
            }
            assertServerHandshakeFailed(serverResult)
        }
    }

    @Test
    fun clientRejectsServerCertificateWithWrongSubjectAlternativeName() {
        val wrongServerContext = tlsContext("server-wrong-san", "root-ca")
        val clientContext = tlsContext("client", "root-ca")
        withTlsServer(wrongServerContext) { port, serverResult ->
            assertThrows(SSLException::class.java) {
                CompanionTlsContext.openClientSocket(clientContext, "localhost", port).use { client ->
                    client.soTimeout = 4_000
                    client.startHandshake()
                }
            }
            assertServerHandshakeFailed(serverResult)
        }
    }

    @Test
    fun clientRejectsServerCertificateFromUntrustedAuthority() {
        val rogueServerContext = tlsContext("rogue-server", "root-ca")
        val clientContext = tlsContext("client", "root-ca")
        withTlsServer(rogueServerContext) { port, serverResult ->
            assertThrows(SSLException::class.java) {
                CompanionTlsContext.openClientSocket(clientContext, "localhost", port).use { client ->
                    client.soTimeout = 4_000
                    client.startHandshake()
                }
            }
            assertServerHandshakeFailed(serverResult)
        }
    }

    @Test
    fun pinnedMutualTlsHandshakeSucceedsWhenBothPeerPinsMatch() {
        val serverContext = pinnedTlsContext("server", "root-ca", setOf("client"))
        val clientContext = pinnedTlsContext("client", "root-ca", setOf("server"))
        withTlsServer(serverContext) { port, serverResult ->
            CompanionTlsContext.openClientSocket(clientContext, "localhost", port).use { client ->
                client.soTimeout = 4_000
                client.startHandshake()
                assertTrue(client.session.isValid)
                assertTrue(client.session.peerCertificates.isNotEmpty())
            }
            assertTrue(serverResult.get(5, TimeUnit.SECONDS).contains("CN=client"))
        }
    }

    @Test
    fun pinnedClientRejectsTrustedServerWithWrongSpkiPin() {
        val serverContext = tlsContext("server", "root-ca")
        val clientContext = pinnedTlsContext("client", "root-ca", setOf("rogue-server"))
        withTlsServer(serverContext) { port, serverResult ->
            assertThrows(SSLException::class.java) {
                CompanionTlsContext.openClientSocket(clientContext, "localhost", port).use { client ->
                    client.soTimeout = 4_000
                    client.startHandshake()
                }
            }
            try { serverResult.get(5, TimeUnit.SECONDS) } catch (_: ExecutionException) {
                // A peer that rejects the server certificate may also terminate the server handshake.
            }
        }
    }

    @Test
    fun pinnedServerRejectsTrustedClientWithWrongSpkiPin() {
        val serverContext = pinnedTlsContext("server", "root-ca", setOf("rogue-client"))
        val clientContext = tlsContext("client", "root-ca")
        withTlsServer(serverContext) { port, serverResult ->
            CompanionTlsContext.openClientSocket(clientContext, "localhost", port).use { client ->
                client.soTimeout = 4_000
                try { client.startHandshake() } catch (_: Exception) { /* server verdict below */ }
            }
            assertServerHandshakeFailed(serverResult)
        }
    }

    @Test
    fun pinnedContextAcceptsCurrentAndNextPeerPinDuringRotationWindow() {
        val serverContext = tlsContext("server", "root-ca")
        val clientContext = pinnedTlsContext("client", "root-ca", setOf("server", "rogue-server"))
        withTlsServer(serverContext) { port, serverResult ->
            CompanionTlsContext.openClientSocket(clientContext, "localhost", port).use { client ->
                client.soTimeout = 4_000
                client.startHandshake()
                assertTrue(client.session.isValid)
            }
            assertTrue(serverResult.get(5, TimeUnit.SECONDS).contains("CN=client"))
        }
    }

    @Test
    fun concurrentAcceptCallsDoNotOverwriteSharedListenerTimeout() {
        val serverProfile = pinnedProfile("server", setOf("client"), "client-peer")
        val clientProfile = pinnedProfile("client", setOf("server"), "server-peer")
        val listener = serverProfile.bindServer(0, address = InetAddress.getByName("127.0.0.1"))
        val executor = Executors.newFixedThreadPool(2)
        val firstStarted = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        try {
            val firstAccept = executor.submit<CompanionTlsConnection> {
                firstStarted.countDown()
                serverProfile.accept(listener, "pairing-session", "client-peer", timeoutMs = 3_000)
            }
            assertTrue(firstStarted.await(1, TimeUnit.SECONDS))
            Thread.sleep(150)
            val secondAccept = executor.submit<CompanionTlsConnection> {
                secondStarted.countDown()
                serverProfile.accept(listener, "pairing-session", "client-peer", timeoutMs = 200)
            }
            assertTrue(secondStarted.await(1, TimeUnit.SECONDS))
            Thread.sleep(350)

            val clientConnection = clientProfile.connect(
                "localhost", listener.localPort, "pairing-session", "server-peer", timeoutMs = 3_000
            )
            assertTrue(clientConnection.socket.session.isValid)
            clientConnection.socket.close()

            val accepted = firstAccept.get(4, TimeUnit.SECONDS)
            accepted.socket.close()
            val secondFailure = assertThrows(ExecutionException::class.java) {
                secondAccept.get(2, TimeUnit.SECONDS)
            }
            assertTrue("Second accept should time out independently after the first accept completes", 
                secondFailure.cause is SocketTimeoutException)
        } finally {
            serverProfile.closeServer(listener)
            executor.shutdownNow()
            executor.awaitTermination(2, TimeUnit.SECONDS)
        }
    }

    private fun pinnedProfile(
        identityName: String,
        peerNames: Set<String>,
        expectedPeerId: String
    ): CompanionPinnedTlsProfile {
        val password = "test-only-password".toCharArray()
        val identityStore = emptyKeyStore()
        val leaf = certificate("$identityName.pem")
        val root = certificate("root-ca.pem")
        identityStore.setKeyEntry("identity", privateKey("$identityName-key.pem"), password, arrayOf(leaf, root))
        val trustStore = emptyKeyStore().apply { setCertificateEntry("trusted-root", root) }
        val pins = peerNames.map { CompanionSpkiSha256Pin.fromCertificate(certificate("$it.pem")) }.toSet()
        return CompanionPinnedTlsProfile.create(identityStore, password, trustStore, mapOf(expectedPeerId to pins))
    }

    @Test
    fun pinnedContextRejectsEmptyPinSetAndNonCanonicalPinStrings() {
        val identity = emptyKeyStore()
        val trust = emptyKeyStore().apply { setCertificateEntry("root", certificate("root-ca.pem")) }
        assertThrows(IllegalArgumentException::class.java) {
            CompanionTlsContext.createPinnedMutualTlsContext(identity, null, trust, emptySet())
        }
        assertThrows(IllegalArgumentException::class.java) { CompanionSpkiSha256Pin.parse("A".repeat(64)) }
        assertThrows(IllegalArgumentException::class.java) { CompanionSpkiSha256Pin.parse("0".repeat(63)) }
    }

    private fun pinnedTlsContext(
        identityName: String,
        trustedCaName: String,
        peerPinNames: Set<String>
    ): SSLContext {
        val password = "test-only-password".toCharArray()
        val identityStore = emptyKeyStore()
        val leaf = certificate("$identityName.pem")
        val root = certificate(if (identityName.startsWith("rogue-")) "rogue-ca.pem" else "root-ca.pem")
        identityStore.setKeyEntry("identity", privateKey("$identityName-key.pem"), password, arrayOf(leaf, root))
        val trustStore = emptyKeyStore().apply {
            setCertificateEntry("trusted-root", certificate("$trustedCaName.pem"))
        }
        val pins = peerPinNames.map { CompanionSpkiSha256Pin.fromCertificate(certificate("$it.pem")) }.toSet()
        return CompanionTlsContext.createPinnedMutualTlsContext(identityStore, password, trustStore, pins)
    }

    private fun tlsContext(identityName: String, trustedCaName: String): SSLContext {
        val password = "test-only-password".toCharArray()
        val identityStore = emptyKeyStore()
        val leaf = certificate("$identityName.pem")
        val root = certificate(if (identityName.startsWith("rogue-")) "rogue-ca.pem" else "root-ca.pem")
        val privateKey = privateKey("$identityName-key.pem")
        identityStore.setKeyEntry("identity", privateKey, password, arrayOf(leaf, root))

        val trustStore = emptyKeyStore()
        trustStore.setCertificateEntry("trusted-root", certificate("$trustedCaName.pem"))
        return CompanionTlsContext.createMutualTlsContext(identityStore, password, trustStore)
    }

    private fun trustOnlyContext(trustedCaName: String): SSLContext {
        val trustStore = emptyKeyStore().apply {
            setCertificateEntry("trusted-root", certificate("$trustedCaName.pem"))
        }
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(trustStore)
        return SSLContext.getInstance("TLS").apply {
            init(null, factory.trustManagers, SecureRandom())
        }
    }

    private fun emptyKeyStore(): KeyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
        load(null, null)
    }

    private fun certificate(fileName: String): X509Certificate {
        val bytes = resource(fileName)
        return CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(bytes)) as X509Certificate
    }

    private fun privateKey(fileName: String) = KeyFactory.getInstance("RSA").generatePrivate(
        PKCS8EncodedKeySpec(pemBytes(resource(fileName)))
    )

    private fun pemBytes(bytes: ByteArray): ByteArray {
        val text = String(bytes, StandardCharsets.US_ASCII)
        val body = text.lineSequence()
            .filterNot { it.startsWith("-----") }
            .joinToString("")
        return Base64.getDecoder().decode(body)
    }

    private fun resource(fileName: String): ByteArray =
        javaClass.getResourceAsStream("/companion-tls/$fileName")?.use { it.readBytes() }
            ?: throw AssertionError("Missing test TLS fixture: $fileName")

    private fun withTlsServer(
        context: SSLContext,
        forceTls12: Boolean = false,
        block: (Int, java.util.concurrent.Future<String>) -> Unit
    ) {
        val server = context.serverSocketFactory.createServerSocket(
            0, 8, InetAddress.getByName("127.0.0.1")
        ) as SSLServerSocket
        CompanionTlsContext.configureMutualTlsServerSocket(server)
        if (forceTls12) server.enabledProtocols = arrayOf("TLSv1.2")
        server.soTimeout = 5_000
        val executor = Executors.newSingleThreadExecutor()
        var acceptedSocket: SSLSocket? = null
        val result = executor.submit<String> {
            server.use { listening ->
                val accepted = listening.accept() as SSLSocket
                acceptedSocket = accepted
                accepted.use { socket ->
                    socket.soTimeout = 4_000
                    socket.startHandshake()
                    socket.session.peerCertificates
                        .filterIsInstance<X509Certificate>()
                        .first().subjectX500Principal.name
                }
            }
        }
        try {
            block(server.localPort, result)
        } finally {
            try { acceptedSocket?.close() } catch (_: Exception) { /* best-effort test cleanup */ }
            server.close()
            executor.shutdownNow()
            executor.awaitTermination(2, TimeUnit.SECONDS)
        }
    }

    private fun assertServerHandshakeFailed(result: java.util.concurrent.Future<String>) {
        val failure = assertThrows(ExecutionException::class.java) {
            result.get(5, TimeUnit.SECONDS)
        }
        assertTrue(failure.cause != null)
        assertFalse(failure.cause is TimeoutException)
    }
}
