package pl.aimasterpro.app.core

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ProviderModelProfilesTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun reset() {
        context.getSharedPreferences("provider_model_profiles_v1", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun separatesProviderCredentialsFromModelProfilesAndPreservesLegacyIds() {
        val root = ProviderConfig(
            id = "groq", name = "Groq API", kind = ProviderKind.OPENAI_COMPATIBLE,
            baseUrl = "https://api.groq.com/openai/v1", model = "", apiKey = "must-not-be-copied"
        )
        val child = root.copy(
            id = "groq__model_123", name = "Groq • GPT OSS", model = "openai/gpt-oss-120b",
            apiKey = "", parentProviderId = "groq"
        )

        val result = LegacyProviderModelMigration.convert(listOf(root, child))

        assertEquals(1, result.providers.size)
        assertEquals("groq", result.providers.single().id)
        assertEquals("groq", result.providers.single().credentialRefId)
        assertEquals(1, result.models.size)
        assertEquals("groq__model_123", result.models.single().id)
        assertEquals("groq", result.models.single().providerProfileId)
        assertEquals("openai/gpt-oss-120b", result.models.single().modelId)
        assertEquals("groq__model_123", result.models.single().legacyConfigId)
        assertTrue(result.skippedConfigIds.isEmpty())
    }

    @Test fun distinguishesLocalOllamaFromRemoteApiModels() {
        val local = ProviderConfig(
            id = "ollama", name = "Ollama", kind = ProviderKind.OPENAI_COMPATIBLE,
            baseUrl = "http://127.0.0.1:11434/v1", model = "qwen2.5:7b"
        )
        val remote = ProviderConfig(
            id = "remote", name = "Remote", kind = ProviderKind.OPENAI_COMPATIBLE,
            baseUrl = "https://api.example.test/v1", model = "example-model"
        )

        val result = LegacyProviderModelMigration.convert(listOf(local, remote))

        assertEquals(ModelRuntimeKind.LOCAL_OLLAMA, result.models.first { it.id == "ollama" }.runtimeKind)
        assertEquals(ModelRuntimeKind.REMOTE_API, result.models.first { it.id == "remote" }.runtimeKind)
    }

    @Test fun skipsOrphanedChildProfilesWithoutInventingProviderConnections() {
        val orphan = ProviderConfig(
            id = "orphan-model", name = "Orphan", kind = ProviderKind.OPENAI_COMPATIBLE,
            baseUrl = "https://api.example.test/v1", model = "model-x", parentProviderId = "missing-provider"
        )

        val result = LegacyProviderModelMigration.convert(listOf(orphan))

        assertTrue(result.providers.isEmpty())
        assertTrue(result.models.isEmpty())
        assertEquals(listOf("orphan-model"), result.skippedConfigIds)
    }

    @Test fun migrationCommitsCredentialFreeCatalogAtomicallyAndIsIdempotent() {
        val config = ProviderConfig(
            id = "safe-provider", name = "Safe Provider", kind = ProviderKind.OPENAI_COMPATIBLE,
            baseUrl = "https://api.example.test/v1", model = "model-x", apiKey = "top-secret"
        )

        assertTrue(ProviderModelProfileStore.migrateLegacyConfigs(context, listOf(config)))
        assertTrue(ProviderModelProfileStore.isMigrated(context))
        assertFalse(ProviderModelProfileStore.readProviderJson(context).contains("top-secret"))
        assertFalse(ProviderModelProfileStore.readModelJson(context).contains("top-secret"))
        assertTrue(ProviderModelProfileStore.migrateLegacyConfigs(context, emptyList()))
        assertEquals(1, org.json.JSONArray(ProviderModelProfileStore.readProviderJson(context)).length())
        assertEquals(1, org.json.JSONArray(ProviderModelProfileStore.readModelJson(context)).length())
    }

    @Test fun emptyLegacyStoreDoesNotSetMigrationMarker() {
        assertFalse(ProviderModelProfileStore.migrateLegacyConfigs(context, emptyList()))
        assertFalse(ProviderModelProfileStore.isMigrated(context))
    }
}
