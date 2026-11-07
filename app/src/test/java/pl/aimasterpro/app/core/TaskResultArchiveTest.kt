package pl.aimasterpro.app.core

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class TaskResultArchiveTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("task_result_archive_v1", Context.MODE_PRIVATE)

    @Before fun reset() {
        prefs.edit().clear().commit()
    }

    @Test fun successfulReportIsDurableAndAddressableByTaskId() {
        assertTrue(TaskResultArchive.put(
            context, "task-1", "developer", "Pełny raport agenta.", "provider-a", "model-a", 1_000L
        ))
        assertEquals(
            TaskResultArchive.Entry("task-1", "developer", "Pełny raport agenta.", "provider-a", "model-a", 1_000L),
            TaskResultArchive.get(context, "task-1")
        )
    }

    @Test fun writingSameTaskReplacesItsEntryWithoutDuplicatingIt() {
        assertTrue(TaskResultArchive.put(context, "task-1", "developer", "Old report", "provider-a", "model-a", 1_000L))
        assertTrue(TaskResultArchive.put(context, "task-2", "reviewer", "Other report", "provider-b", "model-b", 2_000L))
        assertTrue(TaskResultArchive.put(context, "task-1", "developer", "Updated report", "provider-c", "model-c", 3_000L))
        val entries = TaskResultArchive.latest(context)
        assertEquals(2, entries.size)
        assertEquals("Updated report", TaskResultArchive.get(context, "task-1")?.report)
        assertEquals("task-1", entries.first().taskId)
    }

    @Test fun archivedReportsRedactCommonProviderCredentials() {
        val report = "API key: sk-12345678901234567890 and Bearer abcdefghijklmnopqrstuvwxyz123456"
        assertTrue(TaskResultArchive.put(context, "secret-task", "developer", report, "provider-a", "model-a", 1_000L))
        val stored = prefs.getString("entries", null).orEmpty()
        assertFalse(stored.contains("sk-12345678901234567890"))
        assertFalse(stored.contains("abcdefghijklmnopqrstuvwxyz123456"))
        assertTrue(TaskResultArchive.get(context, "secret-task")?.report?.contains("[REDACTED]") == true)
    }

    @Test fun corruptArchiveIsNotReplacedByAnEmptyArchive() {
        val corrupt = """[{"taskId":"task-1","agentId":"developer","report":"saved","providerId":"p","model":"m","createdAt":1000},null]"""
        assertTrue(prefs.edit().putString("entries", corrupt).commit())
        assertNull(TaskResultArchive.get(context, "missing"))
        assertFalse(TaskResultArchive.put(context, "task-2", "developer", "new report", "p", "m", 2_000L))
        assertEquals(corrupt, prefs.getString("entries", null))
    }

    @Test fun fractionalArchiveTimestampIsRejectedWithoutOverwritingExistingData() {
        val corrupt = """[{"taskId":"fractional-time","agentId":"developer","report":"saved","providerId":"p","model":"m","createdAt":1000.5}]"""
        assertTrue(prefs.edit().putString("entries", corrupt).commit())
        assertNull(TaskResultArchive.get(context, "fractional-time"))
        assertFalse(TaskResultArchive.put(context, "new-task", "developer", "new report", "p", "m", 2_000L))
        assertEquals(corrupt, prefs.getString("entries", null))
    }

    @Test fun preferenceAccessFailureDoesNotPretendArchiveIsEmptyOrAcceptNewReport() {
        val failingContext = object : android.content.ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
                throw IllegalStateException("controlled result archive preferences failure")
            }
        }
        assertNull(TaskResultArchive.get(failingContext, "task-1"))
        assertTrue(TaskResultArchive.latest(failingContext).isEmpty())
        assertFalse(TaskResultArchive.put(
            failingContext, "task-new", "developer", "New report", "provider-a", "model-a", 2_000L
        ))
    }

    @Test fun blankAndOversizedReportsAreRejected() {
        assertFalse(TaskResultArchive.put(context, "blank", "developer", " ", "p", "m", 1_000L))
        assertFalse(TaskResultArchive.put(context, "large", "developer", "x".repeat(32_001), "p", "m", 1_000L))
        assertNull(TaskResultArchive.get(context, "blank"))
        assertNull(TaskResultArchive.get(context, "large"))
    }
}
