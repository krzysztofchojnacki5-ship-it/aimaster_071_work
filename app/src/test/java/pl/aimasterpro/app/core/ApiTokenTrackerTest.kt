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

    @Test fun clearRecordsRemovesPersistedUsage() {
        ApiTokenTracker.recordQuery(context, "ollama", "llama3", "MASTER", "chat-1", 10, 5, status = "REAL", baseUrl = "http://127.0.0.1:11434/v1")
        ApiTokenTracker.clearRecords(context)
        assertTrue(ApiTokenTracker.loadAllRecordsFromStorage(context).isEmpty())
    }
}
