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
    @Test fun disabledCatalogAgentCannotBeSelectedByRouter() {
        val enabled = AgentCatalog.all.filterNot { it.id == "mobile-engineer" }
        val result = AgentRoutingPolicy.route("Sprawdź aplikację Android i popraw błąd", catalog = enabled)
        assertTrue(result.agents.none { it.id == "mobile-engineer" })
    }

    @Test fun enabledCustomAgentCanBeSelectedByTheSameRouterAsBuiltInAgents() {
        val custom = AgentSpec(
            id = "custom-security-auditor",
            name = "Własny audytor bezpieczeństwa",
            category = "Jakość, bezpieczeństwo i zgodność",
            responsibility = "Audytuje bezpieczeństwo aplikacji.",
            keywords = listOf("bezpieczeństwo", "klucz api", "sekret")
        )
        val result = AgentRoutingPolicy.route(
            "Sprawdź bezpieczeństwo i klucze API w aplikacji",
            catalog = listOf(custom)
        )
        assertEquals(Mode.ONE_SPECIALIST, result.mode)
        assertEquals(custom.id, result.agents.single().id)
    }

    @Test fun explicitTeamFallsBackWhenTooFewAgentsAreEnabled() {
        val onlyOne = AgentCatalog.all.filter { it.id == "critical-reviewer" }
        val result = AgentRoutingPolicy.route("Uruchom cały zespół i przygotuj niezależne opinie", catalog = onlyOne)
        assertEquals(AgentRoutingPolicy.Mode.MASTER_ONLY, result.mode)
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
