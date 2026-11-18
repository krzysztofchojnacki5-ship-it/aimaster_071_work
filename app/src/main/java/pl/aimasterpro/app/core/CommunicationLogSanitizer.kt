package pl.aimasterpro.app.core

/** Redacts common provider, cloud and source-control credentials before prompts enter local logs. */
object CommunicationLogSanitizer {
    private val bearer = Regex("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]+")
    private val google = Regex("\\bAIzaSy[A-Za-z0-9_-]{20,}\\b")
    private val openAiStyle = Regex("\\bsk-[A-Za-z0-9_-]{16,}\\b")
    private val groq = Regex("\\bgsk_[A-Za-z0-9_-]{16,}\\b")
    private val github = Regex("\\bgh[pousr]_[A-Za-z0-9_]{20,}\\b")
    private val slack = Regex("\\bxox[baprs]-[A-Za-z0-9-]{10,}\\b")
    private val urlUserInfo = Regex("(?i)([a-z][a-z0-9+.-]*://[^\\s/:@]+:)[^\\s/@]+(@)")
    private val privateKeyBlock = Regex("(?s)-----BEGIN (?:RSA |EC |OPENSSH |DSA |ENCRYPTED )?PRIVATE KEY-----.*?-----END (?:RSA |EC |OPENSSH |DSA |ENCRYPTED )?PRIVATE KEY-----")
    private val keyValue = Regex("(?i)(\\b(?:api[_-]?key|x-api-key|access[_-]?token|refresh[_-]?token|id[_-]?token|session[_-]?token|token|authorization|proxy-authorization|auth|client[_-]?secret|password|secret)\\b\\s*[=:]\\s*)(\\\"[^\\\"]*\\\"|'[^']*'|[^\\s,;]+)")
    private val jsonSecret = Regex("(?i)(\\\"(?:apiKey|api_key|access_token|refresh_token|client_secret|password|secret)\\\"\\s*:\\s*\\\")[^\\\"]*(\\\")")

    fun sanitize(value: String): String = value
        .replace(bearer, "Bearer [REDACTED]")
        .replace(google, "AIzaSy[REDACTED]")
        .replace(openAiStyle, "sk-[REDACTED]")
        .replace(groq, "gsk_[REDACTED]")
        .replace(github, "gh[pousr]_[REDACTED]")
        .replace(slack, "xox-[REDACTED]")
        .replace(urlUserInfo) { match -> match.groupValues[1] + "[REDACTED]" + match.groupValues[2] }
        .replace(privateKeyBlock, "[REDACTED PRIVATE KEY]")
        .replace(keyValue) { match -> match.groupValues[1] + "[REDACTED]" }
        .replace(jsonSecret) { match -> match.groupValues[1] + "[REDACTED]" + match.groupValues[2] }
}
