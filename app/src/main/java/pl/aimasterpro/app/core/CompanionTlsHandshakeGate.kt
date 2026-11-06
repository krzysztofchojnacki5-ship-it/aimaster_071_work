package pl.aimasterpro.app.core

import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLServerSocket

/**
 * Admission adapter joining pinned TLS handshakes to the revocation-aware connection registry.
 *
 * Critical ordering invariant: registry.ticket(peerId) is captured before invoking profile.accept
 * or profile.connect (both perform startHandshake internally). The same ticket is then used to
 * register the authenticated socket. A revocation at any point between those operations makes
 * registration stale; the socket is closed and its unconsumed TLS receipt is discarded.
 */
class CompanionTlsHandshakeGate(private val registry: CompanionConnectionRegistry) {
    class OwnedConnection internal constructor(
        internal val connection: CompanionTlsConnection,
        private val peerId: String,
        private val registry: CompanionConnectionRegistry,
        private val profile: CompanionPinnedTlsProfile,
        private val owner: CompanionSessionOwner
    ) : CompanionRevocationAwareConnection {
        private val closed = AtomicBoolean(false)
        private val profileCloseListener: () -> Unit = { close() }

        init {
            if (!profile.addCloseListener(profileCloseListener)) {
                closed.set(true)
                throw IOException("Profil TLS Companion został zamknięty przed rejestracją połączenia.")
            }
        }

        internal val isClosed: Boolean get() = closed.get()
        val socket get() = connection.socket

        override fun close() = terminate(revoked = false)

        override fun closeForRevocation() = terminate(revoked = true)

        private fun terminate(revoked: Boolean) {
            if (!closed.compareAndSet(false, true)) return
            // Change the owner state before potentially blocking socket I/O. A slow/broken
            // close must never leave an ACTIVE owner after the registry has revoked this peer.
            try {
                if (revoked) owner.invalidateForPeerRevocation() else owner.close()
            } finally {
                // Owner state transition is allowed to fail, but it must never prevent
                // transport shutdown, TLS receipt disposal, or registry release.
                try {
                    profile.closeSocket(connection.socket)
                } finally {
                    try {
                        profile.discard(connection.receipt)
                    } finally {
                        registry.release(peerId, this)
                    }
                }
            }
        }
    }

    /** Preferred path: profile and owner are inseparably paired by CompanionProvisioningStore. */
    fun accept(
        provision: CompanionProvisionedSession,
        server: SSLServerSocket,
        pairingSessionId: String,
        expectedPeerId: String,
        timeoutMs: Int = 5_000
    ): OwnedConnection = accept(
        provision.tlsProfile, server, pairingSessionId, expectedPeerId,
        provision.ownerForAdmission(), timeoutMs
    )

    /** Preferred outbound path using the exact profile bound to the provisioned session owner. */
    fun connect(
        provision: CompanionProvisionedSession,
        expectedHost: String,
        port: Int,
        pairingSessionId: String,
        expectedPeerId: String,
        timeoutMs: Int = 5_000
    ): OwnedConnection = connect(
        provision.tlsProfile, expectedHost, port, pairingSessionId, expectedPeerId,
        provision.ownerForAdmission(), timeoutMs
    )

    fun accept(
        profile: CompanionPinnedTlsProfile,
        server: SSLServerSocket,
        pairingSessionId: String,
        expectedPeerId: String,
        owner: CompanionSessionOwner,
        timeoutMs: Int = 5_000
    ): OwnedConnection = establish(profile, expectedPeerId, owner) {
        profile.accept(server, pairingSessionId, expectedPeerId, timeoutMs)
    }

    fun connect(
        profile: CompanionPinnedTlsProfile,
        expectedHost: String,
        port: Int,
        pairingSessionId: String,
        expectedPeerId: String,
        owner: CompanionSessionOwner,
        timeoutMs: Int = 5_000
    ): OwnedConnection = establish(profile, expectedPeerId, owner) {
        profile.connect(expectedHost, port, pairingSessionId, expectedPeerId, timeoutMs)
    }

    /** Cleanup is exhaustive and preserves the activation failure as the primary exception. */
    private fun cleanupRejectedConnection(
        owner: CompanionSessionOwner,
        profile: CompanionPinnedTlsProfile,
        connection: CompanionTlsConnection,
        primary: Throwable
    ) {
        try {
            owner.close()
        } catch (cleanup: Throwable) {
            if (cleanup !== primary) primary.addSuppressed(cleanup)
        }
        try {
            profile.closeSocket(connection.socket)
        } catch (cleanup: Throwable) {
            if (cleanup !== primary) primary.addSuppressed(cleanup)
        }
        try {
            profile.discard(connection.receipt)
        } catch (cleanup: Throwable) {
            if (cleanup !== primary) primary.addSuppressed(cleanup)
        }
    }

    private fun establish(
        profile: CompanionPinnedTlsProfile,
        peerId: String,
        owner: CompanionSessionOwner,
        handshake: () -> CompanionTlsConnection
    ): OwnedConnection {
        require(owner.isBoundToProfile(profile)) {
            "Owner sesji Companion nie jest związany z przekazanym profilem TLS."
        }
        val ticket = registry.ticket(peerId) // MUST precede the TLS handshake.
        try {
            val connection = handshake()
            val activation = try {
                owner.activate(connection.receipt)
            } catch (error: Throwable) {
                cleanupRejectedConnection(owner, profile, connection, error)
                throw error
            }
            if (!activation.accepted) {
                val error = IOException("Owner Companion odrzucił tożsamość TLS: ${activation.code}.")
                cleanupRejectedConnection(owner, profile, connection, error)
                throw error
            }
            val owned = try {
                OwnedConnection(connection, peerId, registry, profile, owner)
            } catch (error: Throwable) {
                cleanupRejectedConnection(owner, profile, connection, error)
                throw error
            }
            // Register the wrapper, not the raw socket: revocation then closes the socket AND
            // discards its still-unconsumed TLS receipt through OwnedConnection.close().
            if (!registry.register(ticket, owned)) {
                throw IOException("Połączenie Companion zostało unieważnione podczas handshake TLS.")
            }
            // Profile closure may race between wrapper creation and registry registration. If its
            // callback won that race, release the just-added closed wrapper instead of leaking it.
            if (owned.isClosed) {
                registry.release(peerId, owned)
                throw IOException("Profil TLS Companion został zamknięty podczas rejestracji połączenia.")
            }
            return owned
        } finally {
            // Handshake/activation exceptions must not retain a live ticket indefinitely.
            registry.cancel(ticket)
        }
    }
}
