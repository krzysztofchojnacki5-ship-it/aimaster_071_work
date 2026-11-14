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
 * Filtering then performs only substring/category checks and preserves stable priority order.
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

    fun search(query: String, filter: Int): Result<T> {
        val normalizedQuery = query.trim().lowercase(Locale.ROOT)
        val matches = orderedRecords.asSequence().filter { indexed ->
            val record = indexed.record
            val matchesQuery = normalizedQuery.isBlank() || indexed.normalizedText.contains(normalizedQuery)
            val matchesFilter = when (filter) {
                1 -> record.isLocal || record.freeByPricing == true
                2 -> record.freeByPricing == false && !record.unknownPrice
                3 -> record.unknownPrice
                else -> true
            }
            matchesQuery && matchesFilter
        }.map { it.record }.toList()

        return Result(
            records = matches,
            freeCount = matches.count { it.isLocal || it.freeByPricing == true },
            paidCount = matches.count { it.freeByPricing == false && !it.unknownPrice },
            unknownCount = matches.count { it.unknownPrice }
        )
    }
}
