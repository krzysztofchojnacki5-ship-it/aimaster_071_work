package pl.aimasterpro.app.agents

/**
 * Deterministic peer-review assignment. Keeps the review round small, prevents self-review,
 * and spreads reviews across distinct reviewers whenever the selected team permits it.
 */
object PeerReviewPlanner {
    fun createTasks(
        completedReports: List<TeamTaskResult>,
        selectedAgents: List<AgentSpec>,
        chatId: String,
        requestId: String,
        originalPrompt: String,
        maxReviews: Int = 2,
        reviewerQualityScore: (String) -> Double? = { _ -> null },
        reviewerQualityScoreForCategory: ((String, String) -> Double?)? = null,
        reviewCategoryForTarget: (String) -> String = { "" }
    ): List<TeamTask> {
        require(maxReviews >= 0) { "maxReviews nie może być ujemne" }
        if (maxReviews == 0) return emptyList()
        val agents = selectedAgents.distinctBy { it.id }
        if (agents.size < 2) return emptyList()
        val reports = completedReports.filter {
            it.status == TeamTaskResult.Status.COMPLETED && it.report.isNotBlank() && it.reviewOfAgentId.isBlank()
        }.distinctBy { it.agentId }
        val usedReviewers = mutableSetOf<String>()
        return reports.take(maxReviews).mapIndexedNotNull { index, target ->
            val category = runCatching { reviewCategoryForTarget(target.agentId) }.getOrNull().orEmpty().trim()
            val rankedAgents = agents.mapIndexed { agentIndex, agent ->
                val categoryScore = runCatching { reviewerQualityScoreForCategory?.invoke(agent.id, category) }.getOrNull()
                val score = (categoryScore ?: runCatching { reviewerQualityScore(agent.id) }.getOrNull())
                    ?.takeIf { it.isFinite() && it in 0.0..1.0 } ?: 0.5
                Triple(agentIndex, agent, score)
            }.sortedWith(compareByDescending<Triple<Int, AgentSpec, Double>> { it.third }.thenBy { it.first })
                .map { it.second }
            val reviewer = rankedAgents.firstOrNull { it.id != target.agentId && it.id !in usedReviewers }
                ?: rankedAgents.firstOrNull { it.id != target.agentId }
                ?: return@mapIndexedNotNull null
            usedReviewers += reviewer.id
            TeamTask(
                id = "peer-review-$chatId-$requestId-${index + 1}-${reviewer.id}-${target.agentId}",
                agentId = reviewer.id,
                goal = "Peer Review: ${reviewer.name} recenzuje raport agenta ${target.agentId}",
                prompt = """
                    Zadanie pierwotne użytkownika:
                    $originalPrompt

                    Specjalizacja ocenianego raportu: ${category.ifBlank { "ogólna" }}
                    Raport do recenzji — autor: ${target.agentId}
                    --- POCZĄTEK RAPORTU ---
                    ${target.report}
                    --- KONIEC RAPORTU ---

                    Przeprowadź niezależną, krytyczną recenzję. Nie przepisuj raportu.
                    Wskaż: (1) poprawne i dobrze uzasadnione ustalenia, (2) błędy lub nieścisłości,
                    (3) pominięte ryzyka i brakujące dowody, (4) konkretne poprawki.
                    Jeśli nie możesz czegoś zweryfikować, napisz to wprost. Nie wymyślaj źródeł.
                    Zwróć zwięzły raport recenzencki do MASTER-a.
                """.trimIndent(),
                writeScope = emptyList(),
                reviewOfAgentId = target.agentId
            )
        }
    }
}
