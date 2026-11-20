package pl.aimasterpro.app.core

import org.junit.Assert.*
import org.junit.Test

class DiagnosticsStorageHealthTest {
    private fun sources(vararg available: Boolean) = available.mapIndexed { index, ok ->
        DiagnosticsStorageHealth.Source(
            id = "store-$index",
            label = "Magazyn $index",
            available = ok,
            detail = if (ok) "Odczyt poprawny" else "Odczyt niepotwierdzony"
        )
    }

    @Test fun allStoresAvailableProducesHealthySnapshot() {
        val result = DiagnosticsStorageHealth.build(sources(true, true, true, true))
        assertEquals(DiagnosticsStorageHealth.OverallStatus.HEALTHY, result.overallStatus)
        assertEquals(4, result.availableCount)
        assertEquals(0, result.unavailableCount)
        assertTrue(result.summary.contains("4/4"))
        assertTrue(result.details.contains("ODCZYT OK"))
    }

    @Test fun partialStoreFailureProducesDegradedSnapshotWithoutCallingItEmpty() {
        val result = DiagnosticsStorageHealth.build(sources(true, false, true, false))
        assertEquals(DiagnosticsStorageHealth.OverallStatus.DEGRADED, result.overallStatus)
        assertEquals(2, result.availableCount)
        assertEquals(2, result.unavailableCount)
        assertTrue(result.summary.contains("Odzyskiwanie może być niepełne"))
        assertTrue(result.details.contains("NIEDOSTĘPNY"))
    }

    @Test fun noAvailableStoreProducesUnavailableSnapshot() {
        val result = DiagnosticsStorageHealth.build(sources(false, false, false))
        assertEquals(DiagnosticsStorageHealth.OverallStatus.UNAVAILABLE, result.overallStatus)
        assertEquals(0, result.availableCount)
        assertEquals(3, result.unavailableCount)
        assertTrue(result.summary.contains("0/3"))
    }

    @Test fun duplicateSourceIdsAreRejected() {
        val rows = listOf(
            DiagnosticsStorageHealth.Source("same", "Pierwszy", true, ""),
            DiagnosticsStorageHealth.Source("same", "Drugi", false, "")
        )
        assertThrows(IllegalArgumentException::class.java) {
            DiagnosticsStorageHealth.build(rows)
        }
    }

    @Test fun emptyOrOversizedSourceSetIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            DiagnosticsStorageHealth.build(emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            DiagnosticsStorageHealth.build((0..20).map {
                DiagnosticsStorageHealth.Source("store-$it", "Store $it", true, "")
            })
        }
    }
}
