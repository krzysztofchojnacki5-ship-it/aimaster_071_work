package pl.aimasterpro.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Real mutual-TLS integration tests for the production admission gate. */
class CompanionTlsHandshakeGateTest {
    private val secret = ByteArray(32) { (it + 7).toByte() }

    @Test fun acceptedTlsConnectionIsOwnedAndPeerRevocationClosesSocketAndOwner() {
        val authority = CompanionPairingAuthority(SecureRandom(), 60_000L)
        val challenge = authority.begin("android-client", secret)
        val proof = authority.createProof(challenge, "desktop-companion", secret)
        val verification = authority.verify(proof, secret)
        assertTrue(verification.accepted)

        val password = "test-only-password".toCharArray()
        val root = certificate("root-ca.pem")
        val serverIdentity = keyStore("server", root, password)
        val clientIdentity = keyStore("client", root, password)
        val trust = emptyKeyStore().apply { setCertificateEntry("root", root) }
        val clientPin = CompanionSpkiSha256Pin.fromCertificate(certificate("client.pem"))
        val serverPin = CompanionSpkiSha256Pin.fromCertificate(certificate("server.pem"))
        val serverProfile = CompanionPinnedTlsProfile.create(
            serverIdentity, password, trust, mapOf("android-client" to setOf(clientPin))
        )
        val clientProfile = CompanionPinnedTlsProfile.create(
            clientIdentity, password, trust, mapOf("desktop-companion" to setOf(serverPin))
        )
        val owner = CompanionSessionOwner(
            authority, secret, serverProfile,
            handshakeTimeoutNanos = 10_000_000_000L,
            idleTimeoutNanos = 20_000_000_000L,
            transferTimeoutNanos = 30_000_000_000L,
            hardLifetimeNanos = 100_000_000_000L,
            trustValidator = { peerId, pin -> peerId == "android-client" && pin == clientPin }
        )
        val provision = CompanionProvisionedSession(serverProfile, owner)
        assertTrue(provision.acceptPairingReceipt(verification.receipt!!).accepted)

        val registry = CompanionConnectionRegistry()
        val gate = CompanionTlsHandshakeGate(registry)
        val server = serverProfile.bindServer(0, address = InetAddress.getByName("127.0.0.1"))
        val executor = Executors.newSingleThreadExecutor()
        var clientConnection: CompanionTlsConnection? = null
        try {
            val accepted = executor.submit<CompanionTlsHandshakeGate.OwnedConnection> {
                gate.accept(provision, server, challenge.sessionId, "android-client")
            }
            clientConnection = clientProfile.connect(
                "localhost", server.localPort, challenge.sessionId, "desktop-companion"
            )
            val owned = accepted.get(5, TimeUnit.SECONDS)

            assertEquals(CompanionSessionOwner.State.ACTIVE, provision.state())
            assertEquals(1, registry.liveCount())
            assertFalse(owned.socket.isClosed)

            assertEquals(1, registry.revoke("android-client"))
            assertTrue(owned.socket.isClosed)
            assertEquals(CompanionSessionOwner.State.FAILED, provision.state())
            assertEquals(0, registry.liveCount())

            // The wrapper is idempotent even if application cleanup follows revocation.
            owned.close()
            assertEquals(CompanionSessionOwner.State.FAILED, provision.state())

            // Deterministically revoke after TLS succeeds but before registry registration:
            // the owner's trust callback runs between ticket capture and register().
            val challenge2 = authority.begin("android-client", secret)
            val proof2 = authority.createProof(challenge2, "desktop-companion", secret)
            val verification2 = authority.verify(proof2, secret)
            assertTrue(verification2.accepted)
            val racingOwner = CompanionSessionOwner(
                authority, secret, serverProfile,
                handshakeTimeoutNanos = 10_000_000_000L,
                idleTimeoutNanos = 20_000_000_000L,
                transferTimeoutNanos = 30_000_000_000L,
                hardLifetimeNanos = 100_000_000_000L,
                trustValidator = { peerId, pin ->
                    assertEquals("android-client", peerId)
                    assertEquals(clientPin, pin)
                    registry.revoke(peerId)
                    true
                }
            )
            assertTrue(racingOwner.acceptPairingReceipt(verification2.receipt!!).accepted)
            val racingAccept = executor.submit<CompanionTlsHandshakeGate.OwnedConnection> {
                gate.accept(serverProfile, server, challenge2.sessionId, "android-client", racingOwner)
            }
            val racingClient = clientProfile.connect(
                "localhost", server.localPort, challenge2.sessionId, "desktop-companion"
            )
            try {
                val unexpected = racingAccept.get(5, TimeUnit.SECONDS)
                unexpected.close()
                throw AssertionError("Admission must reject a ticket fenced during activation")
            } catch (expected: java.util.concurrent.ExecutionException) {
                assertTrue(expected.cause is java.io.IOException)
            } finally {
                clientProfile.discard(racingClient.receipt)
                try { racingClient.socket.close() } catch (_: Exception) { }
            }
            assertEquals(CompanionSessionOwner.State.FAILED, racingOwner.state())
            assertEquals(0, registry.liveCount())
        } finally {
            clientConnection?.let {
                clientProfile.discard(it.receipt)
                try { it.socket.close() } catch (_: Exception) { }
            }
            serverProfile.closeServer(server)
            executor.shutdownNow()
            executor.awaitTermination(2, TimeUnit.SECONDS)
        }
    }

