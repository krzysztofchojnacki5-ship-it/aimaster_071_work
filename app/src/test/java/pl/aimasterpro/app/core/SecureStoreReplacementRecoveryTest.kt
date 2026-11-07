package pl.aimasterpro.app.core

import android.content.Context
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

@RunWith(RobolectricTestRunner::class)
class SecureStoreReplacementRecoveryTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val defaults = linkedMapOf(
        "MASTER" to "old-provider",
        "CODER" to "old-provider",
        "RESEARCHER" to "old-provider"
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
        val catalog = SecureStore(context).allConfigs().map { it.optString("id") }.toSet()
        assertEquals(setOf("new-master", "new-coder"), catalog)

        val recovered = AgentProviderAssignmentStore.reconcileExistingAssignments(
            context, defaults, catalog, "old-provider"
        )
        assertNotNull(recovered)
        assertEquals("new-master", recovered!!["MASTER"])
        assertEquals("new-coder", recovered["CODER"])
        assertEquals("new-master", recovered["RESEARCHER"])
        assertEquals("new-master", context.getSharedPreferences("chat_state", Context.MODE_PRIVATE)
            .getString("selected_provider", null))
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
        val catalog = SecureStore(context).allConfigs().map { it.optString("id") }.toSet()
        assertEquals(setOf("old-provider"), catalog)

        val recovered = AgentProviderAssignmentStore.reconcileExistingAssignments(
            context, defaults, catalog, "old-provider"
        )
        assertNotNull(recovered)
        assertEquals("old-provider", recovered!!["MASTER"])
        assertEquals("old-provider", recovered["CODER"])
        assertEquals("old-provider", recovered["RESEARCHER"])
        assertEquals("old-provider", context.getSharedPreferences("chat_state", Context.MODE_PRIVATE)
            .getString("selected_provider", null))
    }
}
