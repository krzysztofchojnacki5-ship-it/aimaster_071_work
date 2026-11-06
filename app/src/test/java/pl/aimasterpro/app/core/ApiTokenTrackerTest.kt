package pl.aimasterpro.app.core

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ApiTokenTrackerTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        ApiTokenTracker.clearRecords(context)
        ApiTokenTracker.setLimits(context, 100_000L, 50.0, 50_000L)
    }

    @After fun tearDown() {
        ApiTokenTracker.clearRecords(context)
    }

    @Test fun successfulUsageIsPersistedAndVisibleInMonitorPeriods() {
        ApiTokenTracker.recordQuery(context, "ollama", "llama3", "MASTER", "chat-1", 120, 30, status = "REAL", baseUrl = "http://127.0.0.1:11434/v1")
        val all = ApiTokenTracker.loadAllRecordsFromStorage(context)
        assertEquals(1, all.size)
        assertEquals(150L, all.single().totalTokens)
        assertEquals("REAL", all.single().status)
        assertEquals(1, ApiTokenTracker.filterRecords(all, "Sesja").size)
        assertEquals(1, ApiTokenTracker.filterRecords(all, "Dzisiaj").size)
    }

    @Test fun zeroMonthlyCostLimitDisablesMoneyGateButDailyTokenLimitStillApplies() {
        ApiTokenTracker.setLimits(context, daily = 1_000L, monthly = 0.0, perAgent = 1_000L)
        val plan = ApiTokenTracker.planBatch(
            context,
            listOf(BudgetRequest("task-1", "MASTER", "openai", "https://api.openai.com/v1", "gpt-4o", 10, 10))
        )
        assertTrue(plan.allowedTaskIds.contains("task-1"))

        ApiTokenTracker.setLimits(context, daily = 10L, monthly = 0.0, perAgent = 1_000L)
        val blocked = ApiTokenTracker.planBatch(
            context,
            listOf(BudgetRequest("task-2", "MASTER", "ollama", "http://127.0.0.1:11434/v1", "llama3", 10, 10))
        )
        assertFalse(blocked.allowedTaskIds.contains("task-2"))
    }

    @Test fun synthesisReservationIsConsumedByActualMasterUsageAndReleased() {
        val plan = ApiTokenTracker.planBatch(
            context,
            listOf(BudgetRequest(
                taskId = "master-synthesis",
                agentId = "MASTER",
                providerId = "ollama",
                baseUrl = "http://127.0.0.1:11434/v1",
                modelName = "llama3",
                estimatedInputTokens = 100,
                estimatedOutputTokens = 100
            ))
        )
        assertTrue(plan.allowedTaskIds.contains("master-synthesis"))
        assertEquals(200L, ApiTokenTracker.activeReservationStats().tokens)

        ApiTokenTracker.recordQuery(
            context, "ollama", "llama3", "MASTER", "master-synthesis",
            inputTokens = 50, outputTokens = 20, status = "REAL",
            baseUrl = "http://127.0.0.1:11434/v1", reservationId = plan.reservationId
        )
        assertEquals("Actual token usage should consume its matching reservation", 130L, ApiTokenTracker.activeReservationStats().tokens)

        ApiTokenTracker.releaseBatchReservation(plan.reservationId)
        assertEquals(0L, ApiTokenTracker.activeReservationStats().tokens)
    }

    @Test fun clearRecordsRemovesPersistedUsage() {
        ApiTokenTracker.recordQuery(context, "ollama", "llama3", "MASTER", "chat-1", 10, 5, status = "REAL", baseUrl = "http://127.0.0.1:11434/v1")
        ApiTokenTracker.clearRecords(context)
        assertTrue(ApiTokenTracker.loadAllRecordsFromStorage(context).isEmpty())
    }
    @Test fun corruptUsageLedgerIsNotOverwrittenByNewUsageRecord() {
        val prefs = context.getSharedPreferences("api_token_tracker_records_v1", Context.MODE_PRIVATE)
        val corrupt = """[{"qId":"old-record","tot":10},broken]"""
        assertTrue(prefs.edit().putString("records", corrupt).commit())

        val result = runCatching {
            ApiTokenTracker.recordQuery(
                context, "provider", "gpt-4o-mini", "MASTER", "new-task",
                inputTokens = 5, outputTokens = 3, status = "REAL"
            )
        }
        assertTrue("A durable usage write must fail when the existing ledger is corrupt", result.isFailure)
        assertEquals("Corrupt persisted usage data must be preserved", corrupt, prefs.getString("records", null))
        assertEquals("The current process must retain the billable usage record", 1, ApiTokenTracker.loadAllRecordsFromStorage(context).count { it.taskId == "new-task" })
    }

    @Test fun corruptUsageLedgerBlocksNewBudgetReservations() {
        val prefs = context.getSharedPreferences("api_token_tracker_records_v1", Context.MODE_PRIVATE)
        val corrupt = """not-json"""
        assertTrue(prefs.edit().putString("records", corrupt).commit())

        val plan = ApiTokenTracker.planBatch(
            context,
            listOf(BudgetRequest("blocked-task", "MASTER", "provider", "https://example.invalid/v1", "gpt-4o-mini", 10, 10))
        )
        assertTrue(plan.allowedTaskIds.isEmpty())
        assertTrue(plan.blockedReasons.getValue("blocked-task").contains("rejestr użycia API"))
        assertEquals(corrupt, prefs.getString("records", null))
    }

    @Test fun duplicateBudgetTaskIdsAreRejectedBeforeReservation() {
        val plan = ApiTokenTracker.planBatch(
            context,
            listOf(
                BudgetRequest("same-task", "MASTER", "provider-a", "https://a.invalid/v1", "gpt-4o-mini", 10, 10),
                BudgetRequest("same-task", "reviewer", "provider-b", "https://b.invalid/v1", "gpt-4o-mini", 10, 10)
            )
        )
        assertTrue(plan.allowedTaskIds.isEmpty())
        assertTrue(plan.blockedReasons.values.all { it.contains("niepuste i unikalne") })
        assertEquals(0L, ApiTokenTracker.activeReservationStats().tokens)
    }

    @Test fun corruptUsageLedgerBlocksLegacyLimitCheckAndReportsUnknownBudget() {
        val prefs = context.getSharedPreferences("api_token_tracker_records_v1", Context.MODE_PRIVATE)
        assertTrue(prefs.edit().putString("records", """[broken""").commit())

        val check = ApiTokenTracker.checkLimits(context, "MASTER", 1L)
        assertFalse(check.allowed)
        assertTrue(check.warningMessage.orEmpty().contains("Rejestr użycia API"))

        val snapshot = ApiTokenTracker.budgetUsageSnapshot(context)
        assertFalse(snapshot.ledgerHealthy)
        assertEquals(0L, snapshot.remainingTokens)
    }

}
