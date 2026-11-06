package pl.aimasterpro.app.agents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import pl.aimasterpro.app.core.ProviderConfig
import pl.aimasterpro.app.core.ProviderKind
import pl.aimasterpro.app.core.ProviderQuotaScheduler
import pl.aimasterpro.app.core.WorkMode
import pl.aimasterpro.app.network.ProviderClient
import pl.aimasterpro.app.network.TestHttpServer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import java.util.Collections

@RunWith(RobolectricTestRunner::class)
class TeamRunEngineNetworkTest {
    @Test
    fun oneAgentHttpFailureDoesNotCancelOtherParallelAgents() {
        val activeRequests = AtomicInteger()
        val peakParallel = AtomicInteger()
        val communicationLog = Collections.synchronizedList(mutableListOf<String>())
        val server = TestHttpServer { body, _ ->
            val active = activeRequests.incrementAndGet()
            peakParallel.updateAndGet { old -> maxOf(old, active) }
            try {
                Thread.sleep(120L)
                if (body.contains("FAIL_THIS_AGENT")) {
                    500 to "temporary test failure"
                } else {
                    200 to """{"choices":[{"message":{"content":"agent-report"}}],"usage":{"prompt_tokens":4,"completion_tokens":3,"total_tokens":7}}"""
                }
            } finally { activeRequests.decrementAndGet() }
        }
        try {
            val config = ProviderConfig(
                "local-test", "Local HTTP test", ProviderKind.OPENAI_COMPATIBLE,
                "http://127.0.0.1:${server.address.port}/v1", "test-model", ""
            )
            val ids = listOf(
                "repository-researcher", "performance-engineer", "accessibility-specialist",
                "app-architect", "api-researcher", "qa-architect", "reliability-engineer"
            )
            val tasks = ids.mapIndexed { index, id ->
                TeamTask("task-$index", id, "Network test", if (index == 2) "FAIL_THIS_AGENT" else "Return short report")
            }
            val assignments = ids.associateWith { id ->
                ProviderConfig(
                    "local-test-$id", "Local HTTP test $id", ProviderKind.OPENAI_COMPATIBLE,
                    "http://127.0.0.1:${server.address.port}/v1", "test-model-$id", ""
                )
            }
            val results = TeamRunEngine(ProviderClient(), maxParallel = 4).runBatch(
                tasks = tasks,
                provider = config,
                agentProvidersMap = assignments,
                isFreeProvider = { true },
                mode = WorkMode.PLANNING,
                onLog = { sender, recipient, message, status ->
                    communicationLog.add("$sender->$recipient [$status]: $message")
                }
            )
            assertEquals(7, results.size)
            assertEquals(1, results.count { it.status == TeamTaskResult.Status.FAILED })
            assertEquals(6, results.count { it.status == TeamTaskResult.Status.COMPLETED })
            assertEquals(7, server.requestCount.get())
            assertTrue("Expected parallel execution", peakParallel.get() > 1)
            assertTrue("Parallel requests must remain capped at four", peakParallel.get() <= 4)
            assertTrue(results.first { it.agentId == "accessibility-specialist" }.report.contains("HTTP 500"))
            assertTrue("Each provider call must log the outbound request", communicationLog.any {
                it.contains("[REQUEST]") && it.contains("--- USER ---") && it.contains("Return short report")
            })
            assertTrue("Successful agent responses must be logged in full", communicationLog.any {
                it.contains("[REPORT]") && it.contains("agent-report")
            })
        } finally { server.stop(0) }
    }
    @Test
    fun requestCheckpointIsDurableBeforeHttpAndBlocksSendWhenPersistenceFails() {
        ProviderQuotaScheduler.resetForTests()
        val server = TestHttpServer { _, _ ->
            200 to """{"choices":[{"message":{"content":"checkpointed-report"}}],"usage":{"prompt_tokens":4,"completion_tokens":3,"total_tokens":7}}"""
        }
        try {
            val config = ProviderConfig(
                "checkpoint-test", "Checkpoint test", ProviderKind.OPENAI_COMPATIBLE,
                "http://127.0.0.1:${server.address.port}/v1", "test-model", ""
            )
            val lifecycle = Collections.synchronizedList(mutableListOf<String>())
            val successful = TeamRunEngine(ProviderClient()).runBatch(
                tasks = listOf(TeamTask("checkpoint-task", "qa-architect", "Checkpoint", "Return report")),
                provider = config,
                agentProvidersMap = mapOf("qa-architect" to config),
                isFreeProvider = { true },
                mode = WorkMode.PLANNING,
                onRequestStarting = { task, actual, input, output, key ->
                    lifecycle.add("checkpoint")
                    assertEquals("checkpoint-task", task.id)
                    assertEquals(config.id, actual.id)
                    assertTrue(input > 0L)
                    assertEquals("team:checkpoint-task:${config.id}", key)
                    assertEquals(4_096L, output)
                    true
                },
                onUsage = { _, _, _, _ -> lifecycle.add("usage") }
            ).single()
            assertEquals(TeamTaskResult.Status.COMPLETED, successful.status)
            assertEquals(1, server.requestCount.get())
            assertEquals(listOf("checkpoint", "usage"), lifecycle.toList())

            ProviderQuotaScheduler.resetForTests()
            val blocked = TeamRunEngine(ProviderClient()).runBatch(
                tasks = listOf(TeamTask("checkpoint-blocked", "qa-architect", "Checkpoint", "Do not send")),
                provider = config,
                agentProvidersMap = mapOf("qa-architect" to config),
                isFreeProvider = { true },
                mode = WorkMode.PLANNING,
                onRequestStarting = { _, _, _, _, _ -> false }
            ).single()
            assertEquals(TeamTaskResult.Status.FAILED, blocked.status)
            assertEquals("Failed checkpoint persistence must prevent HTTP", 1, server.requestCount.get())
        } finally {
            ProviderQuotaScheduler.resetForTests()
            server.stop(0)
        }
    }

