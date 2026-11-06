package pl.aimasterpro.app.core

import org.junit.Assert.*
import org.junit.Test

class MasterResponseCheckTest {
    @Test fun flagsMissingStepsWhenUserAskedForProcedure() {
        val r = MasterResponseCheck.evaluate("Wyjaśnij krok po kroku jak wdrożyć zmianę", "To można zrobić przez konfigurację.")
        assertFalse(r.passed)
        assertTrue(r.warnings.any { it.contains("sekwencji kroków") })
    }
    @Test fun flagsTestSuccessClaimWithoutStructuredEvidence() {
        val result = MasterResponseCheck.evaluate("Sprawdź testy", "Testy przeszły, build PASS.")
        assertFalse(result.passed)
        assertTrue(result.warnings.any { it.contains("nie przekazano dowodu wykonania") })
    }

    @Test fun acceptsTestSuccessClaimOnlyWithCompleteObservedEvidence() {
        val evidence = listOf(VerificationEvidence.toolExecution(
            "task-1", VerificationEvidenceKind.UNIT_TESTS, VerificationEvidenceStatus.PASS,
            "4 testy przeszły", "host-run-42", 100L
        ))
        val assessment = VerificationAssessment.assess(evidence, setOf(VerificationEvidenceKind.UNIT_TESTS))
        val result = MasterResponseCheck.evaluate("Sprawdź testy", "Testy przeszły.", assessment)
        assertTrue(result.passed)
    }

    @Test fun unrelatedWorkspacePassCannotAuthorizeTestSuccessWording() {
        val evidence = listOf(VerificationEvidence.runtimeCheck(
            "task-1", VerificationEvidenceKind.WORKSPACE_INTEGRITY, VerificationEvidenceStatus.PASS,
            "manifest ok", 100L
        ))
        val assessment = VerificationAssessment.assess(evidence, setOf(VerificationEvidenceKind.WORKSPACE_INTEGRITY))
        val result = MasterResponseCheck.evaluate("Sprawdź testy", "Testy przeszły.", assessment)
        assertFalse(result.passed)
        assertTrue(result.warnings.any { it.contains("innego rodzaju kontroli") })
    }

    @Test fun compilationPassCannotAuthorizeAndroidBuildClaim() {
        val evidence = listOf(VerificationEvidence.toolExecution(
            "task-1", VerificationEvidenceKind.COMPILATION, VerificationEvidenceStatus.PASS,
            "Kotlin compilation passed", "run-compile", 100L
        ))
        val assessment = VerificationAssessment.assess(evidence, setOf(VerificationEvidenceKind.COMPILATION))
        val result = MasterResponseCheck.evaluate("Sprawdź APK", "Build PASS.", assessment)
        assertFalse(result.passed)
        assertTrue(result.warnings.any { it.contains("innego rodzaju kontroli") })
    }

    @Test fun compilationClaimRequiresCompilationEvidence() {
        val evidence = listOf(VerificationEvidence.toolExecution(
            "task-1", VerificationEvidenceKind.COMPILATION, VerificationEvidenceStatus.PASS,
            "Kotlin compilation passed", "run-compile", 100L
        ))
        val assessment = VerificationAssessment.assess(evidence, setOf(VerificationEvidenceKind.COMPILATION))
        val result = MasterResponseCheck.evaluate("Sprawdź kompilację", "Kompilacja zakończyła się powodzeniem.", assessment)
        assertTrue(result.passed)
    }

    @Test fun agentClaimCannotAuthorizeTestSuccessWording() {
        val claim = VerificationEvidence.agentClaim("task-1", VerificationEvidenceKind.UNIT_TESTS,
            "Agent twierdzi, że testy przeszły.", 100L)
        val assessment = VerificationAssessment.assess(listOf(claim), setOf(VerificationEvidenceKind.UNIT_TESTS))
        val result = MasterResponseCheck.evaluate("Sprawdź testy", "Testy przeszły.", assessment)
        assertFalse(result.passed)
        assertTrue(result.warnings.any { it.contains("zestaw dowodów jest niekompletny") })
    }

    @Test fun acceptsStructuredAnswer() {
        val r = MasterResponseCheck.evaluate("Podaj instrukcję krok po kroku", "1. Najpierw przygotuj projekt.\n2. Następnie uruchom testy.\n3. Na końcu sprawdź wynik.")
        assertTrue(r.passed)
    }
}