    @Test fun provisionRejectsOwnerBoundToDifferentTlsProfile() {
        val password = "test-only-password".toCharArray()
        val root = certificate("root-ca.pem")
        val identity = keyStore("server", root, password)
        val trust = emptyKeyStore().apply { setCertificateEntry("root", root) }
        val pin = CompanionSpkiSha256Pin.fromCertificate(certificate("client.pem"))
        val firstProfile = CompanionPinnedTlsProfile.create(identity, password, trust,
            mapOf("android-client" to setOf(pin)))
        val otherProfile = CompanionPinnedTlsProfile.create(identity, password, trust,
            mapOf("android-client" to setOf(pin)))
        val authority = CompanionPairingAuthority(SecureRandom(), 60_000L)
        val owner = CompanionSessionOwner(
            authority, secret, firstProfile,
            handshakeTimeoutNanos = 10_000_000_000L,
            idleTimeoutNanos = 20_000_000_000L,
            transferTimeoutNanos = 30_000_000_000L,
            hardLifetimeNanos = 100_000_000_000L,
            trustValidator = { _, _ -> true }
        )
        try {
            CompanionProvisionedSession(otherProfile, owner)
            throw AssertionError("Provision must reject a mismatched TLS profile")
        } catch (_: IllegalArgumentException) {
            // Expected: the owner is bound by identity to firstProfile.
        }
        val registry = CompanionConnectionRegistry()
        val gate = CompanionTlsHandshakeGate(registry)
        try {
            gate.connect(otherProfile, "127.0.0.1", 1, "session", "android-client", owner)
            throw AssertionError("Gate must reject a profile/owner cross-wire before admission")
        } catch (_: IllegalArgumentException) {
            // Expected: no admission ticket may be allocated for a mismatched profile.
        }
        assertEquals(0, registry.liveCount())
    }

    @Test fun profileCloseClosesPendingReceiptAndListenerAndIsIdempotent() {
        val password = "test-only-password".toCharArray()
        val root = certificate("root-ca.pem")
        val serverIdentity = keyStore("server", root, password)
        val clientIdentity = keyStore("client", root, password)
        val trust = emptyKeyStore().apply { setCertificateEntry("root", root) }
        val clientPin = CompanionSpkiSha256Pin.fromCertificate(certificate("client.pem"))
        val serverPin = CompanionSpkiSha256Pin.fromCertificate(certificate("server.pem"))
        val serverProfile = CompanionPinnedTlsProfile.create(
            serverIdentity, password, trust, mapOf("android-client" to setOf(clientPin))
        )
        val clientProfile = CompanionPinnedTlsProfile.create(
            clientIdentity, password, trust, mapOf("desktop-companion" to setOf(serverPin))
        )
        val server = serverProfile.bindServer(0, address = InetAddress.getByName("127.0.0.1"))
        val executor = Executors.newSingleThreadExecutor()
        var client: CompanionTlsConnection? = null
        try {
            val future = executor.submit<CompanionTlsConnection> {
                serverProfile.accept(server, "session-close-test", "android-client")
            }
            client = clientProfile.connect(
                "localhost", server.localPort, "session-close-test", "desktop-companion"
            )
            val accepted = future.get(5, TimeUnit.SECONDS)
            assertFalse(server.isClosed)
            assertFalse(accepted.socket.isClosed)

            serverProfile.close()
            serverProfile.close()

            assertTrue(server.isClosed)
            assertTrue(accepted.socket.isClosed)
            assertTrue(serverProfile.consume(accepted.receipt) == null)
            var bindRejected = false
            try {
                serverProfile.bindServer(0, address = InetAddress.getByName("127.0.0.1"))
            } catch (_: IllegalStateException) {
                bindRejected = true
            }
            assertTrue(bindRejected)
        } finally {
            serverProfile.close()
            client?.let {
                clientProfile.discard(it.receipt)
                try { it.socket.close() } catch (_: Exception) { }
            }
            clientProfile.close()
            executor.shutdownNow()
            executor.awaitTermination(2, TimeUnit.SECONDS)
        }
    }

