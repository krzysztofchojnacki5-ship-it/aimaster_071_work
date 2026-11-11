package pl.aimasterpro.app.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Status is deliberately conservative: absence of pricing/entitlement data is UNKNOWN, never FREE. */
enum class FreeStatus(val label: String, val isAllowedForSelection: Boolean) {
    FREE("FREE", true),
    FREE_TIER("FREE TIER", true),
    PAID("PAID", false),
    UNAVAILABLE("UNAVAILABLE", false),
    DEPRECATED("DEPRECATED", false),
    UNKNOWN("NIEZWERYFIKOWANY", false),
    QUOTA_EXCEEDED("LIMIT WYCZERPANY", false),
    AUTH_ERROR("BŁĄD AUTORYZACJI", false)
}

data class FreeModelSpec(
    val providerId: String,
    val providerName: String,
    val modelId: String,
    val modelName: String,
    val freeStatus: FreeStatus,
    val isOnline: Boolean = false,
    val rateLimitInfo: String = "Brak potwierdzonych danych o limicie",
    val qualityScore: Int = 0,
    val speedScore: Int = 0,
    val verificationDate: String = "",
    val isRecommendedFor: List<String> = emptyList(),
    /** ID profilu z kluczem API w SecureStore; różni się od providerId przy profilach własnych. */
    val providerConfigId: String = providerId,
    val verificationSource: String = "",
    /** Exact endpoint used for discovery; required to prove that an Ollama server is local. */
    val endpointBaseUrl: String = ""
)

object FreeModelsCatalog {
    private const val PREFS_NAME = "free_models_catalog_v3"
    private const val KEY_LAST_UPDATE = "last_update_ts"
    private const val KEY_CACHED_MODELS = "cached_models_json"

    /** Only providers whose local/free marker is explicit are seeded. No stale cloud model IDs. */
    val defaultFreeCatalog: List<FreeModelSpec> = emptyList()

