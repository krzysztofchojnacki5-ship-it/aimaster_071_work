package pl.aimasterpro.app.core

import org.junit.Assert.*
import org.junit.Test

class QueueResumePlannerTest {
    @Test fun archivedPrefixIsKeptAndOnlyUnstartedSuffixIsPending() {
        val plan = QueueResumePlanner.plan(listOf(
            QueueResumePlanner.AgentEvidence("agent-a", true, "COMPLETED"),
            QueueResumePlanner.AgentEvidence("agent-b", true, "COMPLETED"),
            QueueResumePlanner.AgentEvidence("agent-c", false, "RUNNING")
        ))
        assertTrue(plan.safe)
        assertEquals(listOf("agent-a", "agent-b"), plan.completedAgentIds)
        assertEquals(listOf("agent-c"), plan.pendingAgentIds)
    }

    @Test fun uncertainProviderRequestWithoutArchivedReportIsNeverReplayed() {
        val plan = QueueResumePlanner.plan(listOf(
            QueueResumePlanner.AgentEvidence("agent-a", true),
            QueueResumePlanner.AgentEvidence("agent-b", false, "REQUESTING")
        ))
        assertFalse(plan.safe)
        assertTrue(plan.reason.contains("niejednoznaczny"))
    }

    @Test fun archivedReportAfterAnUnfinishedAgentBreaksOrderedHandoffChain() {
        val plan = QueueResumePlanner.plan(listOf(
            QueueResumePlanner.AgentEvidence("agent-a", false, "RUNNING"),
            QueueResumePlanner.AgentEvidence("agent-b", true)
        ))
        assertFalse(plan.safe)
        assertTrue(plan.reason.contains("kolejności"))
    }

    @Test fun failedOrBlockedQueueResultRequiresFreshRetry() {
        val plan = QueueResumePlanner.plan(listOf(
            QueueResumePlanner.AgentEvidence("agent-a", true, queueResultStatus = "BLOCKED")
        ))
        assertFalse(plan.safe)
        assertTrue(plan.reason.contains("od początku"))
    }

    @Test fun allArchivedReportsCanBeReconciledWithoutProviderRequests() {
        val plan = QueueResumePlanner.plan(listOf(
            QueueResumePlanner.AgentEvidence("agent-a", true),
            QueueResumePlanner.AgentEvidence("agent-b", true)
        ))
        assertTrue(plan.safe)
        assertTrue(plan.pendingAgentIds.isEmpty())
        assertEquals(listOf("agent-a", "agent-b"), plan.completedAgentIds)
    }

    @Test fun missingCheckpointIsSafeOnlyBeforeAnyRequestHasBeenRecorded() {
        val plan = QueueResumePlanner.plan(listOf(
            QueueResumePlanner.AgentEvidence("agent-a", false)
        ))
        assertTrue(plan.safe)
        assertEquals(listOf("agent-a"), plan.pendingAgentIds)
    }
}
