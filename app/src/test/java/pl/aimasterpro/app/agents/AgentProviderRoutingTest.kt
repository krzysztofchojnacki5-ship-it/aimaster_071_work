package pl.aimasterpro.app.agents

import org.junit.Assert.assertEquals
import org.junit.Test
import pl.aimasterpro.app.core.AgentRole

class AgentProviderRoutingTest {
    @Test fun diagnosticsUseDebuggerProfile() {
        val agent = AgentCatalog.find("diagnostics-lead")!!
        assertEquals(AgentRole.DEBUGGER, AgentProviderRouting.roleFor(agent))
    }

    @Test fun mobileEngineeringUsesDeveloperProfile() {
        val agent = AgentCatalog.find("mobile-engineer")!!
        assertEquals(AgentRole.DEVELOPER, AgentProviderRouting.roleFor(agent))
    }

    @Test fun qualityAgentsUseReviewerProfile() {
        val agent = AgentCatalog.find("qa-architect")!!
        assertEquals(AgentRole.REVIEWER, AgentProviderRouting.roleFor(agent))
    }

    @Test fun documentationAgentsUseWriterProfile() {
        val agent = AgentCatalog.find("technical-writer")!!
        assertEquals(AgentRole.WRITER, AgentProviderRouting.roleFor(agent))
    }

    @Test fun researchAgentsUseResearcherProfile() {
        val agent = AgentCatalog.find("api-researcher")!!
        assertEquals(AgentRole.RESEARCHER, AgentProviderRouting.roleFor(agent))
    }
}
