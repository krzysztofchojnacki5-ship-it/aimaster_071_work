package pl.aimasterpro.app.agents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SynthesisContextBuilderTest {
    @Test fun failedAndBlockedRunsAreNeverPassedToSynthesis() {
        val result = SynthesisContextBuilder.build(listOf(
            TeamTaskResult("1", "coder", TeamTaskResult.Status.COMPLETED, "Ustalenie poprawne"),
            TeamTaskResult("2", "reviewer", TeamTaskResult.Status.FAILED, "HTTP 429 API ERROR"),
            TeamTaskResult("3", "tester", TeamTaskResult.Status.BLOCKED, "BRAK KONFIGURACJI")
        ))
        assertTrue(result.text.contains("Ustalenie poprawne"))
        assertTrue("Synthesis context must preserve task identity", result.text.contains("coder • 1"))
        assertFalse(result.text.contains("HTTP 429"))
        assertFalse(result.text.contains("BRAK KONFIGURACJI"))
        assertEquals(1, result.includedReports)
        assertEquals(2, result.excludedReports)
    }

    @Test fun synthesisContextIsBoundedAndReportsAreFairlyTruncated() {
        val results = (1..4).map { index ->
            TeamTaskResult("$index", "agent-$index", TeamTaskResult.Status.COMPLETED, "x".repeat(12_000))
        }
        val packed = SynthesisContextBuilder.build(results, maxPerReportChars = 6_000, maxTotalChars = 8_000)
        assertTrue(packed.text.length <= 8_000)
        assertEquals(4, packed.includedReports)
        assertTrue(packed.truncatedReports > 0)
        assertTrue(packed.text.contains("agent-1"))
        assertTrue(packed.text.contains("agent-4"))
    }

    @Test fun peerReviewIsExplicitlyAttributedToItsOriginalReportAuthor() {
        val packed = SynthesisContextBuilder.build(listOf(
            TeamTaskResult("review-1", "critical-reviewer", TeamTaskResult.Status.COMPLETED,
                "W raporcie brakuje dowodu.", reviewOfAgentId = "app-architect")
        ))
        assertTrue(packed.text.contains("Peer Review: critical-reviewer → app-architect"))
        assertTrue(packed.text.contains("W raporcie brakuje dowodu."))
    }

    @Test fun emptySuccessfulReportSetProducesEmptyContext() {
        val packed = SynthesisContextBuilder.build(listOf(
            TeamTaskResult("1", "coder", TeamTaskResult.Status.FAILED, "timeout")
        ))
        assertEquals("", packed.text)
        assertEquals(0, packed.includedReports)
        assertEquals(1, packed.excludedReports)
    }
}
