package pl.aimasterpro.app.agents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import pl.aimasterpro.app.core.ProviderConfig
import pl.aimasterpro.app.core.ProviderKind
import pl.aimasterpro.app.core.WorkMode
import pl.aimasterpro.app.network.ProviderClient
import pl.aimasterpro.app.network.TestHttpServer
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

}