    @Test fun profileCloseClosesAdmittedOwnerAndReleasesRegistryHandle() {
        val password = "test-only-password".toCharArray()
        val root = certificate("root-ca.pem")
        val serverIdentity = keyStore("server", root, password)
        val clientIdentity = keyStore("client", root, password)
        val trust = emptyKeyStore().apply { setCertificateEntry("root", root) }
        val clientPin = CompanionSpkiSha256Pin.fromCertificate(certificate("client.pem"))
        val serverPin = CompanionSpkiSha256Pin.fromCertificate(certificate("server.pem"))
        val serverProfile = CompanionPinnedTlsProfile.create(
            serverIdentity, password, trust, mapOf("android-client" to setOf(clientPin))
        )
        val clientProfile = CompanionPinnedTlsProfile.create(
            clientIdentity, password, trust, mapOf("desktop-companion" to setOf(serverPin))
        )
        val authority = CompanionPairingAuthority(SecureRandom(), 60_000L)
        val challenge = authority.begin("android-client", secret)
        val proof = authority.createProof(challenge, "desktop-companion", secret)
        val verification = authority.verify(proof, secret)
        assertTrue(verification.accepted)
        val owner = CompanionSessionOwner(
            authority, secret, serverProfile,
            handshakeTimeoutNanos = 10_000_000_000L,
            idleTimeoutNanos = 20_000_000_000L,
            transferTimeoutNanos = 30_000_000_000L,
            hardLifetimeNanos = 100_000_000_000L,
            trustValidator = { peerId, pin -> peerId == "android-client" && pin == clientPin }
        )
        assertTrue(owner.acceptPairingReceipt(verification.receipt!!).accepted)
        val registry = CompanionConnectionRegistry()
        val ticket = registry.ticket("android-client")
        val server = serverProfile.bindServer(0, address = InetAddress.getByName("127.0.0.1"))
        val executor = Executors.newSingleThreadExecutor()
        var client: CompanionTlsConnection? = null
        try {
            val future = executor.submit<CompanionTlsConnection> {
                serverProfile.accept(server, challenge.sessionId, "android-client")
            }
            client = clientProfile.connect("localhost", server.localPort, challenge.sessionId, "desktop-companion")
            val connection = future.get(5, TimeUnit.SECONDS)
            assertTrue(owner.activate(connection.receipt).accepted)
            val owned = CompanionTlsHandshakeGate.OwnedConnection(
                connection, "android-client", registry, serverProfile, owner
            )
            assertTrue(registry.register(ticket, owned))
            assertEquals(1, registry.liveCount())

            serverProfile.close()

            assertTrue(connection.socket.isClosed)
            assertEquals(CompanionSessionOwner.State.CLOSED, owner.state())
            assertEquals(0, registry.liveCount())
        } finally {
            registry.cancel(ticket)
            serverProfile.close()
            client?.let {
                clientProfile.discard(it.receipt)
                try { it.socket.close() } catch (_: Exception) { }
            }
            clientProfile.close()
            executor.shutdownNow()
            executor.awaitTermination(2, TimeUnit.SECONDS)
        }
    }

