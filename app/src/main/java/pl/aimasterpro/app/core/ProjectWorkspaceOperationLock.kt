package pl.aimasterpro.app.core

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/** One process-wide gate for every host operation touching the private project workspace. */
object ProjectWorkspaceOperationLock {
    private val lock = ReentrantLock(true)

    fun <T> withLock(operation: () -> T): T {
        lock.lock()
        try {
            return operation()
        } finally {
            lock.unlock()
        }
    }

    /**
     * Fair, non-blocking UI probe. The timed zero-wait overload honors the fair queue;
     * the untimed tryLock() overload is permitted to barge ahead of queued waiters.
     * null means the gate is owned or an earlier waiter has priority.
     */
    fun <T> tryWithLock(operation: () -> T): T? {
        val acquired = try {
            lock.tryLock(0L, TimeUnit.NANOSECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!acquired) return null
        try {
            return operation()
        } finally {
            lock.unlock()
        }
    }
}