    @Test
    fun completedReportIsPersistedBeforeTerminalCheckpoint() {
        ProviderQuotaScheduler.resetForTests()
        val server = TestHttpServer { _, _ ->
            200 to """{"choices":[{"message":{"content":"durable-agent-report"}}],"usage":{"prompt_tokens":5,"completion_tokens":4,"total_tokens":9}}"""
        }
        try {
            val config = ProviderConfig(
                "result-persist-test", "Result persistence test", ProviderKind.OPENAI_COMPATIBLE,
                "http://127.0.0.1:${server.address.port}/v1", "test-model", ""
            )
            val events = Collections.synchronizedList(mutableListOf<String>())
            val result = TeamRunEngine(ProviderClient()).runBatch(
                tasks = listOf(TeamTask("result-task", "qa-architect", "Persist report", "Return report")),
                provider = config,
                agentProvidersMap = mapOf("qa-architect" to config),
                isFreeProvider = { true },
                mode = WorkMode.PLANNING,
                onTaskResult = { completed ->
                    assertEquals("result-task", completed.taskId)
                    assertTrue(completed.report.contains("durable-agent-report"))
                    events.add("result-persisted")
                    true
                },
                onTaskState = { _, state, _, _, _, _ ->
                    if (state == "COMPLETED") events.add("terminal-checkpoint")
                }
            ).single()
            assertEquals(TeamTaskResult.Status.COMPLETED, result.status)
            assertTrue(events.indexOf("result-persisted") >= 0)
            assertTrue(events.indexOf("terminal-checkpoint") > events.indexOf("result-persisted"))

            ProviderQuotaScheduler.resetForTests()
            var pendingState = ""
            val notPersisted = TeamRunEngine(ProviderClient()).runBatch(
                tasks = listOf(TeamTask("result-pending-task", "qa-architect", "Persist report", "Return report")),
                provider = config,
                agentProvidersMap = mapOf("qa-architect" to config),
                isFreeProvider = { true },
                mode = WorkMode.PLANNING,
                onTaskResult = { false },
                onTaskState = { _, state, _, _, _, _ -> if (state == "RESULT_PERSIST_PENDING") pendingState = state }
            ).single()
            assertEquals(TeamTaskResult.Status.COMPLETED, notPersisted.status)
            assertEquals("RESULT_PERSIST_PENDING", pendingState)
        } finally {
            ProviderQuotaScheduler.resetForTests()
            server.stop(0)
        }
    }

