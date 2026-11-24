package pl.aimasterpro.app.core

import org.junit.Assert.*
import org.junit.Test

class QueueExecutionResultFinalizerTest {
    @Test fun budgetBlockedResultsArePersistedInAssignmentOrder() {
        val saved = mutableListOf<QueueExecutionResultFinalizer.AgentResult>()
        val outcome = QueueExecutionResultFinalizer.persistBudgetBlockedResults(
            assignedAgentIds = listOf("agent-a", "agent-b", "agent-c"),
            blockedReasons = mapOf("agent-c" to "limit C", "agent-a" to "limit A")
        ) { result ->
            saved += result
            true
        }

        assertTrue(outcome.success)
        assertEquals(listOf("agent-a", "agent-c"), saved.map { it.agentId })
        assertTrue(saved.all { it.status == QueueExecutionResultFinalizer.Status.BLOCKED })
    }

    @Test fun invalidBudgetAgentIsRejectedBeforeAnyPersistence() {
        var writes = 0
        val outcome = QueueExecutionResultFinalizer.persistBudgetBlockedResults(
            assignedAgentIds = listOf("agent-a"),
            blockedReasons = mapOf("foreign-agent" to "not assigned")
        ) {
            writes++
            true
        }

        assertFalse(outcome.success)
        assertEquals("INVALID_BUDGET_RESULT_SET", outcome.error)
        assertEquals(0, writes)
    }

    @Test fun missingEngineResultsBecomeExplicitFailures() {
        val saved = mutableListOf<QueueExecutionResultFinalizer.AgentResult>()
        val outcome = QueueExecutionResultFinalizer.persistAllowedResults(
            allowedAgentIds = listOf("agent-a", "agent-b"),
            results = listOf(
                QueueExecutionResultFinalizer.AgentResult(
                    "agent-a", QueueExecutionResultFinalizer.Status.COMPLETED, "raport A"
                )
            )
        ) { result ->
            saved += result
            true
        }

        assertTrue(outcome.success)
        assertEquals(listOf("agent-a", "agent-b"), saved.map { it.agentId })
        assertEquals(QueueExecutionResultFinalizer.Status.COMPLETED, saved[0].status)
        assertEquals(QueueExecutionResultFinalizer.Status.FAILED, saved[1].status)
    }

    @Test fun duplicateOrForeignEngineResultsAreRejectedBeforePersistence() {
        val duplicate = QueueExecutionResultFinalizer.AgentResult(
            "agent-a", QueueExecutionResultFinalizer.Status.COMPLETED, "raport"
        )
        var writes = 0
        val duplicateOutcome = QueueExecutionResultFinalizer.persistAllowedResults(
            listOf("agent-a", "agent-b"), listOf(duplicate, duplicate)
        ) {
            writes++
            true
        }
        val foreignOutcome = QueueExecutionResultFinalizer.persistAllowedResults(
            listOf("agent-a"),
            listOf(QueueExecutionResultFinalizer.AgentResult(
                "agent-b", QueueExecutionResultFinalizer.Status.COMPLETED, "foreign"
            ))
        ) {
            writes++
            true
        }

        assertFalse(duplicateOutcome.success)
        assertEquals("INVALID_ENGINE_RESULT_SET", duplicateOutcome.error)
        assertFalse(foreignOutcome.success)
        assertEquals("INVALID_ENGINE_RESULT_SET", foreignOutcome.error)
        assertEquals(0, writes)
    }

    @Test fun emptyAllowedSetIsANoOpOnlyWhenThereAreNoResults() {
        var writes = 0
        val empty = QueueExecutionResultFinalizer.persistAllowedResults(emptyList(), emptyList()) {
            writes++
            true
        }
        val unexpected = QueueExecutionResultFinalizer.persistAllowedResults(
            emptyList(),
            listOf(QueueExecutionResultFinalizer.AgentResult(
                "agent-a", QueueExecutionResultFinalizer.Status.COMPLETED, "unexpected"
            ))
        ) {
            writes++
            true
        }

        assertTrue(empty.success)
        assertFalse(unexpected.success)
        assertEquals(0, writes)
    }

    @Test fun persistenceFailuresAreAggregatedWithoutSkippingRemainingAgents() {
        val attempted = mutableListOf<String>()
        val outcome = QueueExecutionResultFinalizer.persistAllowedResults(
            allowedAgentIds = listOf("agent-a", "agent-b", "agent-c"),
            results = listOf(
                QueueExecutionResultFinalizer.AgentResult("agent-a", QueueExecutionResultFinalizer.Status.COMPLETED, "A"),
                QueueExecutionResultFinalizer.AgentResult("agent-b", QueueExecutionResultFinalizer.Status.BLOCKED, "B"),
                QueueExecutionResultFinalizer.AgentResult("agent-c", QueueExecutionResultFinalizer.Status.FAILED, "C")
            )
        ) { result ->
            attempted += result.agentId
            result.agentId != "agent-b"
        }

        assertFalse(outcome.success)
        assertEquals(listOf("agent-b"), outcome.failedAgentIds)
        assertEquals(listOf("agent-a", "agent-b", "agent-c"), attempted)
    }

    @Test fun retryableProviderFailuresRemainBlockedInsteadOfTerminalFailures() {
        val saved = mutableListOf<QueueExecutionResultFinalizer.AgentResult>()
        val outcome = QueueExecutionResultFinalizer.persistAllowedResults(
            allowedAgentIds = listOf("agent-a", "agent-b"),
            results = listOf(
                QueueExecutionResultFinalizer.AgentResult(
                    "agent-a", QueueExecutionResultFinalizer.Status.BLOCKED, "SAFE_RETRY: RATE_LIMIT_QUOTA"
                ),
                QueueExecutionResultFinalizer.AgentResult(
                    "agent-b", QueueExecutionResultFinalizer.Status.BLOCKED, "SAFE_RETRY: CONNECT_ERROR"
                )
            )
        ) { result ->
            saved += result
            true
        }
        assertTrue(outcome.success)
        assertEquals(2, saved.size)
        assertTrue(saved.all { it.status == QueueExecutionResultFinalizer.Status.BLOCKED })
        assertTrue(saved.all { it.detail.startsWith("SAFE_RETRY:") })
    }
}
