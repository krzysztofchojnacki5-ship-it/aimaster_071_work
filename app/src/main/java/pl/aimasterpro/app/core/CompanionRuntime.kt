package pl.aimasterpro.app.core

import java.io.Closeable

/**
 * Application-process owner for the Companion connection registry and its trust binding.
 * Construct exactly once from the application-scoped provisioning store. Screens/services should
 * receive this instance; they must not create their own registry or revocation binding.
 */
class CompanionRuntime private constructor(
    val registry: CompanionConnectionRegistry,
    private val binding: CompanionTrustRevocationBinding
) : Closeable {
    /** TLS admission is bound to this runtime's registry; callers must not create a parallel gate. */
    val tlsHandshakeGate: CompanionTlsHandshakeGate = CompanionTlsHandshakeGate(registry)
    private val lifecycleLock = java.lang.Object()
    private enum class State { OPEN, CLOSING, CLOSED }
    private var state = State.OPEN
    private var closeFailure: Throwable? = null

    fun ensureOpen() {
        synchronized(lifecycleLock) {
            check(state == State.OPEN) { "Runtime Companion jest zamykany lub zamknięty." }
        }
    }

    override fun close() {
        synchronized(lifecycleLock) {
            while (state == State.CLOSING) awaitUninterruptibly(lifecycleLock)
            if (state == State.CLOSED) {
                closeFailure?.let { throw it }
                return
            }
            state = State.CLOSING
        }
        var failure: Throwable? = null
        try {
            // Fence admission and close live sessions before detaching the trust listener.
            // Any handshake already in flight has its ticket invalidated and cannot register.
            registry.shutdown()
        } catch (error: Throwable) {
            failure = error
        }
        try {
            binding.close()
        } catch (error: Throwable) {
            if (failure == null) failure = error else if (failure !== error) failure.addSuppressed(error)
        }
        synchronized(lifecycleLock) {
            closeFailure = failure
            state = State.CLOSED
            lifecycleLock.notifyAll()
        }
        failure?.let { throw it }
    }

    companion object {
        /** Creates one complete owner. If binding fails, no partially initialized runtime escapes. */
        fun create(source: CompanionTrustRevocationSource): CompanionRuntime {
            val registry = CompanionConnectionRegistry()
            val binding = CompanionTrustRevocationBinding(source, registry)
            return CompanionRuntime(registry, binding)
        }
    }
}

/**
 * Small synchronized holder suitable for an Application-owned field. It deliberately does not
 * retain Context or provide a process-global static singleton; the Android Application owns it.
 */
class CompanionRuntimeSlot {
    private var runtime: CompanionRuntime? = null

    @Synchronized
    fun getOrCreate(source: CompanionTrustRevocationSource): CompanionRuntime {
        runtime?.let {
            it.ensureOpen()
            return it
        }
        return CompanionRuntime.create(source).also { runtime = it }
    }

    @Synchronized
    fun close() {
        val current = runtime ?: return
        runtime = null
        current.close()
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
