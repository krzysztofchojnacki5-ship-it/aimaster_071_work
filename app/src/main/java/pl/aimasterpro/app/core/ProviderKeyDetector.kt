package pl.aimasterpro.app.core

data class ProviderKeyDetection(
    val kind: ProviderKind,
    val providerName: String,
    val defaultBaseUrl: String
)

/**
 * Local-only prefix recognition. The key is never sent anywhere; ambiguous generic "sk-"
 * tokens deliberately require a manual provider choice.
 */
object ProviderKeyDetector {
    fun detect(rawKey: String): ProviderKeyDetection? {
        val key = rawKey.trim().removePrefix("Bearer ").trim()
        if (key.isBlank() || key.length > 8_192) return null
        return when {
            key.startsWith("sk-ant-", ignoreCase = true) ->
                ProviderKeyDetection(ProviderKind.ANTHROPIC, "Anthropic", "https://api.anthropic.com/v1")
            key.startsWith("AIza", ignoreCase = true) ->
                ProviderKeyDetection(ProviderKind.GEMINI, "Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai")
            key.startsWith("gsk_", ignoreCase = true) ->
                ProviderKeyDetection(ProviderKind.OPENAI_COMPATIBLE, "Groq", "https://api.groq.com/openai/v1")
            key.startsWith("sk-or-v1-", ignoreCase = true) ->
                ProviderKeyDetection(ProviderKind.OPENAI_COMPATIBLE, "OpenRouter", "https://openrouter.ai/api/v1")
            key.startsWith("xai-", ignoreCase = true) ->
                ProviderKeyDetection(ProviderKind.OPENAI_COMPATIBLE, "xAI", "https://api.x.ai/v1")
            key.startsWith("sk-proj-", ignoreCase = true) || key.startsWith("sk-svcacct-", ignoreCase = true) ->
                ProviderKeyDetection(ProviderKind.OPENAI_COMPATIBLE, "OpenAI", "https://api.openai.com/v1")
            else -> null
        }
    }

    fun isAmbiguousGenericSecret(rawKey: String): Boolean {
        val key = rawKey.trim().removePrefix("Bearer ").trim()
        return key.startsWith("sk-", ignoreCase = true) &&
            !key.startsWith("sk-ant-", ignoreCase = true) &&
            !key.startsWith("sk-or-v1-", ignoreCase = true) &&
            !key.startsWith("sk-proj-", ignoreCase = true) &&
            !key.startsWith("sk-svcacct-", ignoreCase = true)
    }
}