    @Test
    fun usagePersistenceFailureDoesNotRetrySuccessfulProviderRequest() {
        ProviderQuotaScheduler.resetForTests()
        val server = TestHttpServer { _, _ ->
            200 to """{"choices":[{"message":{"content":"valid-agent-report"}}],"usage":{"prompt_tokens":4,"completion_tokens":3,"total_tokens":7}}"""
        }
        try {
            val primary = ProviderConfig(
                "primary-test", "Primary test", ProviderKind.OPENAI_COMPATIBLE,
                "http://127.0.0.1:${server.address.port}/v1", "test-model", ""
            )
            val fallback = primary.copy(id = "fallback-test", name = "Fallback test", model = "fallback-model")
            val logs = Collections.synchronizedList(mutableListOf<String>())
            val lifecycle = Collections.synchronizedList(mutableListOf<String>())
            val checkpointStates = Collections.synchronizedList(mutableListOf<String>())
            val result = TeamRunEngine(ProviderClient()).runBatch(
                tasks = listOf(TeamTask("usage-task", "qa-architect", "Usage persistence test", "Return a report")),
                provider = primary,
                agentProvidersMap = mapOf("qa-architect" to primary),
                fallbackProviders = listOf(fallback),
                isFreeProvider = { true },
                mode = WorkMode.PLANNING,
                onLog = { _, _, message, status -> logs.add("$status $message") },
                onUsage = { _, _, _, _ ->
                    lifecycle.add("usage")
                    throw IllegalStateException("simulated local storage failure")
                },
                onTaskState = { _, state, _, _, _, _ -> checkpointStates.add(state) },
                onTaskFinished = { lifecycle.add("finished") }
            ).single()

            assertEquals(TeamTaskResult.Status.COMPLETED, result.status)
            assertEquals("valid-agent-report", result.report)
            assertEquals("A successful response must not trigger a second provider request", 1, server.requestCount.get())
            assertTrue("The local accounting failure must remain visible", logs.any { it.contains("USAGE_RECORD_FAILED") })
            assertEquals("Worker completion callback must follow usage accounting", listOf("usage", "finished"), lifecycle.toList())
            assertTrue("Failed durable usage accounting must remain recoverable after a completed response",
                checkpointStates.contains("USAGE_PENDING"))
            assertTrue("The task must not be marked terminal before usage can be recovered",
                checkpointStates.last() != "COMPLETED")
        } finally {
            ProviderQuotaScheduler.resetForTests()
            server.stop(0)
        }
    }

    @Test
    fun postResponseLoggingFailureDoesNotRetrySuccessfulAgentRequest() {
        ProviderQuotaScheduler.resetForTests()
        val server = TestHttpServer { _, _ ->
            200 to """{"choices":[{"message":{"content":"report-survives-logging-failure"}}],"usage":{"prompt_tokens":4,"completion_tokens":3,"total_tokens":7}}"""
        }
        try {
            val primary = ProviderConfig(
                "logging-primary", "Logging primary", ProviderKind.OPENAI_COMPATIBLE,
                "http://127.0.0.1:${server.address.port}/v1", "test-model", ""
            )
            val fallback = primary.copy(id = "logging-fallback", name = "Logging fallback", model = "fallback-model")
            val result = TeamRunEngine(ProviderClient()).runBatch(
                tasks = listOf(TeamTask("logging-task", "qa-architect", "Logging isolation", "Return a report")),
                provider = primary,
                agentProvidersMap = mapOf("qa-architect" to primary),
                fallbackProviders = listOf(fallback),
                isFreeProvider = { true },
                mode = WorkMode.PLANNING,
                onLog = { _, _, _, status ->
                    if (status == "COMPLETE" || status == "REPORT") {
                        throw IllegalStateException("simulated local logging failure")
                    }
                }
            ).single()

            assertEquals(TeamTaskResult.Status.COMPLETED, result.status)
            assertEquals("report-survives-logging-failure", result.report)
            assertEquals("Logging failure must not trigger a second paid request", 1, server.requestCount.get())
        } finally {
            ProviderQuotaScheduler.resetForTests()
            server.stop(0)
        }
    }

