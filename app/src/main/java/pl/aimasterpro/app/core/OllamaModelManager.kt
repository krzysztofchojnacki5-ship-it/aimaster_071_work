package pl.aimasterpro.app.core

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

/** Native Ollama model-weight download. Cloud model discovery is deliberately separate. */
object OllamaModelManager {
    data class Progress(
        val status: String,
        val digest: String?,
        val completedBytes: Long?,
        val totalBytes: Long?,
        val percent: Int?
    )

    data class PullResult(
        val model: String,
        val completedBytes: Long,
        val totalBytes: Long,
        val progressEvents: Int,
        val finalStatus: String
    )

    class Cancellation {
        private val cancelled = AtomicBoolean(false)
        private val activeConnection = AtomicReference<HttpURLConnection?>(null)
        val isCancelled: Boolean get() = cancelled.get()

        fun cancel() {
            cancelled.set(true)
            activeConnection.get()?.disconnect()
        }

        internal fun attach(connection: HttpURLConnection) {
            activeConnection.set(connection)
            if (cancelled.get()) connection.disconnect()
        }

        internal fun detach(connection: HttpURLConnection) {
            activeConnection.compareAndSet(connection, null)
        }

        internal fun ensureActive() {
            if (cancelled.get()) throw CancellationException("OLLAMA_PULL_CANCELLED")
        }
    }

    private val modelNamePattern = Regex("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}")

    fun isOllamaProvider(config: ProviderConfig): Boolean =
        FreeModelsCatalog.isLocalOllamaEndpoint(config.baseUrl)

    /** Converts OpenAI-compatible Ollama base URL (/v1) to the native API root. */
    fun nativeBaseUrl(baseUrl: String, allowInsecureLanOllama: Boolean = false): String {
        val uri = URI(baseUrl.trim())
        require(uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) {
            "OLLAMA_URL_SCHEME_UNSUPPORTED"
        }
        require(!uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
            uri.rawQuery == null && uri.rawFragment == null) { "OLLAMA_URL_INVALID" }
        val path = uri.path.orEmpty().trimEnd('/')
        require(path.isBlank() || path == "/v1") { "OLLAMA_URL_PATH_UNSUPPORTED" }
        val endpointDecision = ProviderEndpointPolicy.validate(baseUrl.trim(), "", allowInsecureLanOllama)
        require(endpointDecision.allowed) { endpointDecision.category }
        return URI(uri.scheme, null, uri.host, uri.port, "", null, null).toASCIIString().trimEnd('/')
    }

    fun pull(
        baseUrl: String,
        model: String,
        cancellation: Cancellation = Cancellation(),
        allowInsecureLanOllama: Boolean = false,
        onProgress: (Progress) -> Unit = {}
    ): PullResult {
        val safeModel = model.trim()
        require(modelNamePattern.matches(safeModel)) { "OLLAMA_MODEL_NAME_INVALID" }
        cancellation.ensureActive()
        val endpoint = URL("${nativeBaseUrl(baseUrl, allowInsecureLanOllama)}/api/pull")
        val connection = endpoint.openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 120_000
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.setRequestProperty("Accept", "application/x-ndjson")
        cancellation.attach(connection)

        var completed = 0L
        var total = 0L
        val perDigestProgress = linkedMapOf<String, Pair<Long, Long>>()
        var events = 0
        var lastStatus = ""
        var successSeen = false
        var responseLinesRead = 0
        var responseCharactersRead = 0
        try {
            cancellation.ensureActive()
            // Model names are restricted to a conservative ASCII allow-list above,
            // so this fixed JSON shape needs no general-purpose serializer.
            val requestBody = """{"name":"$safeModel","stream":true}"""
            connection.outputStream.use { output ->
                output.write(requestBody.toByteArray(Charsets.UTF_8))
                output.flush()
            }
            val responseCode = connection.responseCode
            val responseStream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
            if (responseStream == null) throw IOException("OLLAMA_HTTP_$responseCode")
            responseStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { rawLine ->
                    responseLinesRead++
                    responseCharactersRead += rawLine.length
                    cancellation.ensureActive()
                    if (rawLine.isBlank()) return@forEach
                    val item = runCatching { JSONObject(rawLine) }
                        .getOrElse { throw IOException("OLLAMA_STREAM_INVALID_JSON") }
                    val error = item.optString("error").orEmpty().takeIf { it.isNotBlank() }
                    if (error != null) throw IOException("OLLAMA_PULL_ERROR: ${sanitizeError(error)}")
                    val status = item.optString("status").orEmpty().takeIf { it.isNotBlank() }.orEmpty()
                    if (status.isNotBlank()) lastStatus = status
                    val digest = item.optString("digest").orEmpty().takeIf { it.isNotBlank() }
                    val itemCompleted = item.optLong("completed", -1L).takeIf { it >= 0L }
                    val itemTotal = item.optLong("total", -1L).takeIf { it > 0L }
                    if (itemCompleted != null || itemTotal != null) {
                        val progressKey = digest ?: "_default"
                        val previous = perDigestProgress[progressKey] ?: (0L to 0L)
                        perDigestProgress[progressKey] = maxOf(previous.first, itemCompleted ?: previous.first) to
                            maxOf(previous.second, itemTotal ?: previous.second)
                        completed = perDigestProgress.values.sumOf { it.first }
                        total = perDigestProgress.values.sumOf { it.second }
                    }
                    events++
                    val percent = if (total > 0L) ((completed * 100L) / total).toInt().coerceIn(0, 100) else null
                    onProgress(Progress(status, digest, completed.takeIf { total > 0L }, total.takeIf { it > 0L }, percent))
                    if (status.equals("success", ignoreCase = true)) successSeen = true
                }
            }
            cancellation.ensureActive()
            if (responseCode !in 200..299) throw IOException("OLLAMA_HTTP_$responseCode")
            if (!successSeen) throw IOException("OLLAMA_STREAM_ENDED_WITHOUT_SUCCESS: http=$responseCode, lines=$responseLinesRead, chars=$responseCharactersRead, contentType=${connection.contentType}")
            return PullResult(safeModel, completed, total, events, lastStatus)
        } catch (error: Exception) {
            if (cancellation.isCancelled) {
                throw CancellationException("OLLAMA_PULL_CANCELLED").also { it.initCause(error) }
            }
            throw error
        } finally {
            cancellation.detach(connection)
            connection.disconnect()
        }
    }

    private fun sanitizeError(value: String): String = value
        .replace(Regex("(?i)(api[_-]?key|authorization|token|password)\\s*[:=]\\s*[^,\\s]+"), "\$1=[REDACTED]")
        .replace(Regex("[\\r\\n]+"), " ")
        .take(400)
}
