package pl.aimasterpro.app.core

/** Redacts common provider, cloud and source-control credentials before prompts enter local logs. */
object CommunicationLogSanitizer {
    private val bearer = Regex("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]+")
    private val google = Regex("\\bAIzaSy[A-Za-z0-9_-]{20,}\\b")
    private val googleOAuth = Regex("\\bya29\\.[A-Za-z0-9_-]{20,}\\b")
    private val openAiStyle = Regex("\\bsk-[A-Za-z0-9_-]{16,}\\b")
    private val groq = Regex("\\bgsk_[A-Za-z0-9_-]{16,}\\b")
    private val huggingFace = Regex("\\bhf_[A-Za-z0-9]{20,}\\b")
    private val replicate = Regex("\\br8_[A-Za-z0-9]{20,}\\b")
    private val stripe = Regex("\\b(?:sk|rk)_(?:live|test)_[A-Za-z0-9]{16,}\\b")
    private val awsAccessKey = Regex("\\b(?:AKIA|ASIA)[A-Z0-9]{16}\\b")
    private val jwt = Regex("\\beyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\b")
    private val github = Regex("\\bgh[pousr]_[A-Za-z0-9_]{20,}\\b")
    private val slack = Regex("\\bxox[baprs]-[A-Za-z0-9-]{10,}\\b")
    private val urlUserInfo = Regex("(?i)([a-z][a-z0-9+.-]*://[^\\s/:@]+:)[^\\s/@]+(@)")
    private val privateKeyBlock = Regex("(?s)-----BEGIN (?:RSA |EC |OPENSSH |DSA |ENCRYPTED )?PRIVATE KEY-----.*?-----END (?:RSA |EC |OPENSSH |DSA |ENCRYPTED )?PRIVATE KEY-----")
    private val authorizationHeader = Regex("(?i)(\\b(?:authorization|proxy-authorization)\\b\\s*:\\s*)[^\\r\\n]*")
    private val sensitiveHeader = Regex("(?i)(\\b(?:x-goog-api-key|x-auth-token|x-amz-security-token|cookie|set-cookie)\\b\\s*:\\s*)[^\\r\\n]*")
    private val signedUrlParameter = Regex("(?i)([?&](?:sig|signature|x-amz-signature|x-goog-signature|x-amz-credential|x-goog-credential)=)[^&#\\s]+")
    private val keyValue = Regex("(?i)(\\b(?:x-goog-api-key|x-auth-token|x-amz-security-token|api[_-]?key|x-api-key|access[_-]?token|refresh[_-]?token|id[_-]?token|session[_-]?token|token|authorization|proxy-authorization|auth|client[_-]?secret|aws[_-]?secret[_-]?access[_-]?key|aws[_-]?session[_-]?token|private[_-]?key|signing[_-]?key|secret[_-]?key|password|secret)\\b\\s*[=:]\\s*)(?!\\[REDACTED)(\\\"[^\\\"]*\\\"|'[^']*'|[^\\s,;]+)")
    private val jsonSecret = Regex(
        """(?i)("(?:x-goog-api-key|xGoogApiKey|x-auth-token|xAuthToken|x-amz-security-token|cookie|set-cookie|apiKey|api_key|access_token|refresh_token|id_token|session_token|token|authorization|proxy-authorization|auth|client_secret|aws_secret_access_key|aws_session_token|private_key|signing_key|secret_key|password|secret)"\s*:\s*")(?:\\.|[^"\\])*(")"""
    )

    fun sanitize(value: String): String = value
        .replace(authorizationHeader) { match -> match.groupValues[1] + "[REDACTED]" }
        .replace(sensitiveHeader) { match -> match.groupValues[1] + "[REDACTED]" }
        .replace(bearer, "Bearer [REDACTED]")
        .replace(google, "AIzaSy[REDACTED]")
        .replace(googleOAuth, "[REDACTED GOOGLE OAUTH TOKEN]")
        .replace(openAiStyle, "sk-[REDACTED]")
        .replace(groq, "gsk_[REDACTED]")
        .replace(huggingFace, "[REDACTED HUGGING FACE TOKEN]")
        .replace(replicate, "[REDACTED REPLICATE TOKEN]")
        .replace(stripe, "[REDACTED STRIPE KEY]")
        .replace(awsAccessKey, "[REDACTED AWS ACCESS KEY]")
        .replace(jwt, "[REDACTED JWT]")
        .replace(github, "gh[pousr]_[REDACTED]")
        .replace(slack, "xox-[REDACTED]")
        .replace(urlUserInfo) { match -> match.groupValues[1] + "[REDACTED]" + match.groupValues[2] }
        .replace(privateKeyBlock, "[REDACTED PRIVATE KEY]")
        .replace(signedUrlParameter) { match -> match.groupValues[1] + "[REDACTED]" }
        .replace(keyValue) { match -> match.groupValues[1] + "[REDACTED]" }
        .replace(jsonSecret) { match -> match.groupValues[1] + "[REDACTED]" + match.groupValues[2] }
}
