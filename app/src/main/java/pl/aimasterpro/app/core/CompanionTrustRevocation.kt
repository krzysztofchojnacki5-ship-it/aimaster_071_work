package pl.aimasterpro.app.core

import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList

/** peerId=null represents a global trust wipe. */
data class CompanionTrustRevocationEvent(val peerId: String?) {
    init {
        require(peerId == null || peerId.matches(Regex("[A-Za-z0-9._-]{1,80}"))) {
            "Nieprawidłowy identyfikator peer w zdarzeniu revocation."
        }
    }
}

fun interface CompanionTrustRevocationListener {
    fun onTrustRevoked(event: CompanionTrustRevocationEvent)
}

/** A fence participant must perform only non-blocking admission invalidation in begin(). */
fun interface CompanionTrustRevocationFenceListener {
    fun begin(event: CompanionTrustRevocationEvent): Closeable
}

/** Minimal source contract keeps lifecycle binding independently testable. */
interface CompanionTrustRevocationSource {
    fun addTrustRevocationListener(listener: CompanionTrustRevocationListener): Boolean
    fun removeTrustRevocationListener(listener: CompanionTrustRevocationListener): Boolean
    fun addTrustRevocationFenceListener(listener: CompanionTrustRevocationFenceListener): Boolean
    fun removeTrustRevocationFenceListener(listener: CompanionTrustRevocationFenceListener): Boolean
}

/** Thread-safe publisher for non-blocking pre-commit fences and post-mutation observers. */
class CompanionTrustRevocationPublisher {
    private val listeners = CopyOnWriteArrayList<CompanionTrustRevocationListener>()
    private val fences = CopyOnWriteArrayList<CompanionTrustRevocationFenceListener>()

    fun add(listener: CompanionTrustRevocationListener): Boolean = listeners.addIfAbsent(listener)
    fun remove(listener: CompanionTrustRevocationListener): Boolean = listeners.remove(listener)
    fun addFence(listener: CompanionTrustRevocationFenceListener): Boolean = fences.addIfAbsent(listener)
    fun removeFence(listener: CompanionTrustRevocationFenceListener): Boolean = fences.remove(listener)

    /** Begins every registered fence. Returned cleanup is always run outside the store monitor. */
    fun beginRevocation(event: CompanionTrustRevocationEvent): FenceBatch {
        val cleanups = ArrayList<Closeable>()
        val failures = ArrayList<Throwable>()
        fences.forEach { fence ->
            try {
                cleanups += fence.begin(event)
            } catch (error: Throwable) {
                failures += error
            }
        }
        return FenceBatch(cleanups, failures)
    }

    inner class FenceBatch internal constructor(
        private val cleanups: List<Closeable>,
        failures: List<Throwable>
    ) : Closeable {
        private val completionLock = Any()
        private var completed = false
        val preparationFailures: List<Throwable> = failures.toList()

        override fun close() {
            synchronized(completionLock) {
                if (completed) return
                completed = true
                // A concurrent close() waits for cleanup completion instead of returning early.
                val failures = ArrayList<Throwable>()
                failures.addAll(preparationFailures)
                cleanups.forEach { cleanup ->
                    try {
                        cleanup.close()
                    } catch (error: Throwable) {
                        failures += error
                    }
                }
                throwAggregated(failures, "Nie udało się w pełni wykonać cleanup revocation fence.")
            }
        }
    }

    /** Returns callback failures after attempting every observer. */
    fun publish(event: CompanionTrustRevocationEvent): List<Exception> {
        val failures = ArrayList<Exception>()
        var fatal: Error? = null
        listeners.forEach { listener ->
            try {
                listener.onTrustRevoked(event)
            } catch (error: Exception) {
                failures += error
            } catch (error: Error) {
                val firstFatal = fatal
                if (firstFatal == null) fatal = error else if (firstFatal !== error) firstFatal.addSuppressed(error)
            }
        }
        fatal?.let { error ->
            failures.forEach(error::addSuppressed)
            throw error
        }
        return failures
    }

    private fun throwAggregated(errors: List<Throwable>, message: String) {
        if (errors.isEmpty()) return
        val fatal = errors.filterIsInstance<Error>().firstOrNull()
        val primary = fatal ?: IllegalStateException(message, errors.first())
        errors.forEach { error -> if (error !== primary) primary.addSuppressed(error) }
        throw primary
    }
}
