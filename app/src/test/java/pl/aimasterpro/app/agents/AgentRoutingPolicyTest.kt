package pl.aimasterpro.app.agents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRoutingPolicyTest {
    @Test fun casualGreetingDoesNotStartAgents() {
        val result = AgentRoutingPolicy.route("Hej, jak leci?")
        assertEquals(AgentRoutingPolicy.Mode.MASTER_ONLY, result.mode)
        assertTrue(result.agents.isEmpty())
    }

    @Test fun ordinaryKnowledgeQuestionDoesNotStartAgents() {
        val result = AgentRoutingPolicy.route("Co to jest token API?")
        assertEquals(AgentRoutingPolicy.Mode.MASTER_ONLY, result.mode)
        assertTrue(result.agents.isEmpty())
    }

    @Test fun technicalIssueStartsOnlyOneRelevantSpecialist() {
        val result = AgentRoutingPolicy.route("Sprawdź błąd kompilacji w Kotlinie")
        assertEquals(AgentRoutingPolicy.Mode.ONE_SPECIALIST, result.mode)
        assertEquals(1, result.agents.size)
    }

    @Test fun teamStartsOnlyWhenExplicitlyRequested() {
        val result = AgentRoutingPolicy.route("Uruchom cały zespół i przygotuj niezależne opinie o architekturze")
        assertEquals(AgentRoutingPolicy.Mode.TEAM, result.mode)
        assertEquals(3, result.agents.size)
    }

    @Test fun complexCrossDomainTaskCanUseSmallTeam() {
        val result = AgentRoutingPolicy.route("Zaprojektuj i zaimplementuj od zera aplikację Android, a następnie przetestuj architekturę")
        assertEquals(AgentRoutingPolicy.Mode.TEAM, result.mode)
        assertTrue(result.agents.size in 2..3)
    }
    @Test fun learnedQualityBreaksOnlyEqualKeywordMatches() {
        val result = AgentRoutingPolicy.route("zoptymalizuj model") { agentId, _ ->
            when (agentId) {
                "model-routing-specialist" -> 0.95
                "ai-engineer" -> 0.10
                else -> null
            }
        }
        assertEquals(AgentRoutingPolicy.Mode.ONE_SPECIALIST, result.mode)
        assertEquals("model-routing-specialist", result.agents.first().id)
    }

    @Test fun learnedQualityCannotOverrideStrongerKeywordMatch() {
        val result = AgentRoutingPolicy.route("zoptymalizuj token model") { agentId, _ ->
            if (agentId == "model-routing-specialist") 1.0 else 0.0
        }
        assertEquals(AgentRoutingPolicy.Mode.ONE_SPECIALIST, result.mode)
        assertEquals("ai-engineer", result.agents.first().id)
    }

}
