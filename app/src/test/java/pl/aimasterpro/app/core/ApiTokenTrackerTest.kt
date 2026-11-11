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
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
class ApiTokenTrackerTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        ApiTokenTracker.resetInMemoryStateForTests()
        SharedPreferencesWriteHealth.resetForTests("api_token_tracker_records_v1")
        ApiTokenTracker.clearRecords(context)
        context.getSharedPreferences("api_token_pricing_v1", Context.MODE_PRIVATE).edit().clear().commit()
        ApiTokenTracker.setLimits(context, 100_000L, 50.0, 50_000L)
    }

    @After fun tearDown() {
        ApiTokenTracker.resetInMemoryStateForTests()
        SharedPreferencesWriteHealth.resetForTests("api_token_tracker_records_v1")
        ApiTokenTracker.clearRecords(context)
    }

    @Test fun repeatedUsageRecoveryWithSameIdempotencyKeyDoesNotDoubleCount() {
        val first = ApiTokenTracker.recordQuery(
            context, "openai", "gpt-4o-mini", "RESEARCHER", "task-recovery-1",
            300L, 120L, status = "REAL", idempotencyKey = "team:task-recovery-1:openai"
        )
        val recovered = ApiTokenTracker.recordQuery(
            context, "openai", "gpt-4o-mini", "RESEARCHER", "task-recovery-1",
            300L, 4_096L, status = "UNAVAILABLE",
            idempotencyKey = "team:task-recovery-1:openai"
        )
        val all = ApiTokenTracker.loadAllRecordsFromStorage(context)
        assertEquals(first.queryId, recovered.queryId)
        assertEquals("REAL", recovered.status)
        assertEquals(1, all.count { it.queryId == first.queryId })
        assertEquals(420L, all.single().totalTokens)
    }

    @Test fun lateRealUsageUpgradesUnavailableEstimateWithoutCreatingSecondCharge() {
        val key = "team:task-late-real:openai"
        val estimate = ApiTokenTracker.recordQuery(
            context, "openai", "gpt-4o-mini", "RESEARCHER", "task-late-real",
            250L, 900L, status = "UNAVAILABLE", idempotencyKey = key
        )
        val actual = ApiTokenTracker.recordQuery(
            context, "openai", "gpt-4o-mini", "RESEARCHER", "task-late-real",
            310L, 145L, reasoningTokens = 35L, cachedTokens = 20L,
            status = "REAL", idempotencyKey = key
        )
        val all = ApiTokenTracker.loadAllRecordsFromStorage(context)
        assertEquals(estimate.queryId, actual.queryId)
        assertEquals("REAL", actual.status)
        assertEquals(310L, actual.inputTokens)
        assertEquals(145L, actual.outputTokens)
        assertEquals(35L, actual.reasoningTokens)
        assertEquals(20L, actual.cachedTokens)
        assertEquals(455L, actual.totalTokens)
        assertEquals(1, all.count { it.queryId == key })
        assertEquals("REAL", all.single().status)
        assertEquals(455L, all.single().totalTokens)

        val duplicateActual = ApiTokenTracker.recordQuery(
            context, "openai", "gpt-4o-mini", "RESEARCHER", "task-late-real",
            999L, 999L, status = "REAL", idempotencyKey = key
        )
        assertEquals(455L, duplicateActual.totalTokens)
        assertEquals(1, ApiTokenTracker.loadAllRecordsFromStorage(context).count { it.queryId == key })

        ApiTokenTracker.resetInMemoryStateForTests()
        val afterProcessRestart = ApiTokenTracker.loadAllRecordsFromStorage(context)
        assertEquals(1, afterProcessRestart.count { it.queryId == key })
        assertEquals("REAL", afterProcessRestart.single { it.queryId == key }.status)
        assertEquals(455L, afterProcessRestart.single { it.queryId == key }.totalTokens)
    }

    @Test fun idempotencyKeyCannotBeReusedForAnotherTaskIdentity() {
        ApiTokenTracker.recordQuery(
            context, "openai", "gpt-4o-mini", "RESEARCHER", "task-original",
            100L, 20L, idempotencyKey = "stable-request-key"
        )
        assertTrue(runCatching {
            ApiTokenTracker.recordQuery(
                context, "openai", "gpt-4o-mini", "RESEARCHER", "task-different",
                100L, 20L, idempotencyKey = "stable-request-key"
            )
        }.isFailure)
        assertEquals(1, ApiTokenTracker.loadAllRecordsFromStorage(context).size)
    }

    @Test fun usageSummarySaturatesInsteadOfWrappingOnLargeTotals() {
        fun record(id: String) = TokenRecord(
            queryId = id, timestamp = 1L, providerId = "provider", modelName = "model",
            agentId = "MASTER", taskId = id, inputTokens = Long.MAX_VALUE,
            outputTokens = 0L, totalTokens = Long.MAX_VALUE, reasoningTokens = 0L,
            cachedTokens = 0L, status = "REAL", costUsd = Double.MAX_VALUE,
            costPln = Double.MAX_VALUE
        )

        val summary = ApiTokenTracker.calculateSummary(listOf(record("large-1"), record("large-2")))
        assertEquals(Long.MAX_VALUE, summary.inputTokens)
        assertEquals(Long.MAX_VALUE, summary.totalTokens)
        assertEquals(Double.MAX_VALUE, summary.costUsd, 0.0)
        assertEquals(Double.MAX_VALUE, summary.costPln, 0.0)
    }

    @Test fun disabledMonthlyLimitStillRejectsCostAggregateOverflow() {
        ApiTokenTracker.setLimits(context, daily = 10_000L, monthly = 0.0, perAgent = 10_000L)
        ApiTokenTracker.setExchangeRate(context, 1_000.0)
        ApiTokenTracker.replaceCatalogPricingForProvider(
            context, "huge-cost-provider", "https://huge-cost.example/v1",
            listOf(CatalogModelPrice("huge-cost-model", Double.MAX_VALUE, 0.0, explicitlyFree = false))
        )

        val plan = ApiTokenTracker.planBatch(
            context,
            listOf(BudgetRequest(
                "huge-cost-task", "MASTER", "huge-cost-provider",
                "https://huge-cost.example/v1", "huge-cost-model", 1L, 0L
            )),
            globalReserveCostPln = Double.MAX_VALUE
        )

        assertTrue("An individually finite estimate must not overflow the aggregate when the money gate is disabled",
            plan.allowedTaskIds.isEmpty())
        assertTrue(plan.blockedReasons.getValue("huge-cost-task").contains("zakres bezpiecznej reprezentacji"))
        ApiTokenTracker.releaseBatchReservation(plan.reservationId)
        ApiTokenTracker.setExchangeRate(context, 4.0)
    }

    @Test fun activeReservationCostAggregatesSaturateInsteadOfBecomingInfinite() {
        ApiTokenTracker.setLimits(context, daily = 10_000L, monthly = 0.0, perAgent = 10_000L)
        val first = ApiTokenTracker.planBatch(
            context, emptyList(), globalReserveCostPln = Double.MAX_VALUE
        )
        val second = ApiTokenTracker.planBatch(
            context,
            listOf(BudgetRequest(
                "overflow-reservation-task", "MASTER", "openai",
                "https://api.openai.com/v1", "gpt-4o-mini", 1L, 0L
            )),
            globalReserveCostPln = Double.MAX_VALUE
        )

        assertTrue(first.reservationId.isNotBlank())
        assertTrue("The second MAX_VALUE reservation must be rejected rather than overflowing the aggregate",
            second.reservationId.isBlank())
        assertTrue(second.blockedReasons.values.any { it.contains("zakres bezpiecznej wyceny") })
        val stats = ApiTokenTracker.activeReservationStats()
        assertEquals(Double.MAX_VALUE, stats.costPln, 0.0)
        assertTrue("Reservation cost diagnostics must never expose Infinity", stats.costPln.isFinite())

        val snapshot = ApiTokenTracker.budgetUsageSnapshot(context)
        assertEquals(Double.MAX_VALUE, snapshot.activeReservedCostPln, 0.0)
        assertTrue("Budget snapshot cost must remain finite", snapshot.activeReservedCostPln.isFinite())

        ApiTokenTracker.releaseBatchReservation(first.reservationId)
        ApiTokenTracker.releaseBatchReservation(second.reservationId)
    }

    @Test fun reasoningTokensRemainOutputMetadataAndAreNotChargedTwice() {
        val record = ApiTokenTracker.recordQuery(
            context, "openai", "gpt-4o-mini", "MASTER", "reasoning-cost",
            inputTokens = 1_000_000L,
            outputTokens = 1_000_000L,
            reasoningTokens = 500_000L,
            status = "REAL"
        )

        // Reasoning is a subset of output tokens for this usage schema, not an extra
        // billable bucket. The default model rates are $0.15/$0.60 per million.
        assertEquals(2_000_000L, record.totalTokens)
        assertEquals(500_000L, record.reasoningTokens)
        assertEquals(0.75, record.costUsd, 0.0000001)
        assertEquals(3.0, record.costPln, 0.000001)
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

    @Test fun requestedBatchReleaseKeepsRunningWorkerBudgetUntilUsageIsRecordedAndWorkerExits() {
        val plan = ApiTokenTracker.planBatch(
            context,
            listOf(BudgetRequest("late-worker", "MASTER", "ollama",
                "http://127.0.0.1:11434/v1", "llama3", 20L, 20L))
        )
        assertTrue(plan.allowedTaskIds.contains("late-worker"))
        assertEquals(40L, ApiTokenTracker.activeReservationStats().tokens)

        // The coordinator may finish after Future.cancel(true), while an interrupt-ignoring
        // callable is still running. Its reservation must remain until that callable exits.
        ApiTokenTracker.requestBatchReservationRelease(plan.reservationId)
        assertEquals("A release request must not free a live worker's budget",
            40L, ApiTokenTracker.activeReservationStats().tokens)

        ApiTokenTracker.recordQuery(
            context, "ollama", "llama3", "MASTER", "late-worker",
            inputTokens = 10L, outputTokens = 5L, status = "REAL",
            baseUrl = "http://127.0.0.1:11434/v1", reservationId = plan.reservationId,
            idempotencyKey = "late-worker-real-usage"
        )
        assertEquals("Recorded usage should replace the corresponding part of the live estimate",
            25L, ApiTokenTracker.activeReservationStats().tokens)

        ApiTokenTracker.completeReservationTask(plan.reservationId, "late-worker")
        assertEquals("The reservation is retired only after the worker exits",
            0L, ApiTokenTracker.activeReservationStats().tokens)
        assertEquals(15L, ApiTokenTracker.budgetUsageSnapshot(context).recordedTodayTokens)
    }

    @Test fun concurrentUsageSnapshotsAreSerializedWithSessionRecordWrites() {
        val failure = AtomicReference<Throwable?>(null)
        val writerDone = CountDownLatch(1)
        val writer = Thread {
            try {
                repeat(80) { index ->
                    ApiTokenTracker.recordQuery(
                        context, "openai", "gpt-4o-mini", "QA", "snapshot-task-$index",
                        10L, 5L, status = "REAL", idempotencyKey = "snapshot-key-$index"
                    )
                }
            } catch (error: Throwable) {
                failure.compareAndSet(null, error)
            } finally {
                writerDone.countDown()
            }
        }
        val reader = Thread {
            try {
                while (writerDone.count > 0L) {
                    ApiTokenTracker.loadAllRecordsFromStorage(context)
                }
            } catch (error: Throwable) {
                failure.compareAndSet(null, error)
            }
        }
        writer.start()
        reader.start()
        writer.join(10_000)
        reader.join(10_000)
        assertFalse("Usage writer did not finish", writer.isAlive)
        assertFalse("Usage snapshot reader did not finish", reader.isAlive)
        assertTrue("Concurrent usage snapshot failed: ${failure.get()}", failure.get() == null)
        assertEquals(80, ApiTokenTracker.loadAllRecordsFromStorage(context)
            .count { it.queryId.startsWith("snapshot-key-") })
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

    @Test fun corruptStoredCatalogIsPreservedAndBlocksProviderRefresh() {
        val prefs = context.getSharedPreferences("api_token_pricing_v1", Context.MODE_PRIVATE)
        val corrupt = """[{"providerId":"kept-provider","modelName":"paid-model","baseUrl":"https://kept.example/v1"}"""
        prefs.edit().putString("catalog_model_prices", corrupt).commit()

        val refresh = runCatching {
            ApiTokenTracker.replaceCatalogPricingForProvider(
                context, "new-provider", "https://new.example/v1",
                listOf(CatalogModelPrice("new-model", 0.1, 0.2, explicitlyFree = false))
            )
        }

        assertTrue("A corrupt catalog must fail closed instead of being replaced", refresh.isFailure)
        assertEquals("The original bytes must remain available for recovery",
            corrupt, prefs.getString("catalog_model_prices", null))
    }

    @Test fun catalogRefreshRejectsMalformedRowsWithoutDroppingOtherProviders() {
        val prefs = context.getSharedPreferences("api_token_pricing_v1", Context.MODE_PRIVATE)
        val malformed = """[{"providerId":"kept-provider","modelName":"paid-model","baseUrl":7}]"""
        prefs.edit().putString("catalog_model_prices", malformed).commit()

        val refresh = runCatching {
            ApiTokenTracker.replaceCatalogPricingForProvider(
                context, "new-provider", "https://new.example/v1",
                listOf(CatalogModelPrice("new-model", 0.1, 0.2, explicitlyFree = false))
            )
        }

        assertTrue("A malformed catalog identity must block destructive replacement", refresh.isFailure)
        assertEquals(malformed, prefs.getString("catalog_model_prices", null))
    }

    @Test fun discoveredCatalogPricesOverrideGenericModelFallback() {
        ApiTokenTracker.replaceCatalogPricingForProvider(
            context, "catalog-provider", "https://catalog.example/v1",
            listOf(CatalogModelPrice("vendor/unknown-model", 0.23, 0.91, explicitlyFree = false))
        )

        val exact = ApiTokenTracker.getPricing(
            context, "vendor/unknown-model", "catalog-provider", "https://catalog.example/v1"
        )
        assertEquals(0.23, exact.inputPer1MUsd, 0.0000001)
        assertEquals(0.91, exact.outputPer1MUsd, 0.0000001)

        // A model profile can have its own provider ID while sharing the parent URL/model.
        val profile = ApiTokenTracker.getPricing(
            context, "vendor/unknown-model", "catalog-provider-profile", "https://catalog.example/v1/"
        )
        assertEquals(0.23, profile.inputPer1MUsd, 0.0000001)
        assertEquals(0.91, profile.outputPer1MUsd, 0.0000001)
    }

    @Test fun usageLedgerChargesUsingDiscoveredCatalogPrices() {
        ApiTokenTracker.replaceCatalogPricingForProvider(
            context, "catalog-billing-provider", "https://billing.example/v1",
            listOf(CatalogModelPrice("vendor/billing-model", 0.23, 0.91, explicitlyFree = false))
        )
        val record = ApiTokenTracker.recordQuery(
            context, "catalog-billing-provider", "vendor/billing-model", "MASTER", "catalog-billing",
            inputTokens = 1_000_000L, outputTokens = 1_000_000L,
            status = "REAL", baseUrl = "https://billing.example/v1",
            idempotencyKey = "catalog-billing-provider:catalog-billing"
        )
        assertEquals(1.14, record.costUsd, 0.000001)
        assertEquals(4.56, record.costPln, 0.00001)
    }

    @Test fun cachedInputTokensUseCatalogCacheReadRate() {
        ApiTokenTracker.replaceCatalogPricingForProvider(
            context, "catalog-cache-provider", "https://cache.example/v1",
            listOf(CatalogModelPrice("vendor/cache-model", 0.23, 0.91, false, 0.05))
        )
        val record = ApiTokenTracker.recordQuery(
            context, "catalog-cache-provider", "vendor/cache-model", "MASTER", "catalog-cache",
            inputTokens = 1_000_000L, outputTokens = 0L, cachedTokens = 500_000L,
            status = "REAL", baseUrl = "https://cache.example/v1",
            idempotencyKey = "catalog-cache-provider:catalog-cache"
        )
        // Half regular input at $0.23/M and half cache-read input at $0.05/M.
        assertEquals(0.14, record.costUsd, 0.000001)
        assertEquals(0.56, record.costPln, 0.00001)
    }

    @Test fun explicitlyFreeCatalogModelDoesNotInheritPaidFallback() {
        ApiTokenTracker.replaceCatalogPricingForProvider(
            context, "catalog-free-provider", "https://free.example/v1",
            listOf(CatalogModelPrice("vendor/free-model", 0.0, 0.0, explicitlyFree = true))
        )
        val pricing = ApiTokenTracker.getPricing(
            context, "vendor/free-model", "catalog-free-provider", "https://free.example/v1"
        )
        assertEquals(0.0, pricing.inputPer1MUsd, 0.0)
        assertEquals(0.0, pricing.outputPer1MUsd, 0.0)
    }

    @Test fun contradictoryPersistedFreeFlagCannotZeroOutPublishedPaidRates() {
        val prefs = context.getSharedPreferences("api_token_pricing_v1", Context.MODE_PRIVATE)
        prefs.edit().putString(
            "catalog_model_prices",
            """[{"providerId":"corrupt-free-provider","modelName":"vendor/contradictory-free","baseUrl":"https://corrupt.example/v1","inputPer1MUsd":0.12,"outputPer1MUsd":0.34,"explicitlyFree":true}]"""
        ).commit()

        val pricing = ApiTokenTracker.getPricing(
            context, "vendor/contradictory-free", "corrupt-free-provider", "https://corrupt.example/v1"
        )
        assertEquals(0.12, pricing.inputPer1MUsd, 0.0000001)
        assertEquals(0.34, pricing.outputPer1MUsd, 0.0000001)
    }

    @Test fun explicitPaidProviderCatalogOverridesStaleFreeAllowlist() {
        ApiTokenTracker.replaceCatalogPricingForProvider(
            context, "openrouter", "https://openrouter.ai/api/v1",
            listOf(CatalogModelPrice("openrouter/free", 0.12, 0.34, explicitlyFree = false))
        )
        val pricing = ApiTokenTracker.getPricing(
            context, "openrouter/free", "openrouter", "https://openrouter.ai/api/v1"
        )
        assertEquals(0.12, pricing.inputPer1MUsd, 0.0000001)
        assertEquals(0.34, pricing.outputPer1MUsd, 0.0000001)
    }

    @Test fun incompleteOrInvalidCatalogPricesAreRejected() {
        assertTrue(runCatching {
            ApiTokenTracker.replaceCatalogPricingForProvider(
                context, "catalog-invalid-provider", "https://invalid.example",
                listOf(CatalogModelPrice("vendor/model", 0.1, null))
            )
        }.isFailure)
        assertTrue(runCatching {
            ApiTokenTracker.replaceCatalogPricingForProvider(
                context, "catalog-invalid-provider", "https://invalid.example",
                listOf(CatalogModelPrice("vendor/model", Double.NaN, 0.2))
            )
        }.isFailure)
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
    @Test fun duplicateIdempotencyKeysMakeUsageLedgerUnhealthyAndBlockWrites() {
        val prefs = context.getSharedPreferences("api_token_tracker_records_v1", Context.MODE_PRIVATE)
        val duplicate = """[
            {"qId":"same-request","ts":1,"prov":"openai","model":"gpt-4o-mini","agent":"MASTER","task":"task-1","in":10,"out":5,"tot":15,"reas":0,"cache":0,"status":"REAL","usd":0.0,"pln":0.0},
            {"qId":"same-request","ts":2,"prov":"openai","model":"gpt-4o-mini","agent":"MASTER","task":"task-1","in":20,"out":5,"tot":25,"reas":0,"cache":0,"status":"REAL","usd":0.0,"pln":0.0}
        ]""".trimIndent()
        assertTrue(prefs.edit().putString("records", duplicate).commit())

        val plan = ApiTokenTracker.planBatch(
            context,
            listOf(BudgetRequest("blocked-task", "MASTER", "openai", "https://api.openai.com/v1", "gpt-4o-mini", 10, 10))
        )
        assertTrue(plan.allowedTaskIds.isEmpty())
        assertTrue(plan.blockedReasons.getValue("blocked-task").contains("rejestr użycia API"))
        assertTrue(runCatching {
            ApiTokenTracker.recordQuery(
                context, "openai", "gpt-4o-mini", "MASTER", "new-task",
                10L, 5L, status = "REAL", idempotencyKey = "new-request"
            )
        }.isFailure)
        assertEquals(duplicate, prefs.getString("records", null))
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

    @Test fun fractionalAndCoercedUsageLedgerValuesAreRejectedWithoutOverwritingData() {
        val prefs = context.getSharedPreferences("api_token_tracker_records_v1", Context.MODE_PRIVATE)
        val malformedRows = listOf(
            """[{"qId":"fractional-input","ts":1,"prov":"p","model":"m","agent":"MASTER","task":"x","in":1.5,"out":1,"tot":2.5,"reas":0,"cache":0,"status":"REAL","usd":0.01,"pln":0.04}]""",
            """[{"qId":"string-input","ts":1,"prov":"p","model":"m","agent":"MASTER","task":"x","in":"10","out":1,"tot":11,"reas":0,"cache":0,"status":"REAL","usd":0.01,"pln":0.04}]""",
            """[{"qId":"fractional-time","ts":1.5,"prov":"p","model":"m","agent":"MASTER","task":"x","in":1,"out":1,"tot":2,"reas":0,"cache":0,"status":"REAL","usd":0.01,"pln":0.04}]""",
            """[{"qId":"string-cost","ts":1,"prov":"p","model":"m","agent":"MASTER","task":"x","in":1,"out":1,"tot":2,"reas":0,"cache":0,"status":"REAL","usd":"0.01","pln":0.04}]""",
            """[{"qId":"truncated-row","tot":0}]"""
        )

        malformedRows.forEachIndexed { index, raw ->
            assertTrue(prefs.edit().putString("records", raw).commit())
            val plan = ApiTokenTracker.planBatch(
                context,
                listOf(BudgetRequest("must-block-$index", "MASTER", "provider", "https://p.invalid/v1", "gpt-4o-mini", 1, 1))
            )
            assertTrue("Malformed ledger row $index must block reservations", plan.allowedTaskIds.isEmpty())
            assertFalse("Malformed ledger row $index must be reported unhealthy",
                ApiTokenTracker.budgetUsageSnapshot(context).ledgerHealthy)
            assertEquals("Malformed ledger row $index must remain intact", raw, prefs.getString("records", null))
        }
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


    @Test fun inconsistentPersistedTokenTotalMakesLedgerUnhealthyAndBlocksWrites() {
        val prefs = context.getSharedPreferences("api_token_tracker_records_v1", Context.MODE_PRIVATE)
        val corrupt = """[{"qId":"bad-total","ts":1,"prov":"p","model":"m","agent":"MASTER","task":"x","in":900,"out":700,"tot":10,"reas":0,"cache":0,"status":"REAL","usd":0.01,"pln":0.04}]"""
        assertTrue(prefs.edit().putString("records", corrupt).commit())

        val plan = ApiTokenTracker.planBatch(
            context,
            listOf(BudgetRequest("must-block-total", "MASTER", "provider", "https://p.invalid/v1", "gpt-4o-mini", 1, 1))
        )
        assertTrue("An internally inconsistent token ledger must fail closed", plan.allowedTaskIds.isEmpty())
        assertTrue(plan.blockedReasons.getValue("must-block-total").contains("rejestr użycia API"))

        val write = runCatching {
            ApiTokenTracker.recordQuery(
                context, "provider", "gpt-4o-mini", "MASTER", "new-task",
                inputTokens = 5, outputTokens = 3, status = "REAL"
            )
        }
        assertTrue("A malformed historical total must not be overwritten by a new record", write.isFailure)
        assertEquals("The original ledger must remain intact for recovery", corrupt, prefs.getString("records", null))
    }


    @Test fun lateRealUsageCanRefineRequestedAliasToCanonicalProviderModel() {
        val key = "team:task-canonical-recovery:gateway"
        val estimate = ApiTokenTracker.recordQuery(
            context, "gateway", "requested-alias", "RESEARCHER", "task-canonical-recovery",
            250L, 900L, status = "UNAVAILABLE", idempotencyKey = key
        )
        val actual = ApiTokenTracker.recordQuery(
            context, "gateway", "canonical-model-v2", "RESEARCHER", "task-canonical-recovery",
            310L, 145L, reasoningTokens = 35L, cachedTokens = 20L,
            status = "REAL", idempotencyKey = key
        )
        val all = ApiTokenTracker.loadAllRecordsFromStorage(context)
        assertEquals(estimate.queryId, actual.queryId)
        assertEquals("canonical-model-v2", actual.modelName)
        assertEquals("REAL", actual.status)
        assertEquals(455L, actual.totalTokens)
        assertEquals(1, all.count { it.queryId == key })
        assertEquals("canonical-model-v2", all.single().modelName)
    }

    @Test fun staleUnavailableRecoveryCannotDowngradeCanonicalRealUsage() {
        val key = "team:task-canonical-replay:gateway"
        val actual = ApiTokenTracker.recordQuery(
            context, "gateway", "canonical-model-v2", "RESEARCHER", "task-canonical-replay",
            310L, 145L, status = "REAL", idempotencyKey = key
        )
        val replayed = ApiTokenTracker.recordQuery(
            context, "gateway", "requested-alias", "RESEARCHER", "task-canonical-replay",
            250L, 4_096L, status = "UNAVAILABLE", idempotencyKey = key
        )
        val all = ApiTokenTracker.loadAllRecordsFromStorage(context)
        assertEquals(actual.queryId, replayed.queryId)
        assertEquals("canonical-model-v2", replayed.modelName)
        assertEquals("REAL", replayed.status)
        assertEquals(455L, replayed.totalTokens)
        assertEquals(1, all.count { it.queryId == key })
    }
    @Test fun cacheReadAndWriteTokensUseIndependentCatalogRates() {
        ApiTokenTracker.replaceCatalogPricingForProvider(
            context, "catalog-cache-write-provider", "https://cache-write.example/v1",
            listOf(CatalogModelPrice("vendor/cache-write-model", 0.23, 0.91, false, 0.05, 0.30))
        )
        val record = ApiTokenTracker.recordQuery(
            context, "catalog-cache-write-provider", "vendor/cache-write-model", "MASTER", "cache-write-task",
            inputTokens = 1_000_000L, outputTokens = 0L, cachedTokens = 200_000L,
            cacheWriteTokens = 300_000L, status = "REAL",
            baseUrl = "https://cache-write.example/v1", idempotencyKey = "cache-write-request"
        )
        // 500k regular at $0.23/M + 200k read at $0.05/M + 300k write at $0.30/M.
        assertEquals(0.215, record.costUsd, 0.000001)
        assertEquals(0.86, record.costPln, 0.00001)
        assertEquals(200_000L, record.cachedTokens)
        assertEquals(300_000L, record.cacheWriteTokens)
        val persisted = ApiTokenTracker.loadAllRecordsFromStorage(context).single()
        assertEquals(300_000L, persisted.cacheWriteTokens)
    }

    @Test fun cacheReadAndWriteCountsCannotExceedInputTokens() {
        val result = runCatching {
            ApiTokenTracker.recordQuery(
                context, "provider", "model", "MASTER", "invalid-cache-counts",
                inputTokens = 100L, outputTokens = 1L, cachedTokens = 70L,
                cacheWriteTokens = 31L, status = "REAL"
            )
        }
        assertTrue(result.isFailure)
        assertTrue(ApiTokenTracker.loadAllRecordsFromStorage(context).isEmpty())
    }

    @Test fun malformedCatalogJsonStopsPricingInsteadOfUsingGenericFallback() {
        val prefs = context.getSharedPreferences("api_token_pricing_v1", Context.MODE_PRIVATE)
        assertTrue(prefs.edit().putString("catalog_model_prices", "not-json").commit())
        assertTrue(runCatching {
            ApiTokenTracker.getPricing(context, "unknown-expensive-model", "provider", "https://pricing.example/v1")
        }.isFailure)
    }

    @Test fun malformedOptionalCatalogRateCannotBeSilentlyDropped() {
        val prefs = context.getSharedPreferences("api_token_pricing_v1", Context.MODE_PRIVATE)
        val malformed = """[{"providerId":"provider","modelName":"vendor/model","baseUrl":"https://pricing.example/v1","inputPer1MUsd":0.2,"outputPer1MUsd":0.8,"cachedInputPer1MUsd":"0.01","explicitlyFree":false}]"""
        assertTrue(prefs.edit().putString("catalog_model_prices", malformed).commit())
        assertTrue(runCatching {
            ApiTokenTracker.getPricing(context, "vendor/model", "provider", "https://pricing.example/v1")
        }.isFailure)
    }

    @Test fun paidCatalogEntryWithoutBothPrimaryRatesFailsClosed() {
        val prefs = context.getSharedPreferences("api_token_pricing_v1", Context.MODE_PRIVATE)
        val incomplete = """[{"providerId":"provider","modelName":"vendor/model","baseUrl":"https://pricing.example/v1","inputPer1MUsd":null,"outputPer1MUsd":0.8,"explicitlyFree":false}]"""
        assertTrue(prefs.edit().putString("catalog_model_prices", incomplete).commit())
        assertTrue(runCatching {
            ApiTokenTracker.getPricing(context, "vendor/model", "provider", "https://pricing.example/v1")
        }.isFailure)
    }

    @Test fun duplicateCatalogIdentityCannotSelectAnArbitraryPrice() {
        val prefs = context.getSharedPreferences("api_token_pricing_v1", Context.MODE_PRIVATE)
        val duplicate = """[{"providerId":"provider","modelName":"vendor/model","baseUrl":"https://pricing.example/v1","inputPer1MUsd":0.2,"outputPer1MUsd":0.8},{"providerId":"provider","modelName":"vendor/model","baseUrl":"https://pricing.example/v1/","inputPer1MUsd":0.5,"outputPer1MUsd":1.2}]"""
        assertTrue(prefs.edit().putString("catalog_model_prices", duplicate).commit())
        assertTrue(runCatching {
            ApiTokenTracker.getPricing(context, "vendor/model", "provider", "https://pricing.example/v1")
        }.isFailure)
    }

    @Test fun legacyUsageRowsWithoutCacheWriteFieldRemainReadable() {
        val prefs = context.getSharedPreferences("api_token_tracker_records_v1", Context.MODE_PRIVATE)
        val legacy = """[{"qId":"legacy-cache-row","ts":1,"prov":"p","model":"m","agent":"MASTER","task":"legacy","in":10,"out":2,"tot":12,"reas":0,"cache":3,"status":"REAL","usd":0.01,"pln":0.04}]"""
        assertTrue(prefs.edit().putString("records", legacy).commit())
        val rows = ApiTokenTracker.loadAllRecordsFromStorage(context)
        assertEquals(1, rows.size)
        assertEquals(0L, rows.single().cacheWriteTokens)
        assertEquals(0L, rows.single().cacheWrite1hTokens)
        assertTrue(ApiTokenTracker.budgetUsageSnapshot(context).ledgerHealthy)
    }

    @Test fun budgetReservationUsesHighestPublishedCacheInputRate() {
        ApiTokenTracker.setLimits(context, daily = 2_000_000L, monthly = 50.0, perAgent = 2_000_000L)
        ApiTokenTracker.replaceCatalogPricingForProvider(
            context, "cache-reserve-provider", "https://cache-reserve.example/v1",
            listOf(CatalogModelPrice("vendor/cache-reserve-model", 0.23, 0.91, false, 0.05, 0.30))
        )
        val plan = ApiTokenTracker.planBatch(
            context,
            listOf(BudgetRequest(
                "cache-reserve-task", "MASTER", "cache-reserve-provider",
                "https://cache-reserve.example/v1", "vendor/cache-reserve-model",
                1_000_000L, 0L
            ))
        )
        assertTrue(plan.allowedTaskIds.contains("cache-reserve-task"))
        // Reserve the whole prompt at the highest published input rate ($0.30/M), not $0.23/M.
        assertEquals(1.20, plan.estimatedReservedCostPln, 0.00001)
        assertEquals(1.20, ApiTokenTracker.activeReservationStats().costPln, 0.00001)
        ApiTokenTracker.releaseBatchReservation(plan.reservationId)
    }

    @Test fun reasoningTokensUseSeparateCatalogRateWhenProviderPublishesOne() {
        ApiTokenTracker.replaceCatalogPricingForProvider(
            context, "reasoning-price-provider", "https://reasoning-price.example/v1",
            listOf(CatalogModelPrice(
                "vendor/reasoning-price-model", 0.23, 0.50, false,
                internalReasoningPer1MUsd = 0.90
            ))
        )
        val record = ApiTokenTracker.recordQuery(
            context, "reasoning-price-provider", "vendor/reasoning-price-model",
            "MASTER", "reasoning-price-task",
            inputTokens = 0L, outputTokens = 1_000_000L, reasoningTokens = 400_000L,
            status = "REAL", baseUrl = "https://reasoning-price.example/v1",
            reasoningTokensKnown = true
        )
        // 600k visible output at $0.50/M + 400k reasoning at $0.90/M.
        assertEquals(0.66, record.costUsd, 0.000001)
        assertEquals(2.64, record.costPln, 0.00001)
    }

    @Test fun reasoningTokensCannotExceedOutputTokensInUsageLedger() {
        val result = runCatching {
            ApiTokenTracker.recordQuery(
                context, "provider", "model", "MASTER", "bad-reasoning-count",
                inputTokens = 1L, outputTokens = 5L, reasoningTokens = 6L, status = "REAL"
            )
        }
        assertTrue(result.isFailure)
        assertTrue(ApiTokenTracker.loadAllRecordsFromStorage(context).isEmpty())
    }

    @Test fun reservationUsesPublishedInternalReasoningRateForOutputEstimate() {
        ApiTokenTracker.setLimits(context, daily = 2_000_000L, monthly = 50.0, perAgent = 2_000_000L)
        ApiTokenTracker.replaceCatalogPricingForProvider(
            context, "reasoning-reserve-provider", "https://reasoning-reserve.example/v1",
            listOf(CatalogModelPrice(
                "vendor/reasoning-reserve-model", 0.23, 0.50, false,
                internalReasoningPer1MUsd = 0.90
            ))
        )
        val plan = ApiTokenTracker.planBatch(
            context,
            listOf(BudgetRequest(
                "reasoning-reserve-task", "MASTER", "reasoning-reserve-provider",
                "https://reasoning-reserve.example/v1", "vendor/reasoning-reserve-model",
                0L, 1_000_000L
            ))
        )
        assertEquals(3.60, plan.estimatedReservedCostPln, 0.00001)
        ApiTokenTracker.releaseBatchReservation(plan.reservationId)
    }

    @Test fun fiveMinuteAndOneHourCacheWritesUseTheirOwnPublishedRates() {
        ApiTokenTracker.replaceCatalogPricingForProvider(
            context, "cache-ttl-provider", "https://cache-ttl.example/v1",
            listOf(CatalogModelPrice(
                "vendor/cache-ttl-model", 0.23, 0.91, false,
                cachedInputPer1MUsd = 0.05,
                cacheWriteInputPer1MUsd = 0.30,
                cacheWriteInput1hPer1MUsd = 0.50
            ))
        )
        val record = ApiTokenTracker.recordQuery(
            context, "cache-ttl-provider", "vendor/cache-ttl-model", "MASTER", "cache-ttl-task",
            inputTokens = 1_000_000L, outputTokens = 0L, cachedTokens = 100_000L,
            cacheWriteTokens = 200_000L, cacheWrite1hTokens = 300_000L,
            status = "REAL", baseUrl = "https://cache-ttl.example/v1"
        )
        // 400k regular + 100k read + 200k 5m write + 300k 1h write.
        assertEquals(0.307, record.costUsd, 0.000001)
        assertEquals(1.228, record.costPln, 0.00001)
        val stored = ApiTokenTracker.loadAllRecordsFromStorage(context).single()
        assertEquals(200_000L, stored.cacheWriteTokens)
        assertEquals(300_000L, stored.cacheWrite1hTokens)
    }

    @Test fun reservationUsesHighestPublishedOneHourCacheWriteRate() {
        ApiTokenTracker.setLimits(context, daily = 2_000_000L, monthly = 50.0, perAgent = 2_000_000L)
        ApiTokenTracker.replaceCatalogPricingForProvider(
            context, "cache-ttl-reserve-provider", "https://cache-ttl-reserve.example/v1",
            listOf(CatalogModelPrice(
                "vendor/cache-ttl-reserve-model", 0.23, 0.91, false,
                cacheWriteInputPer1MUsd = 0.30,
                cacheWriteInput1hPer1MUsd = 0.50
            ))
        )
        val plan = ApiTokenTracker.planBatch(
            context,
            listOf(BudgetRequest(
                "cache-ttl-reserve-task", "MASTER", "cache-ttl-reserve-provider",
                "https://cache-ttl-reserve.example/v1", "vendor/cache-ttl-reserve-model",
                1_000_000L, 0L
            ))
        )
        assertEquals(2.00, plan.estimatedReservedCostPln, 0.00001)
        ApiTokenTracker.releaseBatchReservation(plan.reservationId)
    }

    @Test fun missingReasoningBreakdownUsesConservativePublishedRate() {
        ApiTokenTracker.replaceCatalogPricingForProvider(
            context, "reasoning-unknown-provider", "https://reasoning-unknown.example/v1",
            listOf(CatalogModelPrice(
                "vendor/reasoning-unknown-model", 0.23, 0.50, false,
                internalReasoningPer1MUsd = 0.90
            ))
        )
        val record = ApiTokenTracker.recordQuery(
            context, "reasoning-unknown-provider", "vendor/reasoning-unknown-model",
            "MASTER", "reasoning-unknown-task",
            inputTokens = 0L, outputTokens = 1_000_000L, reasoningTokens = 0L,
            status = "REAL", baseUrl = "https://reasoning-unknown.example/v1",
            reasoningTokensKnown = false
        )
        // Without a provider breakdown, reserve/charge the whole output at the highest published rate.
        assertEquals(0.90, record.costUsd, 0.000001)
        assertEquals(3.60, record.costPln, 0.00001)
        assertFalse(record.reasoningTokensKnown)
    }

    @Test fun ambiguousUsageLedgerCommitQuarantinesBudgetAndFurtherWrites() {
        val failing = AmbiguousCommitContext(context, "api_token_tracker_records_v1")
        val result = runCatching {
            ApiTokenTracker.recordQuery(
                failing, "provider", "model", "MASTER", "ambiguous-usage",
                inputTokens = 40L, outputTokens = 20L, status = "REAL",
                idempotencyKey = "ambiguous-usage-key"
            )
        }
        assertTrue("An ambiguous durable usage write must be reported", result.isFailure)
        assertFalse("Budget diagnostics must expose the quarantined ledger",
            ApiTokenTracker.budgetUsageSnapshot(context).ledgerHealthy)
        val plan = ApiTokenTracker.planBatch(
            context,
            listOf(BudgetRequest("blocked-after-ambiguous-write", "MASTER", "provider",
                "https://provider.invalid/v1", "model", 1L, 1L))
        )
        assertTrue("No new work may be reserved against an uncertain usage ledger", plan.allowedTaskIds.isEmpty())
        assertTrue(runCatching {
            ApiTokenTracker.recordQuery(
                context, "provider", "model", "MASTER", "second-usage",
                inputTokens = 1L, outputTokens = 1L, status = "REAL",
                idempotencyKey = "second-usage-key"
            )
        }.isFailure)
    }


    @Test fun explicitlyApprovedUnknownPricingStillPersistsTokenUsageWithoutInventingZeroCost() {
        val pricingPrefs = context.getSharedPreferences("api_token_pricing_v1", Context.MODE_PRIVATE)
        val incomplete = """[{"providerId":"unknown-price-provider","modelName":"vendor/unpriced-model","baseUrl":"https://unknown-price.example/v1","inputPer1MUsd":null,"outputPer1MUsd":0.8,"explicitlyFree":false}]"""
        assertTrue(pricingPrefs.edit().putString("catalog_model_prices", incomplete).commit())
        val blockedByDefault = runCatching {
            ApiTokenTracker.recordQuery(
                context, "unknown-price-provider", "vendor/unpriced-model", "MASTER", "unknown-price-default",
                inputTokens = 120L, outputTokens = 80L, status = "REAL",
                baseUrl = "https://unknown-price.example/v1", idempotencyKey = "unknown-price-default"
            )
        }
        assertTrue("Unknown pricing must remain blocked unless explicitly approved", blockedByDefault.isFailure)
        assertTrue(ApiTokenTracker.loadAllRecordsFromStorage(context).isEmpty())

        val approved = ApiTokenTracker.recordQuery(
            context, "unknown-price-provider", "vendor/unpriced-model", "MASTER", "unknown-price-approved",
            inputTokens = 120L, outputTokens = 80L, status = "REAL",
            baseUrl = "https://unknown-price.example/v1", idempotencyKey = "unknown-price-approved",
            allowUnknownPricing = true
        )
        assertEquals(200L, approved.totalTokens)
        assertFalse(approved.costKnown)
        assertEquals(0.0, approved.costPln, 0.0)

        val summary = ApiTokenTracker.calculateSummary(ApiTokenTracker.loadAllRecordsFromStorage(context))
        assertEquals(200L, summary.totalTokens)
        assertEquals("Unknown cost must not be presented as a verified zero price", 0.0, summary.costPln, 0.0)

        ApiTokenTracker.resetInMemoryStateForTests()
        val restored = ApiTokenTracker.loadAllRecordsFromStorage(context).single()
        assertFalse("Unknown-cost state must survive process restart", restored.costKnown)
        assertEquals(200L, restored.totalTokens)
    }

    @Test fun legacyUsageRowsWithoutCostKnownFlagRemainKnownCostRows() {
        val prefs = context.getSharedPreferences("api_token_tracker_records_v1", Context.MODE_PRIVATE)
        val legacy = """[{"qId":"legacy-known-cost","ts":1,"prov":"p","model":"m","agent":"MASTER","task":"legacy","in":10,"out":2,"tot":12,"reas":0,"cache":0,"status":"REAL","usd":0.01,"pln":0.04}]"""
        assertTrue(prefs.edit().putString("records", legacy).commit())
        val row = ApiTokenTracker.loadAllRecordsFromStorage(context).single()
        assertTrue("Legacy rows must retain their historical known-cost interpretation", row.costKnown)
        assertEquals(0.04, ApiTokenTracker.calculateSummary(listOf(row)).costPln, 0.000001)
    }

}