    @Test
    fun peerReviewTargetIsVisibleInTheActualOutboundRequestAndResult() {
        val server = TestHttpServer { _, _ ->
            200 to """{"choices":[{"message":{"content":"review-report"}}],"usage":{"prompt_tokens":5,"completion_tokens":4,"total_tokens":9}}"""
        }
        try {
            val config = ProviderConfig(
                "local-test", "Local HTTP test", ProviderKind.OPENAI_COMPATIBLE,
                "http://127.0.0.1:${server.address.port}/v1", "test-model", ""
            )
            val logs = Collections.synchronizedList(mutableListOf<String>())
            val result = TeamRunEngine(ProviderClient()).runBatch(
                tasks = listOf(TeamTask(
                    "peer-review-test", "critical-reviewer", "Review report", "Check this report",
                    reviewOfAgentId = "app-architect"
                )),
                provider = config,
                agentProvidersMap = mapOf("critical-reviewer" to config),
                isFreeProvider = { true },
                mode = WorkMode.PLANNING,
                onLog = { sender, recipient, message, status -> logs.add("$sender->$recipient [$status]: $message") }
            ).single()
            println("RESULT REPORT: ${result.report}")
            assertEquals(TeamTaskResult.Status.COMPLETED, result.status)
            assertEquals("app-architect", result.reviewOfAgentId)
            assertTrue(logs.any { it.contains("TaskType: PEER_REVIEW") && it.contains("ReviewOfAgent: app-architect") })
            assertTrue(logs.any { it.contains("review-report") && it.contains("[REPORT]") })
        } finally { server.stop(0) }
    }

    @Test
    fun schedulerCooldownDoesNotRecordProviderHealthFailure() {
        ProviderQuotaScheduler.resetForTests()
        val server = TestHttpServer { _, _ ->
            200 to """{"choices":[{"message":{"content":"should-not-be-called"}}]}"""
        }
        val client = ProviderClient()
        try {
            val config = ProviderConfig(
                "quota-test", "Quota test", ProviderKind.OPENAI_COMPATIBLE,
                "http://127.0.0.1:${server.address.port}/v1", "test-model", ""
            )
            val quotaKey = ProviderQuotaScheduler.key(config.id, config.baseUrl, config.model)
            ProviderQuotaScheduler.markRateLimited(quotaKey)
            val healthEvents = Collections.synchronizedList(mutableListOf<Boolean>())
            val logs = Collections.synchronizedList(mutableListOf<String>())

            val result = TeamRunEngine(client).runBatch(
                tasks = listOf(TeamTask("quota-task", "qa-architect", "Scheduler test", "Return a report")),
                provider = config,
                agentProvidersMap = mapOf("qa-architect" to config),
                isFreeProvider = { true },
                mode = WorkMode.PLANNING,
                onLog = { _, _, message, status -> logs.add("$status $message") },
                onProviderHealth = { _, success, _, _ -> healthEvents.add(success) },
                persistedCooldownMs = { 0L },
                requestScope = client.beginRequestScope()
            )

            assertEquals(TeamTaskResult.Status.FAILED, result.single().status)
            assertTrue("Scheduler should explain why the profile was skipped", logs.any { it.contains("MODEL_THROTTLED") })
            assertTrue("A scheduler cooldown must not be stored as a provider failure", healthEvents.isEmpty())
            assertEquals("No API request should be sent while the scheduler is in cooldown", 0, server.requestCount.get())
        } finally {
            client.cancelActiveRequests()
            ProviderQuotaScheduler.resetForTests()
            server.stop(0)
        }
    }
    @Test
    fun preSubmitValidationFailureStillSignalsTaskCompletion() {
        val finished = Collections.synchronizedList(mutableListOf<String>())
        val config = ProviderConfig(
            "validation-test", "Validation test", ProviderKind.OPENAI_COMPATIBLE,
            "http://127.0.0.1:1/v1", "test-model", ""
        )
        val result = runCatching {
            TeamRunEngine(ProviderClient()).runBatch(
                tasks = listOf(TeamTask("invalid-task", "qa-architect", "", "prompt")),
                provider = config,
                isFreeProvider = { true },
                mode = WorkMode.PLANNING,
                onTaskFinished = { finished.add(it) }
            )
        }

        assertTrue("Invalid task should fail validation before any worker is submitted", result.isFailure)
        assertEquals("Reservation lifecycle must still close pre-submit tasks", listOf("invalid-task"), finished.toList())
    }

