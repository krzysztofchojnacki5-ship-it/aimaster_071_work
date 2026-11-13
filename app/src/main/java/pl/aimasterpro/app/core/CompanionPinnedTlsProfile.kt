package pl.aimasterpro.app.core

import java.io.Closeable
import java.net.InetAddress
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.IdentityHashMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket

/** Opaque one-time capability issued only after this profile completes a pinned mTLS handshake. */
interface CompanionTlsPeerReceipt

/** The local endpoint role determines which pairing identity must match the authenticated peer. */
enum class CompanionTlsLocalRole { CLIENT, SERVER }

/** A completed connection and its unforgeable, single-use TLS peer proof. */
data class CompanionTlsConnection internal constructor(
    val socket: SSLSocket,
    val receipt: CompanionTlsPeerReceipt
)

/**
 * Owns the pinned SSLContext and every socket from which it may issue peer receipts.
 * Callers cannot submit arbitrary SSLSession objects or sockets from a trust-all context.
 */
class CompanionPinnedTlsProfile private constructor(
    private val context: javax.net.ssl.SSLContext,
    private val pinsByPeerId: Map<String, Set<CompanionSpkiSha256Pin>>
) : Closeable {
    private data class IssuedPeer(
        val pairingSessionId: String,
        val peerId: String,
        val role: CompanionTlsLocalRole,
        val pin: CompanionSpkiSha256Pin,
        val tlsSessionId: ByteArray,
        val tlsSession: SSLSession,
        val socket: SSLSocket,
        val issuedAtNanos: Long
    )

    private class Receipt : CompanionTlsPeerReceipt

    private val serverSockets = IdentityHashMap<SSLServerSocket, Boolean>()
    /** Includes handshaking, receipt-pending and admitted sockets until explicit close. */
    private val activeSockets = IdentityHashMap<SSLSocket, Boolean>()
    private val closeListeners = IdentityHashMap<() -> Unit, Boolean>()
    @Volatile private var closed = false
    private val issued = IdentityHashMap<CompanionTlsPeerReceipt, IssuedPeer>()
    private val expiryTasks = IdentityHashMap<CompanionTlsPeerReceipt, ScheduledFuture<*>>()

    /** Creates, configures and registers a server socket owned by this pinned profile. */
    @Synchronized
    fun bindServer(port: Int, backlog: Int = 16, address: InetAddress? = null): SSLServerSocket {
        require(port in 0..65535)
        require(backlog in 1..128)
        synchronized(this) {
            check(!closed) { "Profil TLS Companion jest zamknięty." }
            check(serverSockets.size < MAX_SERVER_SOCKETS) { "Limit gniazd serwera Companion został osiągnięty." }
        }
        val socket = if (address == null) {
            context.serverSocketFactory.createServerSocket(port, backlog)
        } else {
            context.serverSocketFactory.createServerSocket(port, backlog, address)
        } as? SSLServerSocket ?: throw IllegalStateException("Dostawca TLS nie utworzył SSLServerSocket.")
        try {
            CompanionTlsContext.configureMutualTlsServerSocket(socket)
            synchronized(this) {
                check(!closed) { "Profil TLS Companion został zamknięty podczas tworzenia listenera." }
                serverSockets[socket] = true
            }
            return socket
        } catch (error: Throwable) {
            try { socket.close() } catch (_: Exception) { }
            throw error
        }
    }

    /** Closes and unregisters a server socket created by this profile. */
    fun closeServer(server: SSLServerSocket) {
        val owned = synchronized(this) { serverSockets.remove(server) != null }
        if (!owned) return
        // Do not wait for the accept monitor: closing the listener must unblock a pending accept.
        try { server.close() } catch (_: Exception) { }
    }

    /** Accepts exactly one peer, completes the TLS handshake and issues a bound one-time receipt. */
    fun accept(
        server: SSLServerSocket,
        pairingSessionId: String,
        expectedPeerId: String,
        timeoutMs: Int = 5_000
    ): CompanionTlsConnection {
        requireValidBinding(pairingSessionId, expectedPeerId)
        require(timeoutMs in 1..60_000)
        // SSLServerSocket.soTimeout belongs to the listener, not to an individual accept call.
        // Serialize timeout assignment and accept() so concurrent callers cannot overwrite it.
        // Handshake is deliberately outside this monitor so a slow peer cannot block the next accept.
        val socket = synchronized(server) {
            synchronized(this) {
                check(!closed) { "Profil TLS Companion jest zamknięty." }
                check(serverSockets.containsKey(server)) { "Gniazdo serwera nie należy do tego profilu TLS." }
            }
            server.soTimeout = timeoutMs
            server.accept() as? SSLSocket
                ?: throw IllegalStateException("Dostawca TLS nie zaakceptował SSLSocket.")
        }
        registerActiveSocket(socket)
        return establish(socket, pairingSessionId, expectedPeerId, CompanionTlsLocalRole.SERVER, timeoutMs)
    }

    /** Connects, completes the TLS handshake and issues a bound one-time receipt. */
    fun connect(
        expectedHost: String,
        port: Int,
        pairingSessionId: String,
        expectedPeerId: String,
        timeoutMs: Int = 5_000
    ): CompanionTlsConnection {
        requireValidBinding(pairingSessionId, expectedPeerId)
        require(timeoutMs in 1..60_000)
        synchronized(this) { check(!closed) { "Profil TLS Companion jest zamknięty." } }
        val socket = CompanionTlsContext.openClientSocket(context, expectedHost, port, timeoutMs)
        registerActiveSocket(socket)
        return establish(socket, pairingSessionId, expectedPeerId, CompanionTlsLocalRole.CLIENT, timeoutMs)
    }

    /**
     * Permanently closes this profile. It fences new listeners/connections first, then closes all
     * in-flight handshakes, receipt-pending sockets and admitted sockets outside the monitor.
     */
    override fun close() {
        val servers: List<SSLServerSocket>
        val sockets: List<SSLSocket>
        val listeners: List<() -> Unit>
        synchronized(this) {
            if (closed) return
            closed = true
            servers = serverSockets.keys.toList()
            serverSockets.clear()
            sockets = activeSockets.keys.toList()
            activeSockets.clear()
            listeners = closeListeners.keys.toList()
            closeListeners.clear()
            issued.values.forEach { it.tlsSessionId.fill(0) }
            issued.clear()
            expiryTasks.values.forEach { it.cancel(false) }
            expiryTasks.clear()
        }
        // Closing listeners first unblocks accept(); callbacks transition owners and release
        // registry handles before sockets are force-closed as a final safety net.
        servers.forEach { try { it.close() } catch (_: Exception) { } }
        var failure: Throwable? = null
        listeners.forEach { listener ->
            try { listener() } catch (error: Throwable) {
                if (failure == null) failure = error else if (failure !== error) failure!!.addSuppressed(error)
            }
        }
        sockets.forEach(::closeSocketWithoutTracking)
        failure?.let { throw it }
    }

    internal fun addCloseListener(listener: () -> Unit): Boolean = synchronized(this) {
        if (closed) false else { closeListeners[listener] = true; true }
    }

    internal fun removeCloseListener(listener: () -> Unit) {
        synchronized(this) { closeListeners.remove(listener) }
    }

    private fun registerActiveSocket(socket: SSLSocket) {
        val accepted = synchronized(this) {
            if (closed) false else { activeSockets[socket] = true; true }
        }
        if (!accepted) {
            closeSocketWithoutTracking(socket)
            throw IllegalStateException("Profil TLS Companion został zamknięty przed rozpoczęciem handshake.")
        }
    }

    private fun closeSocketWithoutTracking(socket: SSLSocket) {
        try { socket.soTimeout = SOCKET_CLOSE_TIMEOUT_MS } catch (_: Exception) { }
        try { socket.shutdownInput() } catch (_: Exception) { }
        try { socket.close() } catch (_: Exception) { }
    }

    /** Drops an unconsumed proof and closes its socket without performing I/O under the profile lock. */
    internal fun discard(receipt: CompanionTlsPeerReceipt) {
        val peer = synchronized(this) {
            expiryTasks.remove(receipt)?.cancel(false)
            issued.remove(receipt)
        }
        if (peer != null) {
            peer.tlsSessionId.fill(0)
            closeQuietly(peer.socket)
        }
    }

    /**
     * Purges expired, invalid, or already-closed receipt entries. The default clock is monotonic;
     * the explicit time parameter is an internal deterministic test seam.
     */
    internal fun purgeExpiredReceipts(nowNanos: Long = System.nanoTime()): Int {
        val expired = synchronized(this) { purgeExpiredReceiptsLocked(nowNanos) }
        expired.forEach(::closeQuietly)
        return expired.size
    }

    /** Consumes the exact receipt once and returns only metadata validated by this profile. */
    internal fun consume(receipt: CompanionTlsPeerReceipt): PeerIdentity? {
        purgeExpiredReceipts()
        val peer = synchronized(this) {
            expiryTasks.remove(receipt)?.cancel(false)
            issued.remove(receipt)
        } ?: return null
        if (peer.socket.isClosed || !peer.tlsSession.isValid || !peer.tlsSession.id.contentEquals(peer.tlsSessionId)) {
            peer.tlsSessionId.fill(0)
            closeQuietly(peer.socket)
            return null
        }
        val sessionId = peer.tlsSessionId.copyOf()
        peer.tlsSessionId.fill(0)
        return PeerIdentity(peer.pairingSessionId, peer.peerId, peer.role, peer.pin, sessionId)
    }

    private fun purgeExpiredReceiptsLocked(nowNanos: Long): List<SSLSocket> {
        val expired = ArrayList<SSLSocket>()
        val iterator = issued.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val peer = entry.value
            if (peer.socket.isClosed || !peer.tlsSession.isValid || nowNanos - peer.issuedAtNanos >= RECEIPT_TTL_NANOS) {
                expired += peer.socket
                peer.tlsSessionId.fill(0)
                expiryTasks.remove(entry.key)?.cancel(false)
                iterator.remove()
            }
        }
        return expired
    }

    internal fun closeSocket(socket: SSLSocket) {
        synchronized(this) { activeSockets.remove(socket) }
        // SSLSocket.close() may wait for the peer's close_notify. Revocation/failed admission
        // must not block indefinitely on an untrusted peer, so bound its shutdown wait.
        try { socket.soTimeout = SOCKET_CLOSE_TIMEOUT_MS } catch (_: Exception) { }
        try { socket.shutdownInput() } catch (_: Exception) { }
        try { socket.close() } catch (_: Exception) { }
    }

    private fun closeQuietly(socket: SSLSocket) = closeSocket(socket)

    /** Scheduled expiry removes the capability under lock and closes its socket after unlock. */
    private fun expireReceipt(receipt: CompanionTlsPeerReceipt) {
        val peer = synchronized(this) {
            expiryTasks.remove(receipt)
            issued.remove(receipt)
        } ?: return
        peer.tlsSessionId.fill(0)
        closeQuietly(peer.socket)
    }

    internal data class PeerIdentity(
        val pairingSessionId: String,
        val peerId: String,
        val role: CompanionTlsLocalRole,
        val pin: CompanionSpkiSha256Pin,
        val tlsSessionId: ByteArray
    )

    private fun establish(
        socket: SSLSocket,
        pairingSessionId: String,
        expectedPeerId: String,
        role: CompanionTlsLocalRole,
        timeoutMs: Int
    ): CompanionTlsConnection {
        try {
            socket.soTimeout = timeoutMs
            socket.startHandshake()
            val session = socket.session
            require(session.isValid) { "Sesja TLS nie jest prawidłowa." }
            require(session.protocol == "TLSv1.2" || session.protocol == "TLSv1.3") {
                "Niedozwolona wersja TLS."
            }
            val leaf = session.peerCertificates.firstOrNull() as? X509Certificate
                ?: throw CertificateException("Brak certyfikatu X.509 peer.")
            val pin = CompanionSpkiSha256Pin.fromCertificate(leaf)
            val expectedPins = pinsByPeerId[expectedPeerId]
                ?: throw CertificateException("Brak provisioningu SPKI dla identyfikatora peer.")
            if (expectedPins.none { it == pin }) throw CertificateException("SPKI peer nie pasuje do przypiętej tożsamości.")
            // Expired/orphaned receipts must release both their map slot and their socket.
            purgeExpiredReceipts()
            val receipt = Receipt()
            synchronized(this) {
                check(!closed) { "Profil TLS Companion został zamknięty podczas handshake." }
                check(!socket.isClosed) { "Socket TLS został zamknięty przed wydaniem receipt." }
                check(issued.size < MAX_RECEIPTS) { "Limit nieodebranych potwierdzeń TLS został osiągnięty." }
                val issuedPeer = IssuedPeer(
                    pairingSessionId, expectedPeerId, role, pin, session.id.copyOf(), session,
                    socket, System.nanoTime()
                )
                issued[receipt] = issuedPeer
                try {
                    expiryTasks[receipt] = RECEIPT_EXPIRY_EXECUTOR.schedule(
                        { expireReceipt(receipt) }, RECEIPT_TTL_NANOS, TimeUnit.NANOSECONDS
                    )
                } catch (error: RuntimeException) {
                    issued.remove(receipt)
                    issuedPeer.tlsSessionId.fill(0)
                    throw error
                }
            }
            return CompanionTlsConnection(socket, receipt)
        } catch (error: Throwable) {
            closeSocket(socket)
            throw error
        }
    }

    private fun requireValidBinding(sessionId: String, peerId: String) {
        require(sessionId.matches(Regex("[A-Za-z0-9._-]{1,80}"))) { "Nieprawidłowy identyfikator sesji parowania." }
        require(peerId.matches(Regex("[A-Za-z0-9._-]{1,80}"))) { "Nieprawidłowy identyfikator peer." }
    }

    companion object {
        const val MAX_RECEIPTS = 32
        const val MAX_SERVER_SOCKETS = 8
        internal const val RECEIPT_TTL_NANOS = 30_000_000_000L
        private const val SOCKET_CLOSE_TIMEOUT_MS = 250
        private val RECEIPT_EXPIRY_EXECUTOR = ScheduledThreadPoolExecutor(1) { runnable ->
            Thread(runnable, "CompanionTlsReceiptExpiry").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }

        /** Creates a profile whose SSLContext and pin set remain private to the profile. */
        fun create(
            identityKeyStore: KeyStore,
            keyPassword: CharArray?,
            trustStore: KeyStore,
            pinsByPeerId: Map<String, Set<CompanionSpkiSha256Pin>>
        ): CompanionPinnedTlsProfile {
            require(pinsByPeerId.isNotEmpty() && pinsByPeerId.all { (id, pins) ->
                id.matches(Regex("[A-Za-z0-9._-]{1,80}")) && pins.isNotEmpty()
            }) { "Każdy identyfikator peer musi mieć niepusty zestaw pinów SPKI." }
            val immutablePins = pinsByPeerId.mapValues { it.value.toSet() }.toMap()
            val context = CompanionTlsContext.createPinnedMutualTlsContext(
                identityKeyStore, keyPassword, trustStore, immutablePins.values.flatten().toSet()
            )
            return CompanionPinnedTlsProfile(context, immutablePins)
        }
    }
}
