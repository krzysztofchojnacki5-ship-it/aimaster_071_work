package pl.aimasterpro.app.core

/**
 * Single in-process owner for a paired Companion session.
 *
 * PAIRED means the PSK challenge-response was verified. ACTIVE additionally requires a one-time
 * peer receipt from the exact pinned TLS profile bound at construction time.
 */
class CompanionSessionOwner private constructor(
    private val pairingAuthority: CompanionPairingAuthority,
    pairingSecret: ByteArray,
    private val tlsProfile: CompanionPinnedTlsProfile,
    private val timeoutPolicy: CompanionSessionTimeoutPolicy,
    private val trustValidator: (String, CompanionSpkiSha256Pin) -> Boolean
) {
    /** Production constructor: the owner creates and exclusively controls its timeout policy. */
    internal constructor(
        pairingAuthority: CompanionPairingAuthority,
        pairingSecret: ByteArray,
        tlsProfile: CompanionPinnedTlsProfile,
        handshakeTimeoutNanos: Long,
        idleTimeoutNanos: Long,
        transferTimeoutNanos: Long,
        hardLifetimeNanos: Long,
        trustValidator: (String, CompanionSpkiSha256Pin) -> Boolean
    ) : this(
        pairingAuthority,
        pairingSecret,
        tlsProfile,
        CompanionSessionTimeoutPolicy(
            handshakeTimeoutNanos, idleTimeoutNanos, transferTimeoutNanos, hardLifetimeNanos
        ),
        trustValidator
    )

    /** Internal deterministic clock seam for tests; production callers cannot replace the clock. */
    internal constructor(
        pairingAuthority: CompanionPairingAuthority,
        pairingSecret: ByteArray,
        tlsProfile: CompanionPinnedTlsProfile,
        handshakeTimeoutNanos: Long,
        idleTimeoutNanos: Long,
        transferTimeoutNanos: Long,
        hardLifetimeNanos: Long,
        clockNanos: () -> Long,
        trustValidator: (String, CompanionSpkiSha256Pin) -> Boolean
    ) : this(
        pairingAuthority,
        pairingSecret,
        tlsProfile,
        CompanionSessionTimeoutPolicy(
            handshakeTimeoutNanos, idleTimeoutNanos, transferTimeoutNanos, hardLifetimeNanos, clockNanos
        ),
        trustValidator
    )
    enum class State { NEW, PAIRED, ACTIVE, TRANSFERRING, CLOSED, FAILED }

    data class Result(val accepted: Boolean, val code: String, val state: State)

    private val pairingSecret = pairingSecret.copyOf()
    private var currentState = State.NEW
    private var verifiedIdentity: VerifiedCompanionPairing? = null
    private var verifiedTlsPeer: CompanionPinnedTlsProfile.PeerIdentity? = null
    private var receiver: CompanionFrameProtocol.Receiver? = null
    private val incomingDirection = CompanionFrameProtocol.Direction.COMPANION_TO_ANDROID

    init {
        require(pairingSecret.size in 16..64) { "Sekret parowania musi mieć 16–64 bajty." }
    }

    /** Identity check used by the provisioning capability; profile equality is referential. */
    internal fun isBoundToProfile(profile: CompanionPinnedTlsProfile): Boolean = tlsProfile === profile

    @Synchronized
    fun state(): State = currentState

    /** Identity is exposed only after this owner consumed a receipt from its configured authority. */
    @Synchronized
    fun identity(): VerifiedCompanionPairing? = verifiedIdentity

    /** Consumes an authority-issued, one-time receipt and prepares the inbound frame receiver. */
    @Synchronized
    fun acceptPairingReceipt(receipt: CompanionPairingReceipt): Result {
        if (currentState != State.NEW) return result(false, "INVALID_STATE")
        if (expireIfNeeded()) return result(false, timeoutCode())

        val identity = pairingAuthority.consumeVerifiedPairing(receipt)
            ?: return result(false, "INVALID_RECEIPT")

        return try {
            val sessionKey = CompanionFrameProtocol.deriveSessionKey(
                pairingSecret,
                identity.sessionId,
                identity.clientId,
                identity.companionId,
                identity.nonce,
                incomingDirection
            )
            receiver = CompanionFrameProtocol.Receiver(identity.sessionId, incomingDirection, sessionKey)
            sessionKey.fill(0)
            verifiedIdentity = identity
            timeoutPolicy.markHandshakeFinished()
            if (timeoutPolicy.currentTimeout() != CompanionSessionTimeoutPolicy.Timeout.NONE) {
                failInternal()
                result(false, timeoutCode())
            } else {
                currentState = State.PAIRED
                pairingSecret.fill(0)
                result(true, "PAIRED")
            }
        } catch (_: RuntimeException) {
            failInternal()
            result(false, "PAIRING_SETUP_FAILED")
        } finally {
            // The owner needs the PSK only while converting the one-time receipt into a session key.
            pairingSecret.fill(0)
        }
    }

    /**
     * Compatibility guard: pairing alone is deliberately insufficient to activate a network
     * session. A caller must provide a one-time receipt issued by the pinned TLS profile.
     */
    @Synchronized
    fun activate(): Result {
        if (currentState != State.PAIRED) return result(false, "INVALID_STATE")
        if (expireIfNeeded()) return result(false, timeoutCode())
        return result(false, "TLS_IDENTITY_REQUIRED")
    }

    /** Consumes a profile-issued TLS proof and binds it to the exact pairing identity. */
    @Synchronized
    fun activate(receipt: CompanionTlsPeerReceipt): Result {
        if (currentState != State.PAIRED) return result(false, "INVALID_STATE")
        if (expireIfNeeded()) return result(false, timeoutCode())
        val pairing = verifiedIdentity ?: run {
            failInternal()
            return result(false, "PAIRING_IDENTITY_UNAVAILABLE")
        }
        val peer = tlsProfile.consume(receipt) ?: return result(false, "INVALID_TLS_RECEIPT")
        val expectedPeerId = when (peer.role) {
            CompanionTlsLocalRole.CLIENT -> pairing.companionId
            CompanionTlsLocalRole.SERVER -> pairing.clientId
        }
        if (peer.pairingSessionId != pairing.sessionId || peer.peerId != expectedPeerId) {
            peer.tlsSessionId.fill(0)
            failInternal()
            return result(false, "TLS_IDENTITY_MISMATCH")
        }
        if (expireIfNeeded()) {
            peer.tlsSessionId.fill(0)
            return result(false, timeoutCode())
        }
        val trusted = try {
            isPeerTrusted(peer.peerId, peer.pin)
        } catch (error: Error) {
            // A fatal validator failure must not strand a consumed TLS identity in a PAIRED owner.
            peer.tlsSessionId.fill(0)
            failInternal()
            throw error
        }
        if (!trusted) {
            peer.tlsSessionId.fill(0)
            failInternal()
            return result(false, "PEER_REVOKED")
        }
        verifiedTlsPeer = peer
        currentState = State.ACTIVE
        return result(true, "ACTIVE")
    }

    /** Returns the authenticated peer metadata only after successful TLS-gated activation. */
    @Synchronized
    internal fun tlsPeerIdentity(): CompanionPinnedTlsProfile.PeerIdentity? {
        if (currentState == State.ACTIVE || currentState == State.TRANSFERRING) {
            if (expireIfNeeded()) return null
            if (failIfPeerTrustInvalid()) return null
        }
        return verifiedTlsPeer
    }

    /** Verifies an inbound frame. Only an accepted authenticated frame refreshes idle activity. */
    @Synchronized
    fun verifyIncoming(frame: CompanionFrameProtocol.Frame): CompanionFrameProtocol.Verification {
        if (currentState != State.ACTIVE && currentState != State.TRANSFERRING) {
            return CompanionFrameProtocol.Verification(false, stateFailureCode())
        }
        if (expireIfNeeded()) return CompanionFrameProtocol.Verification(false, "SESSION_TIMEOUT")
        if (failIfPeerTrustInvalid()) return CompanionFrameProtocol.Verification(false, "PEER_REVOKED")
        val activeReceiver = receiver
            ?: return CompanionFrameProtocol.Verification(false, "RECEIVER_UNAVAILABLE")
        val verification = activeReceiver.verify(frame)
        if (verification.accepted) {
            timeoutPolicy.recordAuthenticatedActivity()
            if (expireIfNeeded()) {
                return CompanionFrameProtocol.Verification(false, "SESSION_TIMEOUT")
            }
            if (frame.messageType == CompanionFrameProtocol.MessageType.CLOSE) closeInternal()
        } else if (verification.code == "SEQUENCE_EXHAUSTED") {
            failInternal()
        } else if (verification.code == "SESSION_CLOSED") {
            closeInternal()
        }
        return verification
    }

    @Synchronized
    fun beginTransfer(): Result {
        if (currentState != State.ACTIVE) return result(false, "INVALID_STATE")
        if (expireIfNeeded()) return result(false, timeoutCode())
        if (failIfPeerTrustInvalid()) return result(false, "PEER_REVOKED")
        return try {
            timeoutPolicy.beginTransfer()
            currentState = State.TRANSFERRING
            result(true, "TRANSFER_STARTED")
        } catch (_: IllegalStateException) {
            if (expireIfNeeded()) result(false, timeoutCode()) else result(false, "TRANSFER_NOT_ALLOWED")
        }
    }

    /** Call only after the caller has independently validated the complete transfer and its digest. */
    @Synchronized
    fun finishTransferAfterValidation(): Result {
        if (currentState != State.TRANSFERRING) return result(false, "INVALID_STATE")
        if (expireIfNeeded()) return result(false, timeoutCode())
        if (failIfPeerTrustInvalid()) return result(false, "PEER_REVOKED")
        return try {
            timeoutPolicy.finishTransfer()
            currentState = State.ACTIVE
            result(true, "TRANSFER_FINISHED")
        } catch (_: IllegalStateException) {
            if (expireIfNeeded()) result(false, timeoutCode()) else result(false, "TRANSFER_NOT_ACTIVE")
        }
    }

    /** Converts any elapsed policy deadline into a terminal FAILED session. */
    @Synchronized
    fun pollTimeout(): Result {
        if (currentState == State.CLOSED || currentState == State.FAILED) return result(false, stateFailureCode())
        if (expireIfNeeded()) return result(false, timeoutCode())
        if (failIfPeerTrustInvalid()) return result(false, "PEER_REVOKED")
        return result(true, "NO_TIMEOUT")
    }

    @Synchronized
    fun close() {
        if (currentState == State.CLOSED || currentState == State.FAILED) return
        closeInternal()
    }

    /** Terminal fail-closed transition invoked by the connection owner after peer revocation. */
    @Synchronized
    internal fun invalidateForPeerRevocation() {
        if (currentState == State.CLOSED || currentState == State.FAILED) return
        failInternal()
    }

    /** Revalidates the active peer against the durable trust store before every session operation. */
    private fun failIfPeerTrustInvalid(): Boolean {
        val peer = verifiedTlsPeer ?: return false
        if (isPeerTrusted(peer.peerId, peer.pin)) return false
        failInternal()
        return true
    }

    private fun isPeerTrusted(peerId: String, pin: CompanionSpkiSha256Pin): Boolean =
        try {
            trustValidator(peerId, pin)
        } catch (_: Exception) {
            false
        } catch (error: Error) {
            // A fatal trust-store failure must never leave an ACTIVE/TRANSFERRING owner usable.
            // Preserve fatal-error semantics, but clear the authenticated session first.
            failInternal()
            throw error
        }

    private fun expireIfNeeded(): Boolean {
        val timeout = timeoutPolicy.currentTimeout()
        if (timeout == CompanionSessionTimeoutPolicy.Timeout.NONE) return false
        failInternal()
        return true
    }

    private fun timeoutCode(): String = "SESSION_TIMEOUT"

    private fun stateFailureCode(): String = when (currentState) {
        State.CLOSED -> "SESSION_CLOSED"
        State.FAILED -> "SESSION_FAILED"
        else -> "INVALID_STATE"
    }

    private fun closeInternal() {
        receiver?.close()
        receiver = null
        verifiedTlsPeer?.tlsSessionId?.fill(0)
        verifiedTlsPeer = null
        verifiedIdentity = null
        pairingSecret.fill(0)
        currentState = State.CLOSED
    }

    private fun failInternal() {
        receiver?.close()
        receiver = null
        verifiedTlsPeer?.tlsSessionId?.fill(0)
        verifiedTlsPeer = null
        verifiedIdentity = null
        pairingSecret.fill(0)
        currentState = State.FAILED
    }

    private fun result(accepted: Boolean, code: String) = Result(accepted, code, currentState)
}