    @Test fun provisionCloseClosesOwnerAndItsExactTlsProfile() {
        val password = "test-only-password".toCharArray()
        val root = certificate("root-ca.pem")
        val identity = keyStore("server", root, password)
        val trust = emptyKeyStore().apply { setCertificateEntry("root", root) }
        val pin = CompanionSpkiSha256Pin.fromCertificate(certificate("client.pem"))
        val profile = CompanionPinnedTlsProfile.create(identity, password, trust, mapOf("android-client" to setOf(pin)))
        val authority = CompanionPairingAuthority(SecureRandom(), 60_000L)
        val owner = CompanionSessionOwner(
            authority, secret, profile,
            handshakeTimeoutNanos = 10_000_000_000L,
            idleTimeoutNanos = 20_000_000_000L,
            transferTimeoutNanos = 30_000_000_000L,
            hardLifetimeNanos = 100_000_000_000L,
            trustValidator = { _, _ -> true }
        )
        val provision = CompanionProvisionedSession(profile, owner)
        val server = profile.bindServer(0, address = InetAddress.getByName("127.0.0.1"))

        provision.close()
        provision.close()

        assertEquals(CompanionSessionOwner.State.CLOSED, provision.state())
        assertTrue(server.isClosed)
        var bindRejected = false
        try {
            profile.bindServer(0, address = InetAddress.getByName("127.0.0.1"))
        } catch (_: IllegalStateException) {
            bindRejected = true
        }
        assertTrue(bindRejected)
    }

    @Test fun repeatedHandshakeFailuresDoNotLeakAdmissionTickets() {
        val registry = CompanionConnectionRegistry()
        val gate = CompanionTlsHandshakeGate(registry)
        val authority = CompanionPairingAuthority(SecureRandom(), 60_000L)
        val challenge = authority.begin("android-client", secret)
        val proof = authority.createProof(challenge, "desktop-companion", secret)
        val verification = authority.verify(proof, secret)
        assertTrue(verification.accepted)

        val password = "test-only-password".toCharArray()
        val root = certificate("root-ca.pem")
        val identity = keyStore("server", root, password)
        val trust = emptyKeyStore().apply { setCertificateEntry("root", root) }
        val pin = CompanionSpkiSha256Pin.fromCertificate(certificate("client.pem"))
        val profile = CompanionPinnedTlsProfile.create(identity, password, trust, mapOf("android-client" to setOf(pin)))
        val owner = CompanionSessionOwner(
            authority, secret, profile,
            handshakeTimeoutNanos = 10_000_000_000L,
            idleTimeoutNanos = 20_000_000_000L,
            transferTimeoutNanos = 30_000_000_000L,
            hardLifetimeNanos = 100_000_000_000L,
            trustValidator = { _, _ -> true }
        )
        assertTrue(owner.acceptPairingReceipt(verification.receipt!!).accepted)
        val unavailablePort = java.net.ServerSocket(0).use { it.localPort }
        repeat(40) {
            try {
                gate.connect(profile, "127.0.0.1", unavailablePort, challenge.sessionId,
                    "android-client", owner, timeoutMs = 250)
                throw AssertionError("Expected connection failure")
            } catch (_: java.io.IOException) {
                // Expected: ticket must be cancelled in establish() finally.
            }
        }
        // If failed attempts leaked tickets, the registry's 32-ticket admission bound would fail.
        val ticket = registry.ticket("android-client")
        registry.cancel(ticket)
        assertEquals(0, registry.liveCount())
    }

    private fun emptyKeyStore(): KeyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }

    private fun keyStore(name: String, root: X509Certificate, password: CharArray): KeyStore = emptyKeyStore().apply {
        setKeyEntry("identity", privateKey("$name-key.pem"), password,
            arrayOf(certificate("$name.pem"), root))
    }

    private fun certificate(name: String): X509Certificate = CertificateFactory.getInstance("X.509")
        .generateCertificate(ByteArrayInputStream(resource(name))) as X509Certificate

    private fun privateKey(name: String) = KeyFactory.getInstance("RSA").generatePrivate(
        PKCS8EncodedKeySpec(pemBytes(resource(name)))
    )

    private fun pemBytes(bytes: ByteArray): ByteArray {
        val body = String(bytes, StandardCharsets.US_ASCII).lineSequence()
            .filterNot { it.startsWith("-----") }.joinToString("")
        return Base64.getDecoder().decode(body)
    }

    private fun resource(name: String): ByteArray = javaClass.getResourceAsStream("/companion-tls/$name")
        ?.use { it.readBytes() } ?: throw AssertionError("Missing TLS fixture: $name")
}
