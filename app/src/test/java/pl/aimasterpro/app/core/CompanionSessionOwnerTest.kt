package pl.aimasterpro.app.core

import org.junit.Test
import org.junit.After
import java.io.ByteArrayInputStream
import java.net.InetAddress
import javax.net.ssl.SSLServerSocket
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

class CompanionSessionOwnerTest {
    private class FakeClock(var millis: Long = 1_000L) {
        fun readMillis(): Long = millis
        fun readNanos(): Long = millis * 1_000_000L
    }

    private val secret = ByteArray(32) { (it + 1).toByte() }

    private data class Fixture(
        val clock: FakeClock,
        val authority: CompanionPairingAuthority,
        val owner: CompanionSessionOwner,
        val challenge: CompanionPairingAuthority.Challenge,
        val receipt: CompanionPairingReceipt,
        val tlsProfile: CompanionPinnedTlsProfile,
        val clientProfile: CompanionPinnedTlsProfile,
        val tlsReceipt: CompanionTlsPeerReceipt,
        val sockets: List<java.io.Closeable>
    )

    private val openSockets = mutableListOf<java.io.Closeable>()

    @After fun closeSockets() {
        openSockets.forEach { try { it.close() } catch (_: Exception) { } }
        openSockets.clear()
    }

    private fun fixture(trustValidator: (String, CompanionSpkiSha256Pin) -> Boolean = { _, _ -> true }): Fixture {
        val clock = FakeClock()
        val authority = CompanionPairingAuthority(SecureRandom(), 60_000L, clock::readMillis)
        val challenge = authority.begin("android-client", secret)
        val proof = authority.createProof(challenge, "desktop-companion", secret)
        clock.millis++
        val verification = authority.verify(proof, secret)
        check(verification.accepted)
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
            clockNanos = clock::readNanos,
            trustValidator = trustValidator
        )
        val server = serverProfile.bindServer(0, address = InetAddress.getByName("127.0.0.1"))
        val executor = Executors.newSingleThreadExecutor()
        val accepted = executor.submit<CompanionTlsConnection> {
            serverProfile.accept(server, challenge.sessionId, "android-client")
        }
        val clientConnection = clientProfile.connect(
            "localhost", server.localPort, challenge.sessionId, "desktop-companion"
        )
        val serverConnection = accepted.get(5, TimeUnit.SECONDS)
        executor.shutdownNow()
        val sockets = listOf(clientConnection.socket, serverConnection.socket, server)
        openSockets.addAll(sockets)
        return Fixture(clock, authority, owner, challenge, verification.receipt!!,
            serverProfile, clientProfile, serverConnection.receipt, sockets)
    }

    private fun activate(f: Fixture): CompanionSessionOwner.Result =
        f.owner.activate(f.tlsReceipt)

    private fun emptyKeyStore(): KeyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }

    private fun keyStore(name: String, root: X509Certificate, password: CharArray): KeyStore {
        val store = emptyKeyStore()
        store.setKeyEntry("identity", privateKey("$name-key.pem"), password,
            arrayOf(certificate("$name.pem"), root))
        return store
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

    private fun incomingFrame(f: Fixture, sequence: Long = 1L, type: CompanionFrameProtocol.MessageType = CompanionFrameProtocol.MessageType.PING): CompanionFrameProtocol.Frame {
        val key = CompanionFrameProtocol.deriveSessionKey(
            secret, f.challenge.sessionId, f.challenge.clientId, "desktop-companion", f.challenge.nonce,
            CompanionFrameProtocol.Direction.COMPANION_TO_ANDROID
        )
        return try {
            CompanionFrameProtocol.create(
                f.challenge.sessionId,
                CompanionFrameProtocol.Direction.COMPANION_TO_ANDROID,
                sequence,
                type,
                byteArrayOf(1, 2, 3),
                key
            )
        } finally {
            key.fill(0)
        }
    }

    @Test fun forgedReceiptCannotPairAndValidReceiptCanStillBeConsumed() {
        val f = fixture()
        val forged = object : CompanionPairingReceipt {}
        check(!f.owner.acceptPairingReceipt(forged).accepted)
        check(f.owner.state() == CompanionSessionOwner.State.NEW)
        check(f.owner.acceptPairingReceipt(f.receipt).accepted)
        check(f.owner.state() == CompanionSessionOwner.State.PAIRED)
    }

    @Test fun receiptIsConsumedOnceAndIdentityIsBoundToOwner() {
        val f = fixture()
        check(f.owner.acceptPairingReceipt(f.receipt).accepted)
        check(f.owner.identity()?.sessionId == f.challenge.sessionId)
        check(f.owner.identity()?.nonce == f.challenge.nonce)
        check(!f.owner.acceptPairingReceipt(f.receipt).accepted)
        check(f.owner.state() == CompanionSessionOwner.State.PAIRED)
    }

    @Test fun receiptFromDifferentAuthorityIsRejected() {
        val f = fixture()
        val foreign = CompanionPairingAuthority(SecureRandom(), 60_000L, f.clock::readMillis)
        val challenge = foreign.begin("other-client", secret)
        val proof = foreign.createProof(challenge, "other-desktop", secret)
        f.clock.millis++
        val receipt = foreign.verify(proof, secret).receipt!!
        check(!f.owner.acceptPairingReceipt(receipt).accepted)
        check(f.owner.state() == CompanionSessionOwner.State.NEW)
    }

    @Test fun activationAndFrameVerificationRequirePairedState() {
        val f = fixture()
        check(!f.owner.activate().accepted)
        check(f.owner.verifyIncoming(incomingFrame(f)).code == "INVALID_STATE")
        check(f.owner.acceptPairingReceipt(f.receipt).accepted)
        check(activate(f).accepted)
        check(f.owner.state() == CompanionSessionOwner.State.ACTIVE)
        check(f.owner.verifyIncoming(incomingFrame(f)).accepted)
    }

    @Test fun invalidFrameDoesNotRefreshAuthenticatedActivity() {
        val f = fixture()
        check(f.owner.acceptPairingReceipt(f.receipt).accepted)
        check(activate(f).accepted)
        val valid = incomingFrame(f)
        val invalid = valid.copy(mac = "0".repeat(64))
        check(f.owner.verifyIncoming(invalid).code == "AUTH_FAILED")
        f.clock.millis += 20_000L
        check(f.owner.pollTimeout().code == "SESSION_TIMEOUT")
        check(f.owner.state() == CompanionSessionOwner.State.FAILED)
    }

    @Test fun authenticatedCloseMakesSessionTerminal() {
        val f = fixture()
        check(f.owner.acceptPairingReceipt(f.receipt).accepted)
        check(activate(f).accepted)
        val close = incomingFrame(f, type = CompanionFrameProtocol.MessageType.CLOSE)
        check(f.owner.verifyIncoming(close).accepted)
        check(f.owner.state() == CompanionSessionOwner.State.CLOSED)
        check(f.owner.verifyIncoming(incomingFrame(f, sequence = 2L)).code == "SESSION_CLOSED")
        check(!f.owner.pollTimeout().accepted)
    }

    @Test fun transferHasSingleBoundedLifecycle() {
        val f = fixture()
        check(f.owner.acceptPairingReceipt(f.receipt).accepted)
        check(activate(f).accepted)
        check(f.owner.finishTransferAfterValidation().code == "INVALID_STATE")
        check(f.owner.beginTransfer().accepted)
        check(f.owner.state() == CompanionSessionOwner.State.TRANSFERRING)
        check(f.owner.beginTransfer().code == "INVALID_STATE")
        check(f.owner.finishTransferAfterValidation().accepted)
        check(f.owner.state() == CompanionSessionOwner.State.ACTIVE)
    }

    @Test fun transferTimeoutFailsAndClosesReceiver() {
        val f = fixture()
        check(f.owner.acceptPairingReceipt(f.receipt).accepted)
        check(activate(f).accepted)
        check(f.owner.beginTransfer().accepted)
        f.clock.millis += 30_000L
        check(f.owner.pollTimeout().code == "SESSION_TIMEOUT")
        check(f.owner.state() == CompanionSessionOwner.State.FAILED)
    }

    @Test fun hardLifetimeCannotBeExtendedByAuthenticatedFrames() {
        val f = fixture()
        check(f.owner.acceptPairingReceipt(f.receipt).accepted)
        check(activate(f).accepted)
        f.clock.millis += 19_000L
        check(f.owner.verifyIncoming(incomingFrame(f)).accepted)
        f.clock.millis = 101_001L // 100 seconds after policy creation; hard deadline is inclusive.
        check(f.owner.pollTimeout().code == "SESSION_TIMEOUT")
        check(f.owner.state() == CompanionSessionOwner.State.FAILED)
    }

    @Test fun handshakeTimeoutBeforeReceiptIsTerminal() {
        val f = fixture()
        f.clock.millis += 10_000L
        check(!f.owner.acceptPairingReceipt(f.receipt).accepted)
        check(f.owner.state() == CompanionSessionOwner.State.FAILED)
        check(f.owner.identity() == null)
    }

    @Test fun explicitCloseIsIdempotentAndTerminal() {
        val f = fixture()
        check(f.owner.acceptPairingReceipt(f.receipt).accepted)
        check(activate(f).accepted)
        f.owner.close()
        f.owner.close()
        check(f.owner.state() == CompanionSessionOwner.State.CLOSED)
        check(!f.owner.activate().accepted)
    }
    @Test fun fatalTrustValidatorFailureDuringActivationFailsOwnerClosed() {
        val f = fixture { _, _ -> throw AssertionError("trust validator fatal") }
        check(f.owner.acceptPairingReceipt(f.receipt).accepted)
        try {
            f.owner.activate(f.tlsReceipt)
            throw AssertionError("Expected fatal trust validator failure")
        } catch (expected: AssertionError) {
            check(expected.message == "trust validator fatal")
        }
        check(f.owner.state() == CompanionSessionOwner.State.FAILED)
        check(f.owner.identity() == null)
        check(f.owner.tlsPeerIdentity() == null)
    }

    @Test fun handshakeGateClosesSocketAndOwnerWhenActivationThrowsFatalError() {
        val f = fixture { _, _ -> throw AssertionError("gate trust validator fatal") }
        check(f.owner.acceptPairingReceipt(f.receipt).accepted)
        val registry = CompanionConnectionRegistry()
        val gate = CompanionTlsHandshakeGate(registry)
        val server = f.sockets[2] as SSLServerSocket
        val executor = Executors.newSingleThreadExecutor()
        val accepted = executor.submit<CompanionTlsHandshakeGate.OwnedConnection> {
            gate.accept(f.tlsProfile, server, f.challenge.sessionId, "android-client", f.owner)
        }
        val client = f.clientProfile.connect(
            "localhost", server.localPort, f.challenge.sessionId, "desktop-companion"
        )
        openSockets += client.socket
        try {
            accepted.get(5, TimeUnit.SECONDS)
            throw AssertionError("Expected gate activation failure")
        } catch (expected: java.util.concurrent.ExecutionException) {
            check(expected.cause is AssertionError)
            check(expected.cause?.message == "gate trust validator fatal")
        } finally {
            executor.shutdownNow()
        }
        check(f.owner.state() == CompanionSessionOwner.State.FAILED)
        check(registry.liveCount() == 0)
        client.socket.soTimeout = 1_000
        val peerClosed = try {
            client.socket.getInputStream().read() == -1
        } catch (_: java.io.IOException) {
            true
        }
        check(peerClosed)
    }

    @Test fun registryRevocationFailsOwnerAndClosesLiveLoopbackTlsConnection() {
        val f = fixture()
        check(f.owner.acceptPairingReceipt(f.receipt).accepted)
        val registry = CompanionConnectionRegistry()
        val gate = CompanionTlsHandshakeGate(registry)
        val server = f.tlsProfile.bindServer(0, address = InetAddress.getByName("127.0.0.1"))
        val executor = Executors.newSingleThreadExecutor()
        val accepted = executor.submit<CompanionTlsHandshakeGate.OwnedConnection> {
            gate.accept(f.tlsProfile, server, f.challenge.sessionId, "android-client", f.owner)
        }
        val client = f.clientProfile.connect(
            "localhost", server.localPort, f.challenge.sessionId, "desktop-companion"
        )
        val owned = accepted.get(5, TimeUnit.SECONDS)
        executor.shutdownNow()
        openSockets += client.socket
        openSockets += server
        check(f.owner.state() == CompanionSessionOwner.State.ACTIVE)
        check(registry.liveCount() == 1)
        check(!owned.socket.isClosed)

        check(registry.revoke("android-client") == 1)
        check(f.owner.state() == CompanionSessionOwner.State.FAILED)
        check(owned.socket.isClosed)
        check(registry.liveCount() == 0)
        check(f.owner.identity() == null)
        check(f.owner.tlsPeerIdentity() == null)
    }

    @Test fun closedSocketReceiptIsPurgedBeforeItsTtl() {
        val f = fixture()
        (f.sockets[1] as javax.net.ssl.SSLSocket).close()
        check(f.tlsProfile.purgeExpiredReceipts() == 1)
        check(f.owner.acceptPairingReceipt(f.receipt).accepted)
        check(f.owner.activate(f.tlsReceipt).code == "INVALID_TLS_RECEIPT")
    }

    @Test fun abandonedTlsReceiptExpiresAndClosesItsSocket() {
        val f = fixture()
        val future = System.nanoTime() + CompanionPinnedTlsProfile.RECEIPT_TTL_NANOS + 1L
        check(f.tlsProfile.purgeExpiredReceipts(future) == 1)
        check((f.sockets[1] as javax.net.ssl.SSLSocket).isClosed)
        check(f.owner.acceptPairingReceipt(f.receipt).accepted)
        check(f.owner.activate(f.tlsReceipt).code == "INVALID_TLS_RECEIPT")
        check(f.owner.state() == CompanionSessionOwner.State.PAIRED)
    }

    @Test fun tlsReceiptIsSingleUseAndCannotBeConsumedByAnotherProfile() {
        val f = fixture()
        check(f.owner.acceptPairingReceipt(f.receipt).accepted)
        val password = "test-only-password".toCharArray()
        val root = certificate("root-ca.pem")
        val identity = keyStore("server", root, password)
        val trust = emptyKeyStore().apply { setCertificateEntry("root", root) }
        val foreignProfile = CompanionPinnedTlsProfile.create(
            identity, password, trust,
            mapOf("android-client" to setOf(CompanionSpkiSha256Pin.fromCertificate(certificate("client.pem"))))
        )
        val foreignServer = foreignProfile.bindServer(0, address = InetAddress.getByName("127.0.0.1"))
        val foreignExecutor = Executors.newSingleThreadExecutor()
        val foreignAccepted = foreignExecutor.submit<CompanionTlsConnection> {
            foreignProfile.accept(foreignServer, f.challenge.sessionId, "android-client")
        }
        val clientIdentity = keyStore("client", root, password)
        val clientProfile = CompanionPinnedTlsProfile.create(
            clientIdentity, password, trust,
            mapOf("desktop-companion" to setOf(CompanionSpkiSha256Pin.fromCertificate(certificate("server.pem"))))
        )
        val foreignClient = clientProfile.connect("localhost", foreignServer.localPort, f.challenge.sessionId, "desktop-companion")
        val foreignReceipt = foreignAccepted.get(5, TimeUnit.SECONDS).receipt
        foreignExecutor.shutdownNow()
        foreignClient.socket.close()
        foreignServer.close()
        check(f.owner.activate(foreignReceipt).code == "INVALID_TLS_RECEIPT")
        check(f.owner.state() == CompanionSessionOwner.State.PAIRED)
        check(activate(f).accepted)
    }

    @Test fun tlsReceiptFromAnotherPairingSessionCannotActivateOwner() {
        val first = fixture()
        val second = fixture()
        check(first.owner.acceptPairingReceipt(first.receipt).accepted)
        check(second.owner.acceptPairingReceipt(second.receipt).accepted)
        check(second.owner.activate(first.tlsReceipt).code == "INVALID_TLS_RECEIPT")
        check(second.owner.state() == CompanionSessionOwner.State.PAIRED)
        check(activate(second).accepted)
    }



    @Test fun revokedPeerCannotActivateFromAnAlreadyIssuedTlsReceipt() {
        val f = fixture { _, _ -> false }
        check(f.owner.acceptPairingReceipt(f.receipt).accepted)
        val activation = f.owner.activate(f.tlsReceipt)
        check(!activation.accepted)
        check(activation.code == "PEER_REVOKED")
        check(f.owner.state() == CompanionSessionOwner.State.FAILED)
    }

    @Test fun revokedPeerFailsClosedOnNextSessionOperation() {
        var trusted = true
        val f = fixture { peerId, pin -> peerId == "android-client" && trusted &&
            pin == CompanionSpkiSha256Pin.fromCertificate(certificate("client.pem")) }
        check(f.owner.acceptPairingReceipt(f.receipt).accepted)
        check(activate(f).accepted)
        trusted = false
        val verification = f.owner.verifyIncoming(incomingFrame(f))
        check(!verification.accepted)
        check(verification.code == "PEER_REVOKED")
        check(f.owner.state() == CompanionSessionOwner.State.FAILED)
        check(f.owner.beginTransfer().code == "INVALID_STATE")
    }

    @Test fun fatalTrustValidatorErrorDuringActiveOperationFailsOwnerBeforePropagating() {
        var fatal = false
        val f = fixture { _, _ ->
            if (fatal) throw AssertionError("trust store fatal")
            true
        }
        check(f.owner.acceptPairingReceipt(f.receipt).accepted)
        check(activate(f).accepted)
        fatal = true
        try {
            f.owner.beginTransfer()
            error("Expected fatal trust validator error")
        } catch (expected: AssertionError) {
            check(expected.message == "trust store fatal")
        }
        check(f.owner.state() == CompanionSessionOwner.State.FAILED)
        check(f.owner.identity() == null)
        check(f.owner.tlsPeerIdentity() == null)
    }

    @Test fun explicitPeerRevocationSignalImmediatelyFailsAndClearsOwner() {
        val f = fixture()
        check(f.owner.acceptPairingReceipt(f.receipt).accepted)
        check(activate(f).accepted)
        check(f.owner.identity() != null)
        check(f.owner.tlsPeerIdentity() != null)
        f.owner.invalidateForPeerRevocation()
        check(f.owner.state() == CompanionSessionOwner.State.FAILED)
        check(f.owner.identity() == null)
        check(f.owner.tlsPeerIdentity() == null)
        check(f.owner.verifyIncoming(incomingFrame(f)).code == "SESSION_FAILED")
        f.owner.invalidateForPeerRevocation() // idempotent
        check(f.owner.state() == CompanionSessionOwner.State.FAILED)
    }

    @Test fun pairingAloneNeverActivatesNetworkSession() {
        val f = fixture()
        check(f.owner.acceptPairingReceipt(f.receipt).accepted)
        check(f.owner.activate().code == "TLS_IDENTITY_REQUIRED")
        check(f.owner.state() == CompanionSessionOwner.State.PAIRED)
        check(activate(f).accepted)
    }


    @Test fun tlsPeerIdentityIsNotExposedAfterIdleTimeout() {
        val f = fixture()
        check(f.owner.acceptPairingReceipt(f.receipt).accepted)
        check(activate(f).accepted)
        check(f.owner.tlsPeerIdentity() != null)
        f.clock.millis += 21_000L // idle timeout is 20 seconds in this fixture
        check(f.owner.tlsPeerIdentity() == null)
        check(f.owner.state() == CompanionSessionOwner.State.FAILED)
    }

    @Test fun pairingIdentityIsClearedWhenPeerRevoked() {
        var trusted = true
        val f = fixture { peerId, pin -> peerId == "android-client" && trusted &&
            pin == CompanionSpkiSha256Pin.fromCertificate(certificate("client.pem")) }
        check(f.owner.acceptPairingReceipt(f.receipt).accepted)
        check(f.owner.identity() != null)
        check(activate(f).accepted)
        trusted = false
        check(f.owner.verifyIncoming(incomingFrame(f)).code == "PEER_REVOKED")
        check(f.owner.identity() == null)
        check(f.owner.tlsPeerIdentity() == null)
    }
}
