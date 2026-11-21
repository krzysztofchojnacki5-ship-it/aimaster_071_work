package pl.aimasterpro.app.core

import java.util.Locale

/**
 * Tracks the newest provider-catalog search request.
 *
 * UI callbacks can be delayed while the user types or changes filters. A stale callback
 * must never render results for an older query over a newer selection.
 */
internal class CatalogSearchRequestGate {
    data class Request(
        val generation: Long,
        val query: String,
        val filter: Int
    )

    private var generation = 0L

    @Synchronized
    fun begin(query: String, filter: Int): Request {
        generation += 1L
        return Request(
            generation = generation,
            query = query.trim().lowercase(Locale.ROOT),
            filter = filter
        )
    }

    @Synchronized
    fun isCurrent(request: Request): Boolean = request.generation == generation

    @Synchronized
    fun invalidate() {
        generation += 1L
    }
}
