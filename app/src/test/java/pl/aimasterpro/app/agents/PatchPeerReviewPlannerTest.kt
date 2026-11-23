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
    @Test fun higherRatedReviewersArePrioritizedWithoutReusingProviderProfiles() {
        val assignments = listOf(
            PatchPeerReviewPlanner.Assignment(reviewers[0], "groq:model-a"),
            PatchPeerReviewPlanner.Assignment(reviewers[1], "openrouter:model-b"),
            PatchPeerReviewPlanner.Assignment(reviewers[2], "GROQ:MODEL-A")
        )
        val scores = mapOf("critical-reviewer" to 0.20, "systems-architect" to 0.80, "repository-researcher" to 0.99)
        val tasks = PatchPeerReviewPlanner.createTasks(
            "review prompt", "src/Foo.kt", assignments, requestId = "quality-ranked",
            reviewerQualityScore = { scores[it] }
        )
        assertEquals(listOf("repository-researcher", "systems-architect"), tasks.map { it.agentId })
        assertEquals(2, tasks.map { it.providerFingerprint }.distinct().size)
    }

    @Test fun missingReviewerScoresPreserveOriginalAssignmentOrder() {
        val tasks = PatchPeerReviewPlanner.createTasks(
            "review prompt", "src/Foo.kt",
            listOf(
                PatchPeerReviewPlanner.Assignment(reviewers[0], "provider:model-a"),
                PatchPeerReviewPlanner.Assignment(reviewers[1], "provider:model-b")
            ), requestId = "neutral-order", reviewerQualityScore = { null }
        )
        assertEquals(listOf("critical-reviewer", "systems-architect"), tasks.map { it.agentId })
    }

    @Test fun categoryAwareRankingUsesPathDomainAndKeepsProviderIndependence() {
        val assignments = listOf(
            PatchPeerReviewPlanner.Assignment(reviewers[0], "provider:a"),
            PatchPeerReviewPlanner.Assignment(reviewers[1], "provider:b"),
            PatchPeerReviewPlanner.Assignment(reviewers[2], "provider:c")
        )
        val observed = mutableListOf<String>()
        val tasks = PatchPeerReviewPlanner.createTasks(
            "review prompt", "src/Engine.KT", assignments, requestId = "typed-patch",
            reviewerQualityScoreForCategory = { reviewerId, category ->
                observed += category
                when (category) {
                    "patch:kt" -> mapOf("critical-reviewer" to 0.2, "systems-architect" to 0.95, "repository-researcher" to 0.8)[reviewerId]
                    else -> null
                }
            }
        )
        assertEquals("patch:kt", PatchPeerReviewPlanner.qualityCategoryForPath("src/Engine.KT"))
        assertEquals("patch:unknown", PatchPeerReviewPlanner.qualityCategoryForPath("README"))
        assertEquals(listOf("systems-architect", "repository-researcher"), tasks.map { it.agentId })
        assertTrue(observed.all { it == "patch:kt" })
        assertEquals(2, tasks.map { it.providerFingerprint }.distinct().size)
        assertTrue(tasks.all { it.prompt.contains("Kategoria jakości: patch:kt") })
    }

}
