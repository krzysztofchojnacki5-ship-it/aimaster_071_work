package pl.aimasterpro.app.core

/** A provider-screen read failure must not make the entire provider screen unavailable. */
internal data class ProviderScreenUsageRead(
    val totalTokens: Long?,
    val available: Boolean
)

/** One already-decrypted provider row from SecureStore.allConfigs(). */
internal data class ProviderScreenProfileRecord(
    val id: String,
    val name: String,
    val kind: String,
    val baseUrl: String,
    val model: String,
    val apiKey: String,
    val enabled: Boolean,
    val parentProviderId: String,
    val allowInsecureLanOllama: Boolean,
    val secretUnavailable: Boolean
)

internal object ProviderScreenReadPolicy {
    /**
     * Resolves model profiles against the same snapshot as their base providers.
     * Missing/ambiguous parents and unreadable secrets fail closed, matching loadProvider().
     */
    fun resolveProfiles(records: List<ProviderScreenProfileRecord>): List<ProviderScreenProfileRecord> {
        val grouped = records.groupBy { it.id }
        val uniqueById = grouped.filterValues { it.size == 1 }.mapValues { it.value.single() }
        return records.mapNotNull { stored ->
            if (grouped[stored.id]?.size != 1 || stored.secretUnavailable) return@mapNotNull null
            if (stored.parentProviderId.isBlank()) return@mapNotNull stored
            val parent = uniqueById[stored.parentProviderId] ?: return@mapNotNull null
            if (parent.secretUnavailable) return@mapNotNull null
            stored.copy(
                kind = parent.kind,
                baseUrl = parent.baseUrl,
                apiKey = parent.apiKey,
                enabled = parent.enabled,
                allowInsecureLanOllama = parent.allowInsecureLanOllama
            )
        }
    }

    fun readUsage(read: () -> Long): ProviderScreenUsageRead = try {
        ProviderScreenUsageRead(read().coerceAtLeast(0L), available = true)
    } catch (_: Exception) {
        ProviderScreenUsageRead(totalTokens = null, available = false)
    }
}
