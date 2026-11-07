package pl.aimasterpro.app.core

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import pl.aimasterpro.app.data.SecureStore
import pl.aimasterpro.app.data.SecureStoreHealth
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
class SecureStoreReplacementRecoveryTest {
    private val context get() = RuntimeEnvironment.getApplication()
    // Mirrors MainActivity startup: selected_provider is the MASTER legacy fallback;
    // specialist roles default to the seeded Groq profile.
    private val startupDefaults = linkedMapOf(
        "MASTER" to "old-provider",
        "CODER" to "groq",
        "RESEARCHER" to "groq"
    )

    @Before fun reset() {
        context.getSharedPreferences("secure_provider_store", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("chat_state", Context.MODE_PRIVATE).edit().clear().commit()
        SecureStoreHealth.resetForTests()
        SharedPreferencesWriteHealth.resetForTests("chat_state")
    }

    @After fun cleanup() {
        SecureStoreHealth.resetForTests()
        SharedPreferencesWriteHealth.resetForTests("chat_state")
    }

    private fun provider(id: String) = JSONObject()
        .put("id", id)
        .put("name", id)
        .put("kind", "OPENAI_COMPATIBLE")
        .put("baseUrl", "https://example.invalid/v1")
        .put("model", "test-model")
        .put("enabled", true)
        .put("parentProviderId", "")
        .put("apiKey", "")

    private fun seedOldCatalog() {
        assertTrue(SecureStore(context).replaceAllConfigs(listOf(provider("old-provider"))))
        assertTrue(AgentProviderAssignmentStore.save(context, "MASTER", "old-provider"))
        assertTrue(AgentProviderAssignmentStore.save(context, "CODER", "old-provider"))
        assertTrue(AgentProviderAssignmentStore.save(context, "RESEARCHER", "old-provider"))
    }

    /** Reproduces the relevant seedDefaultProviders() effects before startup reconciliation. */
    private fun seedStartupDefaults() {
        val store = SecureStore(context)
        assertTrue(store.saveConfig(provider("gemini")))
        assertTrue(store.saveConfig(provider("groq")))
    }

    @Test fun startupRecoversWhenFailedSecureStoreCommitExposedNewCatalogInMemory() {
        seedOldCatalog()
        val failedContext = AmbiguousCommitContext(
            context, "secure_provider_store", applyBeforeFailure = true
        )
        assertFalse(SecureStore(failedContext).replaceAllConfigs(
            listOf(provider("new-master"), provider("new-coder"))
        ))
        assertTrue(SecureStoreHealth.isUncertain())

        // Process restart: discard process-local uncertainty and reload whichever snapshot survived.
        SecureStoreHealth.resetForTests()
        seedStartupDefaults()
        val catalog = SecureStore(context).allConfigs().map { it.optString("id") }.toSet()
        assertEquals(setOf("new-master", "new-coder", "gemini", "groq"), catalog)

        val recovered = AgentProviderAssignmentStore.reconcileExistingAssignments(
            context, startupDefaults, catalog, "old-provider"
        )
        assertNotNull(recovered)
        // The interrupted import's preferred MASTER ID was not persisted. Startup therefore
        // uses the seeded Gemini fallback; specialist roles use Groq.
        assertEquals("gemini", recovered!!["MASTER"])
        assertEquals("groq", recovered["CODER"])
        assertEquals("groq", recovered["RESEARCHER"])
        assertEquals("gemini", context.getSharedPreferences("chat_state", Context.MODE_PRIVATE)
            .getString("selected_provider", null))
    }

    @Test fun thrownReplacementCommitAfterApplyQuarantinesStoreAndPreservesVisibleSnapshot() {
        seedOldCatalog()
        val throwingStore = SecureStore(context) { editor ->
            editor.apply()
            throw IllegalStateException("simulated replaceAllConfigs commit exception")
        }

        assertFalse(throwingStore.replaceAllConfigs(
            listOf(provider("exception-master"), provider("exception-coder"))
        ))
        assertTrue(SecureStoreHealth.isUncertain())
        assertTrue(throwingStore.allConfigs().isEmpty())
        assertFalse(throwingStore.saveConfig(provider("must-not-save")))

        val prefs = context.getSharedPreferences("secure_provider_store", Context.MODE_PRIVATE)
        assertEquals(setOf("exception-master", "exception-coder"),
            prefs.getStringSet("provider_ids", emptySet()))
        assertFalse(prefs.contains("config_old-provider"))
        assertFalse(prefs.contains("config_must-not-save"))

        SecureStoreHealth.resetForTests()
        assertEquals(setOf("exception-master", "exception-coder"),
            SecureStore(context).allConfigs().map { it.optString("id") }.toSet())
    }

    @Test fun thrownReplacementCommitBeforeApplyKeepsPreviousCatalog() {
        seedOldCatalog()
        val throwingStore = SecureStore(context) { _ ->
            throw IllegalStateException("simulated commit exception before apply")
        }

        assertFalse(throwingStore.replaceAllConfigs(
            listOf(provider("exception-master"), provider("exception-coder"))
        ))
        assertTrue(SecureStoreHealth.isUncertain())
        assertTrue(throwingStore.allConfigs().isEmpty())

        val prefs = context.getSharedPreferences("secure_provider_store", Context.MODE_PRIVATE)
        assertEquals(setOf("old-provider"), prefs.getStringSet("provider_ids", emptySet()))
        assertTrue(prefs.contains("config_old-provider"))
        assertFalse(prefs.contains("config_exception-master"))
        assertFalse(prefs.contains("config_exception-coder"))

        SecureStoreHealth.resetForTests()
        assertEquals(setOf("old-provider"),
            SecureStore(context).allConfigs().map { it.optString("id") }.toSet())
    }

    @Test fun invalidLaterImportEntryLeavesEarlierPreparedEntriesAndOldCatalogUntouched() {
        seedOldCatalog()
        val first = provider("would-be-imported").put("apiKey", "secret-that-must-not-be-encrypted")
        val invalidLater = provider("invalid-later").put("enabled", "not-a-boolean")

        assertFalse(SecureStore(context).replaceAllConfigs(listOf(first, invalidLater)))
        assertFalse(SecureStoreHealth.isUncertain())

        val prefs = context.getSharedPreferences("secure_provider_store", Context.MODE_PRIVATE)
        assertEquals(setOf("old-provider"), prefs.getStringSet("provider_ids", emptySet()))
        assertTrue(prefs.contains("config_old-provider"))
        assertFalse(prefs.contains("key_old-provider"))
        assertFalse(prefs.contains("config_would-be-imported"))
        assertFalse(prefs.contains("key_would-be-imported"))
        assertFalse(prefs.contains("config_invalid-later"))
        assertFalse(prefs.contains("key_invalid-later"))
    }

    @Test fun startupCanReloadDurableCatalogAfterTransientProviderIdReadFailure() {
        seedOldCatalog()
        val base = context
        val delegate = base.getSharedPreferences("secure_provider_store", Context.MODE_PRIVATE)
        val failingPreferences = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)
        ) { _, method, args ->
            if (method.name == "getStringSet" && args?.getOrNull(0) == "provider_ids") {
                throw IllegalStateException("simulated transient provider ID read failure")
            }
            method.invoke(delegate, *(args ?: emptyArray()))
        } as SharedPreferences
        val failingContext = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                if (name == "secure_provider_store") failingPreferences
                else super.getSharedPreferences(name, mode)
        }

        assertTrue(SecureStore(failingContext).allConfigs().isEmpty())
        assertTrue(SecureStoreHealth.isUncertain())
        assertEquals(setOf("old-provider"), delegate.getStringSet("provider_ids", emptySet()))

        // Simulated new process: use the base preferences instance after clearing process-local latch.
        SecureStoreHealth.resetForTests()
        assertEquals(setOf("old-provider"),
            SecureStore(base).allConfigs().map { it.optString("id") }.toSet())
    }

    @Test fun startupKeepsOldAssignmentsWhenFailedSecureStoreCommitDidNotPersist() {
        seedOldCatalog()
        val failedContext = AmbiguousCommitContext(
            context, "secure_provider_store", applyBeforeFailure = false
        )
        assertFalse(SecureStore(failedContext).replaceAllConfigs(
            listOf(provider("new-master"), provider("new-coder"))
        ))
        assertTrue(SecureStoreHealth.isUncertain())

        SecureStoreHealth.resetForTests()
        seedStartupDefaults()
        val catalog = SecureStore(context).allConfigs().map { it.optString("id") }.toSet()
        assertEquals(setOf("old-provider", "gemini", "groq"), catalog)

        val recovered = AgentProviderAssignmentStore.reconcileExistingAssignments(
            context, startupDefaults, catalog, "old-provider"
        )
        assertNotNull(recovered)
        assertEquals("old-provider", recovered!!["MASTER"])
        assertEquals("old-provider", recovered["CODER"])
        assertEquals("old-provider", recovered["RESEARCHER"])
        assertEquals("old-provider", context.getSharedPreferences("chat_state", Context.MODE_PRIVATE)
            .getString("selected_provider", null))
    }
}
