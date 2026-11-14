package pl.aimasterpro.app.core

import java.util.Locale

/**
 * Prevents a cached model catalog from being shown under a provider ID that was
 * reused for a different endpoint during database import.
 */
internal object ProviderCatalogCachePolicy {
    fun normalizeBaseUrl(value: String): String =
        value.trim().trimEnd('/').lowercase(Locale.ROOT)

    fun currentProviderBaseUrls(records: List<ProviderScreenProfileRecord>): Map<String, String> {
        return records.groupBy { it.id }
            .filterValues { it.size == 1 }
            .mapNotNull { (id, rows) ->
                val record = rows.single()
                if (id.isBlank() || record.parentProviderId.isNotBlank() || record.baseUrl.isBlank()) null
                else id to record.baseUrl
            }
            .toMap()
    }

    fun isEntryCurrent(
        providerId: String,
        cachedBaseUrl: String,
        currentProviderBaseUrls: Map<String, String>
    ): Boolean {
        if (providerId.isBlank()) return false
        val cached = normalizeBaseUrl(cachedBaseUrl)
        val current = normalizeBaseUrl(currentProviderBaseUrls[providerId].orEmpty())
        return cached.isNotBlank() && current.isNotBlank() && cached == current
    }
}
