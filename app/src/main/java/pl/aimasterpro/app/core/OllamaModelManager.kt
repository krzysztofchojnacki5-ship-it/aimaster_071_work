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

    fun isOllamaProvider(config: ProviderConfig): Boolean {
        if (config.id.equals("ollama", ignoreCase = true) ||
            config.name.contains("ollama", ignoreCase = true)) return true
        return runCatching { URI(config.baseUrl).port == 11434 }.getOrDefault(false)
    }

    /** Converts OpenAI-compatible Ollama base URL (/v1) to the native API root. */
    fun nativeBaseUrl(baseUrl: String): String {
        val uri = URI(baseUrl.trim())
        require(uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) {
            "OLLAMA_URL_SCHEME_UNSUPPORTED"
        }
        require(!uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
            uri.rawQuery == null && uri.rawFragment == null) { "OLLAMA_URL_INVALID" }
        val path = uri.path.orEmpty().trimEnd('/')
        require(path.isBlank() || path == "/v1") { "OLLAMA_URL_PATH_UNSUPPORTED" }
        return URI(uri.scheme, null, uri.host, uri.port, "", null, null).toASCIIString().trimEnd('/')
    }

    fun pull(
        baseUrl: String,
        model: String,
        cancellation: Cancellation = Cancellation(),
        onProgress: (Progress) -> Unit = {}
    ): PullResult {
        val safeModel = model.trim()
        require(modelNamePattern.matches(safeModel)) { "OLLAMA_MODEL_NAME_INVALID" }
        cancellation.ensureActive()
        val endpoint = URL("${nativeBaseUrl(baseUrl)}/api/pull")
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
        var events = 0
        var lastStatus = ""
        var successSeen = false
        try {
            cancellation.ensureActive()
            val requestBody = JSONObject().put("name", safeModel).put("stream", true).toString()
            connection.outputStream.use { output ->
                output.write(requestBody.toByteArray(Charsets.UTF_8))
                output.flush()
            }
            val responseCode = connection.responseCode
            val responseStream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
            if (responseStream == null) throw IOException("OLLAMA_HTTP_$responseCode")
            responseStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { rawLine ->
                    cancellation.ensureActive()
                    if (rawLine.isBlank()) return@forEach
                    val item = runCatching { JSONObject(rawLine) }
                        .getOrElse { throw IOException("OLLAMA_STREAM_INVALID_JSON") }
                    val error = item.optString("error").takeIf { it.isNotBlank() }
                    if (error != null) throw IOException("OLLAMA_PULL_ERROR: ${sanitizeError(error)}")
                    val status = item.optString("status").takeIf { it.isNotBlank() }.orEmpty()
                    if (status.isNotBlank()) lastStatus = status
                    val digest = item.optString("digest").takeIf { it.isNotBlank() }
                    val itemCompleted = item.optLong("completed", -1L).takeIf { it >= 0L }
                    val itemTotal = item.optLong("total", -1L).takeIf { it > 0L }
                    if (itemCompleted != null) completed = maxOf(completed, itemCompleted)
                    if (itemTotal != null) total = maxOf(total, itemTotal)
                    events++
                    val percent = if (total > 0L) ((completed * 100L) / total).toInt().coerceIn(0, 100) else null
                    onProgress(Progress(status, digest, completed.takeIf { total > 0L }, total.takeIf { it > 0L }, percent))
                    if (status.equals("success", ignoreCase = true)) successSeen = true
                }
            }
            cancellation.ensureActive()
            if (responseCode !in 200..299) throw IOException("OLLAMA_HTTP_$responseCode")
            if (!successSeen) throw IOException("OLLAMA_STREAM_ENDED_WITHOUT_SUCCESS")
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
        .replace(Regex("(?i)(api[_-]?key|authorization|token|password)\\s*[:=]\\s*[^,\\s]+"), "\\$1=[REDACTED]")
        .replace(Regex("[\\r\\n]+"), " ")
        .take(400)
}
