package pl.aimasterpro.app.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class MasterTaskReconciliationReaderTest {
    private lateinit var context: Context
    private val taskId = "task-reconcile-001"
    private val agentId = "critical-reviewer"

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        listOf(
            "aiforteca_master_task_queue_v1",
            "aiforteca_agent_manager_v1",
            "task_result_archive_v1",
            "task_checkpoints_v1"
        ).forEach { name ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
            SharedPreferencesWriteHealth.resetForTests(name)
        }
        AgentManagerStore.resetStorageHealthForTests()
        TaskCheckpointStore.resetStorageHealthForTests()
    }

    private fun startSingleAgentTask(): Int {
        assertTrue(MasterTaskQueueStore.enqueue(
            context, taskId, "Reconciliation test", "Repair only a missing durable result event.", now = 10L
        ))
        assertTrue(MasterTaskQueueStore.assign(context, taskId, listOf(agentId), now = 11L))
        val assigned = MasterTaskQueueStore.snapshot(context).tasks.single()
        assertTrue(MasterTaskQueueStore.transition(
            context, taskId, "MASTER", MasterTaskQueueStore.Status.RUNNING, "Start.", 12L,
            expectedCurrentRunId = assigned.runId,
            expectedAssignedAgentIds = assigned.assignedAgentIds
        ))
        return MasterTaskQueueStore.snapshot(context).tasks.single().runId
    }

    private fun persistReportAndCheckpoint(runId: Int) {
        val executionId = TaskResultArchive.executionTaskId(taskId, agentId, runId)
        assertTrue(TaskResultArchive.put(
            context, executionId, agentId, "Durable specialist report.", "provider-test", "model-test"
        ))
        assertTrue(TaskCheckpointStore.record(
            context,
            TaskCheckpointStore.Event(
                taskId = executionId, agentId = agentId, status = "COMPLETED",
                detail = "Agent completed and report was persisted.",
                timestamp = 20L, providerId = "provider-test", model = "model-test",
                attempts = 1, runId = runId
            )
        ))
    }

    @Test fun repairsOnlyMissingResultEventFromMatchingReportAndCheckpoint() {
        val runId = startSingleAgentTask()
        persistReportAndCheckpoint(runId)

        val before = MasterTaskReconciliationReader.assess(
            context, MasterTaskQueueStore.snapshot(context), taskId
        )
        assertEquals(
            MasterTaskReconciliationPolicy.Decision.REPORTS_NEED_RESULT_EVENT_REPAIR,
            before.decision
        )
        assertEquals(mapOf(agentId to "COMPLETED"), before.repairStatuses)

        assertTrue(MasterTaskReconciliationReader.repairMissingResultEvents(context, taskId))
        val after = MasterTaskQueueStore.snapshot(context)
        assertEquals(MasterTaskQueueStore.Status.COMPLETED, after.tasks.single().status)
        assertTrue(after.events.any {
            it.taskId == taskId && it.actor == "agent:$agentId" &&
                it.operation == "AGENT_RESULT" && it.runId == runId && it.toStatus == "COMPLETED"
        })
        assertTrue(MasterTaskReconciliationReader.repairMissingResultEvents(context, taskId))
        assertEquals(
            MasterTaskReconciliationPolicy.Decision.TERMINAL_STATE_CONSISTENT,
            MasterTaskReconciliationReader.assess(context, after, taskId).decision
        )
    }

    @Test fun refusesRepairWhenCheckpointIsMissing() {
        val runId = startSingleAgentTask()
        val executionId = TaskResultArchive.executionTaskId(taskId, agentId, runId)
        assertTrue(TaskResultArchive.put(
            context, executionId, agentId, "Archived but unverified report.", "provider-test", "model-test"
        ))

        assertFalse(MasterTaskReconciliationReader.repairMissingResultEvents(context, taskId))
        val snapshot = MasterTaskQueueStore.snapshot(context)
        assertEquals(MasterTaskQueueStore.Status.RUNNING, snapshot.tasks.single().status)
        assertFalse(snapshot.events.any { it.taskId == taskId && it.operation == "AGENT_RESULT" })
    }

    @Test fun refusesRepairWhenReportIsMissingEvenIfCheckpointIsTerminal() {
        val runId = startSingleAgentTask()
        val executionId = TaskResultArchive.executionTaskId(taskId, agentId, runId)
        assertTrue(TaskCheckpointStore.record(
            context,
            TaskCheckpointStore.Event(
                taskId = executionId, agentId = agentId, status = "COMPLETED",
                detail = "Checkpoint exists but archive report is absent.",
                timestamp = 20L, providerId = "provider-test", model = "model-test",
                attempts = 1, runId = runId
            )
        ))

        assertFalse(MasterTaskReconciliationReader.repairMissingResultEvents(context, taskId))
        val snapshot = MasterTaskQueueStore.snapshot(context)
        assertEquals(MasterTaskQueueStore.Status.RUNNING, snapshot.tasks.single().status)
        assertFalse(snapshot.events.any { it.taskId == taskId && it.operation == "AGENT_RESULT" })
    }
    @Test fun checkpointFromDifferentAgentCannotAuthorizeReportEventRepair() {
        val runId = startSingleAgentTask()
        val executionId = TaskResultArchive.executionTaskId(taskId, agentId, runId)
        assertTrue(TaskResultArchive.put(
            context, executionId, agentId, "Durable specialist report.", "provider-test", "model-test"
        ))
        assertTrue(TaskCheckpointStore.record(
            context,
            TaskCheckpointStore.Event(
                taskId = executionId, agentId = "different-agent", status = "COMPLETED",
                detail = "Checkpoint identity does not match the assigned agent.",
                timestamp = 21L, providerId = "provider-test", model = "model-test",
                attempts = 1, runId = runId
            )
        ))

        // A later checkpoint with the expected agent must not hide the conflicting
        // identity row for the same execution/run.
        assertTrue(TaskCheckpointStore.record(
            context,
            TaskCheckpointStore.Event(
                taskId = executionId, agentId = agentId, status = "COMPLETED",
                detail = "Conflicting checkpoint identity must remain ambiguous.",
                timestamp = 22L, providerId = "provider-test", model = "model-test",
                attempts = 1, runId = runId
            )
        ))

        val assessment = MasterTaskReconciliationReader.assess(
            context, MasterTaskQueueStore.snapshot(context), taskId
        )
        assertEquals(
            MasterTaskReconciliationPolicy.Decision.CHECKPOINT_EVIDENCE_CONFLICT,
            assessment.decision
        )
        assertTrue(assessment.repairStatuses.isEmpty())
        assertNull(assessment.finalStatus)
        assertFalse(MasterTaskReconciliationReader.repairMissingResultEvents(context, taskId))
    }

    @Test fun conflictingTerminalCheckpointsForSameExecutionBlockAssessmentAndRepair() {
        val runId = startSingleAgentTask()
        persistReportAndCheckpoint(runId)

        val prefs = context.getSharedPreferences("task_checkpoints_v1", Context.MODE_PRIVATE)
        val originalRows = JSONArray(prefs.getString("events", "[]"))
        val original = originalRows.getJSONObject(0)
        val conflicting = JSONObject(original.toString())
            .put("status", "FAILED")
            .put("ts", original.optLong("ts") + 1L)
        val conflictingRows = JSONArray().put(conflicting).put(original)
        assertTrue(prefs.edit().putString("events", conflictingRows.toString()).commit())

        val assessment = MasterTaskReconciliationReader.assess(
            context, MasterTaskQueueStore.snapshot(context), taskId
        )
        assertEquals(
            MasterTaskReconciliationPolicy.Decision.CHECKPOINT_EVIDENCE_CONFLICT,
            assessment.decision
        )
        assertTrue(assessment.repairStatuses.isEmpty())
        assertFalse(MasterTaskReconciliationReader.repairMissingResultEvents(context, taskId))

        val after = MasterTaskQueueStore.snapshot(context)
        assertEquals(MasterTaskQueueStore.Status.RUNNING, after.tasks.single().status)
        assertFalse(after.events.any {
            it.taskId == taskId && it.operation == "AGENT_RESULT" && it.runId == runId
        })
    }

    @Test fun archiveEntryForDifferentAgentCannotBeReconciledIntoAssignedAgentsResult() {
        val runId = startSingleAgentTask()
        val executionId = TaskResultArchive.executionTaskId(taskId, agentId, runId)
        assertTrue(TaskResultArchive.put(
            context, executionId, "different-agent", "Foreign archived report.",
            "provider-test", "model-test"
        ))
        assertTrue(TaskCheckpointStore.record(
            context,
            TaskCheckpointStore.Event(
                taskId = executionId, agentId = agentId, status = "COMPLETED",
                detail = "Checkpoint for the assigned agent.",
                timestamp = 22L, providerId = "provider-test", model = "model-test",
                attempts = 1, runId = runId
            )
        ))

        val assessment = MasterTaskReconciliationReader.assess(
            context, MasterTaskQueueStore.snapshot(context), taskId
        )
        assertEquals(MasterTaskReconciliationPolicy.Decision.MISSING_REPORTS, assessment.decision)
        assertFalse(MasterTaskReconciliationReader.repairMissingResultEvents(context, taskId))
        assertEquals(MasterTaskQueueStore.Status.RUNNING, MasterTaskQueueStore.snapshot(context).tasks.single().status)
    }


}
