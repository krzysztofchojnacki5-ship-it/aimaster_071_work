package pl.aimasterpro.app.core

import org.junit.Assert.*
import org.junit.Test

class ReportConflictDetectorTest {
    @Test fun flagsDifferentNumericValuesForSameClaimAcrossAgents() {
        val result = ReportConflictDetector.detect(listOf(
            ReportConflictDetector.Report("agent-a", "Maksymalny limit wynosi 100 tokenów."),
            ReportConflictDetector.Report("agent-b", "Maksymalny limit wynosi 200 tokenów.")
        ))
        assertEquals(1, result.size)
        assertTrue(result.single().values.contains("100 tokenów"))
        assertTrue(result.single().values.contains("200 tokenów"))
    }

    @Test fun doesNotFlagSameValueOrSameAgent() {
        assertTrue(ReportConflictDetector.detect(listOf(
            ReportConflictDetector.Report("a", "Limit dzienny wynosi 1000 tokenów."),
            ReportConflictDetector.Report("b", "Limit dzienny wynosi 1000 tokenów.")
        )).isEmpty())
        assertTrue(ReportConflictDetector.detect(listOf(
            ReportConflictDetector.Report("a", "Limit dzienny wynosi 1000 tokenów.\nLimit dzienny wynosi 2000 tokenów.")
        )).isEmpty())
    }

    @Test fun instructionDoesNotClaimFullVerificationWhenNoConflict() {
        val instruction = ReportConflictDetector.toMasterInstruction(emptyList())
        assertTrue(instruction.contains("nie jest dowód pełnej zgodności"))
    }
}
