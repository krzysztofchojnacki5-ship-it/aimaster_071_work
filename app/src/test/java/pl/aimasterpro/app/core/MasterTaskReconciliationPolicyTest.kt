package pl.aimasterpro.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MasterTaskReconciliationPolicyTest {
    private val agents = listOf("researcher", "developer")
    private val reports = agents.map { MasterTaskReconciliationPolicy.Evidence(it, 4) }
    private val events = agents.map { MasterTaskReconciliationPolicy.Evidence(it, 4, "COMPLETED") }

    private fun assess(
        status: String = "RUNNING",
        archiveReadable: Boolean = true,
        reportEvidence: List<MasterTaskReconciliationPolicy.Evidence> = reports,
        eventEvidence: List<MasterTaskReconciliationPolicy.Evidence> = events
    ) = MasterTaskReconciliationPolicy.assess(
        status, 4, agents, archiveReadable, reportEvidence, eventEvidence
    )

    @Test fun unreadableArchiveAlwaysFailsClosed() {
        assertEquals(
            MasterTaskReconciliationPolicy.Decision.ARCHIVE_UNREADABLE,
            assess(archiveReadable = false).decision
        )
    }

    @Test fun missingReportDoesNotBecomeCompletedFromQueueEventsAlone() {
        val result = assess(reportEvidence = listOf(reports.first()), eventEvidence = listOf(events.first()))
        assertEquals(MasterTaskReconciliationPolicy.Decision.MISSING_REPORTS, result.decision)
        assertEquals(listOf("developer"), result.missingReportAgentIds)
    }

    @Test fun archivedReportWithoutQueueResultEventRequiresEventRepair() {
        val result = assess(eventEvidence = listOf(events.first()))
        assertEquals(
            MasterTaskReconciliationPolicy.Decision.REPORTS_NEED_RESULT_EVENT_REPAIR,
            result.decision
        )
        assertEquals(listOf("developer"), result.missingResultEventAgentIds)
    }

    @Test fun missingQueueEventCanBeRepairedOnlyFromMatchingTerminalCheckpoint() {
        val result = MasterTaskReconciliationPolicy.assess(
            "RUNNING", 4, agents, true,
            listOf(reports.first(), reports.last().copy(checkpointStatus = "COMPLETED")),
            listOf(events.first())
        )
        assertEquals(
            MasterTaskReconciliationPolicy.Decision.REPORTS_NEED_RESULT_EVENT_REPAIR,
            result.decision
        )
        assertEquals(mapOf("developer" to "COMPLETED"), result.repairStatuses)
        assertEquals("COMPLETED", result.finalStatus)
    }

    @Test fun missingCheckpointJournalBlocksEventRepair() {
        val result = MasterTaskReconciliationPolicy.assess(
            "RUNNING", 4, agents, true,
            reports, listOf(events.first()),
            checkpointJournalReadable = false
        )
        assertEquals(MasterTaskReconciliationPolicy.Decision.CHECKPOINT_UNREADABLE, result.decision)
        assertTrue(result.repairStatuses.isEmpty())
    }

    @Test fun conflictingTerminalCheckpointEvidenceIsRejectedBeforeRepair() {
        val result = MasterTaskReconciliationPolicy.assess(
            "RUNNING", 4, agents, true,
            listOf(reports.first(), reports.last().copy(checkpointConflict = true)),
            listOf(events.first())
        )
        assertEquals(
            MasterTaskReconciliationPolicy.Decision.CHECKPOINT_EVIDENCE_CONFLICT,
            result.decision
        )
        assertTrue(result.repairStatuses.isEmpty())
        assertEquals(null, result.finalStatus)
    }

    @Test fun checkpointAndExistingQueueResultMustAgree() {
        val result = MasterTaskReconciliationPolicy.assess(
            "RUNNING", 4, agents, true,
            listOf(reports.first().copy(checkpointStatus = "FAILED"), reports.last()),
            events
        )
        assertEquals(
            MasterTaskReconciliationPolicy.Decision.CHECKPOINT_RESULT_MISMATCH,
            result.decision
        )
    }

    @Test fun queueResultWithoutArchivedReportIsInconsistent() {
        val result = assess(reportEvidence = listOf(reports.first()))
        assertEquals(MasterTaskReconciliationPolicy.Decision.RESULT_EVENTS_WITHOUT_REPORTS, result.decision)
    }

    @Test fun mixedMissingReportAndOrphanResultEventIsDetected() {
        val result = assess(
            reportEvidence = listOf(reports.first()),
            eventEvidence = listOf(events.last())
        )
        assertEquals(
            MasterTaskReconciliationPolicy.Decision.RESULT_EVENTS_WITHOUT_REPORTS,
            result.decision
        )
    }

    @Test fun differentRunEpochNeverReusesOldReportOrEvent() {
        val result = assess(
            reportEvidence = listOf(reports.first(), reports.last().copy(runId = 3))
        )
        assertEquals(MasterTaskReconciliationPolicy.Decision.EPOCH_MISMATCH, result.decision)
        assertEquals(listOf("developer"), result.unexpectedEvidenceAgentIds)
    }

    @Test fun allDurableEvidenceAllowsFinalizationButDoesNotPerformIt() {
        val running = assess()
        assertEquals(MasterTaskReconciliationPolicy.Decision.READY_TO_FINALIZE, running.decision)
        assertEquals("COMPLETED", running.finalStatus)
        val blockedEvents = events.map { it.copy(resultStatus = "BLOCKED") }
        val blocked = assess(status = "BLOCKED", eventEvidence = blockedEvents)
        assertEquals(MasterTaskReconciliationPolicy.Decision.TERMINAL_STATE_CONSISTENT, blocked.decision)
        assertEquals("BLOCKED", blocked.finalStatus)
    }

    @Test fun failedAgentResultKeepsAggregateTaskFailed() {
        val failedEvents = listOf(events.first(), events.last().copy(resultStatus = "FAILED"))
        val result = assess(eventEvidence = failedEvents)
        assertEquals(MasterTaskReconciliationPolicy.Decision.READY_TO_FINALIZE, result.decision)
        assertEquals("FAILED", result.finalStatus)
    }

    @Test fun unsupportedResultEventStatusFailsClosed() {
        val invalidEvents = listOf(events.first(), events.last().copy(resultStatus = "RUNNING"))
        assertEquals(
            MasterTaskReconciliationPolicy.Decision.INVALID_RESULT_EVENT,
            assess(eventEvidence = invalidEvents).decision
        )
    }

    @Test fun completeTerminalTaskWithMatchingEvidenceIsConsistent() {
        assertEquals(
            MasterTaskReconciliationPolicy.Decision.TERMINAL_STATE_CONSISTENT,
            assess(status = "COMPLETED").decision
        )
    }

    @Test fun terminalQueueStateMustMatchPersistedAgentOutcomes() {
        val result = assess(status = "COMPLETED", eventEvidence = listOf(
            events.first(), events.last().copy(resultStatus = "FAILED")
        ))
        assertEquals(MasterTaskReconciliationPolicy.Decision.TERMINAL_STATE_INCONSISTENT, result.decision)
        assertEquals("FAILED", result.finalStatus)
    }

    @Test fun invalidEpochAndAssigneeSetAreRejected() {
        assertEquals(
            MasterTaskReconciliationPolicy.Decision.INVALID_TASK_EPOCH,
            MasterTaskReconciliationPolicy.assess("RUNNING", 0, agents, true, reports, events).decision
        )
        assertEquals(
            MasterTaskReconciliationPolicy.Decision.NO_ASSIGNEES,
            MasterTaskReconciliationPolicy.assess("RUNNING", 4, emptyList(), true, reports, events).decision
        )
    }

    @Test fun checkpointSelectionKeepsNewestRowFromRequestedRun() {
        fun row(runId: Int, status: String) = TaskCheckpointStore.Event(
            taskId = "execution-1",
            agentId = "developer",
            status = status,
            detail = "",
            timestamp = runId.toLong(),
            providerId = "",
            model = "",
            attempts = 1,
            runId = runId
        )
        val selected = MasterTaskReconciliationReader.selectLatestCheckpointsForRun(
            listOf(row(5, "FAILED"), row(4, "COMPLETED"), row(4, "BLOCKED")),
            runId = 4
        )
        assertEquals(1, selected.size)
        assertEquals("COMPLETED", selected.single().status)
    }

    @Test fun evidenceFromUnassignedAgentFailsClosed() {
        val result = assess(reportEvidence = reports + MasterTaskReconciliationPolicy.Evidence("intruder", 4))
        assertEquals(MasterTaskReconciliationPolicy.Decision.UNASSIGNED_EVIDENCE, result.decision)
    }

    @Test fun duplicateArchivedReportsAreRejectedAsAmbiguous() {
        val result = assess(reportEvidence = reports + reports.first())
        assertEquals(MasterTaskReconciliationPolicy.Decision.DUPLICATE_REPORT_EVIDENCE, result.decision)
    }

    @Test fun duplicateResultEventsAreRejectedAsAmbiguous() {
        val result = assess(eventEvidence = events + events.first())
        assertEquals(MasterTaskReconciliationPolicy.Decision.DUPLICATE_RESULT_EVENT, result.decision)
    }
}
