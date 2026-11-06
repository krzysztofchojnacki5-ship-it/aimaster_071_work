package pl.aimasterpro.app.core

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class TaskCheckpointStoreTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @Before fun reset() { context.getSharedPreferences("task_checkpoints_v1", Context.MODE_PRIVATE).edit().clear().commit() }

    @Test fun startupMarksRunningTasksInterruptedWithoutSavingPrompts() {
        TaskCheckpointStore.record(context, TaskCheckpointStore.Event("task-1", "tester", "RUNNING", "", 100L, "", "", 0, "previous-process"))
        TaskCheckpointStore.record(context, TaskCheckpointStore.Event("task-2", "analyst", "COMPLETED", "done", 200L, "p", "m", 1))
        assertEquals(1, TaskCheckpointStore.recoverStaleRunning(context, 300L))
        val rows = TaskCheckpointStore.latest(context).associateBy { it.taskId }
        assertEquals("INTERRUPTED", rows.getValue("task-1").status)
        assertEquals("COMPLETED", rows.getValue("task-2").status)
        assertFalse(TaskCheckpointStore.load(context).toString().contains("prompt"))
    }

    @Test fun doesNotMarkCurrentProcessTaskInterruptedDuringActivityRecreation() {
        TaskCheckpointStore.record(context, TaskCheckpointStore.Event("task-live", "tester", "RUNNING", "", 100L, "", "", 0))
        assertEquals(0, TaskCheckpointStore.recoverStaleRunning(context, 200L))
        assertEquals("RUNNING", TaskCheckpointStore.latest(context).single().status)
    }

    @Test fun latestUsesJournalOrderWhenTimestampsCollide() {
        TaskCheckpointStore.record(context, TaskCheckpointStore.Event("task-1", "tester", "QUEUED", "", 200L, "", "", 0))
        TaskCheckpointStore.record(context, TaskCheckpointStore.Event("task-1", "tester", "RUNNING", "", 200L, "", "", 0))
        assertEquals(1, TaskCheckpointStore.latest(context).size)
        assertEquals("RUNNING", TaskCheckpointStore.latest(context).single().status)
    }

    @Test fun recordReportsSuccessfulDurableWrite() {
        val saved = TaskCheckpointStore.record(
            context,
            TaskCheckpointStore.Event("task-durable", "tester", "RUNNING", "", 100L, "", "", 0)
        )
        assertTrue(saved)
        assertEquals("RUNNING", TaskCheckpointStore.latest(context).single().status)
    }
    @Test fun lateWorkerCancellationDoesNotOverwriteCoordinatorTimeout() {
        assertTrue(TaskCheckpointStore.record(
            context,
            TaskCheckpointStore.Event("task-timeout", "tester", "TIMED_OUT", "deadline", 300L, "", "", 1)
        ))
        assertTrue(TaskCheckpointStore.record(
            context,
            TaskCheckpointStore.Event("task-timeout", "tester", "CANCELLED", "worker interrupted", 400L, "", "", 1)
        ))
        assertEquals("TIMED_OUT", TaskCheckpointStore.latest(context).single().status)
    }

    @Test fun corruptJournalIsNotSilentlyReplacedByAnEmptyHistory() {
        val prefs = context.getSharedPreferences("task_checkpoints_v1", Context.MODE_PRIVATE)
        val corrupt = """{"events":[broken"""
        assertTrue(prefs.edit().putString("events", corrupt).commit())

        assertFalse(
            TaskCheckpointStore.record(
                context,
                TaskCheckpointStore.Event("task-after-corruption", "tester", "RUNNING", "", 500L, "", "", 0)
            )
        )
        assertEquals(corrupt, prefs.getString("events", null))
        assertTrue(TaskCheckpointStore.load(context).isEmpty())
    }

    @Test fun malformedJournalRowAlsoBlocksAppendInsteadOfDroppingUnknownData() {
        val prefs = context.getSharedPreferences("task_checkpoints_v1", Context.MODE_PRIVATE)
        val malformed = """[{"task":"valid-row"},{"agent":"missing-task-id"}]"""
        assertTrue(prefs.edit().putString("events", malformed).commit())

        assertFalse(
            TaskCheckpointStore.record(
                context,
                TaskCheckpointStore.Event("task-new", "tester", "RUNNING", "", 600L, "", "", 0)
            )
        )
        assertEquals(malformed, prefs.getString("events", null))
    }

}
