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

    private fun assertEstimatedUsage(usageJson: String) {
        val (server, _) = server { _, _ ->
            200 to """{"choices":[{"message":{"content":"response"}}],"usage":$usageJson}"""
        }
        try {
            val result = ProviderClient().chat(config("http://127.0.0.1:${server.address.port}/v1"), "system", "test")
            assertEquals("ESTIMATED", result.usage.status)
            assertTrue(result.usage.inputTokens >= 0L)
            assertTrue(result.usage.outputTokens >= 0L)
            assertEquals(result.usage.inputTokens + result.usage.outputTokens, result.usage.totalTokens)
            assertEquals(0L, result.usage.cachedTokens)
            assertEquals(0L, result.usage.cacheWriteTokens)
            assertEquals(0L, result.usage.reasoningTokens)
        } finally { server.stop(0) }
    }

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
                assertEquals("READ_RESPONSE", e.phase)
                assertTrue("A successful POST response that cannot be read may still be billable", e.requestMayHaveBeenProcessed)
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
                assertEquals("READ_RESPONSE", e.phase)
                assertTrue("A timed-out POST after submission has an unknown billing outcome", e.requestMayHaveBeenProcessed)
            }
            // A timed-out POST may already have been processed: never replay it.
            assertEquals(1, count.get())
        } finally { server.stop(0) }
    }

    @Test
    fun postServerErrorIsMarkedAsAmbiguousAndNotReplayed() {
        val (server, count) = server { _, _ -> 500 to "provider internal error" }
        try {
            try {
                ProviderClient().chat(config("http://127.0.0.1:${server.address.port}/v1"), "system", "test")
                throw AssertionError("Expected HTTP 500")
            } catch (e: ProviderException) {
                assertEquals("SERVER_ERROR", e.category)
                assertEquals("READ_RESPONSE", e.phase)
                assertTrue("A provider 5xx may follow partial processing of a POST", e.requestMayHaveBeenProcessed)
            }
            assertEquals("A possibly processed POST must not be retried", 1, count.get())
        } finally { server.stop(0) }
    }

    @Test
    fun dnsFailureIsReportedAsDnsAndNotGenericNetworkError() {
        try {
            ProviderClient().discoverModels(config("https://this-host-must-not-exist.invalid/v1"))
            throw AssertionError("Expected DNS failure")
        } catch (e: ProviderException) {
            assertEquals("DNS_ERROR", e.category)
            assertEquals("CONNECT", e.phase)
            assertTrue("DNS failure occurs before a billable POST can reach the provider", !e.requestMayHaveBeenProcessed)
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
            assertTrue("Cancellation after the POST reached the server must preserve unknown billing state",
                (failure.get() as ProviderException).requestMayHaveBeenProcessed)
            assertTrue("Cancellation must retain the transport phase",
                (failure.get() as ProviderException).phase in setOf("WAIT_RESPONSE", "READ_RESPONSE"))
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
    fun cancellingParentScopeDisconnectsItsAgentChildrenOnly() {
        val entered = CountDownLatch(1)
        val server = TestHttpServer { _, _ ->
            entered.countDown()
            Thread.sleep(3_000L)
            200 to """{"choices":[{"message":{"content":"late"}}]}"""
        }
        val client = ProviderClient(connectTimeoutMs = 1_000, readTimeoutMs = 5_000)
        val parent = client.beginRequestScope()
        val child = client.childRequestScope(parent)
        val failure = AtomicReference<Throwable?>(null)
        val worker = Thread {
            try {
                client.chat(config("http://127.0.0.1:${server.address.port}/v1"), "system", "child-call", scope = child)
            } catch (t: Throwable) { failure.set(t) }
        }
        try {
            worker.start()
            assertTrue("Child request should reach the local server", entered.await(2, TimeUnit.SECONDS))
            client.cancelRequestScope(parent)
            worker.join(1_500L)
            assertTrue("Parent cancellation should stop its child promptly", !worker.isAlive)
            assertEquals("CANCELLED", (failure.get() as? ProviderException)?.category)
            assertEquals(1, server.requestCount.get())
        } finally {
            client.cancelActiveRequests()
            worker.interrupt()
            server.stop(0)
        }
    }

    @Test
    fun startingIndependentTopLevelScopesDoesNotCancelEarlierRequest() {
        val bothEntered = CountDownLatch(2)
        val server = TestHttpServer { body, _ ->
            bothEntered.countDown()
            Thread.sleep(250L)
            val answer = if (body.contains("first-independent")) "first-ok" else "second-ok"
            200 to """{"choices":[{"message":{"content":"$answer"}}]}"""
        }
        val client = ProviderClient(connectTimeoutMs = 1_000, readTimeoutMs = 3_000)
        val firstScope = client.beginRequestScope()
        val firstResult = AtomicReference<String?>(null)
        val secondResult = AtomicReference<String?>(null)
        val firstWorker = Thread {
            try {
                firstResult.set(client.chat(config("http://127.0.0.1:${server.address.port}/v1"), "system", "first-independent", scope = firstScope).text)
            } catch (t: Throwable) { firstResult.set("ERROR:${t.javaClass.simpleName}:${t.message}") }
        }
        val secondWorker = Thread {
            try {
                val secondScope = client.beginRequestScope()
                secondResult.set(client.chat(config("http://127.0.0.1:${server.address.port}/v1"), "system", "second-independent", scope = secondScope).text)
            } catch (t: Throwable) { secondResult.set("ERROR:${t.javaClass.simpleName}:${t.message}") }
        }
        try {
            firstWorker.start()
            Thread.sleep(50L)
            secondWorker.start()
            assertTrue("Both independent requests should reach the server", bothEntered.await(2, TimeUnit.SECONDS))
            firstWorker.join(2_000L)
            secondWorker.join(2_000L)
            assertTrue("First independent request should complete", !firstWorker.isAlive)
            assertTrue("Second independent request should complete", !secondWorker.isAlive)
            assertEquals("first-ok", firstResult.get())
            assertEquals("second-ok", secondResult.get())
            assertEquals(2, server.requestCount.get())
        } finally {
            client.cancelActiveRequests()
            firstWorker.interrupt()
            secondWorker.interrupt()
            server.stop(0)
        }
    }

    @Test
    fun beginningNewChatScopeDoesNotCancelIndependentModelDiscovery() {
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
            assertTrue("Independent model discovery should complete", !worker.isAlive)
            assertEquals(null, failure.get())
        } finally {
            worker.interrupt()
            server.stop(0)
        }
    }

    @Test
    fun cancellingGlobalRequestsDisconnectsModelDiscoveryWithoutRetry() {
        val entered = CountDownLatch(1)
        val releaseResponse = CountDownLatch(1)
        val server = TestHttpServer { _, _ ->
            entered.countDown()
            releaseResponse.await(2, TimeUnit.SECONDS)
            200 to """{"data":[{"id":"late-catalog-model"}]}"""
        }
        val client = ProviderClient(connectTimeoutMs = 1_000, readTimeoutMs = 5_000)
        val failure = AtomicReference<Throwable?>(null)
        val worker = Thread {
            try {
                client.discoverModels(config("http://127.0.0.1:${server.address.port}/v1"))
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        try {
            worker.start()
            assertTrue("Model discovery did not reach the local server",
                entered.await(2, TimeUnit.SECONDS))
            client.cancelActiveRequests()
            worker.join(1_500L)
            assertTrue("Global cancellation must disconnect model discovery promptly", !worker.isAlive)
            assertEquals("CANCELLED", (failure.get() as? ProviderException)?.category)
            assertEquals("A cancelled GET must not be retried", 1, server.requestCount.get())
        } finally {
            releaseResponse.countDown()
            worker.interrupt()
            server.stop(0)
        }
    }


    @Test
    fun responsePreservesCanonicalProviderAndModelIdentity() {
        val (server, _) = server { _, _ ->
            200 to """{"model":"vendor/canonical-model-v2","choices":[{"message":{"content":"OK"}}],"usage":{"prompt_tokens":3,"completion_tokens":2,"total_tokens":5}}"""
        }
        try {
            val configured = config("http://127.0.0.1:${server.address.port}/v1")
            val result = ProviderClient().chat(configured, "system", "test")
            assertEquals(configured.id, result.providerId)
            assertEquals("vendor/canonical-model-v2", result.model)
        } finally { server.stop(0) }
    }


    @Test
    fun fractionalTokenCountersAreEstimatedInsteadOfCoerced() {
        assertEstimatedUsage("""{"prompt_tokens":3.5,"completion_tokens":2,"total_tokens":5.5}""")
    }

    @Test
    fun stringTokenCountersAreEstimatedInsteadOfCoerced() {
        assertEstimatedUsage("""{"prompt_tokens":"3","completion_tokens":2,"total_tokens":5}""")
    }

    @Test
    fun inconsistentAndOverflowingReportedTotalsAreEstimated() {
        assertEstimatedUsage("""{"prompt_tokens":3,"completion_tokens":2,"total_tokens":99}""")
        assertEstimatedUsage("""{"prompt_tokens":3,"completion_tokens":2,"total_tokens":"5"}""")
        assertEstimatedUsage("""{"prompt_tokens":9223372036854775807,"completion_tokens":1}""")
    }

    @Test
    fun malformedOptionalUsageCountersInvalidateRealUsage() {
        assertEstimatedUsage("""{"prompt_tokens":3,"completion_tokens":2,"total_tokens":5,"prompt_tokens_details":{"cached_tokens":1.5}}""")
        assertEstimatedUsage("""{"prompt_tokens":3,"completion_tokens":2,"total_tokens":5,"completion_tokens_details":{"reasoning_tokens":"1"}}""")
        assertEstimatedUsage("""{"prompt_tokens":3,"completion_tokens":2,"total_tokens":5,"prompt_tokens_details":{"cached_tokens":4}}""")
        assertEstimatedUsage("""{"prompt_tokens":3,"completion_tokens":2,"total_tokens":5,"completion_tokens_details":{"reasoning_tokens":3}}""")
    }


    @Test
    fun anthropicStringUsageCountersAreEstimated() {
        val (server, _) = server { _, _ ->
            200 to """{"content":[{"type":"text","text":"response"}],"usage":{"input_tokens":"3","output_tokens":2}}"""
        }
        try {
            val cfg = ProviderConfig(
                "anthropic-local-test", "Anthropic local test", ProviderKind.ANTHROPIC,
                "http://127.0.0.1:${server.address.port}/v1", "test-model", ""
            )
            val result = ProviderClient().chat(cfg, "system", "test")
            assertEquals("ESTIMATED", result.usage.status)
        } finally { server.stop(0) }
    }

    @Test
    fun nativeGeminiFractionalUsageCountersAreEstimated() {
        val (server, _) = server { _, _ ->
            200 to """{"modelVersion":"test-model","candidates":[{"content":{"parts":[{"text":"response"}]}}],"usageMetadata":{"promptTokenCount":3.5,"candidatesTokenCount":2,"totalTokenCount":5.5}}"""
        }
        try {
            val cfg = ProviderConfig(
                "gemini-native-local-test", "Gemini native local test", ProviderKind.GEMINI,
                "http://127.0.0.1:${server.address.port}/v1beta", "test-model", ""
            )
            val result = ProviderClient().chat(cfg, "system", "test")
            assertEquals("ESTIMATED", result.usage.status)
        } finally { server.stop(0) }
    }

    @Test
    fun anthropicCacheReadTokensAreIncludedInBillableInputTotals() {
        val (server, _) = server { _, _ ->
            200 to """{"content":[{"type":"text","text":"response"}],"usage":{"input_tokens":3,"output_tokens":2,"cache_read_input_tokens":8}}"""
        }
        try {
            val cfg = ProviderConfig(
                "anthropic-cache-local-test", "Anthropic cache local test", ProviderKind.ANTHROPIC,
                "http://127.0.0.1:${server.address.port}/v1", "test-model", ""
            )
            val result = ProviderClient().chat(cfg, "system", "test")
            assertEquals("REAL", result.usage.status)
            assertEquals(11L, result.usage.inputTokens)
            assertEquals(8L, result.usage.cachedTokens)
            assertEquals(13L, result.usage.totalTokens)
        } finally { server.stop(0) }
    }

    @Test
    fun anthropicCacheCreationTokensAreIncludedAndMalformedCountsAreRejected() {
        val (server, _) = server { _, _ ->
            200 to """{"content":[{"type":"text","text":"response"}],"usage":{"input_tokens":3,"output_tokens":2,"cache_read_input_tokens":8,"cache_creation_input_tokens":4}}"""
        }
        try {
            val cfg = ProviderConfig(
                "anthropic-cache-create-local-test", "Anthropic cache creation local test", ProviderKind.ANTHROPIC,
                "http://127.0.0.1:${server.address.port}/v1", "test-model", ""
            )
            val result = ProviderClient().chat(cfg, "system", "test")
            assertEquals("REAL", result.usage.status)
            assertEquals(15L, result.usage.inputTokens)
            assertEquals(8L, result.usage.cachedTokens)
            assertEquals(4L, result.usage.cacheWriteTokens)
            assertEquals(17L, result.usage.totalTokens)
        } finally { server.stop(0) }

        val (malformedServer, _) = server { _, _ ->
            200 to """{"content":[{"type":"text","text":"response"}],"usage":{"input_tokens":3,"output_tokens":2,"cache_creation_input_tokens":"4"}}"""
        }
        try {
            val cfg = ProviderConfig(
                "anthropic-cache-malformed-local-test", "Anthropic cache malformed local test", ProviderKind.ANTHROPIC,
                "http://127.0.0.1:${malformedServer.address.port}/v1", "test-model", ""
            )
            val result = ProviderClient().chat(cfg, "system", "test")
            assertEquals("ESTIMATED", result.usage.status)
            assertEquals(0L, result.usage.cachedTokens)
        } finally { malformedServer.stop(0) }
    }

    @Test
    fun openRouterCacheWriteUsageIsSeparatedAndMalformedWriteCountsAreEstimated() {
        val (server, _) = server { _, _ ->
            200 to """{"choices":[{"message":{"content":"response"}}],"usage":{"prompt_tokens":100,"completion_tokens":2,"total_tokens":102,"prompt_tokens_details":{"cached_tokens":20,"cache_write_tokens":30}}}"""
        }
        try {
            val result = ProviderClient().chat(
                config("http://127.0.0.1:${server.address.port}/v1"), "system", "test"
            )
            assertEquals("REAL", result.usage.status)
            assertEquals(100L, result.usage.inputTokens)
            assertEquals(20L, result.usage.cachedTokens)
            assertEquals(30L, result.usage.cacheWriteTokens)
            assertEquals(102L, result.usage.totalTokens)
        } finally { server.stop(0) }

        assertEstimatedUsage("""{"prompt_tokens":100,"completion_tokens":2,"total_tokens":102,"prompt_tokens_details":{"cached_tokens":20,"cache_write_tokens":"30"}}""")
        assertEstimatedUsage("""{"prompt_tokens":100,"completion_tokens":2,"total_tokens":102,"prompt_tokens_details":{"cached_tokens":80,"cache_write_tokens":30}}""")
    }

    @Test
    fun openRouterModelCatalogParsesSeparateCacheReadAndWriteRates() {
        val (server, _) = server { _, _ ->
            200 to """{"data":[{"id":"vendor/cache-model","name":"Cache Model","pricing":{"prompt":"0.00000023","completion":"0.00000091","input_cache_read":"0.00000005","input_cache_write":"0.00000030","internal_reasoning":"0.00000090"}}]}"""
        }
        try {
            val config = config("http://127.0.0.1:${server.address.port}/v1").copy(
                baseUrl = "http://127.0.0.1:${server.address.port}/v1"
            )
            val model = ProviderClient().discoverModelDetails(config).single()
            assertEquals("vendor/cache-model", model.id)
            assertEquals(0.23, model.promptPricePerMillion!!, 0.000001)
            assertEquals(0.91, model.completionPricePerMillion!!, 0.000001)
            assertEquals(0.05, model.cachedPromptPricePerMillion!!, 0.000001)
            assertEquals(0.30, model.cachedPromptWritePricePerMillion!!, 0.000001)
            assertEquals(0.90, model.internalReasoningPricePerMillion!!, 0.000001)
        } finally { server.stop(0) }
    }

    @Test
    fun openRouterPaidCacheRatePreventsFreeModelClassification() {
        assertEquals(
            false,
            resolveOpenRouterFreePricing(
                "vendor/cache-write-paid", true, 0.0, 0.0, 0.0, 0.0000001
            )
        )
        assertEquals(
            true,
            resolveOpenRouterFreePricing(
                "vendor/fully-free", true, 0.0, 0.0, 0.0, 0.0
            )
        )
        assertEquals(
            false,
            resolveOpenRouterFreePricing(
                "openrouter/free", true, null, null, null, 0.0000001
            )
        )
        assertEquals(
            false,
            resolveOpenRouterFreePricing(
                "vendor/paid-reasoning", true, 0.0, 0.0, 0.0, 0.0, 0.0000001
            )
        )
        assertEquals(
            null,
            resolveOpenRouterFreePricing(
                "vendor/non-openrouter", false, 0.0, 0.0, null, null
            )
        )
    }

}
