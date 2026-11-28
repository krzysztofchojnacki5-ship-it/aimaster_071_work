package pl.aimasterpro.app.core

/**
 * Fail-closed persistence boundary for the MASTER queue's TeamRunEngine results.
 *
 * The queue must not report a finished execution until every assigned agent has a
 * durable terminal result. Missing worker results become explicit failures; malformed
 * or duplicate result identities are rejected before any result is written.
 */
object QueueExecutionResultFinalizer {
    enum class Status { COMPLETED, FAILED, BLOCKED }

    data class AgentResult(
        val agentId: String,
        val status: Status,
        val detail: String
    )

    data class Outcome(
        val success: Boolean,
        val failedAgentIds: List<String> = emptyList(),
        val error: String = ""
    )

    fun persistBudgetBlockedResults(
        assignedAgentIds: List<String>,
        blockedReasons: Map<String, String>,
        persist: (AgentResult) -> Boolean
    ): Outcome {
        if (!validIds(assignedAgentIds) || blockedReasons.keys.any { it !in assignedAgentIds } ||
            blockedReasons.values.any { it.isBlank() }) {
            return Outcome(false, error = "INVALID_BUDGET_RESULT_SET")
        }

        val failed = mutableListOf<String>()
        assignedAgentIds.filter { it in blockedReasons }.forEach { agentId ->
            val result = AgentResult(
                agentId = agentId,
                status = Status.BLOCKED,
                detail = blockedReasons.getValue(agentId).take(500)
            )
            if (!runCatching { persist(result) }.getOrDefault(false)) failed += agentId
        }
        return Outcome(failed.isEmpty(), failed)
    }

    fun persistAllowedResults(
        allowedAgentIds: List<String>,
        results: List<AgentResult>,
        persist: (AgentResult) -> Boolean
    ): Outcome {
        if (allowedAgentIds.isEmpty()) {
            return if (results.isEmpty()) Outcome(true)
            else Outcome(false, error = "INVALID_ALLOWED_AGENT_SET")
        }
        if (!validIds(allowedAgentIds)) {
            return Outcome(false, error = "INVALID_ALLOWED_AGENT_SET")
        }
        val resultIds = results.map { it.agentId }
        if (results.any { it.agentId.isBlank() || it.detail.isBlank() } ||
            resultIds.distinct().size != resultIds.size ||
            resultIds.any { it !in allowedAgentIds }) {
            return Outcome(false, error = "INVALID_ENGINE_RESULT_SET")
        }

        val byAgent = results.associateBy { it.agentId }
        val failed = mutableListOf<String>()
        allowedAgentIds.forEach { agentId ->
            val result = byAgent[agentId] ?: AgentResult(
                agentId = agentId,
                status = Status.FAILED,
                detail = "TeamRunEngine nie zwrócił wyniku dla tego agenta."
            )
            if (!runCatching { persist(result) }.getOrDefault(false)) failed += agentId
        }
        return Outcome(failed.isEmpty(), failed)
    }

    private fun validIds(ids: List<String>): Boolean =
        ids.isNotEmpty() && ids.all { it.isNotBlank() } && ids.distinct().size == ids.size
}
