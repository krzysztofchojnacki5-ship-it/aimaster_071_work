package pl.aimasterpro.app.agents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerReviewPlannerTest {
    private fun agent(id: String) = AgentSpec(id, id.uppercase(), "test", "test scope", emptyList())
    private fun report(id: String) = TeamTaskResult("task-$id", id, TeamTaskResult.Status.COMPLETED, "Report from $id")

    @Test
    fun assignsDifferentReviewersAndNeverSelfReviewsWhenTeamAllows() {
        val tasks = PeerReviewPlanner.createTasks(
            completedReports = listOf(report("agent-a"), report("agent-b")),
            selectedAgents = listOf(agent("agent-a"), agent("agent-b"), agent("agent-c")),
            chatId = "chat", requestId = "request", originalPrompt = "Inspect this", maxReviews = 2
        )
        assertEquals(2, tasks.size)
        assertTrue(tasks.all { it.agentId != it.reviewOfAgentId })
        assertEquals(2, tasks.map { it.agentId }.distinct().size)
        assertEquals(setOf("agent-a", "agent-b"), tasks.map { it.reviewOfAgentId }.toSet())
    }

    @Test
    fun twoAgentTeamCrossReviewsAndFailedReportsAreIgnored() {
        val tasks = PeerReviewPlanner.createTasks(
            completedReports = listOf(
                report("agent-a"),
                TeamTaskResult("failed", "agent-b", TeamTaskResult.Status.FAILED, "not a report")
            ),
            selectedAgents = listOf(agent("agent-a"), agent("agent-b")),
            chatId = "chat", requestId = "request", originalPrompt = "Inspect this"
        )
        assertEquals(1, tasks.size)
        assertEquals("agent-b", tasks.single().agentId)
        assertEquals("agent-a", tasks.single().reviewOfAgentId)
        assertTrue(tasks.single().prompt.contains("Report from agent-a"))
    }

    @Test
    fun doesNotCreateReviewForOneAgentOrEmptyReport() {
        assertTrue(PeerReviewPlanner.createTasks(
            listOf(report("agent-a")), listOf(agent("agent-a")), "c", "r", "p"
        ).isEmpty())
        assertTrue(PeerReviewPlanner.createTasks(
            listOf(TeamTaskResult("empty", "agent-a", TeamTaskResult.Status.COMPLETED, " ")),
            listOf(agent("agent-a"), agent("agent-b")), "c", "r", "p"
        ).isEmpty())
    }
    @Test fun userRatedReviewQualityPrioritizesReviewersWithoutAllowingSelfReview() {
        val scores = mapOf("agent-a" to 0.99, "agent-b" to 0.95, "agent-c" to 0.80, "agent-d" to 0.60)
        val tasks = PeerReviewPlanner.createTasks(
            completedReports = listOf(report("agent-a"), report("agent-b")),
            selectedAgents = listOf(agent("agent-a"), agent("agent-b"), agent("agent-c"), agent("agent-d")),
            chatId = "chat", requestId = "rated", originalPrompt = "Inspect", maxReviews = 2,
            reviewerQualityScore = { scores[it] }
        )
        assertEquals(listOf("agent-b", "agent-a"), tasks.map { it.agentId })
        assertTrue(tasks.all { it.agentId != it.reviewOfAgentId })
        assertEquals(2, tasks.map { it.agentId }.distinct().size)
    }

    @Test fun missingOrInvalidReviewerScoresKeepStableInputOrder() {
        val tasks = PeerReviewPlanner.createTasks(
            completedReports = listOf(report("agent-a")),
            selectedAgents = listOf(agent("agent-a"), agent("agent-b"), agent("agent-c")),
            chatId = "chat", requestId = "neutral", originalPrompt = "Inspect",
            reviewerQualityScore = { Double.NaN }
        )
        assertEquals("agent-b", tasks.single().agentId)
    }

    @Test fun reviewerRankingIsScopedToTheSpecializationOfEachReviewedReport() {
        val categories = mapOf("agent-a" to "code", "agent-b" to "research")
        val scores = mapOf(
            "code" to mapOf("agent-b" to 0.95, "agent-c" to 0.20, "agent-d" to 0.10),
            "research" to mapOf("agent-a" to 0.90, "agent-c" to 0.30, "agent-d" to 0.10)
        )
        val requestedCategories = mutableListOf<String>()
        val tasks = PeerReviewPlanner.createTasks(
            completedReports = listOf(report("agent-a"), report("agent-b")),
            selectedAgents = listOf(agent("agent-a"), agent("agent-b"), agent("agent-c"), agent("agent-d")),
            chatId = "chat", requestId = "category-ranked", originalPrompt = "Inspect", maxReviews = 2,
            reviewerQualityScoreForCategory = { reviewer, category ->
                requestedCategories += category
                scores[category]?.get(reviewer)
            },
            reviewCategoryForTarget = { categories[it].orEmpty() }
        )
        assertEquals(listOf("agent-b", "agent-a"), tasks.map { it.agentId })
        assertEquals(listOf("code", "research"), requestedCategories.distinct())
        assertTrue(tasks.all { it.agentId != it.reviewOfAgentId })
    }

    @Test fun categoryScoreFailureFallsBackToLegacyGlobalRanking() {
        val tasks = PeerReviewPlanner.createTasks(
            completedReports = listOf(report("agent-a")),
            selectedAgents = listOf(agent("agent-a"), agent("agent-b"), agent("agent-c")),
            chatId = "chat", requestId = "category-fallback", originalPrompt = "Inspect",
            reviewerQualityScore = { if (it == "agent-c") 0.9 else 0.1 },
            reviewerQualityScoreForCategory = { _, _ -> error("unavailable") }
        )
        assertEquals("agent-c", tasks.single().agentId)
    }

}
