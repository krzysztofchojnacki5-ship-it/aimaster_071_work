package pl.aimasterpro.app.core

import android.content.Context
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class QualityLearningStoreTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("ai_quality_learning_v1", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("ai_master_memory_store_v1", Context.MODE_PRIVATE).edit().remove("memories").commit()
    }
    @After fun tearDown() {
        context.getSharedPreferences("ai_quality_learning_v1", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("ai_master_memory_store_v1", Context.MODE_PRIVATE).edit().remove("memories").commit()
    }

    @Test fun ratingCanBeChangedWithoutDoubleCountingAndKeepsAuditHistory() {
        QualityLearningStore.rate(context, "task-1", "AGENT", 1, "Pomógł", "coder", "groq", "model-a")
        QualityLearningStore.rate(context, "task-1", "AGENT", -1, "Po poprawce jednak błędny", "coder", "groq", "model-a")
        val rows = QualityLearningStore.feedback(context)
        assertEquals(1, rows.size)
        assertEquals(-1, rows.single().rating)
        assertEquals(1, rows.single().history.size)
        assertEquals("Pomógł", rows.single().history.single().comment)
        assertEquals(1, rows.single().history.single().rating)
        assertEquals(1, QualityLearningStore.summary(context).total)
    }

    @Test fun proposalsOnlyEnterSharedMemoryAfterApproval() {
        val proposal = QualityLearningStore.addProposal(context, "Preferuj model A dla kodu", 8, "wstępna", listOf("t1"), listOf("coder"), listOf("groq/model-a"))
        assertFalse(AiMemoryStore.getMemories(context).any { it.contains("Preferuj model A") })
        assertTrue(QualityLearningStore.setProposalStatus(context, proposal.id, "APPROVED"))
        assertTrue(AiMemoryStore.getMemories(context).any { it.contains("Preferuj model A") })
        assertEquals("APPROVED", QualityLearningStore.proposals(context).single().status)
    }

    @Test fun revokingApprovalRemovesThePreviouslyInjectedRule() {
        val proposal = QualityLearningStore.addProposal(context, "Reguła do cofnięcia", 6, "wstępna", emptyList(), emptyList(), emptyList())
        QualityLearningStore.setProposalStatus(context, proposal.id, "APPROVED")
        assertTrue(AiMemoryStore.getMemories(context).any { it.contains("Reguła do cofnięcia") })
        QualityLearningStore.setProposalStatus(context, proposal.id, "REJECTED")
        assertFalse(AiMemoryStore.getMemories(context).any { it.contains("Reguła do cofnięcia") })
    }

    @Test fun rejectedProposalNeverEntersMemory() {
        val proposal = QualityLearningStore.addProposal(context, "Zła reguła", 2, "niska", emptyList(), emptyList(), emptyList())
        QualityLearningStore.setProposalStatus(context, proposal.id, "REJECTED")
        assertFalse(AiMemoryStore.getMemories(context).any { it.contains("Zła reguła") })
    }
    @Test fun malformedFeedbackJournalIsNotOverwrittenByNewRating() {
        val prefs = context.getSharedPreferences("ai_quality_learning_v1", Context.MODE_PRIVATE)
        val corrupt = """[{"id":"rating-1","targetId":"task-1","targetType":"AGENT"},null]"""
        assertTrue(prefs.edit().putString("feedback", corrupt).commit())
        QualityLearningStore.rate(context, "task-2", "AGENT", 1, "Nowa ocena", "coder")
        assertEquals(corrupt, prefs.getString("feedback", null))
    }

    @Test fun malformedProposalJournalBlocksMutationWithoutReplacingEvidence() {
        val prefs = context.getSharedPreferences("ai_quality_learning_v1", Context.MODE_PRIVATE)
        val corrupt = """[{"id":"proposal-1","text":"old proposal"},null]"""
        assertTrue(prefs.edit().putString("proposals", corrupt).commit())
        assertFalse(QualityLearningStore.setProposalStatus(context, "proposal-1", "APPROVED"))
        assertTrue(runCatching {
            QualityLearningStore.addProposal(context, "new proposal", 1, "low", emptyList(), emptyList(), emptyList())
        }.isFailure)
        assertEquals(corrupt, prefs.getString("proposals", null))
    }

    @Test fun sameTargetIdCanHaveDifferentTargetTypes() {
        QualityLearningStore.rate(context, "shared-id", "AGENT", 1, "Raport agenta", "coder", "groq", "model-a")
        QualityLearningStore.rate(context, "shared-id", "CHAT_ANSWER", -1, "Odpowiedź końcowa", "MASTER", "openrouter", "model-b")
        val rows = QualityLearningStore.feedback(context)
        assertEquals(2, rows.size)
        assertTrue(rows.any { it.targetType == "AGENT" && it.rating == 1 })
        assertTrue(rows.any { it.targetType == "CHAT_ANSWER" && it.rating == -1 })
    }

    @Test fun reratingWithoutMetadataPreservesOriginalAgentAndModel() {
        QualityLearningStore.rate(context, "task-meta", "AGENT", 1, "Dobra odpowiedź", "coder", "groq", "model-a", "code")
        QualityLearningStore.rate(context, "task-meta", "AGENT", -1, "Po weryfikacji błąd")
        val row = QualityLearningStore.feedback(context).single()
        assertEquals("coder", row.agentId)
        assertEquals("groq", row.providerId)
        assertEquals("model-a", row.model)
        assertEquals("code", row.category)
    }

    @Test fun analysisContextIsBoundedAndDoesNotIncludeFullLongComments() {
        repeat(30) { index ->
            QualityLearningStore.rate(context, "task-$index", "AGENT", if (index % 2 == 0) 1 else -1,
                "X".repeat(1000), "coder", "groq", "model-a")
        }
        val analysis = QualityLearningStore.analysisContext(context)
        assertTrue(analysis.length <= 12000)
        assertFalse(analysis.contains("X".repeat(500)))
    }

    @Test fun learnedQualityRequiresFiveIndependentAgentTasks() {
        repeat(4) { index ->
            QualityLearningStore.rate(context, "cold-$index", "AGENT", -1, "", "coder", "groq", "model-a", "Inżynieria")
        }
        var rows = QualityLearningStore.feedback(context)
        assertNull(QualityLearningStore.qualityScore(rows, "coder", "Inżynieria"))
        QualityLearningStore.rate(context, "cold-4", "AGENT", -1, "", "coder", "groq", "model-a", "Inżynieria")
        rows = QualityLearningStore.feedback(context)
        assertNotNull(QualityLearningStore.qualityScore(rows, "coder", "Inżynieria"))
        assertTrue(QualityLearningStore.qualityScore(rows, "coder", "Inżynieria")!! < 0.5)
    }

    @Test fun duplicateRatingForSameTaskDoesNotIncreaseLearningSampleCount() {
        repeat(4) { index ->
            QualityLearningStore.rate(context, "same-$index", "AGENT", 1, "", "coder", "groq", "model-a", "Inżynieria")
        }
        QualityLearningStore.rate(context, "same-0", "AGENT", -1, "Zmiana oceny", "coder", "groq", "model-a", "Inżynieria")
        assertNull(QualityLearningStore.qualityScore(QualityLearningStore.feedback(context), "coder", "Inżynieria"))
    }

    @Test fun chatAnswerRatingsDoNotPolluteSpecialistAgentRanking() {
        QualityLearningStore.rate(context, "agent-task", "AGENT", 1, "Raport dobry", "coder", "groq", "model-a", "Inżynieria")
        QualityLearningStore.rate(context, "chat-task", "CHAT_ANSWER", -1, "Synteza słaba", "MASTER", "openrouter", "model-b", "Inżynieria")
        val summary = QualityLearningStore.summary(context)
        assertEquals(2, summary.total)
        assertEquals(1, summary.ratedAgents)
        val analysis = QualityLearningStore.analysisContext(context)
        val agentSection = analysis.substringAfter("OCENY UŻYTKOWNIKA — AGENT:").substringBefore("OCENY UŻYTKOWNIKA — MODEL/PROVIDER:")
        assertTrue(agentSection.contains("coder"))
        assertFalse(agentSection.contains("MASTER"))
    }

}
