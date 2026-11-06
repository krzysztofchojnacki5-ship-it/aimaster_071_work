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

    @Test fun staleRequestingTaskReconcilesUnknownUsageBeforeMarkingInterrupted() {
        val event = TaskCheckpointStore.Event(
            taskId = "task-request-in-flight", agentId = "RESEARCHER", status = "REQUESTING",
            detail = "request started", timestamp = 100L, providerId = "openai", model = "gpt-4o-mini",
            attempts = 1, processId = "previous-process", estimatedInputTokens = 450L,
            estimatedOutputTokens = 1_200L, idempotencyKey = "team:task-request-in-flight:openai",
            baseUrl = "https://api.openai.com"
        )
        assertTrue(TaskCheckpointStore.record(context, event))
        var recoveredEvent: TaskCheckpointStore.Event? = null
        assertEquals(1, TaskCheckpointStore.recoverStaleRunning(context, 200L) {
            recoveredEvent = it
            true
        })
        assertEquals(450L, recoveredEvent?.estimatedInputTokens)
        assertEquals(1_200L, recoveredEvent?.estimatedOutputTokens)
        assertEquals("team:task-request-in-flight:openai", recoveredEvent?.idempotencyKey)
        assertEquals("INTERRUPTED", TaskCheckpointStore.latest(context).single().status)
        assertTrue(TaskCheckpointStore.latest(context).single().detail.contains("UNAVAILABLE"))
    }

    @Test fun singleChatRequestCheckpointRetainsProviderAndIdempotencyForRestartRecovery() {
        val event = TaskCheckpointStore.Event(
            taskId = "chat-chat-123-request-456", agentId = "MASTER", status = "REQUESTING",
            detail = "single chat POST", timestamp = 100L, providerId = "openrouter",
            model = "model-x", attempts = 2, processId = "previous-process",
            estimatedInputTokens = 320L, estimatedOutputTokens = 4_096L,
            idempotencyKey = "chat:chat-chat-123-request-456:openrouter",
            baseUrl = "https://openrouter.ai"
        )
        assertTrue(TaskCheckpointStore.record(context, event))
        var recovered: TaskCheckpointStore.Event? = null
        assertEquals(1, TaskCheckpointStore.recoverStaleRunning(context, 200L) {
            recovered = it
            true
        })
        assertEquals("openrouter", recovered?.providerId)
        assertEquals("model-x", recovered?.model)
        assertEquals("chat:chat-chat-123-request-456:openrouter", recovered?.idempotencyKey)
        assertEquals("https://openrouter.ai", recovered?.baseUrl)
        assertEquals(4_096L, recovered?.estimatedOutputTokens)
    }

    @Test fun failedUnknownUsageRecoveryRemainsPendingAndCanBeRetried() {
        assertTrue(TaskCheckpointStore.record(context, TaskCheckpointStore.Event(
            taskId = "task-pending-usage", agentId = "RESEARCHER", status = "REQUESTING",
            detail = "", timestamp = 100L, providerId = "openai", model = "gpt-4o-mini",
            attempts = 1, processId = "previous-process", estimatedInputTokens = 200L,
            estimatedOutputTokens = 500L, idempotencyKey = "team:task-pending-usage:openai"
        )))
        assertEquals(1, TaskCheckpointStore.recoverStaleRunning(context, 200L) { false })
        assertEquals("INTERRUPTED_USAGE_PENDING", TaskCheckpointStore.latest(context).single().status)
        assertEquals(1, TaskCheckpointStore.recoverStaleRunning(context, 300L) { true })
        assertEquals("INTERRUPTED", TaskCheckpointStore.latest(context).single().status)
    }

    @Test fun resultPersistencePendingTaskIsRecoveredWithoutRebilling() {
        assertTrue(TaskCheckpointStore.record(context, TaskCheckpointStore.Event(
            taskId = "task-result-pending", agentId = "RESEARCHER", status = "RESULT_PERSIST_PENDING",
            detail = "report cache write failed", timestamp = 100L, providerId = "openai",
            model = "gpt-4o-mini", attempts = 1, processId = "previous-process",
            estimatedInputTokens = 200L, estimatedOutputTokens = 500L,
            idempotencyKey = "team:task-result-pending:openai"
        )))
        var usageRecoveryCalled = false
        assertEquals(1, TaskCheckpointStore.recoverStaleRunning(context, 200L) {
            usageRecoveryCalled = true
            false
        })
        assertFalse(usageRecoveryCalled)
        assertEquals("INTERRUPTED", TaskCheckpointStore.latest(context).single().status)
        assertTrue(TaskCheckpointStore.latest(context).single().detail.contains("trwały zapis wyniku"))
    }

    @Test fun usageRecordedTaskIsMarkedInterruptedWithoutDuplicateRecoveryCallback() {
        assertTrue(TaskCheckpointStore.record(context, TaskCheckpointStore.Event(
            taskId = "task-usage-done", agentId = "RESEARCHER", status = "USAGE_RECORDED",
            detail = "usage saved", timestamp = 100L, providerId = "openai", model = "gpt-4o-mini",
            attempts = 1, processId = "previous-process", estimatedInputTokens = 200L,
            estimatedOutputTokens = 500L, idempotencyKey = "team:task-usage-done:openai"
        )))
        var callbackCalled = false
        assertEquals(1, TaskCheckpointStore.recoverStaleRunning(context, 200L) {
            callbackCalled = true
            false
        })
        assertFalse(callbackCalled)
        assertEquals("INTERRUPTED", TaskCheckpointStore.latest(context).single().status)
    }

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

    @Test fun negativePersistedUsageEstimateMakesJournalUnreadableAndPreventsOverwrite() {
        val prefs = context.getSharedPreferences("task_checkpoints_v1", Context.MODE_PRIVATE)
        val malformed = """[{"task":"bad-estimate","status":"REQUESTING","estimatedInputTokens":-10}]"""
        assertTrue(prefs.edit().putString("events", malformed).commit())
        assertFalse(TaskCheckpointStore.record(
            context, TaskCheckpointStore.Event("new-task", "tester", "RUNNING", "", 700L, "", "", 0)
        ))
        assertEquals(malformed, prefs.getString("events", null))
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
