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
}