    @Test
    fun coordinatorInterruptionMarksQueuedTasksCancelled() {
        val requestStarted = CountDownLatch(1)
        val server = TestHttpServer { _, _ ->
            requestStarted.countDown()
            Thread.sleep(700L)
            200 to """{"choices":[{"message":{"content":"late-report"}}],"usage":{"prompt_tokens":2,"completion_tokens":2,"total_tokens":4}}"""
        }
        val client = ProviderClient()
        val states = Collections.synchronizedList(mutableListOf<Pair<String, String>>())
        val finished = Collections.synchronizedList(mutableListOf<String>())
        val finishedLatch = CountDownLatch(2)
        val config = ProviderConfig(
            "interrupt-test", "Interrupt test", ProviderKind.OPENAI_COMPATIBLE,
            "http://127.0.0.1:" + server.address.port + "/v1", "test-model", ""
        )
        val tasks = listOf(
            TeamTask("interrupt-running", "qa-architect", "Interrupt test", "Return a report"),
            TeamTask("interrupt-queued", "api-researcher", "Interrupt test", "Return a report")
        )
        val coordinator = Thread {
            TeamRunEngine(client, maxParallel = 1).runBatch(
                tasks = tasks,
                provider = config,
                agentProvidersMap = tasks.associate { it.agentId to config.copy(id = "provider-" + it.agentId) },
                isFreeProvider = { true },
                mode = WorkMode.PLANNING,
                onLog = { _, _, _, _ -> },
                onTaskState = { task, status, _, _, _, _ -> states.add(task.id to status) },
                requestScope = client.beginRequestScope(),
                onTaskFinished = { taskId ->
                    finished.add(taskId)
                    finishedLatch.countDown()
                }
            )
        }
        try {
            coordinator.start()
            assertTrue("First agent should reach the local HTTP server", requestStarted.await(5, TimeUnit.SECONDS))
            coordinator.interrupt()
            coordinator.join(5_000L)
            assertTrue("Coordinator should finish after interruption", !coordinator.isAlive)
            assertTrue("Running task should reach a terminal cancellation state", states.any {
                it.first == "interrupt-running" && it.second == "CANCELLED"
            })
            assertTrue("Queued task must not remain QUEUED after coordinator interruption", states.any {
                it.first == "interrupt-queued" && it.second == "CANCELLED"
            })
            assertTrue("Every worker must signal actual completion after cancellation",
                finishedLatch.await(5, TimeUnit.SECONDS))
            assertEquals(setOf("interrupt-running", "interrupt-queued"), finished.toSet())
        } finally {
            client.cancelActiveRequests()
            server.stop(0)
            if (coordinator.isAlive) {
                coordinator.interrupt()
                coordinator.join(1_000L)
            }
        }
    }


