package pl.aimasterpro.app.core

import java.net.URI
import java.util.Locale

/**
 * Prevents a cached model catalog from being shown under a provider ID that was
 * reused for a different endpoint during database import.
 */
internal object ProviderCatalogCachePolicy {
    /**
     * Canonicalizes only URL components that are case-insensitive. API paths,
     * query parameters and fragments remain case-sensitive/significant.
     * Invalid or non-absolute URLs fail closed and cannot validate a cache entry.
     */
    fun normalizeBaseUrl(value: String): String {
        val input = value.trim()
        if (input.isBlank()) return ""
        val uri = runCatching { URI(input) }.getOrNull() ?: return ""
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return ""
        val host = uri.host?.lowercase(Locale.ROOT) ?: return ""
        if (!uri.isAbsolute || uri.rawUserInfo != null) return ""

        val path = uri.rawPath.orEmpty().trimEnd('/')
        return buildString {
            append(scheme)
            append("://")
            append(host)
            if (uri.port >= 0) {
                append(':')
                append(uri.port)
            }
            append(path)
            uri.rawQuery?.let { append('?'); append(it) }
            uri.rawFragment?.let { append('#'); append(it) }
        }
    }

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
