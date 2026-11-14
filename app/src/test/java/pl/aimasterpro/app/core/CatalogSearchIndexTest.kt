package pl.aimasterpro.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class CatalogSearchIndexTest {
    private data class Model(val id: String)

    private fun index(): CatalogSearchIndex<Model> = CatalogSearchIndex(
        listOf(
            CatalogSearchRecord(Model("paid-b"), "Provider B Display B paid-b", false, false, false),
            CatalogSearchRecord(Model("unknown"), "Provider C Unknown unknown", false, null, true),
            CatalogSearchRecord(Model("local"), "Local Ollama local", true, null, false),
            CatalogSearchRecord(Model("free"), "Provider A Display Free free", false, true, false)
        )
    )

    @Test
    fun searchMatchesProviderNameDisplayNameAndModelIdCaseInsensitively() {
        val index = index()

        assertEquals(listOf("paid-b"), index.search("  PROVIDER B ", 0).records.map { it.value.id })
        assertEquals(listOf("unknown"), index.search("unknown", 0).records.map { it.value.id })
        assertEquals(listOf("free"), index.search("DISPLAY FREE", 0).records.map { it.value.id })
    }

    @Test
    fun allFilterPreservesPriorityAndReportsCountsFromFilteredRows() {
        val result = index().search("", 0)

        assertEquals(listOf("local", "free", "unknown", "paid-b"), result.records.map { it.value.id })
        assertEquals(2, result.freeCount)
        assertEquals(1, result.paidCount)
        assertEquals(1, result.unknownCount)
    }

    @Test
    fun categoryFiltersReturnOnlyTheirMatchingRows() {
        val catalog = index()

        assertEquals(listOf("local", "free"), catalog.search("", 1).records.map { it.value.id })
        assertEquals(listOf("paid-b"), catalog.search("", 2).records.map { it.value.id })
        assertEquals(listOf("unknown"), catalog.search("", 3).records.map { it.value.id })
    }

    @Test
    fun equalPriorityRowsKeepOriginalCatalogOrder() {
        val result = index().search("", 1)

        assertEquals(listOf("local", "free"), result.records.map { it.value.id })
    }

    @Test
    fun equivalentNormalizedQueryAndFilterReuseCachedResult() {
        val catalog = index()

        val first = catalog.search("  PROVIDER B ", 0)
        val repeated = catalog.search("provider b", 0)

        assertSame(first, repeated)
    }

    @Test
    fun changingQueryOrFilterInvalidatesCachedResult() {
        val catalog = index()

        val initial = catalog.search("", 0)
        val changedQuery = catalog.search("provider", 0)
        val changedFilter = catalog.search("provider", 1)

        assertNotSame(initial, changedQuery)
        assertNotSame(changedQuery, changedFilter)
        assertEquals(listOf("local", "free"), changedFilter.records.map { it.value.id })
    }

    @Test
    fun cacheRetainsOnlyMostRecentSearchResult() {
        val catalog = index()

        val first = catalog.search("provider b", 0)
        catalog.search("unknown", 0)
        val repeatedAfterEviction = catalog.search("provider b", 0)

        assertNotSame(first, repeatedAfterEviction)
        assertEquals(first.records.map { it.value.id }, repeatedAfterEviction.records.map { it.value.id })
    }
}
