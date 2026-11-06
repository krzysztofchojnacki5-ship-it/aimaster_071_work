package pl.aimasterpro.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SmartBudgetAndHealthTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun reset() {
        ApiTokenTracker.clearRecords(context)
        context.getSharedPreferences("model_health_history_v1", 0).edit().clear().commit()
        context.getSharedPreferences("agent_efficiency_history_v1", 0).edit().clear().commit()
        ApiTokenTracker.setLimits(context, daily = 10_000, monthly = 0.0, perAgent = 10_000)
    }

    @Test fun activeBatchReservationBlocksSecondConcurrentBatch() {
        val first = ApiTokenTracker.planBatch(context, listOf(
            BudgetRequest("task-a", "agent-a", "custom", "https://api.example.test/v1", "paid-model", 4_000, 4_000)
        ))
        assertTrue(first.allowedTaskIds.contains("task-a"))

        val second = ApiTokenTracker.planBatch(context, listOf(
            BudgetRequest("task-b", "agent-b", "custom", "https://api.example.test/v1", "paid-model", 3_000, 1_000)
        ))
        assertTrue("The first reservation must be counted before a second batch is accepted", second.allowedTaskIds.isEmpty())
        assertTrue(ApiTokenTracker.activeReservationStats().tokens >= 8_000)

        ApiTokenTracker.releaseBatchReservation(first.reservationId)
        val afterRelease = ApiTokenTracker.planBatch(context, listOf(
            BudgetRequest("task-c", "agent-c", "custom", "https://api.example.test/v1", "paid-model", 3_000, 1_000)
        ))
        assertTrue(afterRelease.allowedTaskIds.contains("task-c"))
        ApiTokenTracker.releaseBatchReservation(second.reservationId)
        ApiTokenTracker.releaseBatchReservation(afterRelease.reservationId)
    }

    @Test fun budgetSnapshotIncludesRecordedUsageAndActiveReservations() {
        ApiTokenTracker.setLimits(context, daily = 10_000, monthly = 0.0, perAgent = 10_000)
        ApiTokenTracker.recordQuery(
            context, "groq", "openai/gpt-oss-20b", "agent-a", "task-old",
            inputTokens = 2_000, outputTokens = 1_000, status = "REAL"
        )
        val plan = ApiTokenTracker.planBatch(
            context,
            listOf(BudgetRequest("task-new", "agent-b", "groq", "https://api.groq.com/openai/v1", "openai/gpt-oss-20b", 2_000, 1_000)),
            globalReserveTokens = 1_000
        )

        val snapshot = ApiTokenTracker.budgetUsageSnapshot(context)
        assertEquals(3_000L, snapshot.recordedTodayTokens)
        assertEquals(4_000L, snapshot.activeReservedTokens)
        assertEquals(3_000L, snapshot.remainingTokens)
        assertEquals(10_000L, snapshot.dailyLimitTokens)

        ApiTokenTracker.releaseBatchReservation(plan.reservationId)
    }

    @Test fun recentUnhealthyPrimaryIsRankedAfterHealthyAlternative() {
        val primary = ProviderConfig("primary", "Primary", ProviderKind.OPENAI_COMPATIBLE, "https://one.example/v1", "model-a")
        val backup = ProviderConfig("backup", "Backup", ProviderKind.OPENAI_COMPATIBLE, "https://two.example/v1", "model-b")
        repeat(2) { ModelHealthTracker.record(context, primary, false, "DNS_ERROR", 100L) }
        val ranked = ModelHealthTracker.rank(context, listOf(primary, backup))
        assertEquals("backup", ranked.first().id)
    }

    @Test fun efficiencyStatsAggregateRealExecutionOutcomes() {
        AgentEfficiencyTracker.record(context, AgentEfficiencyTracker.Outcome(
            System.currentTimeMillis(), "t1", "agent-a", "COMPLETED", 2_000, 1, "groq", "free-model", ""
        ))
        AgentEfficiencyTracker.record(context, AgentEfficiencyTracker.Outcome(
            System.currentTimeMillis(), "t2", "agent-a", "FAILED", 4_000, 2, "groq", "free-model", "DNS_ERROR"
        ))
        val stats = AgentEfficiencyTracker.stats(context).single()
        assertEquals(2, stats.tasks)
        assertEquals(1, stats.completed)
        assertEquals(1, stats.failed)
        assertEquals(3_000L, stats.avgDurationMs)
        assertEquals(1.5, stats.avgAttempts, 0.001)
    }
}
