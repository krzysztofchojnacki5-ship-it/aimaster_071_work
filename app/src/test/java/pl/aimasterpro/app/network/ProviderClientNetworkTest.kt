package pl.aimasterpro.app.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import pl.aimasterpro.app.core.ProviderConfig
import pl.aimasterpro.app.core.ProviderKind
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class ProviderClientNetworkTest {
    private fun server(handler: (String, Int) -> Pair<Int, String>): Pair<TestHttpServer, AtomicInteger> {
        val server = TestHttpServer(handler)
        return server to server.requestCount
    }

    private fun config(base: String) = ProviderConfig(
        "local-test", "Local HTTP test", ProviderKind.OPENAI_COMPATIBLE,
        base, "test-model", ""
    )

    @Test
    fun singleOpenAiCompatibleRequestUsesHttpAndClosesResponse() {
        val (server, count) = server { _, _ ->
            200 to """{"choices":[{"message":{"content":"OK"}}],"usage":{"prompt_tokens":3,"completion_tokens":2,"total_tokens":5}}"""
        }
        try {
            val result = ProviderClient().chat(config("http://127.0.0.1:${server.address.port}/v1"), "system", "test")
            assertEquals("OK", result.text)
            assertEquals("REAL", result.usage.status)
            assertEquals(3L, result.usage.inputTokens)
            assertEquals(2L, result.usage.outputTokens)
            assertEquals(1, count.get())
        } finally { server.stop(0) }
    }

    @Test
    fun rejectsCleartextLanEndpointsAndApiKeysBeforeOpeningConnection() {
        val lan = config("http://192.168.1.50:11434/v1")
        try {
            ProviderClient().chat(lan, "system", "test")
            throw AssertionError("Expected cleartext LAN endpoint rejection")
        } catch (e: ProviderException) {
            assertEquals("SECURITY_HTTP", e.category)
        }

        val keyedLoopback = lan.copy(baseUrl = "http://127.0.0.1:11434/v1", apiKey = "must-not-travel-over-http")
        try {
            ProviderClient().chat(keyedLoopback, "system", "test")
            throw AssertionError("Expected cleartext API-key rejection")
        } catch (e: ProviderException) {
            assertEquals("SECURITY_HTTP", e.category)
        }

        val queryCredential = lan.copy(baseUrl = "http://127.0.0.1:11434/v1?access_token=secret")
        try {
            ProviderClient().chat(queryCredential, "system", "test")
            throw AssertionError("Expected cleartext URL credential rejection")
        } catch (e: ProviderException) {
            assertEquals("SECURITY_HTTP", e.category)
        }
    }

    @Test
    fun oversizedProviderResponseIsRejectedWithDedicatedCategory() {
        val oversizedBody = "x".repeat(BoundedResponseReader.DEFAULT_MAX_BYTES + 1)
        val (server, count) = server { _, _ -> 200 to oversizedBody }
        try {
            try {
                ProviderClient().chat(config("http://127.0.0.1:${server.address.port}/v1"), "system", "test")
                throw AssertionError("Expected oversized response rejection")
            } catch (e: ProviderException) {
                assertEquals("RESPONSE_TOO_LARGE", e.category)
            }
            assertEquals(1, count.get())
        } finally { server.stop(0) }
    }

    @Test
    fun partialUsageIsMarkedEstimatedRatherThanReal() {
        val (server, _) = server { _, _ ->
            200 to """{"choices":[{"message":{"content":"OK response text"}}],"usage":{"prompt_tokens":3}}"""
        }
        try {
            val result = ProviderClient().chat(config("http://127.0.0.1:${server.address.port}/v1"), "system", "test")
            assertEquals("ESTIMATED", result.usage.status)
            assertTrue(result.usage.outputTokens > 0L)
        } finally { server.stop(0) }
    }

    @Test
    fun geminiOpenAiCompatibleMissingUsageUsesPositiveEstimatedTokenCounts() {
        val (server, _) = server { _, _ ->
            200 to """{"choices":[{"message":{"content":"Gemini response"}}]}"""
        }
        try {
            val config = ProviderConfig(
                "gemini-local-test", "Gemini compatible test", ProviderKind.GEMINI,
                "http://127.0.0.1:${server.address.port}/v1beta/openai", "test-model", ""
            )
            val result = ProviderClient().chat(config, "system prompt", "user prompt")
            assertEquals("ESTIMATED", result.usage.status)
            assertTrue(result.usage.inputTokens > 0L)
            assertTrue(result.usage.outputTokens > 0L)
            assertEquals(result.usage.inputTokens + result.usage.outputTokens, result.usage.totalTokens)
            assertTrue(result.usage.totalTokens >= 0L)
        } finally { server.stop(0) }
    }

    @Test
    fun getRetriesTransientServerFailureWithBackoff() {
        val (server, count) = server { _, attempt ->
            if (attempt == 1) 503 to "temporary" else 200 to """{"data":[{"id":"test-model"}]}"""
        }
        try {
            val models = ProviderClient().discoverModels(config("http://127.0.0.1:${server.address.port}/v1"))
            assertEquals(listOf("test-model"), models)
            assertEquals(2, count.get())
        } finally { server.stop(0) }
    }

    @Test
    fun postReadTimeoutIsClassifiedAndNotReplayed() {
        val (server, count) = server { _, _ ->
            Thread.sleep(350L)
            200 to """{"choices":[{"message":{"content":"late"}}]}"""
        }
        try {
            try {
                ProviderClient(connectTimeoutMs = 500, readTimeoutMs = 100)
                    .chat(config("http://127.0.0.1:${server.address.port}/v1"), "system", "test")
                throw AssertionError("Expected read timeout")
            } catch (e: ProviderException) {
                assertTrue("Expected READ_TIMEOUT or RESPONSE_TIMEOUT but got ${e.category}", e.category == "READ_TIMEOUT" || e.category == "RESPONSE_TIMEOUT")
            }
            // A timed-out POST may already have been processed: never replay it.
            assertEquals(1, count.get())
        } finally { server.stop(0) }
    }

    @Test
    fun dnsFailureIsReportedAsDnsAndNotGenericNetworkError() {
        try {
            ProviderClient().discoverModels(config("https://this-host-must-not-exist.invalid/v1"))
            throw AssertionError("Expected DNS failure")
        } catch (e: ProviderException) {
            assertEquals("DNS_ERROR", e.category)
            assertTrue(e.message.orEmpty().contains("this-host-must-not-exist.invalid"))
        }
    }

    @Test
    fun cancellingActivePostDisconnectsRequestAndDoesNotRetry() {
        val entered = CountDownLatch(1)
        val server = TestHttpServer { _, _ ->
            entered.countDown()
            try {
                Thread.sleep(3_000L)
                200 to """{"choices":[{"message":{"content":"late"}}]}"""
            } catch (_: Exception) {
                500 to "cancelled"
            }
        }
        val client = ProviderClient(connectTimeoutMs = 1_000, readTimeoutMs = 5_000)
        val requestScope = client.beginRequestScope()
        val failure = AtomicReference<Throwable?>(null)
        val worker = Thread {
            try {
                client.chat(config("http://127.0.0.1:${server.address.port}/v1"), "system", "test", scope = requestScope)
            } catch (t: Throwable) {
                failure.set(t)
            }
        }
        try {
            worker.start()
            assertTrue("Request did not reach local server", entered.await(2, TimeUnit.SECONDS))
            client.cancelActiveRequests()
            worker.join(1_500L)
            assertTrue("Cancelled request should stop promptly", !worker.isAlive)
            assertTrue(failure.get() is ProviderException)
            assertEquals("CANCELLED", (failure.get() as ProviderException).category)
            assertEquals("Cancelled POST must not be replayed", 1, server.requestCount.get())
            client.beginRequestScope() // a new user task must not revive the old batch token
            try {
                client.chat(config("http://127.0.0.1:${server.address.port}/v1"), "system", "late batch task", scope = requestScope)
                throw AssertionError("Late worker should remain blocked after scope cancellation")
            } catch (e: ProviderException) {
                assertEquals("CANCELLED", e.category)
            }
            assertEquals("Late batch worker must not start a second POST", 1, server.requestCount.get())
        } finally {
            client.cancelActiveRequests()
            worker.interrupt()
            server.stop(0)
        }
    }

    @Test
    fun cancellingOneChildScopeDisconnectsOnlyThatAgentRequest() {
        val bothEntered = CountDownLatch(2)
        val cancelledEntered = CountDownLatch(1)
        val keptEntered = CountDownLatch(1)
        val server = TestHttpServer { body, _ ->
            bothEntered.countDown()
            if (body.contains("cancel-me")) {
                cancelledEntered.countDown()
                Thread.sleep(3_000L)
                200 to """{"choices":[{"message":{"content":"cancelled-late"}}]}"""
            } else {
                keptEntered.countDown()
                Thread.sleep(250L)
                200 to """{"choices":[{"message":{"content":"sibling-survived"}}]}"""
            }
        }
        val client = ProviderClient(connectTimeoutMs = 1_000, readTimeoutMs = 5_000)
        val parent = client.beginRequestScope()
        val cancelledScope = client.childRequestScope(parent)
        val survivingScope = client.childRequestScope(parent)
        val cancelledFailure = AtomicReference<Throwable?>(null)
        val survivingResult = AtomicReference<String?>(null)
        val cancelledWorker = Thread {
            try {
                client.chat(config("http://127.0.0.1:${server.address.port}/v1"), "system", "cancel-me", scope = cancelledScope)
            } catch (t: Throwable) { cancelledFailure.set(t) }
        }
        val survivingWorker = Thread {
            try {
                survivingResult.set(client.chat(config("http://127.0.0.1:${server.address.port}/v1"), "system", "keep-me", scope = survivingScope).text)
            } catch (t: Throwable) { survivingResult.set("ERROR:${t.javaClass.simpleName}:${t.message}") }
        }
        try {
            cancelledWorker.start()
            survivingWorker.start()
            assertTrue("Both requests should reach the local server", bothEntered.await(2, TimeUnit.SECONDS))
            assertTrue(cancelledEntered.await(1, TimeUnit.SECONDS))
            assertTrue(keptEntered.await(1, TimeUnit.SECONDS))
            client.cancelRequestScope(cancelledScope)
            cancelledWorker.join(1_500L)
            survivingWorker.join(2_500L)
            assertTrue("Cancelled child should stop promptly", !cancelledWorker.isAlive)
            assertTrue("Sibling request must be allowed to complete", !survivingWorker.isAlive)
            assertEquals("CANCELLED", (cancelledFailure.get() as? ProviderException)?.category)
            assertEquals("sibling-survived", survivingResult.get())
            assertEquals("Only the two original agent POSTs should be sent", 2, server.requestCount.get())
        } finally {
            client.cancelActiveRequests()
            cancelledWorker.interrupt()
            survivingWorker.interrupt()
            server.stop(0)
        }
    }

    @Test
    fun beginningNewChatScopeDoesNotCancelUnscopedModelDiscovery() {
        val entered = CountDownLatch(1)
        val server = TestHttpServer { _, _ ->
            entered.countDown()
            Thread.sleep(250L)
            200 to """{"data":[{"id":"catalog-model"}]}"""
        }
        val client = ProviderClient(connectTimeoutMs = 1_000, readTimeoutMs = 2_000)
        val failure = AtomicReference<Throwable?>(null)
        val worker = Thread {
            try { client.discoverModels(config("http://127.0.0.1:${server.address.port}/v1")) }
            catch (t: Throwable) { failure.set(t) }
        }
        try {
            worker.start()
            assertTrue("Discovery did not reach local server", entered.await(2, TimeUnit.SECONDS))
            client.beginRequestScope()
            worker.join(2_000L)
            assertTrue("Unscoped discovery should complete", !worker.isAlive)
            assertEquals(null, failure.get())
        } finally {
            worker.interrupt()
            server.stop(0)
        }
    }
}
