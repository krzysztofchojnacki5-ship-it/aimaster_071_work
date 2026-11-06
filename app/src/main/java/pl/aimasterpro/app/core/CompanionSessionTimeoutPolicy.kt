package pl.aimasterpro.app.core

/**
 * Monotonic timeout bookkeeping for a future Companion session owner.
 * This class does not authenticate peers, verify frames, open transports, or authorize execution.
 * Callers must record activity only after the corresponding frame has been authenticated.
 */
class CompanionSessionTimeoutPolicy(
    private val handshakeTimeoutNanos: Long,
    private val idleTimeoutNanos: Long,
    private val transferTimeoutNanos: Long,
    private val hardLifetimeNanos: Long,
    private val clockNanos: () -> Long = System::nanoTime
) {
    enum class Timeout { NONE, HANDSHAKE, IDLE, TRANSFER, HARD_LIFETIME }

    private val createdAtNanos: Long
    private var lastActivityAtNanos: Long
    private var handshakeFinished = false
    private var transferStartedAtNanos: Long? = null

    init {
        require(handshakeTimeoutNanos > 0L) { "Timeout handshake musi być dodatni." }
        require(idleTimeoutNanos > 0L) { "Timeout bezczynności musi być dodatni." }
        require(transferTimeoutNanos > 0L) { "Timeout transferu musi być dodatni." }
        require(hardLifetimeNanos > 0L) { "Maksymalny czas sesji musi być dodatni." }
        createdAtNanos = clockNanos()
        lastActivityAtNanos = createdAtNanos
    }

    /** Ends the handshake timer; bookkeeping only, not proof that authentication succeeded. */
    @Synchronized
    fun markHandshakeFinished() {
        val now = clockNanos()
        if (timeoutAt(now) != Timeout.NONE || handshakeFinished) return
        handshakeFinished = true
        lastActivityAtNanos = now
    }

    /** Records already-authenticated activity. It never extends the hard session lifetime. */
    @Synchronized
    fun recordAuthenticatedActivity() {
        val now = clockNanos()
        if (timeoutAt(now) != Timeout.NONE || !handshakeFinished) return
        lastActivityAtNanos = now
    }

    /** Starts the single bounded transfer window. */
    @Synchronized
    fun beginTransfer() {
        val now = clockNanos()
        check(timeoutAt(now) == Timeout.NONE) { "Sesja wygasła." }
        check(handshakeFinished) { "Faza handshake nie została zakończona." }
        check(transferStartedAtNanos == null) { "Transfer już trwa." }
        transferStartedAtNanos = now
        lastActivityAtNanos = now
    }

    /** Ends the transfer window after the caller has independently validated transfer completion. */
    @Synchronized
    fun finishTransfer() {
        val now = clockNanos()
        check(timeoutAt(now) == Timeout.NONE) { "Sesja wygasła." }
        check(transferStartedAtNanos != null) { "Brak aktywnego transferu." }
        transferStartedAtNanos = null
        lastActivityAtNanos = now
    }

    @Synchronized
    fun currentTimeout(): Timeout = timeoutAt(clockNanos())

    /** All decisions in a state transition use the exact same monotonic sample. */
    private fun timeoutAt(now: Long): Timeout {
        // Subtraction is intentional: nanoTime is an arbitrary monotonic counter and may wrap.
        if (now - createdAtNanos >= hardLifetimeNanos) return Timeout.HARD_LIFETIME
        if (!handshakeFinished && now - createdAtNanos >= handshakeTimeoutNanos) return Timeout.HANDSHAKE
        val transferStarted = transferStartedAtNanos
        if (transferStarted != null && now - transferStarted >= transferTimeoutNanos) return Timeout.TRANSFER
        if (handshakeFinished && now - lastActivityAtNanos >= idleTimeoutNanos) return Timeout.IDLE
        return Timeout.NONE
    }
}
