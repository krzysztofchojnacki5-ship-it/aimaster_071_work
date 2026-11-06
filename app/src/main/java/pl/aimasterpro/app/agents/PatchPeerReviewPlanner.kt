package pl.aimasterpro.app.agents

import java.util.UUID

/**
 * Creates a small, deterministic patch-review round. A reviewer is eligible only when
 * its provider fingerprint is configured and differs from every other selected reviewer.
 * This prevents two nominally different agents on the same model profile being presented
 * as two independent model reviews.
 */
object PatchPeerReviewPlanner {
    data class Assignment(val agent: AgentSpec, val providerFingerprint: String)
    data class ReviewTask(
        val id: String,
        val agentId: String,
        val goal: String,
        val prompt: String,
        val providerFingerprint: String
    )

    fun createTasks(
        reviewerPrompt: String,
        path: String,
        assignments: List<Assignment>,
        requestId: String = UUID.randomUUID().toString(),
        maxReviewers: Int = 2
    ): List<ReviewTask> {
        require(maxReviewers in 1..3) { "maxReviewers musi należeć do zakresu 1..3" }
        if (reviewerPrompt.isBlank() || path.isBlank()) return emptyList()

        val chosen = mutableListOf<Assignment>()
        val agentIds = mutableSetOf<String>()
        val providerIds = mutableSetOf<String>()
        assignments.forEach { assignment ->
            val agentId = assignment.agent.id.trim()
            val providerId = assignment.providerFingerprint.trim().lowercase()
            if (agentId.isBlank() || providerId.isBlank()) return@forEach
            if (!agentIds.add(agentId)) return@forEach
            if (!providerIds.add(providerId)) return@forEach
            chosen += assignment.copy(providerFingerprint = providerId)
            if (chosen.size == maxReviewers) return@forEach
        }
        if (chosen.size < 2) return emptyList()

        return chosen.mapIndexed { index, assignment ->
            ReviewTask(
                id = "patch-review-$requestId-${index + 1}-${assignment.agent.id}",
                agentId = assignment.agent.id,
                goal = "Niezależny audyt patcha ${path.take(180)}",
                prompt = buildString {
                    appendLine("Wykonaj niezależny audyt poniższej poprawki. Nie zakładaj, że autor ma rację.")
                    appendLine("Recenzent: ${assignment.agent.name} (${assignment.agent.id})")
                    appendLine("Wymagane: dowody, konkretne problemy, brakujące testy i jeden ścisły werdykt.")
                    appendLine("Nie uruchamiaj, nie stosuj ani nie zatwierdzaj patcha.")
                    appendLine()
                    append(reviewerPrompt)
                },
                providerFingerprint = assignment.providerFingerprint
            )
        }
    }
}
