package pl.aimasterpro.app.agents

/**
 * Deterministic, side-effect-free assignment planner for MASTER.
 *
 * Provider readiness and enabled-agent state are supplied as verified snapshots by the
 * coordinator. This class never guesses provider health and never persists assignments.
 */
object MasterAgentCoordinator {
    data class AgentLoad(
        val activeTasks: Int = 0,
        val maxActiveTasks: Int = 1
    )

    data class Candidate(
        val agent: AgentSpec,
        val skillScore: Int,
        val activeTasks: Int,
        val totalScore: Int
    )

    data class Plan(
        val selectedAgents: List<AgentSpec>,
        val rankedCandidates: List<Candidate>,
        val rejectedAgentIds: Map<String, String>,
        val reason: String
    ) {
        val isAssignable: Boolean get() = selectedAgents.isNotEmpty()
    }

    fun plan(
        objective: String,
        catalog: List<AgentSpec>,
        enabledAgentIds: Set<String>,
        providerReadyAgentIds: Set<String>,
        loads: Map<String, AgentLoad> = emptyMap(),
        maxAgents: Int = 3
    ): Plan {
        if (objective.isBlank() || objective.length > 1_200 || catalog.isEmpty() ||
            enabledAgentIds.isEmpty() || maxAgents !in 1..8) {
            return Plan(emptyList(), emptyList(), emptyMap(), "INVALID_INPUT")
        }

        val ranked = AgentCatalog.scoreForPrompt(objective, catalog)
        val rejected = linkedMapOf<String, String>()
        val eligible = mutableListOf<Candidate>()

        ranked.forEach { match ->
            val id = match.agent.id
            val load = loads[id] ?: AgentLoad()
            when {
                id !in enabledAgentIds -> rejected[id] = "AGENT_DISABLED"
                id !in providerReadyAgentIds -> rejected[id] = "PROVIDER_NOT_READY"
                load.activeTasks < 0 || load.maxActiveTasks !in 1..8 ->
                    rejected[id] = "INVALID_WORKLOAD_SNAPSHOT"
                load.activeTasks >= load.maxActiveTasks -> rejected[id] = "AGENT_AT_CAPACITY"
                else -> {
                    // Skill evidence dominates; load is a deterministic tie-breaker/penalty.
                    val score = match.keywordScore * 100 - load.activeTasks * 15
                    eligible += Candidate(match.agent, match.keywordScore, load.activeTasks, score)
                }
            }
        }

        val ordered = eligible.sortedWith(
            compareByDescending<Candidate> { it.totalScore }
                .thenBy { it.activeTasks }
                .thenBy { it.agent.id }
        )
        val selected = ordered.take(maxAgents).map { it.agent }
        val reason = when {
            selected.isNotEmpty() -> "READY"
            ranked.isEmpty() -> "NO_SKILL_MATCH"
            rejected.values.any { it == "PROVIDER_NOT_READY" } -> "NO_PROVIDER_READY_AGENT"
            rejected.values.any { it == "AGENT_AT_CAPACITY" } -> "ALL_MATCHING_AGENTS_AT_CAPACITY"
            else -> "NO_ELIGIBLE_AGENT"
        }
        return Plan(selected, ordered, rejected, reason)
    }
}
