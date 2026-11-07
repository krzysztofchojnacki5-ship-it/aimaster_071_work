package pl.aimasterpro.app.data

import android.content.Context
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SecureStoreImportTest {
    private fun seedExistingConfig(context: Context) {
        context.getSharedPreferences("secure_provider_store", Context.MODE_PRIVATE).edit()
            .putStringSet("provider_ids", setOf("existing"))
            .putString("config_existing", JSONObject().put("id", "existing").put("name", "Existing").toString())
            .putString("key_existing", "unchanged-encrypted-value")
            .commit()
    }

    @Test fun emptyReplacementIsRejectedWithoutChangingExistingConfig() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        seedExistingConfig(context)

        val result = SecureStore(context).replaceAllConfigs(emptyList())

        assertFalse(result)
        val prefs = context.getSharedPreferences("secure_provider_store", Context.MODE_PRIVATE)
        assertEquals(setOf("existing"), prefs.getStringSet("provider_ids", emptySet()))
        assertEquals("unchanged-encrypted-value", prefs.getString("key_existing", null))
        assertEquals("Existing", JSONObject(prefs.getString("config_existing", "{}")!!).getString("name"))
    }

    @Test fun duplicateProviderIdsAreRejectedBeforeAnyMutation() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        seedExistingConfig(context)
        val duplicateConfigs = listOf(
            JSONObject().put("id", "duplicate").put("name", "First").put("apiKey", "first-secret"),
            JSONObject().put("id", "duplicate").put("name", "Second").put("apiKey", "second-secret")
        )

        val result = SecureStore(context).replaceAllConfigs(duplicateConfigs)

        assertFalse(result)
        val prefs = context.getSharedPreferences("secure_provider_store", Context.MODE_PRIVATE)
        assertEquals(setOf("existing"), prefs.getStringSet("provider_ids", emptySet()))
        assertEquals("unchanged-encrypted-value", prefs.getString("key_existing", null))
        assertFalse(prefs.contains("config_duplicate"))
        assertFalse(prefs.contains("key_duplicate"))
    }
}
