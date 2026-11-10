package pl.aimasterpro.app.core

import java.util.Locale

/**
 * Prevents a cached model catalog from being shown under a provider ID that was
 * reused for a different endpoint during database import.
 */
internal object ProviderCatalogCachePolicy {
    fun normalizeBaseUrl(value: String): String =
        value.trim().trimEnd('/').lowercase(Locale.ROOT)

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
