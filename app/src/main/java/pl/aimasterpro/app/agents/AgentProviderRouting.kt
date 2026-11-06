package pl.aimasterpro.app.agents

import pl.aimasterpro.app.core.AgentRole

/** Maps catalog specialists to the user's existing role-level provider assignments.
 * It never selects a provider itself; MainActivity validates the saved profile and
 * applies the free-model guard before passing it to TeamRunEngine.
 */
object AgentProviderRouting {
    fun roleFor(agent: AgentSpec): AgentRole {
        val id = agent.id.lowercase()
        val category = agent.category.lowercase()
        return when {
            id.contains("diagnos") || id.contains("incident") || id.contains("debug") ||
                id.contains("reliability") || id == "support-analyst" -> AgentRole.DEBUGGER
            category.contains("badania") || id.contains("research") || id.contains("verifier") ||
                id.contains("benchmark") || id.contains("analyst") -> AgentRole.RESEARCHER
            category.contains("jakość") || category.contains("jakosc") ||
                id.contains("review") || id.contains("auditor") || id.contains("security") ||
                id.contains("test") || id.contains("qa") -> AgentRole.REVIEWER
            category.contains("inżynieria") || category.contains("inzynieria") ||
                id.contains("engineer") || id.contains("architect") || id.contains("database") ||
                id.contains("migration") -> AgentRole.DEVELOPER
            id.contains("writer") || id.contains("editor") || id.contains("localization") ||
                id.contains("docs") -> AgentRole.WRITER
            category.contains("operacje") || category.contains("koordynacja") -> AgentRole.RESEARCHER
            else -> AgentRole.ASSISTANT
        }
    }
}
