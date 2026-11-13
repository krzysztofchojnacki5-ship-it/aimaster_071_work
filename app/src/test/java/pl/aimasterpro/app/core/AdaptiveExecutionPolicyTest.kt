package pl.aimasterpro.app.core

import org.junit.Assert.*
import org.junit.Test

class AdaptiveExecutionPolicyTest {
    @Test fun simpleQuestionUsesOneAgentAndSmallBudget() {
        val p = AdaptiveExecutionPolicy.plan("Popraw literówkę w tym zdaniu", 4)
        assertEquals(1, p.maxAgents)
        assertEquals(1400L, p.outputTokensPerAgent)
    }
    @Test fun complexTaskGetsLargerTeamAndReserve() {
        val p = AdaptiveExecutionPolicy.plan("Zaprojektuj od zera architekturę Android Kotlin API, zaimplementuj i przetestuj bezpieczeństwo", 4)
        assertTrue(p.maxAgents >= 2)
        assertTrue(p.outputTokensPerAgent > 1400L)
        assertTrue(p.synthesisReserveTokens > 1800L)
    }
    @Test fun routedTeamKeepsAtLeastTwoAgentsForComplexTasks() {
        assertEquals(2, AdaptiveExecutionPolicy.plan("Pełny projekt wieloetapowy", 3, teamMode = true).maxAgents)
    }
    @Test fun explicitTeamRequestCanUseFourAgents() {
        assertEquals(4, AdaptiveExecutionPolicy.plan("Audyt kompleksowo", 4, true).maxAgents)
    }
}
