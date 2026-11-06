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
import java.util.concurrent.CountDownLatch

@RunWith(RobolectricTestRunner::class)
class ApiTokenTrackerTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        ApiTokenTracker.resetInMemoryStateForTests()
        ApiTokenTracker.clearRecords(context)
        ApiTokenTracker.setLimits(context, 100_000L, 50.0, 50_000L)
    }

    @After fun tearDown() {
        ApiTokenTracker.resetInMemoryStateForTests()
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

    @Test fun reservationIsConsumedOnlyByTheProviderThatWasBudgeted() {
        val plan = ApiTokenTracker.planBatch(
            context,
            listOf(BudgetRequest("fallback-task", "RESEARCHER", "provider-a", "https://a.invalid/v1", "gpt-4o-mini", 100, 100))
        )
        assertTrue(plan.allowedTaskIds.contains("fallback-task"))

        ApiTokenTracker.recordQuery(
            context, "provider-b", "gpt-4o-mini", "RESEARCHER", "fallback-task",
            inputTokens = 40, outputTokens = 20, status = "REAL",
            baseUrl = "https://b.invalid/v1", reservationId = plan.reservationId
        )
        assertEquals("A fallback must not consume another provider's reservation", 200L, ApiTokenTracker.activeReservationStats().tokens)

        ApiTokenTracker.recordQuery(
            context, "provider-a", "different-model", "RESEARCHER", "fallback-task",
            inputTokens = 10, outputTokens = 5, status = "REAL",
            baseUrl = "https://a.invalid/v1", reservationId = plan.reservationId
        )
        assertEquals("A different model must not consume the reserved model's estimate", 200L, ApiTokenTracker.activeReservationStats().tokens)

        ApiTokenTracker.recordQuery(
            context, "provider-a", "gpt-4o-mini", "RESEARCHER", "fallback-task",
            inputTokens = 10, outputTokens = 5, status = "REAL",
            baseUrl = "https://different.invalid/v1", reservationId = plan.reservationId
        )
        assertEquals("A different endpoint must not consume the reserved endpoint's estimate", 200L, ApiTokenTracker.activeReservationStats().tokens)

        ApiTokenTracker.recordQuery(
            context, "provider-a", "gpt-4o-mini", "RESEARCHER", "fallback-task",
            inputTokens = 30, outputTokens = 20, status = "REAL",
            baseUrl = "https://a.invalid/v1", reservationId = plan.reservationId
        )
        assertEquals(150L, ApiTokenTracker.activeReservationStats().tokens)
        ApiTokenTracker.releaseBatchReservation(plan.reservationId)
    }

    @Test fun actualUsageAboveEstimateIsStillCountedAgainstTheNextBatch() {
        ApiTokenTracker.setLimits(context, daily = 30L, monthly = 0.0, perAgent = 30L)
        val plan = ApiTokenTracker.planBatch(
            context,
            listOf(BudgetRequest("underestimated", "RESEARCHER", "ollama", "http://127.0.0.1:11434/v1", "llama3", 5, 5))
        )
        assertTrue(plan.allowedTaskIds.contains("underestimated"))
        ApiTokenTracker.recordQuery(
            context, "ollama", "llama3", "RESEARCHER", "underestimated",
            inputTokens = 15, outputTokens = 10, status = "REAL",
            baseUrl = "http://127.0.0.1:11434/v1", reservationId = plan.reservationId
        )
        ApiTokenTracker.releaseBatchReservation(plan.reservationId)

        val next = ApiTokenTracker.planBatch(
            context,
            listOf(BudgetRequest("next-task", "RESEARCHER", "ollama", "http://127.0.0.1:11434/v1", "llama3", 4, 2))
        )
        assertFalse("Actual overage must reduce the next available budget", next.allowedTaskIds.contains("next-task"))
    }

    @Test fun negativeUsageAndTokenOverflowAreRejected() {
        val negative = runCatching {
            ApiTokenTracker.recordQuery(context, "provider", "gpt-4o-mini", "MASTER", "negative", -1, 2)
        }
        assertTrue(negative.isFailure)

        val overflow = runCatching {
            ApiTokenTracker.recordQuery(context, "provider", "gpt-4o-mini", "MASTER", "overflow", Long.MAX_VALUE, 1)
        }
        assertTrue(overflow.isFailure)
    }

    @Test fun deferredReservationReleaseWaitsForLateWorkerUsageAndCompletion() {
        val plan = ApiTokenTracker.planBatch(
            context,
            listOf(BudgetRequest("late-worker", "RESEARCHER", "ollama",
                "http://127.0.0.1:11434/v1", "llama3", 100L, 100L))
        )
        assertTrue(plan.allowedTaskIds.contains("late-worker"))

        ApiTokenTracker.requestBatchReservationRelease(plan.reservationId)
        assertEquals("Release request must retain tokens while worker may still return usage",
            200L, ApiTokenTracker.activeReservationStats().tokens)

        ApiTokenTracker.recordQuery(
            context, "ollama", "llama3", "RESEARCHER", "late-worker",
            inputTokens = 30L, outputTokens = 20L, status = "REAL",
            baseUrl = "http://127.0.0.1:11434/v1", reservationId = plan.reservationId
        )
        assertEquals("Late usage must still consume its matching reservation",
            150L, ApiTokenTracker.activeReservationStats().tokens)

        ApiTokenTracker.completeReservationTask(plan.reservationId, "late-worker")
        assertEquals("Reservation is released only after the worker exits",
            0L, ApiTokenTracker.activeReservationStats().tokens)
    }

    @Test fun clearingUsageHistoryDoesNotFreeInFlightBudgetReservations() {
        val plan = ApiTokenTracker.planBatch(
            context,
            listOf(BudgetRequest("clear-during-run", "MASTER", "ollama",
                "http://127.0.0.1:11434/v1", "llama3", 40L, 60L))
        )
        assertTrue(plan.allowedTaskIds.contains("clear-during-run"))
        assertTrue(ApiTokenTracker.clearRecords(context))
        assertEquals("Clear history must not release a running task's budget",
            100L, ApiTokenTracker.activeReservationStats().tokens)

        ApiTokenTracker.recordQuery(
            context, "ollama", "llama3", "MASTER", "clear-during-run",
            inputTokens = 10L, outputTokens = 10L, status = "REAL",
            baseUrl = "http://127.0.0.1:11434/v1", reservationId = plan.reservationId
        )
        assertEquals("Late usage after clear must still be persisted",
            1, ApiTokenTracker.loadAllRecordsFromStorage(context).count { it.taskId == "clear-during-run" })
        assertEquals(80L, ApiTokenTracker.activeReservationStats().tokens)

        ApiTokenTracker.completeReservationTask(plan.reservationId, "clear-during-run")
        ApiTokenTracker.requestBatchReservationRelease(plan.reservationId)
        assertEquals(0L, ApiTokenTracker.activeReservationStats().tokens)
    }

    @Test fun concurrentBatchPlanningCannotReserveTheSameDailyBudgetTwice() {
        ApiTokenTracker.setLimits(context, daily = 100L, monthly = 0.0, perAgent = 1_000L)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val plans = java.util.Collections.synchronizedList(mutableListOf<BatchBudgetPlan>())
        val failures = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
        val workers = (1..2).map { index ->
            Thread {
                ready.countDown()
                try {
                    start.await()
                    plans += ApiTokenTracker.planBatch(
                        context,
                        listOf(BudgetRequest("parallel-$index", "MASTER", "ollama",
                            "http://127.0.0.1:11434/v1", "llama3", 30L, 30L))
                    )
                } catch (error: Throwable) {
                    failures += error
                }
            }.apply { start() }
        }
        ready.await()
        start.countDown()
        workers.forEach { it.join(5_000L) }

        assertTrue("Concurrent planner threads must finish", workers.none { it.isAlive })
        assertTrue("Planner thread failed: $failures", failures.isEmpty())
        assertEquals("Only one 60-token reservation fits a 100-token daily budget",
            1, plans.sumOf { it.allowedTaskIds.size })
        plans.forEach { ApiTokenTracker.releaseBatchReservation(it.reservationId) }
    }

    @Test fun concurrentReservationReleaseAndCompletionAlwaysRetireTheBatch() {
        val plan = ApiTokenTracker.planBatch(
            context,
            listOf(BudgetRequest("release-race", "MASTER", "ollama",
                "http://127.0.0.1:11434/v1", "llama3", 20L, 20L))
        )
        assertTrue(plan.allowedTaskIds.contains("release-race"))
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val failures = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
        val release = Thread {
            ready.countDown()
            try {
                start.await()
                ApiTokenTracker.requestBatchReservationRelease(plan.reservationId)
            } catch (error: Throwable) { failures += error }
        }
        val finish = Thread {
            ready.countDown()
            try {
                start.await()
                ApiTokenTracker.completeReservationTask(plan.reservationId, "release-race")
            } catch (error: Throwable) { failures += error }
        }
        release.start()
        finish.start()
        ready.await()
        start.countDown()
        release.join(5_000L)
        finish.join(5_000L)

        assertTrue("Reservation lifecycle threads must finish", !release.isAlive && !finish.isAlive)
        assertTrue("Reservation lifecycle thread failed: $failures", failures.isEmpty())
        assertEquals("Either ordering of release and completion must retire the reservation",
            0L, ApiTokenTracker.activeReservationStats().tokens)
    }

    @Test fun concurrentUsageWritesAreSerializedAndBothRecordsPersist() {
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val failures = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
        val workers = (1..2).map { index ->
            Thread {
                ready.countDown()
                try {
                    start.await()
                    ApiTokenTracker.recordQuery(
                        context, "ollama", "llama3", "MASTER", "parallel-write-$index",
                        inputTokens = 10, outputTokens = 5, status = "REAL",
                        baseUrl = "http://127.0.0.1:11434/v1"
                    )
                } catch (error: Throwable) {
                    failures += error
                }
            }.apply { start() }
        }
        ready.await()
        start.countDown()
        workers.forEach { it.join(5_000L) }

        assertTrue("Usage writer threads must finish", workers.none { it.isAlive })
        assertTrue("Usage writer failed: $failures", failures.isEmpty())
        val parallelRecords = ApiTokenTracker.loadAllRecordsFromStorage(context)
            .filter { it.taskId.startsWith("parallel-write-") }
        assertEquals("Both serialized writes must remain in the durable ledger", 2, parallelRecords.size)
    }

    @Test fun globalSynthesisCostReserveBlocksOtherMonthlySpendingUntilReleased() {
        ApiTokenTracker.setLimits(context, daily = 10_000L, monthly = 0.0005, perAgent = 10_000L)
        val reserve = ApiTokenTracker.planBatch(
            context, emptyList(), globalReserveTokens = 100L,
            globalReserveAgentId = "MASTER", globalReserveCostPln = 0.00025
        )
        assertTrue("A synthesis-only reservation must be persisted in memory", reserve.reservationId.isNotBlank())
        assertEquals(0.00025, ApiTokenTracker.activeReservationStats().costPln, 1.0e-9)

        val competing = BudgetRequest(
            "competing-monthly-task", "RESEARCHER", "openai", "https://api.openai.com/v1",
            "gpt-4o-mini", 100L, 100L
        )
        val blocked = ApiTokenTracker.planBatch(context, listOf(competing))
        assertFalse("The monthly synthesis allowance must not be spent by another batch",
            blocked.allowedTaskIds.contains(competing.taskId))
        assertTrue(blocked.blockedReasons.getValue(competing.taskId).contains("miesięczny budżet"))

        ApiTokenTracker.releaseBatchReservation(reserve.reservationId)
        val afterRelease = ApiTokenTracker.planBatch(context, listOf(competing))
        assertTrue("Releasing the synthesis guard must return its unused monthly allowance",
            afterRelease.allowedTaskIds.contains(competing.taskId))
        ApiTokenTracker.releaseBatchReservation(afterRelease.reservationId)
    }

    @Test fun globalSynthesisCostReserveAloneCannotExceedMonthlyLimit() {
        ApiTokenTracker.setLimits(context, daily = 10_000L, monthly = 0.0005, perAgent = 10_000L)
        val reserve = ApiTokenTracker.planBatch(
            context, emptyList(), globalReserveTokens = 100L,
            globalReserveAgentId = "MASTER", globalReserveCostPln = 0.0006
        )
        assertTrue("An over-limit synthesis reserve must fail closed", reserve.reservationId.isBlank())
        assertEquals(0, ApiTokenTracker.activeReservationStats().tasks)
        assertEquals(0L, ApiTokenTracker.activeReservationStats().tokens)
    }

    @Test fun monthlyCostBudgetIncludesActiveReservationsAndRecordedUsage() {
        ApiTokenTracker.setLimits(context, daily = 100_000L, monthly = 0.0005, perAgent = 50_000L)
        val request = BudgetRequest(
            "paid-monthly-task", "MASTER", "openai", "https://api.openai.com/v1",
            "gpt-4o-mini", 100L, 100L
        )
        val first = ApiTokenTracker.planBatch(context, listOf(request))
        assertTrue(first.allowedTaskIds.contains(request.taskId))
        assertTrue("An active batch must receive a non-empty reservation id", first.reservationId.isNotBlank())

        val whileReserved = ApiTokenTracker.planBatch(
            context, listOf(request.copy(taskId = "second-paid-task"))
        )
        assertFalse("An active reservation must count against the monthly cost limit",
            whileReserved.allowedTaskIds.contains("second-paid-task"))
        assertTrue(whileReserved.reservationId.isBlank())
        assertTrue(whileReserved.blockedReasons.getValue("second-paid-task").contains("miesięczny budżet"))

        ApiTokenTracker.recordQuery(
            context, "openai", "gpt-4o-mini", "MASTER", request.taskId,
            inputTokens = 100L, outputTokens = 100L, status = "REAL",
            baseUrl = "https://api.openai.com/v1", reservationId = first.reservationId
        )
        ApiTokenTracker.releaseBatchReservation(first.reservationId)

        val afterRecordedUsage = ApiTokenTracker.planBatch(
            context, listOf(request.copy(taskId = "third-paid-task"))
        )
        assertFalse("Persisted real usage must continue to count after reservation release",
            afterRecordedUsage.allowedTaskIds.contains("third-paid-task"))
        assertTrue(afterRecordedUsage.blockedReasons.getValue("third-paid-task").contains("miesięczny budżet"))
    }

    @Test fun monthlyLimitThatOverflowsFloatStorageIsRejected() {
        assertTrue(runCatching {
            ApiTokenTracker.setLimits(context, daily = 1_000L, monthly = Double.MAX_VALUE, perAgent = 1_000L)
        }.isFailure)
    }

    @Test fun positiveMonthlyLimitThatUnderflowsFloatStorageIsRejected() {
        assertTrue(runCatching {
            ApiTokenTracker.setLimits(context, daily = 1_000L, monthly = 1.0e-50, perAgent = 1_000L)
        }.isFailure)
        assertEquals("A rejected tiny positive limit must not silently disable the cost gate",
            50.0, ApiTokenTracker.getLimits(context).monthlyCostPlnLimit, 0.0)
    }

    @Test fun mutatingReturnedPricingDoesNotChangeSharedDefaults() {
        val first = ApiTokenTracker.getPricing(context, "gpt-4o-mini")
        first.inputPer1MUsd = Double.NaN
        first.outputPer1MUsd = -100.0

        val second = ApiTokenTracker.getPricing(context, "gpt-4o-mini")
        assertTrue(second.inputPer1MUsd.isFinite())
        assertTrue(second.outputPer1MUsd >= 0.0)
        assertTrue(second.inputPer1MUsd > 0.0)
    }

    @Test fun invalidExchangeRatesAreRejected() {
        assertTrue(runCatching { ApiTokenTracker.setExchangeRate(context, Double.NaN) }.isFailure)
        assertTrue(runCatching { ApiTokenTracker.setExchangeRate(context, 0.0) }.isFailure)
        assertTrue(runCatching { ApiTokenTracker.setExchangeRate(context, Double.POSITIVE_INFINITY) }.isFailure)
    }

    @Test fun negativePersistedCostMakesUsageLedgerUnhealthy() {
        val prefs = context.getSharedPreferences("api_token_tracker_records_v1", Context.MODE_PRIVATE)
        val corrupt = """[{"qId":"bad-cost","ts":1,"prov":"p","model":"m","agent":"MASTER","task":"x","in":1,"out":1,"tot":2,"reas":0,"cache":0,"status":"REAL","usd":-1.0,"pln":-4.0}]"""
        assertTrue(prefs.edit().putString("records", corrupt).commit())
        val plan = ApiTokenTracker.planBatch(
            context,
            listOf(BudgetRequest("must-block", "MASTER", "provider", "https://p.invalid/v1", "gpt-4o-mini", 1, 1))
        )
        assertTrue(plan.allowedTaskIds.isEmpty())
        assertTrue(plan.blockedReasons.getValue("must-block").contains("rejestr użycia API"))
    }

    @Test fun clearRecordsRemovesPersistedUsageOnlyAfterDurableCommit() {
        ApiTokenTracker.recordQuery(context, "ollama", "llama3", "MASTER", "chat-1", 10, 5, status = "REAL", baseUrl = "http://127.0.0.1:11434/v1")
        assertTrue(ApiTokenTracker.clearRecords(context))
        assertTrue(ApiTokenTracker.loadAllRecordsFromStorage(context).isEmpty())
        assertTrue(context.getSharedPreferences("api_token_tracker_records_v1", Context.MODE_PRIVATE).getString("records", null).isNullOrBlank())
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

    @Test fun globalSynthesisReserveAlsoProtectsMasterPerAgentLimit() {
        ApiTokenTracker.setLimits(context, daily = 10_000L, monthly = 0.0, perAgent = 100L)
        val first = ApiTokenTracker.planBatch(
            context,
            listOf(BudgetRequest("specialist-a", "RESEARCHER", "ollama", "http://127.0.0.1:11434/v1", "llama3", 10L, 10L)),
            globalReserveTokens = 80L,
            globalReserveAgentId = "MASTER"
        )
        assertTrue(first.allowedTaskIds.contains("specialist-a"))
        assertEquals(100L, ApiTokenTracker.activeReservationStats().tokens)

        val synthesis = ApiTokenTracker.planBatch(
            context,
            listOf(BudgetRequest("master-synthesis", "MASTER", "ollama", "http://127.0.0.1:11434/v1", "llama3", 15L, 10L))
        )
        assertFalse("The reserved synthesis allowance must count against MASTER's per-agent cap",
            synthesis.allowedTaskIds.contains("master-synthesis"))
        assertTrue(synthesis.blockedReasons.getValue("master-synthesis").contains("limit"))
        ApiTokenTracker.releaseBatchReservation(first.reservationId)
    }

    @Test fun currentGlobalSynthesisReserveIsIncludedBeforePlanningMasterTasks() {
        ApiTokenTracker.setLimits(context, daily = 10_000L, monthly = 0.0, perAgent = 100L)
        val plan = ApiTokenTracker.planBatch(
            context,
            listOf(BudgetRequest("master-task", "MASTER", "ollama", "http://127.0.0.1:11434/v1", "llama3", 30L, 20L)),
            globalReserveTokens = 80L,
            globalReserveAgentId = "MASTER"
        )
        assertFalse("The current batch must not spend tokens already reserved for MASTER synthesis",
            plan.allowedTaskIds.contains("master-task"))
        assertEquals(80L, ApiTokenTracker.activeReservationStats().tokens)
        ApiTokenTracker.releaseBatchReservation(plan.reservationId)
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
