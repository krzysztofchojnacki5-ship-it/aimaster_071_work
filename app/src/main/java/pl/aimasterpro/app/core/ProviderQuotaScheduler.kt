package pl.aimasterpro.app.core

import java.net.URL
import java.util.Locale

/**
 * Process-local, conservative scheduler for shared provider/model profiles.
 * It does not pretend to know provider quotas: it serializes free-tier calls,
 * spaces request starts and applies a cooldown only after an observed HTTP 429.
 */
object ProviderQuotaScheduler {
    private data class State(
        var active: Int = 0,
        var nextAllowedAt: Long = 0L,
        var cooldownUntil: Long = 0L,
        var consecutiveRateLimits: Int = 0
    )

    data class AcquireResult(val lease: Lease?, val reason: String? = null, val waitMs: Long = 0L)

    class Lease internal constructor(private val key: String) : AutoCloseable {
        @Volatile private var closed = false
        override fun close() {
            if (closed) return
            closed = true
            release(key)
        }
    }

    private val lock = Object()
    private val states = mutableMapOf<String, State>()

    fun key(providerId: String, baseUrl: String, model: String): String {
        val host = runCatching { URL(baseUrl).host.lowercase(Locale.ROOT) }
            .getOrDefault(baseUrl.lowercase(Locale.ROOT))
        return "$host|${providerId.lowercase(Locale.ROOT)}|${model.trim().lowercase(Locale.ROOT)}"
    }

    fun acquire(key: String, isFreeTier: Boolean, timeoutMs: Long = 45_000L): AcquireResult {
        synchronized(lock) {
            val state = states.getOrPut(key) { State() }
            val maxConcurrent = if (isFreeTier) 1 else 2
            val minStartIntervalMs = if (isFreeTier) 1_200L else 250L
            val deadline = System.currentTimeMillis() + timeoutMs.coerceAtLeast(0L)

            while (true) {
                val now = System.currentTimeMillis()
                if (state.cooldownUntil > now) {
                    return AcquireResult(null, "MODEL_COOLDOWN", state.cooldownUntil - now)
                }
                val pacingWait = (state.nextAllowedAt - now).coerceAtLeast(0L)
                if (state.active < maxConcurrent && pacingWait == 0L) {
                    state.active++
                    state.nextAllowedAt = now + minStartIntervalMs
                    return AcquireResult(Lease(key))
                }
                val remaining = deadline - now
                if (remaining <= 0L) {
                    return AcquireResult(null, "MODEL_BUSY_TIMEOUT", 0L)
                }
                val waitFor = if (state.active >= maxConcurrent) 1_000L else pacingWait.coerceAtLeast(1L)
                try {
                    lock.wait(minOf(remaining, waitFor))
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return AcquireResult(null, "INTERRUPTED", 0L)
                }
            }
        }
    }

    fun markRateLimited(key: String): Long = synchronized(lock) {
        val state = states.getOrPut(key) { State() }
        state.consecutiveRateLimits = (state.consecutiveRateLimits + 1).coerceAtMost(8)
        val cooldown = (60_000L * (1L shl (state.consecutiveRateLimits - 1))).coerceAtMost(15 * 60_000L)
        state.cooldownUntil = System.currentTimeMillis() + cooldown
        lock.notifyAll()
        cooldown
    }

    fun markSuccess(key: String) = synchronized(lock) {
        states[key]?.consecutiveRateLimits = 0
    }

    fun cooldownRemainingMs(key: String): Long = synchronized(lock) {
        ((states[key]?.cooldownUntil ?: 0L) - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    private fun release(key: String) = synchronized(lock) {
        states[key]?.let { it.active = (it.active - 1).coerceAtLeast(0) }
        lock.notifyAll()
    }

    internal fun resetForTests() = synchronized(lock) { states.clear(); lock.notifyAll() }
}
