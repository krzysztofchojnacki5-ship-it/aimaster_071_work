package pl.aimasterpro.app.agents

import org.junit.Assert.*
import org.junit.Test

class PatchPeerReviewPlannerTest {
    private val reviewers = listOf(
        AgentSpec("critical-reviewer", "Critical Reviewer", "QA", "Krytyczna recenzja", listOf("review")),
        AgentSpec("systems-architect", "Systems Architect", "Architecture", "Architektura", listOf("system")),
        AgentSpec("repository-researcher", "Repository Researcher", "Research", "Repozytorium", listOf("repo"))
    )

    @Test fun createsTwoTasksForDistinctAgentsAndModelProfiles() {
        val tasks = PatchPeerReviewPlanner.createTasks(
            "VERDICT: APPROVE\n--- DIFF ---", "src/Foo.kt",
            listOf(
                PatchPeerReviewPlanner.Assignment(reviewers[0], "groq:model-a"),
                PatchPeerReviewPlanner.Assignment(reviewers[1], "openrouter:model-b")
            ), "request-1"
        )
        assertEquals(2, tasks.size)
        assertNotEquals(tasks[0].agentId, tasks[1].agentId)
        assertNotEquals(tasks[0].providerFingerprint, tasks[1].providerFingerprint)
        assertTrue(tasks.all { it.prompt.contains("VERDICT: APPROVE") })
    }

    @Test fun refusesSameModelDisguisedAsTwoIndependentReviewers() {
        val tasks = PatchPeerReviewPlanner.createTasks(
            "review prompt", "src/Foo.kt",
            listOf(
                PatchPeerReviewPlanner.Assignment(reviewers[0], "groq:model-a"),
                PatchPeerReviewPlanner.Assignment(reviewers[1], "GROQ:MODEL-A")
            )
        )
        assertTrue(tasks.isEmpty())
    }

    @Test fun ignoresDuplicateAgentAndBlankProvider() {
        val tasks = PatchPeerReviewPlanner.createTasks(
            "review prompt", "src/Foo.kt",
            listOf(
                PatchPeerReviewPlanner.Assignment(reviewers[0], ""),
                PatchPeerReviewPlanner.Assignment(reviewers[0], "provider:model-a"),
                PatchPeerReviewPlanner.Assignment(reviewers[1], "provider:model-b")
            )
        )
        assertEquals(2, tasks.size)
        assertEquals(2, tasks.map { it.agentId }.distinct().size)
    }

    @Test fun refusesIncompleteInputAndInvalidReviewerLimit() {
        assertTrue(PatchPeerReviewPlanner.createTasks("", "src/Foo.kt", emptyList()).isEmpty())
        assertThrows(IllegalArgumentException::class.java) {
            PatchPeerReviewPlanner.createTasks("review", "src/Foo.kt", emptyList(), maxReviewers = 4)
        }
    }
}