    @Test
    fun cancellingInFlightPostRecordsUnknownUsageAndNeverStartsFallback() {
        val requestEntered = CountDownLatch(1)
        val allowResponse = CountDownLatch(1)
        val primaryServer = TestHttpServer { _, _ ->
            requestEntered.countDown()
            allowResponse.await(3, TimeUnit.SECONDS)
            200 to """{"choices":[{"message":{"content":"late-cancelled-report"}}]}"""
        }
        val fallbackServer = TestHttpServer { _, _ ->
            200 to """{"choices":[{"message":{"content":"fallback-must-not-run"}}]}"""
        }
        try {
            val primary = ProviderConfig(
                "cancel-primary", "Cancel primary", ProviderKind.OPENAI_COMPATIBLE,
                "http://127.0.0.1:${primaryServer.address.port}/v1", "test-model", ""
            )
            val fallback = ProviderConfig(
                "cancel-fallback", "Cancel fallback", ProviderKind.OPENAI_COMPATIBLE,
                "http://127.0.0.1:${fallbackServer.address.port}/v1", "backup-model", ""
            )
            val client = ProviderClient(connectTimeoutMs = 1_000, readTimeoutMs = 5_000)
            val parentScope = client.beginRequestScope()
            val unknownUsage = Collections.synchronizedList(mutableListOf<String>())
            val resultRef = AtomicReference<List<TeamTaskResult>?>(null)
            val coordinator = Thread {
                resultRef.set(TeamRunEngine(client).runBatch(
                    tasks = listOf(TeamTask("cancel-post-task", "qa-architect", "Cancel POST", "Return a report")),
                    provider = primary,
                    agentProvidersMap = mapOf("qa-architect" to primary),
                    fallbackProviders = listOf(fallback),
                    isFreeProvider = { true },
                    mode = WorkMode.PLANNING,
                    onLog = { _, _, _, _ -> },
                    requestScope = parentScope,
                    onUnknownUsage = { agentId, taskId, _, _, _ ->
                        unknownUsage.add("$agentId:$taskId")
                    }
                ))
            }
            coordinator.start()
            assertTrue("Primary provider did not receive the POST", requestEntered.await(3, TimeUnit.SECONDS))
            client.cancelRequestScope(parentScope)
            allowResponse.countDown()
            coordinator.join(5_000)
            assertFalse("Coordinator did not finish after request cancellation", coordinator.isAlive)
            assertEquals(1, primaryServer.requestCount.get())
            assertEquals("Cancellation after POST must never invoke a fallback", 0, fallbackServer.requestCount.get())
            assertEquals(listOf("qa-architect:cancel-post-task"), unknownUsage.toList())
            assertEquals(TeamTaskResult.Status.FAILED, resultRef.get()?.single()?.status)
        } finally {
            allowResponse.countDown()
            primaryServer.stop(0)
            fallbackServer.stop(0)
        }
    }

    @Test
    fun ambiguousPostOutcomeIsAccountedAsUnavailableAndDoesNotFallback() {
        val server = TestHttpServer { _, _ ->
            Thread.sleep(500L)
            200 to """{"choices":[{"message":{"content":"late-report"}}]}"""
        }
        try {
            val primary = ProviderConfig(
                "ambiguous-primary", "Ambiguous primary", ProviderKind.OPENAI_COMPATIBLE,
                "http://127.0.0.1:${server.address.port}/v1", "test-model", ""
            )
            val fallback = primary.copy(id = "ambiguous-fallback", name = "Ambiguous fallback", model = "backup-model")
            val unknownUsage = Collections.synchronizedList(mutableListOf<Triple<String, Long, Long>>())
            val results = TeamRunEngine(ProviderClient(connectTimeoutMs = 500, readTimeoutMs = 100)).runBatch(
                tasks = listOf(TeamTask("ambiguous-task", "qa-architect", "Ambiguous POST", "Return a report")),
                provider = primary,
                agentProvidersMap = mapOf("qa-architect" to primary),
                fallbackProviders = listOf(primary, fallback),
                isFreeProvider = { true },
                mode = WorkMode.PLANNING,
                onLog = { _, _, _, _ -> },
                onUnknownUsage = { agentId, taskId, _, input, output ->
                    unknownUsage.add(Triple("$agentId:$taskId", input, output))
                }
            )
            assertEquals(1, results.size)
            assertEquals(TeamTaskResult.Status.FAILED, results.single().status)
            assertEquals("An ambiguous POST must not be replayed against a fallback provider", 1, server.requestCount.get())
            assertEquals(1, unknownUsage.size)
            assertEquals("qa-architect:ambiguous-task", unknownUsage.single().first)
            assertTrue("Input estimate must be positive", unknownUsage.single().second > 0L)
            assertTrue("Output allowance must be positive", unknownUsage.single().third > 0L)
        } finally {
            server.stop(0)
        }
    }

}