    fun loadCatalog(context: Context): List<FreeModelSpec> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val cached = prefs.getString(KEY_CACHED_MODELS, null) ?: return defaultFreeCatalog
        return runCatching {
            val arr = JSONArray(cached)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val provider = o.optString("providerId")
                    val model = o.optString("modelId")
                    if (provider.isBlank() || model.isBlank()) continue
                    // Re-evaluate every cached entry. Older versions trusted broad provider/name rules.
                    val verified = classifyDiscoveredModel(provider, model,
                        o.optString("providerName", provider), o.optString("providerConfigId", provider),
                        o.optString("endpointBaseUrl"))
                    val storedSource = o.optString("verificationSource")
                    add(verified.copy(
                        isOnline = false, // a cached model is not proof of current availability
                        verificationDate = o.optString("verificationDate"),
                        rateLimitInfo = if (verified.freeStatus.isAllowedForSelection) verified.rateLimitInfo else "Brak potwierdzenia ceny lub darmowego limitu",
                        verificationSource = if (storedSource.isNotBlank()) storedSource else verified.verificationSource
                    ))
                }
            }.distinctBy { "${it.providerId}:${it.modelId}" }
        }.getOrDefault(emptyList())
    }

    fun saveCatalog(context: Context, models: List<FreeModelSpec>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val arr = JSONArray()
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        models.distinctBy { "${it.providerId}:${it.modelId}" }.forEach { m ->
            val verified = classifyDiscoveredModel(m.providerId, m.modelId, m.providerName, m.providerConfigId, m.endpointBaseUrl)
            arr.put(JSONObject()
                .put("providerId", verified.providerId)
                .put("providerName", verified.providerName)
                .put("modelId", verified.modelId)
                .put("modelName", verified.modelName)
                .put("freeStatus", verified.freeStatus.name)
                .put("isOnline", false)
                .put("rateLimitInfo", verified.rateLimitInfo)
                .put("qualityScore", verified.qualityScore)
                .put("speedScore", verified.speedScore)
                .put("verificationDate", now)
                .put("providerConfigId", verified.providerConfigId)
                .put("verificationSource", verified.verificationSource)
                .put("endpointBaseUrl", verified.endpointBaseUrl))
        }
        prefs.edit().putString(KEY_CACHED_MODELS, arr.toString())
            .putLong(KEY_LAST_UPDATE, System.currentTimeMillis()).apply()
    }

    fun lastUpdatedFormatted(context: Context): String {
        val ts = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getLong(KEY_LAST_UPDATE, 0L)
        return if (ts == 0L) "Brak synchronizacji" else
            SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(ts))
    }

    fun getRecommendedModel(roleName: String, context: Context): FreeModelSpec? {
        val available = loadCatalog(context).filter { it.freeStatus.isAllowedForSelection && isFreeModel(it.providerId, it.modelId, it.endpointBaseUrl) }
        return available.firstOrNull { it.isRecommendedFor.any { r -> r.equals(roleName, true) } }
            ?: available.firstOrNull()
    }

    /**
     * Provider-specific allowlist. Groq entries below are restricted to model IDs
     * explicitly listed in Groq's Free Plan rate-limit table (not every Groq model).
     * Source: https://console.groq.com/docs/rate-limits
     */
    private val groqFreePlanModels = setOf(
        "openai/gpt-oss-20b",
        "openai/gpt-oss-120b",
        "qwen/qwen3.8-27b"
    )

    fun isLocalOllamaEndpoint(baseUrl: String): Boolean {
        val parsed = runCatching { java.net.URI(baseUrl.trim()) }.getOrNull() ?: return false
        if (!(parsed.scheme.equals("http", true) || parsed.scheme.equals("https", true)) ||
            parsed.port != 11434 || parsed.host.isNullOrBlank() ||
            parsed.rawUserInfo != null || parsed.rawQuery != null || parsed.rawFragment != null ||
            parsed.path.orEmpty().trimEnd('/').let { it.isNotEmpty() && it != "/v1" }) return false
        val host = parsed.host.lowercase(Locale.ROOT).removePrefix("[").removeSuffix("]")
        return host == "localhost" || host.endsWith(".local") || host == "::1" ||
            host == "127.0.0.1" || CleartextEndpointPolicy.isPrivateLanIpv4(host) ||
            host.startsWith("fe80:") || host.startsWith("fc") || host.startsWith("fd")
    }

    private fun endpointHost(baseUrl: String): String =
        runCatching { java.net.URI(baseUrl.trim()).host.orEmpty().lowercase(Locale.ROOT) }.getOrDefault("")

    private fun isOfficialOpenRouterEndpoint(baseUrl: String): Boolean =
        endpointHost(baseUrl) == "openrouter.ai"

    private fun isOfficialGroqEndpoint(baseUrl: String): Boolean =
        endpointHost(baseUrl) == "api.groq.com"

    fun isFreeModel(providerId: String, modelId: String, baseUrl: String = ""): Boolean {
        val model = modelId.lowercase(Locale.ROOT)
        if (isLocalOllamaEndpoint(baseUrl)) return true
        if (isOfficialOpenRouterEndpoint(baseUrl)) return model == "openrouter/free" || model.endsWith(":free")
        if (isOfficialGroqEndpoint(baseUrl)) return model in groqFreePlanModels
        return false
    }

    fun classifyDiscoveredModel(
        providerId: String,
        modelId: String,
        providerName: String = providerId,
        providerConfigId: String = providerId,
        endpointBaseUrl: String = ""
    ): FreeModelSpec {
        val mid = modelId.lowercase(Locale.ROOT)
        val local = isLocalOllamaEndpoint(endpointBaseUrl)
        val openRouterFree = isOfficialOpenRouterEndpoint(endpointBaseUrl) &&
            (mid == "openrouter/free" || mid.endsWith(":free"))
        val groqFreeTier = isOfficialGroqEndpoint(endpointBaseUrl) && mid in groqFreePlanModels
        val status = when {
            local -> FreeStatus.FREE
            openRouterFree || groqFreeTier -> FreeStatus.FREE_TIER
            else -> FreeStatus.UNKNOWN
        }
        val displayName = modelId.removeSuffix(":free").replace('-', ' ').replace('_', ' ')
            .split(" ").joinToString(" ") { it.replaceFirstChar { c -> if (c.isLowerCase()) c.titlecase(Locale.getDefault()) else c.toString() } }
        return FreeModelSpec(
            providerId = providerId,
            providerName = providerName,
            modelId = modelId,
            modelName = displayName,
            freeStatus = status,
            isOnline = false,
            rateLimitInfo = when (status) {
                FreeStatus.FREE -> "Lokalnie — bez opłat za API; dostępność zależy od lokalnego serwera"
                FreeStatus.FREE_TIER -> if (groqFreeTier) "Groq Free Plan — obowiązują limity per model i organizację" else "Model oznaczony jako darmowy przez OpenRouter; limity konta mogą obowiązywać"
                else -> "Nie potwierdzono ceny ani darmowego limitu — zablokowany do wyboru"
            },
            verificationDate = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()),
            providerConfigId = providerConfigId,
            verificationSource = when (status) {
                FreeStatus.FREE -> "Lokalny endpoint Ollama"
                FreeStatus.FREE_TIER -> if (groqFreeTier) "Oficjalna tabela limitów Groq Free Plan: https://console.groq.com/docs/rate-limits" else "Jawny znacznik :free / openrouter/free"
                else -> "Brak wiarygodnego źródła cenowego w odpowiedzi API"
            },
            endpointBaseUrl = endpointBaseUrl
        )
    }
}
