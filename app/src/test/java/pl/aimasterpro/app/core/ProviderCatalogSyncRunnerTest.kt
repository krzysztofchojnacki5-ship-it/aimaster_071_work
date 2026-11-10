package pl.aimasterpro.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderCatalogSyncRunnerTest {
    @Test
    fun failureForOneProviderDoesNotPreventNextProviderFromSaving() {
        val saved = mutableListOf<String>()
        val report = ProviderCatalogSyncRunner.synchronize(
            providers = listOf("bad", "good"),
            discover = { provider ->
                if (provider == "bad") error("HTTP 401")
                listOf("model-a")
            },
            save = { provider, _ -> saved += provider }
        )

        assertEquals(listOf("good"), saved)
        assertEquals(listOf("good"), report.successfulProviders)
        assertEquals(1, report.failures.size)
        assertEquals("bad", report.failures.single().provider)
        assertEquals("HTTP 401", report.failures.single().message)
        assertFalse(report.stopped)
    }

    @Test
    fun emptyCatalogIsFailureAndDoesNotOverwriteSavedData() {
        val saved = mutableListOf<String>()
        val report = ProviderCatalogSyncRunner.synchronize(
            providers = listOf("empty", "valid"),
            discover = { provider -> if (provider == "empty") emptyList() else listOf("m1") },
            save = { provider, _ -> saved += provider }
        )

        assertEquals(listOf("valid"), saved)
        assertEquals("empty", report.failures.single().provider)
    }

    @Test
    fun persistenceFailureIsIsolatedFromNextProvider() {
        val saved = mutableListOf<String>()
        val report = ProviderCatalogSyncRunner.synchronize(
            providers = listOf("write-fails", "next"),
            discover = { listOf("model") },
            save = { provider, _ ->
                if (provider == "write-fails") error("disk full")
                saved += provider
            }
        )

        assertEquals(listOf("next"), saved)
        assertEquals(1, report.successCount)
        assertEquals("disk full", report.failures.single().message)
    }

    @Test
    fun cancellationStopsBeforeStartingNextProvider() {
        var checks = 0
        val discovered = mutableListOf<String>()
        val report = ProviderCatalogSyncRunner.synchronize(
            providers = listOf("first", "second", "third"),
            isCancelled = { ++checks >= 2 },
            discover = { provider -> discovered += provider; listOf("model") },
            save = { _, _ -> }
        )

        assertEquals(listOf("first"), discovered)
        assertEquals(listOf("first"), report.successfulProviders)
        assertTrue(report.stopped)
    }
}
