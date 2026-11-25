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

    @Test fun archivedReportWithUnreconciledUsageOrBlockedCheckpointIsNotResumed() {
        val plan = QueueResumePlanner.plan(listOf(
            QueueResumePlanner.AgentEvidence("agent-a", true, checkpointStatus = "INTERRUPTED_USAGE_PENDING")
        ))
        assertFalse(plan.safe)
        assertTrue(plan.reason.contains("uzgodnienia"))
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

    @Test fun provenSafeRetryResumesOnlyTheUnfinishedSuffixAndIgnoresItsDiagnosticArchive() {
        val plan = QueueResumePlanner.plan(listOf(
            QueueResumePlanner.AgentEvidence("agent-a", true, "COMPLETED", "COMPLETED"),
            QueueResumePlanner.AgentEvidence(
                "agent-b", true, "BLOCKED", "BLOCKED",
                checkpointDetail = "SAFE_RETRY: ProviderException: RATE_LIMIT_QUOTA (HTTP 429)",
                queueResultDetail = "BLOCKED: SAFE_RETRY: RATE_LIMIT_QUOTA"
            ),
            QueueResumePlanner.AgentEvidence("agent-c", false, "QUEUED")
        ))

        assertTrue(plan.safe)
        assertEquals(listOf("agent-a"), plan.completedAgentIds)
        assertEquals(listOf("agent-b", "agent-c"), plan.pendingAgentIds)
    }

    @Test fun blockedResultWithoutBothIndependentSafeRetryMarkersRemainsUnsafe() {
        val missingCheckpointProof = QueueResumePlanner.plan(listOf(
            QueueResumePlanner.AgentEvidence(
                "agent-a", true, "BLOCKED", "BLOCKED",
                checkpointDetail = "ProviderException: RATE_LIMIT_QUOTA",
                queueResultDetail = "BLOCKED: SAFE_RETRY: RATE_LIMIT_QUOTA"
            )
        ))
        val missingQueueProof = QueueResumePlanner.plan(listOf(
            QueueResumePlanner.AgentEvidence(
                "agent-a", true, "BLOCKED", "BLOCKED",
                checkpointDetail = "SAFE_RETRY: ProviderException: RATE_LIMIT_QUOTA",
                queueResultDetail = "BLOCKED: ProviderException: RATE_LIMIT_QUOTA"
            )
        ))

        assertFalse(missingCheckpointProof.safe)
        assertFalse(missingQueueProof.safe)
    }

    @Test fun archivedReportAfterSafeRetryStillBreaksTheOrderedHandoffChain() {
        val plan = QueueResumePlanner.plan(listOf(
            QueueResumePlanner.AgentEvidence(
                "agent-a", false, "BLOCKED", "BLOCKED",
                checkpointDetail = "SAFE_RETRY: ProviderException: DNS_ERROR",
                queueResultDetail = "BLOCKED: SAFE_RETRY: DNS_ERROR"
            ),
            QueueResumePlanner.AgentEvidence("agent-b", true, "COMPLETED", "COMPLETED")
        ))

        assertFalse(plan.safe)
        assertTrue(plan.reason.contains("kolejności"))
    }
}
