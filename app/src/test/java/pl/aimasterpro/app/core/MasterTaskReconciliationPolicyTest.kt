package pl.aimasterpro.app.core

import org.junit.Assert.assertEquals
import org.junit.Test

class MasterTaskReconciliationPolicyTest {
    private val agents = listOf("researcher", "developer")
    private val reports = agents.map { MasterTaskReconciliationPolicy.Evidence(it, 4) }
    private val events = agents.map { MasterTaskReconciliationPolicy.Evidence(it, 4) }

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
        val blocked = assess(status = "BLOCKED")
        assertEquals(MasterTaskReconciliationPolicy.Decision.READY_TO_FINALIZE, blocked.decision)
        assertEquals("COMPLETED", blocked.finalStatus)
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
}
