package pl.aimasterpro.app.core

import java.util.Locale

/** Immutable search/filter index for a provider model catalog screen. */
internal data class CatalogSearchRecord<T>(
    val value: T,
    val searchText: String,
    val isLocal: Boolean,
    val freeByPricing: Boolean?,
    val unknownPrice: Boolean
)

/**
 * Pre-normalizes searchable text and pre-sorts catalog rows once per screen snapshot.
 * Filtering performs one pass over the index and preserves stable priority order.
 */
internal class CatalogSearchIndex<T>(records: List<CatalogSearchRecord<T>>) {
    data class Result<T>(
        val records: List<CatalogSearchRecord<T>>,
        val freeCount: Int,
        val paidCount: Int,
        val unknownCount: Int
    )

    private data class Indexed<T>(
        val record: CatalogSearchRecord<T>,
        val normalizedText: String,
        val priority: Int,
        val originalIndex: Int
    )

    private val orderedRecords = records.mapIndexed { index, record ->
        val priority = when {
            record.isLocal || record.freeByPricing == true -> 0
            record.unknownPrice -> 1
            else -> 2
        }
        Indexed(
            record = record,
            normalizedText = record.searchText.lowercase(Locale.ROOT),
            priority = priority,
            originalIndex = index
        )
    }.sortedWith(compareBy<Indexed<T>> { it.priority }.thenBy { it.originalIndex })

    // The UI can request the same normalized query twice (e.g. initial render and spinner callback).
    // Retain only the most recent result to avoid repeated scans and allocations without unbounded growth.
    private var cachedQuery: String? = null
    private var cachedFilter: Int? = null
    private var cachedResult: Result<T>? = null

    @Synchronized
    fun search(query: String, filter: Int): Result<T> {
        val normalizedQuery = query.trim().lowercase(Locale.ROOT)
        if (cachedQuery == normalizedQuery && cachedFilter == filter) {
            cachedResult?.let { return it }
        }

        val matches = ArrayList<CatalogSearchRecord<T>>()
        var freeCount = 0
        var paidCount = 0
        var unknownCount = 0

        for (indexed in orderedRecords) {
            val record = indexed.record
            if (normalizedQuery.isNotBlank() && !indexed.normalizedText.contains(normalizedQuery)) continue

            val matchesFilter = when (filter) {
                1 -> record.isLocal || record.freeByPricing == true
                2 -> record.freeByPricing == false && !record.unknownPrice
                3 -> record.unknownPrice
                else -> true
            }
            if (!matchesFilter) continue

            matches.add(record)
            if (record.isLocal || record.freeByPricing == true) freeCount++
            if (record.freeByPricing == false && !record.unknownPrice) paidCount++
            if (record.unknownPrice) unknownCount++
        }

        return Result(matches, freeCount, paidCount, unknownCount).also {
            cachedQuery = normalizedQuery
            cachedFilter = filter
            cachedResult = it
        }
    }
}
