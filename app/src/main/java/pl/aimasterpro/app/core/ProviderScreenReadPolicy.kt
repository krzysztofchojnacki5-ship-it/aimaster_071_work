package pl.aimasterpro.app.core

/** A provider-screen read failure must not make the entire provider screen unavailable. */
internal data class ProviderScreenUsageRead(
    val totalTokens: Long?,
    val available: Boolean
)

internal object ProviderScreenReadPolicy {
    fun readUsage(read: () -> Long): ProviderScreenUsageRead = try {
        ProviderScreenUsageRead(read().coerceAtLeast(0L), available = true)
    } catch (_: Exception) {
        ProviderScreenUsageRead(totalTokens = null, available = false)
    }
}
