package pl.aimasterpro.app.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Connection-level identity. Credentials are never copied into this model; credentialRefId
 * points to the existing SecureStore entry so migration cannot duplicate or expose API keys.
 */
data class ProviderProfile(
    val id: String,
    val name: String,
    val kind: ProviderKind,
    val baseUrl: String,
    val credentialRefId: String,
    val enabled: Boolean,
    val allowInsecureLanOllama: Boolean
)

/** Model-level identity, independent from endpoint credentials and provider connection settings. */
data class ModelProfile(
    val id: String,
    val providerProfileId: String,
    val modelId: String,
    val displayName: String,
    val runtimeKind: ModelRuntimeKind,
    val enabled: Boolean,
    /** Existing SecureStore config ID retained for backward-compatible routing and rollback. */
    val legacyConfigId: String
)

enum class ModelRuntimeKind { REMOTE_API, LOCAL_OLLAMA }

data class ProviderModelMigrationResult(
    val providers: List<ProviderProfile>,
    val models: List<ModelProfile>,
    val skippedConfigIds: List<String>
)

/**
 * Non-destructive adapter from the legacy combined ProviderConfig records.
 * IDs are retained exactly so existing agent assignments remain resolvable.
 */
object LegacyProviderModelMigration {
    fun convert(configs: List<ProviderConfig>): ProviderModelMigrationResult {
        val byId = configs.filter { it.id.isNotBlank() }.associateBy { it.id }
        val roots = byId.values.filter { it.parentProviderId.isBlank() }
        val providers = roots.map { config ->
            ProviderProfile(
                id = config.id,
                name = config.name,
                kind = config.kind,
                baseUrl = config.baseUrl,
                credentialRefId = config.id,
                enabled = config.enabled,
                allowInsecureLanOllama = config.allowInsecureLanOllama
            )
        }
        val rootIds = providers.map { it.id }.toSet()
        val models = mutableListOf<ModelProfile>()
        val skipped = mutableListOf<String>()
        configs.forEach { config ->
            if (config.id.isBlank() || config.model.isBlank()) {
                if (config.id.isNotBlank()) skipped += config.id
                return@forEach
            }
            val providerId = if (config.parentProviderId.isBlank()) config.id else config.parentProviderId
            if (providerId !in rootIds) {
                skipped += config.id
                return@forEach
            }
            val provider = byId[providerId] ?: run {
                skipped += config.id
                return@forEach
            }
            val runtime = if (FreeModelsCatalog.isLocalOllamaEndpoint(provider.baseUrl)) {
                ModelRuntimeKind.LOCAL_OLLAMA
            } else {
                ModelRuntimeKind.REMOTE_API
            }
            models += ModelProfile(
                id = config.id,
                providerProfileId = providerId,
                modelId = config.model,
                displayName = config.name,
                runtimeKind = runtime,
                enabled = config.enabled && provider.enabled,
                legacyConfigId = config.id
            )
        }
        return ProviderModelMigrationResult(
            providers = providers.distinctBy { it.id },
            models = models.distinctBy { it.id },
            skippedConfigIds = skipped.distinct()
        )
    }
}

/**
 * Stores the separated, credential-free view alongside the legacy SecureStore records.
 * The old records remain authoritative until later routing packages explicitly switch over.
 */
object ProviderModelProfileStore {
    private const val PREFS = "provider_model_profiles_v1"
    private const val KEY_SCHEMA = "schema_version"
    private const val KEY_PROVIDERS = "providers_json"
    private const val KEY_MODELS = "models_json"
    private const val SCHEMA_VERSION = 1

    fun migrateLegacyConfigs(context: Context, configs: List<ProviderConfig>): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_SCHEMA, 0) >= SCHEMA_VERSION) return true
        if (configs.isEmpty()) return false
        val converted = LegacyProviderModelMigration.convert(configs)
        if (converted.providers.isEmpty()) return false
        val providersJson = JSONArray().apply {
            converted.providers.forEach { item ->
                put(JSONObject()
                    .put("id", item.id)
                    .put("name", item.name)
                    .put("kind", item.kind.name)
                    .put("baseUrl", item.baseUrl)
                    .put("credentialRefId", item.credentialRefId)
                    .put("enabled", item.enabled)
                    .put("allowInsecureLanOllama", item.allowInsecureLanOllama))
            }
        }
        val modelsJson = JSONArray().apply {
            converted.models.forEach { item ->
                put(JSONObject()
                    .put("id", item.id)
                    .put("providerProfileId", item.providerProfileId)
                    .put("modelId", item.modelId)
                    .put("displayName", item.displayName)
                    .put("runtimeKind", item.runtimeKind.name)
                    .put("enabled", item.enabled)
                    .put("legacyConfigId", item.legacyConfigId))
            }
        }
        // One synchronous commit: schema marker and both catalogs are all-or-nothing.
        return prefs.edit()
            .putString(KEY_PROVIDERS, providersJson.toString())
            .putString(KEY_MODELS, modelsJson.toString())
            .putInt(KEY_SCHEMA, SCHEMA_VERSION)
            .commit()
    }

    fun isMigrated(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_SCHEMA, 0) >= SCHEMA_VERSION

    fun readProviderJson(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PROVIDERS, "[]") ?: "[]"

    fun readModelJson(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_MODELS, "[]") ?: "[]"
}
