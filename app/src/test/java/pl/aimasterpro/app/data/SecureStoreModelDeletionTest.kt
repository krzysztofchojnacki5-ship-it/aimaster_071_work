package pl.aimasterpro.app.data

import android.content.Context
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
class SecureStoreModelDeletionTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("secure_provider_store", Context.MODE_PRIVATE)

    @Before fun reset() {
        prefs.edit().clear().commit()
        SecureStoreHealth.resetForTests()
    }

    @After fun cleanup() {
        SecureStoreHealth.resetForTests()
    }

    private fun profile(id: String, parent: String = "", model: String = "") = JSONObject()
        .put("id", id)
        .put("name", id)
        .put("kind", "OPENAI_COMPATIBLE")
        .put("baseUrl", "https://example.invalid/v1")
        .put("model", model)
        .put("enabled", true)
        .put("parentProviderId", parent)
        .put("apiKey", "")

    @Test fun deletesOnlyChildModelAndPreservesParentProvider() {
        val store = SecureStore(context)
        assertTrue(store.replaceAllConfigs(listOf(
            profile("provider-root"),
            profile("provider-root__model_a", "provider-root", "model-a")
        )))

        assertTrue(store.deleteModelProfile("provider-root__model_a"))

        assertEquals(setOf("provider-root"), prefs.getStringSet("provider_ids", emptySet()))
        assertNotNull(store.readConfig("provider-root"))
        assertNull(prefs.getString("config_provider-root__model_a", null))
        assertNull(prefs.getString("key_provider-root__model_a", null))
    }

    @Test fun refusesToDeleteProviderRootOrUnknownModel() {
        val store = SecureStore(context)
        assertTrue(store.replaceAllConfigs(listOf(profile("provider-root"))))

        assertFalse(store.deleteModelProfile("provider-root"))
        assertFalse(store.deleteModelProfile("missing-model"))

        assertEquals(setOf("provider-root"), prefs.getStringSet("provider_ids", emptySet()))
        assertNotNull(prefs.getString("config_provider-root", null))
    }

    @Test fun refusesToDeleteModelWithDependentProfiles() {
        val store = SecureStore(context)
        assertTrue(store.replaceAllConfigs(listOf(
            profile("provider-root"),
            profile("model-parent", "provider-root", "model-parent"),
            profile("model-child", "model-parent", "model-child")
        )))

        assertFalse(store.deleteModelProfile("model-parent"))

        assertEquals(setOf("provider-root", "model-parent", "model-child"),
            prefs.getStringSet("provider_ids", emptySet()))
    }

    @Test fun malformedCatalogFailsClosedWithoutRemovingAnyRecord() {
        val store = SecureStore(context)
        assertTrue(store.replaceAllConfigs(listOf(
            profile("provider-root"),
            profile("model-a", "provider-root", "model-a")
        )))
        prefs.edit().putString("config_model-a", "{broken").commit()

        assertFalse(store.deleteModelProfile("model-a"))
        assertTrue(store.isStorageUncertain())
        assertEquals(setOf("provider-root", "model-a"), prefs.getStringSet("provider_ids", emptySet()))
        assertEquals("{broken", prefs.getString("config_model-a", null))
    }
}
