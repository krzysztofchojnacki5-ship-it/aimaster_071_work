package pl.aimasterpro.app.core

/**
 * Curated provider onboarding presets. API keys are never embedded here; users create
 * their own credentials on the provider's official dashboard.
 */
data class ProviderPreset(
    val id: String,
    val name: String,
    val kind: ProviderKind,
    val baseUrl: String,
    val apiKeyUrl: String,
    val suggestedModelId: String,
    val modelLabel: String,
    val accessNote: String
) {
    fun toProviderConfig(): ProviderConfig = ProviderConfig(
        id = id,
        name = name,
        kind = kind,
        baseUrl = baseUrl,
        model = suggestedModelId,
        apiKey = "",
        enabled = true
    )
}

object ProviderPresetCatalog {
    val all: List<ProviderPreset> = listOf(
        ProviderPreset(
            "gemini", "Google Gemini API", ProviderKind.GEMINI,
            "https://generativelanguage.googleapis.com/v1beta",
            "https://aistudio.google.com/app/apikey",
            "gemini-3.8-flash", "Gemini 3.8 Flash",
            "Dostępny bezpłatny poziom API z limitami. Warunki i dostępność modelu zależą od konta."
        ),
        ProviderPreset(
            "groq", "Groq API", ProviderKind.OPENAI_COMPATIBLE,
            "https://api.groq.com/openai/v1",
            "https://console.groq.com/keys",
            "openai/gpt-oss-120b", "GPT-OSS 120B",
            "Groq Free Plan — limity RPM, RPD i tokenów; sprawdź bieżący limit konta."
        ),
        ProviderPreset(
            "openrouter", "OpenRouter API", ProviderKind.OPENAI_COMPATIBLE,
            "https://openrouter.ai/api/v1",
            "https://openrouter.ai/settings/keys",
            "openrouter/free", "Free model router",
            "Router wybiera aktualnie dostępny model z darmowej puli; limity i modele mogą się zmieniać."
        ),
        ProviderPreset(
            "anthropic", "Anthropic Claude API", ProviderKind.ANTHROPIC,
            "https://api.anthropic.com/v1",
            "https://console.anthropic.com/settings/keys",
            "claude-sonnet-5", "Claude Sonnet 5",
            "API płatne według cennika Anthropic; brak deklaracji darmowego API."
        ),
        ProviderPreset(
            "openai", "OpenAI API", ProviderKind.OPENAI_COMPATIBLE,
            "https://api.openai.com/v1",
            "https://platform.openai.com/api-keys",
            "", "Pobierz aktualne modele z API",
            "Rozliczanie API jest oddzielne od abonamentu ChatGPT; brak deklaracji darmowego API."
        ),
        ProviderPreset(
            "mistral", "Mistral AI API", ProviderKind.OPENAI_COMPATIBLE,
            "https://api.mistral.ai/v1",
            "https://console.mistral.ai/api-keys",
            "mistral-large-4", "Mistral Large 4",
            "Dostęp i rozliczanie zależą od aktywacji API oraz planu Mistral."
        ),
        ProviderPreset(
            "deepseek", "DeepSeek API", ProviderKind.OPENAI_COMPATIBLE,
            "https://api.deepseek.com",
            "https://platform.deepseek.com/api_keys",
            "deepseek-flash", "DeepSeek V4.1 Flash",
            "API płatne według zużycia; nazwa deepseek-flash wskazuje aktualną wersję Flash."
        )
    )

    fun find(id: String): ProviderPreset? = all.firstOrNull { it.id == id }
}
