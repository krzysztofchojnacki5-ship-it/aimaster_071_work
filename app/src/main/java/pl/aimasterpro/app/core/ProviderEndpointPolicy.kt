package pl.aimasterpro.app.core

import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/** Fail-closed validation for provider endpoints before opening any connection. */
object ProviderEndpointPolicy {
    data class Decision(
        val allowed: Boolean,
        val category: String,
        val message: String,
        val url: URL? = null
    )

    fun validate(rawUrl: String, apiKey: String, allowInsecureLanOllama: Boolean = false): Decision {
        if (rawUrl.isBlank() || rawUrl.length > MAX_URL_CHARS) {
            return reject("INVALID_ENDPOINT", "Adres endpointu jest pusty lub przekracza limit długości.")
        }
        val parsed = try {
            URI(rawUrl).toURL()
        } catch (_: Exception) {
            return reject("INVALID_ENDPOINT", "Nieprawidłowy adres endpointu dostawcy.")
        }
        val scheme = parsed.protocol.lowercase(Locale.ROOT)
        if (scheme != "https" && scheme != "http") {
            return reject("UNSUPPORTED_ENDPOINT_SCHEME", "Dostawca musi używać HTTPS; HTTP jest dostępne wyłącznie dla ściśle określonych endpointów lokalnych.")
        }
        if (parsed.host.isBlank() || parsed.port == 0 || parsed.port > MAX_PORT) {
            return reject("INVALID_ENDPOINT", "Endpoint musi zawierać poprawny host i port.")
        }
        if (parsed.userInfo != null) {
            return reject("URL_CREDENTIALS_NOT_ALLOWED", "Dane logowania nie mogą być umieszczane w adresie URL.")
        }
        if (scheme == "http") {
            val queryContainsCredential = parsed.query?.let { query ->
                val decodedQuery = try {
                    URLDecoder.decode(query, StandardCharsets.UTF_8)
                } catch (_: IllegalArgumentException) {
                    return reject("INVALID_ENDPOINT", "Nieprawidłowe kodowanie query w adresie endpointu.")
                }
                Regex("(?:^|[&;])(?:key|api[_-]?key|token|access[_-]?token|authorization|password|passwd|secret|credential|auth|client[_-]?secret)(?:=|[&;]|$)", RegexOption.IGNORE_CASE)
                    .containsMatchIn(decodedQuery)
            } == true
            val isExplicitOllamaLan = allowInsecureLanOllama &&
                parsed.port == 11434 &&
                (CleartextEndpointPolicy.isPrivateLanIpv4(parsed.host) ||
                    CleartextEndpointPolicy.isPrivateLanIpv6(parsed.host)) &&
                parsed.path.orEmpty().trimEnd('/').let {
                    it.isEmpty() || it in setOf("/v1", "/v1/models", "/v1/chat/completions")
                } &&
                apiKey.isBlank() && !queryContainsCredential
            if ((!CleartextEndpointPolicy.isAllowedHost(parsed.host) && !isExplicitOllamaLan) ||
                apiKey.isNotBlank() || queryContainsCredential) {
                return reject(
                    "SECURITY_HTTP",
                    "HTTP jest domyślnie blokowane. Dla Ollama w prywatnej sieci wymagane jest jawne zezwolenie, adres IPv4 RFC1918, port 11434 i brak klucza API. Dla pozostałych usług użyj HTTPS."
                )
            }
        }
        return Decision(true, "OK", "Endpoint zaakceptowany.", parsed)
    }

    private fun reject(category: String, message: String) = Decision(false, category, message)

    private const val MAX_URL_CHARS = 8_192
    private const val MAX_PORT = 65_535
}
