package pl.aimasterpro.app.core

import org.junit.Assert.*
import org.junit.Test

class MasterQualityGateCoordinatorTest {
    private val scope = MasterEvidenceContextBuilder.Candidate(
        changeSetId = "changeset-12345678",
        taskId = "task-12345678",
        chatId = "chat-12345678",
        snapshotId = "snapshot-12345678",
        updatedAt = 100L
    )

    private fun evidence(kind: VerificationEvidenceKind, status: VerificationEvidenceStatus) =
        VerificationEvidence.runtimeCheck(
            taskId = scope.taskId,
            kind = kind,
            status = status,
            summary = "${kind.name} ${status.name}",
            observedAtEpochMs = kind.ordinal.toLong() + 1L,
            chatId = scope.chatId,
            snapshotId = scope.snapshotId
        )

    private fun input(
        rows: List<VerificationEvidence> = emptyList(),
        ledgerValid: Boolean = true,
        safety: Boolean = true,
        persistence: Boolean = true,
        review: Boolean = true,
        response: Boolean = true,
        responseCheck: Boolean = true
    ) = MasterQualityGateCoordinator.Input(
        selected = scope,
        evidenceRows = rows,
        evidenceLedgerValid = ledgerValid,
        safetyValidated = safety,
        persistenceVerified = persistence,
        independentReviewCompleted = review,
        responsePresent = response,
        responseCheckPassed = responseCheck
    )

    @Test fun allNineGatesPassOnlyWithExactEvidenceAndIndependentReview() {
        val rows = listOf(
            evidence(VerificationEvidenceKind.PATCH_SCOPE, VerificationEvidenceStatus.PASS),
            evidence(VerificationEvidenceKind.PROJECT_TESTS, VerificationEvidenceStatus.PASS),
            evidence(VerificationEvidenceKind.UNIT_TESTS, VerificationEvidenceStatus.PASS),
            evidence(VerificationEvidenceKind.ANDROID_BUILD, VerificationEvidenceStatus.PASS),
            evidence(VerificationEvidenceKind.INTEGRATION_TESTS, VerificationEvidenceStatus.PASS)
        )
        val result = MasterQualityGateCoordinator.evaluate(input(rows))
        assertEquals(QualityGateEvaluator.Outcome.PASS, result.overall)
        assertTrue(result.completionAllowed)
        assertTrue(result.results.all { it.outcome == QualityGateEvaluator.Outcome.PASS })
    }

    @Test fun missingEvidenceBlocksCompletionAndNeverBecomesPass() {
        val result = MasterQualityGateCoordinator.evaluate(input())
        assertFalse(result.completionAllowed)
        assertEquals(QualityGateEvaluator.Outcome.NOT_RUN, result.results.first { it.gate == QualityGateEvaluator.Gate.SCOPE }.outcome)
        assertEquals(QualityGateEvaluator.Outcome.NOT_RUN, result.results.first { it.gate == QualityGateEvaluator.Gate.BUILD }.outcome)
        assertEquals(QualityGateEvaluator.Outcome.NOT_RUN, result.results.first { it.gate == QualityGateEvaluator.Gate.REVIEW }.outcome)
    }

    @Test fun agentClaimsCannotSatisfyProjectTests() {
        val claim = VerificationEvidence.agentClaim(
            taskId = scope.taskId,
            kind = VerificationEvidenceKind.PROJECT_TESTS,
            summary = "Agent twierdzi, że testy przeszły",
            chatId = scope.chatId,
            snapshotId = scope.snapshotId
        )
        val result = MasterQualityGateCoordinator.evaluate(input(rows = listOf(claim)))
        assertEquals(QualityGateEvaluator.Outcome.INCONCLUSIVE,
            result.results.first { it.gate == QualityGateEvaluator.Gate.CORRECTNESS }.outcome)
        assertFalse(result.completionAllowed)
    }

    @Test fun failedBuildEvidenceFailsBuildGate() {
        val result = MasterQualityGateCoordinator.evaluate(input(
            rows = listOf(evidence(VerificationEvidenceKind.ANDROID_BUILD, VerificationEvidenceStatus.FAIL))
        ))
        assertEquals(QualityGateEvaluator.Outcome.FAIL,
            result.results.first { it.gate == QualityGateEvaluator.Gate.BUILD }.outcome)
        assertEquals(QualityGateEvaluator.Outcome.FAIL, result.overall)
    }

    @Test(expected = IllegalArgumentException::class)
    fun evidenceFromAnotherSnapshotIsRejected() {
        val foreign = evidence(VerificationEvidenceKind.PATCH_SCOPE, VerificationEvidenceStatus.PASS)
            .copy(snapshotId = "another-snapshot-123")
        MasterQualityGateCoordinator.evaluate(input(rows = listOf(foreign)))
    }

    @Test fun unavailableEvidenceLedgerBlocksSafetyAndPersistence() {
        val result = MasterQualityGateCoordinator.evaluate(input(
            rows = emptyList(), ledgerValid = false, safety = false, persistence = false
        ))
        assertEquals(QualityGateEvaluator.Outcome.BLOCKED,
            result.results.first { it.gate == QualityGateEvaluator.Gate.SAFETY }.outcome)
        assertEquals(QualityGateEvaluator.Outcome.BLOCKED,
            result.results.first { it.gate == QualityGateEvaluator.Gate.PERSISTENCE }.outcome)
    }
}
