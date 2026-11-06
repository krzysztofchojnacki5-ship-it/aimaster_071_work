package pl.aimasterpro.app.network

import org.json.JSONArray
import org.json.JSONObject
import pl.aimasterpro.app.core.ChatResponse
import pl.aimasterpro.app.core.ProviderEndpointPolicy
import pl.aimasterpro.app.core.ProviderConfig
import pl.aimasterpro.app.core.ProviderKind
import pl.aimasterpro.app.core.TokenUsageResult
import java.net.HttpURLConnection
import java.net.UnknownHostException
import java.net.SocketTimeoutException
import java.net.ConnectException
import java.io.IOException
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class ProviderException(
    val httpCode: Int?,
    message: String,
    val responseBody: String = "",
    val category: String = "UNKNOWN",
    /** Transport phase at failure; empty for failures before an HTTP request was started. */
    val phase: String = "",
    /** True only when a POST may have reached the provider but its outcome is unknown. */
    val requestMayHaveBeenProcessed: Boolean = false
) : Exception(message)

/** Synchronous transport with strict error classification, retry, and token usage extraction. */
data class ProviderModelInfo(
    val id: String,
    val name: String = id,
    val contextLength: Long? = null,
    val promptPricePerMillion: Double? = null,
    val completionPricePerMillion: Double? = null,
    /** true/false only when provider metadata is explicit; null means unknown. */
    val freeByPricing: Boolean? = null,
    val description: String = ""
)

