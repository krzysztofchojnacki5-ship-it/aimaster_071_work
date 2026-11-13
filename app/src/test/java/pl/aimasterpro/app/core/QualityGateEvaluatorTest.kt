package pl.aimasterpro.app.core

import org.junit.Assert.*
import org.junit.Test

class QualityGateEvaluatorTest {
    private fun passingGates() = QualityGateEvaluator.Gate.entries.map {
        QualityGateEvaluator.GateResult(
            gate = it,
            outcome = QualityGateEvaluator.Outcome.PASS,
            evidenceRef = "evidence:${it.name.lowercase()}"
        )
    }

    @Test fun missingGatesAreNotRunAndBlockCompletion() {
        val result = QualityGateEvaluator.evaluate(emptyList())
        assertEquals(QualityGateEvaluator.Outcome.NOT_RUN, result.overall)
        assertFalse(result.completionAllowed)
        assertEquals(QualityGateEvaluator.Gate.entries.toSet(), result.missingGates.toSet())
    }

    @Test fun passWithoutEvidenceBecomesInconclusive() {
        val results = passingGates().toMutableList()
        results[0] = results[0].copy(evidenceRef = "")
        val evaluation = QualityGateEvaluator.evaluate(results)
        assertEquals(QualityGateEvaluator.Outcome.INCONCLUSIVE, evaluation.overall)
        assertFalse(evaluation.completionAllowed)
    }

    @Test fun approvedWaiverRequiresResidualRiskAndCanPermitCompletion() {
        val results = passingGates().toMutableList()
        results[0] = results[0].copy(
            outcome = QualityGateEvaluator.Outcome.WAIVED,
            userApprovedWaiver = true,
            residualRisk = "Test sprzętowy nie jest dostępny; pozostaje ryzyko integracyjne."
        )
        val evaluation = QualityGateEvaluator.evaluate(results)
        assertEquals(QualityGateEvaluator.Outcome.WAIVED, evaluation.overall)
        assertTrue(evaluation.completionAllowed)
    }

    @Test fun unapprovedWaiverBecomesBlocked() {
        val results = passingGates().toMutableList()
        results[0] = results[0].copy(
            outcome = QualityGateEvaluator.Outcome.WAIVED,
            residualRisk = "Ryzyko opisane, ale brak zgody."
        )
        val evaluation = QualityGateEvaluator.evaluate(results)
        assertEquals(QualityGateEvaluator.Outcome.BLOCKED, evaluation.overall)
        assertFalse(evaluation.completionAllowed)
    }

    @Test fun failureHasPriorityOverNotRun() {
        val results = passingGates().toMutableList()
        results[0] = results[0].copy(outcome = QualityGateEvaluator.Outcome.FAIL)
        results.removeAt(1)
        val evaluation = QualityGateEvaluator.evaluate(results)
        assertEquals(QualityGateEvaluator.Outcome.FAIL, evaluation.overall)
        assertFalse(evaluation.completionAllowed)
    }

    @Test(expected = IllegalArgumentException::class)
    fun duplicateGateResultsAreRejected() {
        val pass = passingGates().first()
        QualityGateEvaluator.evaluate(listOf(pass, pass))
    }
}
