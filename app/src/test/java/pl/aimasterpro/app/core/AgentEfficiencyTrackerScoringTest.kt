package pl.aimasterpro.app.core

import org.junit.Assert.*
import org.junit.Test

class AgentEfficiencyTrackerScoringTest {
    private fun row(task: String, status: String) = AgentEfficiencyTracker.Outcome(
        timestamp = 1L, taskId = task, agentId = "agent-a", status = status,
        durationMs = 100L, attempts = 1, providerId = "p", model = "m", errorCategory = ""
    )

    @Test fun reliabilityRequiresMinimumIndependentTasks() {
        assertNull(AgentEfficiencyTracker.reliabilityScore((1..7).map { row("t$it", "COMPLETED") }, "agent-a"))
    }

    @Test fun reliabilityDeduplicatesTaskIdsAndUsesSmoothedCompletionRate() {
        val rows = (1..8).map { row("t$it", if (it <= 6) "COMPLETED" else "FAILED") } + row("t1", "FAILED")
        val score = AgentEfficiencyTracker.reliabilityScore(rows, "agent-a")
        assertNotNull(score)
        assertEquals(8.0 / 12.0, score!!, 0.0001)
    }

    @Test fun reliabilityIsScopedToAgent() {
        assertNull(AgentEfficiencyTracker.reliabilityScore((1..8).map { row("t$it", "COMPLETED") }, "agent-b"))
    }
}
