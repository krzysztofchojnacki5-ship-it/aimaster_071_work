package pl.aimasterpro.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VerificationEvidenceTest {
    private val required = setOf(
        VerificationEvidenceKind.WORKSPACE_INTEGRITY,
        VerificationEvidenceKind.PROJECT_TESTS
    )

    @Test fun agentClaimIsNeverPromotedToPass() {
        val claim = VerificationEvidence.agentClaim("task-1", VerificationEvidenceKind.PROJECT_TESTS,
            "Agent twierdzi, że testy przeszły.", observedAtEpochMs = 10)
        assertEquals(VerificationEvidenceStatus.CLAIM_ONLY, claim.status)
        assertEquals(VerificationEvidenceOrigin.AGENT_CLAIM, claim.origin)
        assertEquals(VerificationAssessmentStatus.INCOMPLETE,
            VerificationAssessment.assess(listOf(claim), required).status)
        assertFalse(runCatching {
            VerificationEvidence("x", "task-1", VerificationEvidenceKind.PROJECT_TESTS,
                VerificationEvidenceStatus.PASS, VerificationEvidenceOrigin.AGENT_CLAIM,
                "fake pass", 10)
        }.isSuccess)
    }

    @Test fun toolPassRequiresARecordedExecutionId() {
        assertFalse(runCatching {
            VerificationEvidence("x", "task-1", VerificationEvidenceKind.UNIT_TESTS,
                VerificationEvidenceStatus.PASS, VerificationEvidenceOrigin.TOOL_EXECUTION,
                "tests passed", 10)
        }.isSuccess)
        val observed = VerificationEvidence.toolExecution("task-1", VerificationEvidenceKind.UNIT_TESTS,
            VerificationEvidenceStatus.PASS, "4 tests passed", "run-123", observedAtEpochMs = 11)
        assertEquals("run-123", observed.executionId)
        assertEquals(VerificationEvidenceOrigin.TOOL_EXECUTION, observed.origin)
    }

    @Test fun assessmentPassesOnlyWhenEveryRequiredKindHasObservedPass() {
        val evidence = listOf(
            VerificationEvidence.runtimeCheck("task-1", VerificationEvidenceKind.WORKSPACE_INTEGRITY,
                VerificationEvidenceStatus.PASS, "manifest ok", 10),
            VerificationEvidence.toolExecution("task-1", VerificationEvidenceKind.PROJECT_TESTS,
                VerificationEvidenceStatus.PASS, "tests passed", "run-1", 11)
        )
        val assessment = VerificationAssessment.assess(evidence, required)
        assertEquals(VerificationAssessmentStatus.PASS, assessment.status)
        assertTrue(assessment.missingKinds.isEmpty())
        assertTrue(assessment.unresolvedKinds.isEmpty())
    }

    @Test fun notRunAndMissingEvidenceRemainIncompleteOrNotRun() {
        val none = VerificationAssessment.assess(emptyList(), required)
        assertEquals(VerificationAssessmentStatus.NOT_RUN, none.status)
        assertEquals(required, none.missingKinds)

        val partial = listOf(VerificationEvidence.runtimeCheck("task-1",
            VerificationEvidenceKind.WORKSPACE_INTEGRITY, VerificationEvidenceStatus.PASS, "ok", 10),
            VerificationEvidence.notRun("task-1", VerificationEvidenceKind.PROJECT_TESTS, "No sandbox", 11))
        val assessment = VerificationAssessment.assess(partial, required)
        assertEquals(VerificationAssessmentStatus.INCOMPLETE, assessment.status)
        assertTrue(assessment.unresolvedKinds.contains(VerificationEvidenceKind.PROJECT_TESTS))
    }

    @Test fun assessmentRejectsEvidenceMixedAcrossDifferentTasks() {
        val mixed = listOf(
            VerificationEvidence.runtimeCheck("task-a", VerificationEvidenceKind.WORKSPACE_INTEGRITY,
                VerificationEvidenceStatus.PASS, "workspace ok", 10),
            VerificationEvidence.toolExecution("task-b", VerificationEvidenceKind.PROJECT_TESTS,
                VerificationEvidenceStatus.PASS, "tests passed", "run-b", 11)
        )
        assertFalse(runCatching { VerificationAssessment.assess(mixed, required) }.isSuccess)
    }

    @Test fun assessmentRejectsMixedChatsAndSnapshots() {
        val a = VerificationEvidence.runtimeCheck("task-1", VerificationEvidenceKind.WORKSPACE_INTEGRITY,
            VerificationEvidenceStatus.PASS, "workspace ok", 10, chatId = "chat-a", snapshotId = "snapshot-a")
        val otherChat = VerificationEvidence.toolExecution("task-1", VerificationEvidenceKind.PROJECT_TESTS,
            VerificationEvidenceStatus.PASS, "tests passed", "run-1", 11, chatId = "chat-b", snapshotId = "snapshot-a")
        assertFalse(runCatching { VerificationAssessment.assess(listOf(a, otherChat), required) }.isSuccess)
        val otherSnapshot = otherChat.copy(chatId = "chat-a", snapshotId = "snapshot-b")
        assertFalse(runCatching { VerificationAssessment.assess(listOf(a, otherSnapshot), required) }.isSuccess)
    }

    @Test fun latestObservedFailureWinsUntilARealRerunPasses() {
        val failed = VerificationEvidence.toolExecution("task-1", VerificationEvidenceKind.PROJECT_TESTS,
            VerificationEvidenceStatus.FAIL, "test failed", "run-1", 10)
        val earlierPass = VerificationEvidence.toolExecution("task-1", VerificationEvidenceKind.WORKSPACE_INTEGRITY,
            VerificationEvidenceStatus.PASS, "workspace ok", "run-2", 9)
        val assessment = VerificationAssessment.assess(listOf(earlierPass, failed), required)
        assertEquals(VerificationAssessmentStatus.FAIL, assessment.status)
        assertTrue(assessment.failedKinds.contains(VerificationEvidenceKind.PROJECT_TESTS))

        val rerunPass = VerificationEvidence.toolExecution("task-1", VerificationEvidenceKind.PROJECT_TESTS,
            VerificationEvidenceStatus.PASS, "rerun passed", "run-3", 12)
        val recovered = VerificationAssessment.assess(listOf(earlierPass, failed, rerunPass), required)
        assertEquals(VerificationAssessmentStatus.PASS, recovered.status)
    }
}