class ProviderClient(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 45_000
) {
    class RequestScope internal constructor(
        val epoch: Long,
        internal val parent: RequestScope? = null
    ) {
        internal val cancelled = AtomicBoolean(false)
    }

    private data class ActiveRequest(val epoch: Long, val scope: RequestScope?)

    private val cancellationEpoch = AtomicLong(0L)
    private val requestScopeLocal = ThreadLocal<RequestScope?>()
    private val activeConnections = ConcurrentHashMap<HttpURLConnection, ActiveRequest>()
    private val unscopedEpoch = Long.MIN_VALUE

    /**
     * Creates an independent top-level operation scope.
     * Starting a model test, audit, or another chat must not cancel unrelated active work.
     * Global invalidation is reserved for the explicit cancelActiveRequests() action.
     */
    fun beginRequestScope(): RequestScope = RequestScope(cancellationEpoch.get())

    /** Creates an independently cancellable child for one agent within a batch. */
    fun childRequestScope(parent: RequestScope): RequestScope {
        if (isCancelled(parent)) throw cancelledException()
        return RequestScope(parent.epoch, parent)
    }

    /** Cancels only this agent's in-flight calls; sibling scopes in the same batch remain active. */
    fun cancelRequestScope(scope: RequestScope) {
        scope.cancelled.set(true)
        activeConnections.entries.toList().forEach { (connection, active) ->
            if (isWithinScope(active.scope, scope)) runCatching { connection.disconnect() }
        }
    }

    /** Immediately disconnects in-flight HTTP calls and invalidates every scoped call. */
    fun cancelActiveRequests() {
        val newEpoch = cancellationEpoch.incrementAndGet()
        disconnectOlderScopes(newEpoch)
    }

    private fun disconnectOlderScopes(newEpoch: Long) {
        activeConnections.entries.toList().forEach { (connection, active) ->
            if (active.epoch != unscopedEpoch && active.epoch < newEpoch) runCatching { connection.disconnect() }
        }
    }

    private fun isWithinScope(candidate: RequestScope?, ancestor: RequestScope): Boolean {
        var current = candidate
        while (current != null) {
            if (current === ancestor) return true
            current = current.parent
        }
        return false
    }

    private fun isCancelled(scope: RequestScope?): Boolean {
        if (Thread.currentThread().isInterrupted) return true
        if (scope == null) return false
        if (scope.epoch != cancellationEpoch.get()) return true
        var current: RequestScope? = scope
        while (current != null) {
            if (current.cancelled.get()) return true
            current = current.parent
        }
        return false
    }

    private fun cancelledException(): ProviderException =
        ProviderException(null, "Żądanie anulowane przez użytkownika.", category = "CANCELLED")

    private fun request(url: String, method: String, apiKey: String, body: JSONObject? = null, authHeader: String = "Authorization", extraHeaders: Map<String, String> = emptyMap()): JSONObject {
        val scope = requestScopeLocal.get()
        val epoch = scope?.epoch ?: unscopedEpoch
        if (isCancelled(scope)) throw cancelledException()
        val endpoint = ProviderEndpointPolicy.validate(url, apiKey)
        if (!endpoint.allowed) throw ProviderException(null, endpoint.message, category = endpoint.category)
        val parsedUrl = endpoint.url ?: throw ProviderException(null, "Nie udało się zweryfikować endpointu.", category = "INVALID_ENDPOINT")
        val conn = (parsedUrl.openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            requestMethod = method
            connectTimeout = connectTimeoutMs.coerceAtLeast(1)
            readTimeout = readTimeoutMs.coerceAtLeast(1)
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Connection", "close")
            if (apiKey.isNotBlank()) setRequestProperty(authHeader, if (authHeader == "Authorization") "Bearer $apiKey" else apiKey)
            extraHeaders.forEach { (name, value) -> setRequestProperty(name, value) }
            if (body != null) { doOutput = true; setRequestProperty("Content-Type", "application/json; charset=utf-8") }
        }
        activeConnections[conn] = ActiveRequest(epoch, scope)
        if (isCancelled(scope)) {
            activeConnections.remove(conn)
            conn.disconnect()
            throw cancelledException()
        }
        var phase = "CONNECT"
        try {
            if (isCancelled(scope)) throw cancelledException()
            if (body != null) {
                phase = "WRITE_REQUEST"
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)); it.flush() }
            }
            if (isCancelled(scope)) throw cancelledException()
            phase = "WAIT_RESPONSE"
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            phase = "READ_RESPONSE"
            val text = stream?.use { BoundedResponseReader.readUtf8(it) }.orEmpty()
            if (isCancelled(scope)) throw cancelledException()

            if (code !in 200..299) {
                val category = when (code) {
                    400 -> "BAD_REQUEST"
                    401 -> "AUTH_UNAUTHORIZED"
                    403 -> "FORBIDDEN"
                    404 -> "ENDPOINT_NOT_FOUND"
                    408 -> "HTTP_REQUEST_TIMEOUT"
                    429 -> "RATE_LIMIT_QUOTA"
                    in 500..599 -> "SERVER_ERROR"
                    else -> "HTTP_ERROR"
                }
                throw ProviderException(code, "Dostawca zwrócił HTTP $code (${category}): ${text.take(200)}", text.take(2500), category = category)
            }
            if (text.isBlank()) throw ProviderException(code, "Dostawca zwrócił pustą odpowiedź HTTP $code.", category = "EMPTY_RESPONSE", phase = phase, requestMayHaveBeenProcessed = method.equals("POST", true))
            return try { JSONObject(text) } catch (e: Exception) {
                throw ProviderException(code, "Nieprawidłowy JSON od dostawcy: ${e.message}", text.take(2500), category = "INVALID_RESPONSE", phase = phase, requestMayHaveBeenProcessed = method.equals("POST", true))
            }
        } catch (e: ProviderException) {
            if (isCancelled(scope)) throw cancelledException()
            throw e
        } catch (e: UnknownHostException) {
            if (isCancelled(scope)) throw cancelledException()
            throw ProviderException(null, "DNS_ERROR podczas $phase dla hosta ${parsedUrl.host}: ${e.message ?: "UnknownHostException"}", category = "DNS_ERROR", phase = phase)
        } catch (e: SocketTimeoutException) {
            if (isCancelled(scope)) throw cancelledException()
            val category = when (phase) {
                "CONNECT", "WRITE_REQUEST" -> "CONNECT_TIMEOUT"
                "WAIT_RESPONSE" -> "RESPONSE_TIMEOUT"
                else -> "READ_TIMEOUT"
            }
            throw ProviderException(null, "$category podczas $phase dla ${parsedUrl.host}: ${e.message ?: "SocketTimeoutException"}", category = category, phase = phase, requestMayHaveBeenProcessed = method.equals("POST", true) && phase != "CONNECT")
        } catch (e: ConnectException) {
            if (isCancelled(scope)) throw cancelledException()
            throw ProviderException(null, "CONNECT_ERROR dla ${parsedUrl.host}: ${e.message ?: "ConnectException"}", category = "CONNECT_ERROR", phase = phase)
        } catch (e: BoundedResponseReader.ResponseTooLargeException) {
            if (isCancelled(scope)) throw cancelledException()
            throw ProviderException(null, e.message ?: "Odpowiedź dostawcy jest zbyt duża.", category = "RESPONSE_TOO_LARGE", phase = phase, requestMayHaveBeenProcessed = method.equals("POST", true))
        } catch (e: BoundedResponseReader.InvalidUtf8Exception) {
            if (isCancelled(scope)) throw cancelledException()
            throw ProviderException(null, e.message ?: "Nieprawidłowe kodowanie odpowiedzi.", category = "INVALID_RESPONSE_ENCODING", phase = phase, requestMayHaveBeenProcessed = method.equals("POST", true))
        } catch (e: IOException) {
            if (isCancelled(scope)) throw cancelledException()
            throw ProviderException(null, "NETWORK_IO podczas $phase dla ${parsedUrl.host}: ${e.message ?: "IOException"}", category = "NETWORK_IO", phase = phase, requestMayHaveBeenProcessed = method.equals("POST", true) && phase != "CONNECT")
        } catch (e: Exception) {
            if (isCancelled(scope)) throw cancelledException()
            throw ProviderException(null, "NETWORK_ERROR podczas $phase dla ${parsedUrl.host}: ${e.message ?: e.javaClass.simpleName}", category = "NETWORK_ERROR", phase = phase, requestMayHaveBeenProcessed = method.equals("POST", true) && phase != "CONNECT")
        } finally {
            activeConnections.remove(conn)
            conn.disconnect()
        }
    }

    /** Retry only failures that are safe to repeat. A POST is retried only if DNS/connection
     * failed before an HTTP connection could be established; read timeouts are never replayed. */
    private fun requestWithRetry(url: String, method: String, apiKey: String, body: JSONObject? = null, authHeader: String = "Authorization", extraHeaders: Map<String, String> = emptyMap()): JSONObject {
        val maxAttempts = 3
        val scope = requestScopeLocal.get()
        var lastException: ProviderException? = null
        for (attempt in 1..maxAttempts) {
            if (isCancelled(scope)) throw cancelledException()
            try {
                return request(url, method, apiKey, body, authHeader, extraHeaders)
            } catch (e: ProviderException) {
                if (e.category == "CANCELLED" || isCancelled(scope)) throw cancelledException()
                lastException = e
                val safeNetworkFailure = e.category in setOf("DNS_ERROR", "CONNECT_ERROR")
                val retryableRead = method.equals("GET", ignoreCase = true) &&
                    (e.category in setOf("CONNECT_TIMEOUT", "RESPONSE_TIMEOUT", "READ_TIMEOUT", "NETWORK_IO") ||
                     (e.httpCode != null && e.httpCode in 500..599))
                val retryable = retryableRead || (safeNetworkFailure && attempt < maxAttempts)
                if (!retryable || attempt >= maxAttempts) throw e
                val exponential = (350L * (1L shl (attempt - 1))).coerceAtMost(2_000L)
                val jitter = ThreadLocalRandom.current().nextLong(0L, 251L)
                if (isCancelled(scope)) throw cancelledException()
                try { Thread.sleep(exponential + jitter) } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw ProviderException(null, "Przerwano oczekiwanie przed ponowieniem: ${e.message}", category = "INTERRUPTED")
                }
            }
        }
        throw lastException ?: ProviderException(null, "Nieznany błąd żądania sieciowego", category = "NETWORK_ERROR")
    }

    fun chat(config: ProviderConfig, systemPrompt: String, userText: String, attachmentMime: String = "", attachmentBase64: String = "",
             scope: RequestScope? = null): ChatResponse {
        val requestScope = scope
        if (isCancelled(requestScope)) throw cancelledException()
        requestScopeLocal.set(requestScope)
        return try {
            if (isCancelled(scope)) throw cancelledException()
            chatInternal(config, systemPrompt, userText, attachmentMime, attachmentBase64)
        } catch (e: ProviderException) {
            // Schema/shape validation happens after a successful HTTP response; that POST may
            // already be billable even though the provider payload cannot be consumed.
            if (e.category == "INVALID_RESPONSE" && !e.requestMayHaveBeenProcessed) {
                throw ProviderException(
                    e.httpCode, e.message ?: "Nieprawidłowa odpowiedź dostawcy.", e.responseBody,
                    e.category, e.phase.ifBlank { "READ_RESPONSE" }, requestMayHaveBeenProcessed = true
                )
            }
            throw e
        } finally {
            requestScopeLocal.remove()
        }
    }

    private fun chatInternal(config: ProviderConfig, systemPrompt: String, userText: String, attachmentMime: String, attachmentBase64: String): ChatResponse = when (config.kind) {
        ProviderKind.ANTHROPIC -> {
            val root = config.baseUrl.trimEnd('/').ifBlank { "https://api.anthropic.com/v1" }
            val endpoint = if (root.endsWith("/messages")) root else "$root/messages"
            val contentBlocks = JSONArray().put(JSONObject().put("type", "text").put("text", userText))
            if (attachmentBase64.isNotBlank()) {
                when {
                    attachmentMime.startsWith("image/") -> contentBlocks.put(JSONObject().put("type", "image").put("source", JSONObject().put("type", "base64").put("media_type", attachmentMime).put("data", attachmentBase64)))
                    attachmentMime == "application/pdf" -> contentBlocks.put(JSONObject().put("type", "document").put("source", JSONObject().put("type", "base64").put("media_type", "application/pdf").put("data", attachmentBase64)))
                    else -> throw ProviderException(null, "Ten typ załącznika nie jest obsługiwany przez Anthropic.", category = "UNSUPPORTED_ATTACHMENT")
                }
            }
            val messages = JSONArray().put(JSONObject().put("role", "user").put("content", contentBlocks))
            val result = requestWithRetry(endpoint, "POST", config.apiKey,
                JSONObject().put("model", config.model).put("max_tokens", 4096).put("system", systemPrompt).put("messages", messages),
                authHeader = "x-api-key", extraHeaders = mapOf("anthropic-version" to "2023-06-01"))
            val content = result.optJSONArray("content") ?: JSONArray()
            val text = (0 until content.length()).mapNotNull { content.optJSONObject(it)?.takeIf { o -> o.optString("type") == "text" }?.optString("text") }.joinToString("\n")

            val usageObj = result.optJSONObject("usage")
            val input = usageObj?.optLong("input_tokens", -1L) ?: -1L
            val output = usageObj?.optLong("output_tokens", -1L) ?: -1L
            val cached = usageObj?.optLong("cache_read_input_tokens", 0L) ?: 0L
            val reasoning = 0L
            val finalInput = if (input >= 0) input else (systemPrompt.length + userText.length) / 4.toLong()
            val finalOutput = if (output >= 0) output else text.length / 4.toLong()
            val finalTotal = if (input >= 0 && output >= 0) input + output else finalInput + finalOutput
            val status = if (input >= 0 && output >= 0) "REAL" else "ESTIMATED"

            ChatResponse(text, TokenUsageResult(finalInput, finalOutput, finalTotal, reasoning, cached, status))
        }
        ProviderKind.OPENAI_COMPATIBLE -> {
            val root = config.baseUrl.trimEnd('/')
            val endpoint = if (root.endsWith("/chat/completions")) root else "$root/chat/completions"
            val userContent: Any = if (attachmentBase64.isBlank()) userText else {
                if (attachmentMime == "application/pdf") throw ProviderException(null, "Ten endpoint OpenAI-compatible nie otrzymuje PDF jako załącznika. Wybierz Gemini lub Anthropic albo wklej tekst z PDF.", category = "UNSUPPORTED_ATTACHMENT")
                if (!attachmentMime.startsWith("image/")) throw ProviderException(null, "Ten typ załącznika nie jest obsługiwany.", category = "UNSUPPORTED_ATTACHMENT")
                JSONArray().put(JSONObject().put("type", "text").put("text", userText))
                    .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:$attachmentMime;base64,$attachmentBase64")))
            }
            val messages = JSONArray()
                .put(JSONObject().put("role", "system").put("content", systemPrompt))
                .put(JSONObject().put("role", "user").put("content", userContent))
            val result = requestWithRetry(endpoint, "POST", config.apiKey,
                JSONObject().put("model", config.model).put("messages", messages))

            val choices = result.optJSONArray("choices") ?: throw ProviderException(null, "Dostawca zwrócił odpowiedź bez pola choices.", category = "INVALID_RESPONSE")
            val text = choices.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()

            val usageObj = result.optJSONObject("usage")
            val promptEval = result.optLong("prompt_eval_count", -1L)
            val evalCount = result.optLong("eval_count", -1L)

            val input = usageObj?.optLong("prompt_tokens", -1L)?.takeIf { it >= 0 } ?: if (promptEval >= 0) promptEval else -1L
            val output = usageObj?.optLong("completion_tokens", -1L)?.takeIf { it >= 0 } ?: if (evalCount >= 0) evalCount else -1L
            val total = usageObj?.optLong("total_tokens", -1L)?.takeIf { it >= 0 } ?: if (input >= 0 && output >= 0) input + output else -1L
            val cached = usageObj?.optJSONObject("prompt_tokens_details")?.optLong("cached_tokens", 0L) ?: 0L
            val reasoning = usageObj?.optJSONObject("completion_tokens_details")?.optLong("reasoning_tokens", 0L) ?: 0L

            val finalInput = if (input >= 0) input else (systemPrompt.length + userText.length) / 4.toLong()
            val finalOutput = if (output >= 0) output else text.length / 4.toLong()
            val finalTotal = if (total >= 0) total else finalInput + finalOutput
            val status = if (input >= 0 && output >= 0) "REAL" else "ESTIMATED"

            ChatResponse(text, TokenUsageResult(finalInput, finalOutput, finalTotal, reasoning, cached, status))
        }
        ProviderKind.GEMINI -> {
            val root = config.baseUrl.trimEnd('/')
            if (root.contains("/openai")) {
                // Official Google Gemini OpenAI-compatible endpoint: https://generativelanguage.googleapis.com/v1beta/openai/
                val endpoint = if (root.endsWith("/chat/completions")) root else "$root/chat/completions"
                val messages = JSONArray()
                    .put(JSONObject().put("role", "system").put("content", systemPrompt))
                    .put(JSONObject().put("role", "user").put("content", userText))
                val result = requestWithRetry(endpoint, "POST", config.apiKey,
                    JSONObject().put("model", config.model).put("messages", messages),
                    authHeader = "Authorization")
                val choices = result.optJSONArray("choices") ?: throw ProviderException(null, "Gemini OpenAI-compatible zwrócił brak pola choices.", category = "INVALID_RESPONSE")
                val text = choices.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()

                val usageObj = result.optJSONObject("usage")
                val input = usageObj?.optLong("prompt_tokens", -1L) ?: -1L
                val output = usageObj?.optLong("completion_tokens", -1L) ?: -1L
                val reportedTotal = usageObj?.optLong("total_tokens", -1L) ?: -1L
                val finalInput = if (input >= 0L) input else (systemPrompt.length + userText.length) / 4L
                val finalOutput = if (output >= 0L) output else text.length / 4L
                // Never persist a negative total when a compatible endpoint omits usage metadata.
                // Negative totals are clamped out of budget accounting and would undercount usage.
                val finalTotal = if (reportedTotal >= 0L) reportedTotal else finalInput + finalOutput
                val status = if (input >= 0L && output >= 0L) "REAL" else "ESTIMATED"
                ChatResponse(text, TokenUsageResult(finalInput, finalOutput, finalTotal, 0L, 0L, status))
            } else {
                // Native Gemini REST API
                val encodedKey = java.net.URLEncoder.encode(config.apiKey, "UTF-8")
                val endpoint = "$root/models/${config.model}:generateContent?key=$encodedKey"
                val parts = JSONArray().put(JSONObject().put("text", "$systemPrompt\n\nUżytkownik: $userText"))
                if (attachmentBase64.isNotBlank()) {
                    if (!(attachmentMime.startsWith("image/") || attachmentMime == "application/pdf")) throw ProviderException(null, "Gemini: obsługiwane załączniki to obrazy i PDF.", category = "UNSUPPORTED_ATTACHMENT")
                    parts.put(JSONObject().put("inlineData", JSONObject().put("mimeType", attachmentMime).put("data", attachmentBase64)))
                }
                val result = requestWithRetry(endpoint, "POST", "", JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", parts))))
                val text = result.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)?.optString("text").orEmpty()

                val meta = result.optJSONObject("usageMetadata")
                val input = meta?.optLong("promptTokenCount", -1L) ?: -1L
                val output = meta?.optLong("candidatesTokenCount", -1L) ?: -1L
                val total = meta?.optLong("totalTokenCount", -1L) ?: -1L
                val cached = meta?.optLong("cachedContentTokenCount", 0L) ?: 0L
                val reasoning = 0L

                val finalInput = if (input >= 0) input else (systemPrompt.length + userText.length) / 4.toLong()
                val finalOutput = if (output >= 0) output else text.length / 4.toLong()
                val finalTotal = if (total >= 0) total else finalInput + finalOutput
                val status = if (input >= 0 && output >= 0) "REAL" else "ESTIMATED"

                ChatResponse(text, TokenUsageResult(finalInput, finalOutput, finalTotal, reasoning, cached, status))
            }
        }
    }

    fun discoverModelDetails(config: ProviderConfig): List<ProviderModelInfo> = when (config.kind) {
        ProviderKind.ANTHROPIC -> {
            val root = config.baseUrl.trimEnd('/').ifBlank { "https://api.anthropic.com/v1" }
            val json = requestWithRetry("$root/models", "GET", config.apiKey, authHeader = "x-api-key", extraHeaders = mapOf("anthropic-version" to "2023-06-01"))
            val arr = json.optJSONArray("data") ?: JSONArray()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                ProviderModelInfo(id, o.optString("display_name", id), o.optLong("context_length", -1L).takeIf { it > 0 })
            }.sortedBy { it.id }
        }
        ProviderKind.OPENAI_COMPATIBLE -> {
            val root = config.baseUrl.trimEnd('/').removeSuffix("/chat/completions")
            val json = requestWithRetry("$root/models", "GET", config.apiKey)
            val arr = json.optJSONArray("data") ?: JSONArray()
            val isOpenRouter = root.contains("openrouter.ai", true)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val pricing = o.optJSONObject("pricing")
                val promptRaw = pricing?.optString("prompt")
                val completionRaw = pricing?.optString("completion")
                val prompt = promptRaw?.toDoubleOrNull()?.times(1_000_000.0)
                val completion = completionRaw?.toDoubleOrNull()?.times(1_000_000.0)
                val hasPricing = prompt != null && completion != null
                val explicitFree = id.equals("openrouter/free", true) || id.endsWith(":free", true)
                val free = if (isOpenRouter) {
                    when {
                        hasPricing && prompt == 0.0 && completion == 0.0 -> true
                        hasPricing -> false
                        explicitFree -> true
                        else -> null
                    }
                } else null
                ProviderModelInfo(
                    id = id,
                    name = o.optString("name", id),
                    contextLength = o.optLong("context_length", -1L).takeIf { it > 0 },
                    promptPricePerMillion = prompt,
                    completionPricePerMillion = completion,
                    freeByPricing = free,
                    description = o.optString("description", "")
                )
            }.sortedBy { it.id }
        }
        ProviderKind.GEMINI -> {
            val root = config.baseUrl.trimEnd('/').ifBlank { "https://generativelanguage.googleapis.com/v1beta" }
            if (root.contains("/openai")) {
                val json = requestWithRetry("$root/models", "GET", config.apiKey)
                val arr = json.optJSONArray("data") ?: JSONArray()
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val id = o.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    ProviderModelInfo(id, o.optString("name", id), o.optLong("context_length", -1L).takeIf { it > 0 })
                }.sortedBy { it.id }
            } else {
                val encodedKey = java.net.URLEncoder.encode(config.apiKey, "UTF-8")
                val json = requestWithRetry("$root/models?key=$encodedKey", "GET", "")
                val arr = json.optJSONArray("models") ?: JSONArray()
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val id = o.optString("name").removePrefix("models/").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    ProviderModelInfo(id, o.optString("displayName", id), o.optLong("inputTokenLimit", -1L).takeIf { it > 0 }, description = o.optString("description", ""))
                }.sortedBy { it.id }
            }
        }
    }

    fun discoverModels(config: ProviderConfig): List<String> = discoverModelDetails(config).map { it.id }
}
