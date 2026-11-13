package pl.aimasterpro.app.core

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class TaskResultCacheFingerprintTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun resetCache() {
        context.getSharedPreferences("task_result_cache_v1", Context.MODE_PRIVATE).edit().clear().commit()
    }
    @Test fun exactInputsProduceStableFingerprint() {
        val a = TaskResultCache.fingerprint("dev", "goal", "prompt", "project", "", "", "provider-a", "https://api.example/v1", "model")
        val b = TaskResultCache.fingerprint("dev", "goal", "prompt", "project", "", "", "provider-a", "https://api.example/v1", "model")
        assertEquals(a, b)
    }
    @Test fun embeddedSeparatorsCannotMakeDifferentInputsCollide() {
        val a = TaskResultCache.fingerprint("dev", "goal\u001fprompt", "x", "project", "", "", "provider-a", "https://api.example/v1", "model")
        val b = TaskResultCache.fingerprint("dev", "goal", "prompt\u001fx", "project", "", "", "provider-a", "https://api.example/v1", "model")
        assertNotEquals(a, b)
    }

    @Test fun resultCachePersistsSuccessfulReportDurably() {
        assertTrue(TaskResultCache.put(context, "fingerprint-1", "Pełny raport agenta.", 1_000L))
        val cached = TaskResultCache.get(context, "fingerprint-1", 1_500L)
        assertEquals("Pełny raport agenta.", cached?.report)
        assertEquals(1_000L, cached?.createdAt)
    }

    @Test fun cachedReportsRedactCommonProviderCredentials() {
        val report = "API key: sk-12345678901234567890 and Bearer abcdefghijklmnopqrstuvwxyz123456"
        assertTrue(TaskResultCache.put(context, "sensitive-report", report, 1_000L))
        val stored = context.getSharedPreferences("task_result_cache_v1", Context.MODE_PRIVATE)
            .getString("entries", null).orEmpty()
        assertFalse(stored.contains("sk-12345678901234567890"))
        assertFalse(stored.contains("abcdefghijklmnopqrstuvwxyz123456"))
        assertTrue(TaskResultCache.get(context, "sensitive-report", 1_500L)?.report?.contains("[REDACTED]") == true)
    }

    @Test fun malformedResultCacheIsNotReplacedByAnEmptyCache() {
        val prefs = context.getSharedPreferences("task_result_cache_v1", Context.MODE_PRIVATE)
        val corrupt = """[{"fingerprint":"known","report":"saved report","createdAt":1000},null]"""
        assertTrue(prefs.edit().putString("entries", corrupt).commit())
        assertNull(TaskResultCache.get(context, "new-fingerprint", 1_500L))
        assertFalse(TaskResultCache.put(context, "new-fingerprint", "new report", 1_500L))
        assertEquals(corrupt, prefs.getString("entries", null))
    }

    @Test fun fractionalCacheTimestampIsRejectedWithoutOverwritingJournal() {
        val prefs = context.getSharedPreferences("task_result_cache_v1", Context.MODE_PRIVATE)
        val corrupt = """[{"fingerprint":"fractional","report":"saved report","createdAt":1000.5}]"""
        assertTrue(prefs.edit().putString("entries", corrupt).commit())

        assertNull(TaskResultCache.get(context, "fractional", 1_500L))
        assertFalse(TaskResultCache.put(context, "new-fingerprint", "new report", 1_500L))
        assertEquals(corrupt, prefs.getString("entries", null))
    }

    @Test fun duplicateCacheFingerprintsAreRejectedAsAmbiguous() {
        val prefs = context.getSharedPreferences("task_result_cache_v1", Context.MODE_PRIVATE)
        val corrupt = """[{"fingerprint":"duplicate","report":"first","createdAt":1000},{"fingerprint":"duplicate","report":"second","createdAt":1100}]"""
        assertTrue(prefs.edit().putString("entries", corrupt).commit())

        assertNull(TaskResultCache.get(context, "duplicate", 1_500L))
        assertFalse(TaskResultCache.put(context, "new-fingerprint", "new report", 1_500L))
        assertEquals(corrupt, prefs.getString("entries", null))
    }

    @Test fun cacheRejectsNegativeCreationTimestamp() {
        assertFalse(TaskResultCache.put(context, "negative-time", "report", -1L))
    }

    @Test fun expiredCacheRowsAreIgnoredButDoNotBlockNewDurableResults() {
        assertTrue(TaskResultCache.put(context, "old", "Old report", 1_000L))
        assertNull(TaskResultCache.get(context, "old", 1_000L + 72L * 60L * 60L * 1000L + 1L))
        assertTrue(TaskResultCache.put(context, "new", "New report", 1_000L + 72L * 60L * 60L * 1000L + 1L))
        assertEquals("New report", TaskResultCache.get(context, "new", 1_000L + 72L * 60L * 60L * 1000L + 1L)?.report)
    }

    @Test fun changedContextAttachmentOrModelInvalidatesCacheKey() {
        val base = TaskResultCache.fingerprint("dev", "goal", "prompt", "project", "", "", "provider-a", "https://api.example/v1", "model")
        assertNotEquals(base, TaskResultCache.fingerprint("dev", "goal", "prompt", "changed", "", "", "provider-a", "https://api.example/v1", "model"))
        assertNotEquals(base, TaskResultCache.fingerprint("dev", "goal", "prompt", "project", "image/png", "abc", "provider-a", "https://api.example/v1", "model"))
        assertNotEquals(base, TaskResultCache.fingerprint("dev", "goal", "prompt", "project", "", "", "provider-a", "https://api.example/v1", "other-model"))
        assertNotEquals(base, TaskResultCache.fingerprint("dev", "goal", "prompt", "project", "", "", "provider-b", "https://api.example/v1", "model"))
        assertNotEquals(base, TaskResultCache.fingerprint("dev", "goal", "prompt", "project", "", "", "provider-a", "https://other.example/v1", "model"))
    }
    @Test fun oversizedRawCacheIsRejectedAndPreserved() {
        val prefs = context.getSharedPreferences("task_result_cache_v1", Context.MODE_PRIVATE)
        val oversized = " ".repeat(4_000_001)
        assertTrue(prefs.edit().putString("entries", oversized).commit())
        assertNull(TaskResultCache.get(context, "new", 1_500L))
        assertFalse(TaskResultCache.put(context, "new", "new report", 1_500L))
        assertEquals(oversized, prefs.getString("entries", null))
    }

    @Test fun cacheRejectsOversizedFingerprintAndReportWithoutMutation() {
        assertFalse(TaskResultCache.put(context, "f".repeat(129), "report", 1_000L))
        assertFalse(TaskResultCache.put(context, "valid-fingerprint", "r".repeat(50_001), 1_000L))
        assertNull(context.getSharedPreferences("task_result_cache_v1", Context.MODE_PRIVATE)
            .getString("entries", null))
    }

}
