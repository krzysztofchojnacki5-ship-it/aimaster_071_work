package pl.aimasterpro.app.core

import java.io.Closeable

/** Starts the registry fence under the store monitor; socket cleanup runs later. */
class CompanionTrustRevocationBinding(
    private val source: CompanionTrustRevocationSource,
    private val registry: CompanionConnectionRegistry
) : Closeable {
    private val lifecycleLock = java.lang.Object()
    private enum class State { OPEN, CLOSING, CLOSED }
    private var state = State.OPEN
    private var closeFailure: Throwable? = null
    private var inFlightCleanups = 0

    private val fence = CompanionTrustRevocationFenceListener { event ->
        val batch = synchronized(lifecycleLock) {
            check(state == State.OPEN) { "Binding revocation Companion jest zamykany lub zamknięty." }
            inFlightCleanups++
            try {
                registry.beginRevocation(event.peerId)
            } catch (error: Throwable) {
                inFlightCleanups--
                lifecycleLock.notifyAll()
                throw error
            }
        }
        val completionLock = Any()
        var completed = false
        Closeable {
            synchronized(completionLock) {
                if (completed) return@Closeable
                completed = true
                try {
                    batch.close()
                } finally {
                    synchronized(lifecycleLock) {
                        inFlightCleanups--
                        lifecycleLock.notifyAll()
                    }
                }
            }
        }
    }

    init {
        check(source.addTrustRevocationFenceListener(fence)) {
            "Nie udało się zarejestrować fence listenera Companion."
        }
    }

    override fun close() {
        var ownsClose = false
        synchronized(lifecycleLock) {
            while (state == State.CLOSING) awaitUninterruptibly(lifecycleLock)
            if (state == State.CLOSED) {
                closeFailure?.let { throw it }
                return
            }
            state = State.CLOSING
            ownsClose = true
        }
        if (!ownsClose) return

        var failure: Throwable? = null
        try {
            source.removeTrustRevocationFenceListener(fence)
        } catch (error: Throwable) {
            failure = error
        }
        synchronized(lifecycleLock) {
            while (inFlightCleanups > 0) awaitUninterruptibly(lifecycleLock)
            closeFailure = failure
            state = State.CLOSED
            lifecycleLock.notifyAll()
        }
        failure?.let { throw it }
    }
}

/** Waits without abandoning a lifecycle transition; restores the interrupt flag afterwards. */
@Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
private fun awaitUninterruptibly(monitor: java.lang.Object) {
    var interrupted = false
    while (true) {
        try {
            monitor.wait()
            break
        } catch (_: InterruptedException) {
            interrupted = true
        }
    }
    if (interrupted) Thread.currentThread().interrupt()
}
