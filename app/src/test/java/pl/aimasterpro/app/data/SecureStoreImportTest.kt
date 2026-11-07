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
    private fun validConfig(id: String = "provider_one") = JSONObject()
        .put("id", id).put("name", "Provider").put("kind", "OPENAI_COMPATIBLE")
        .put("baseUrl", "").put("model", "").put("apiKey", "")
        .put("enabled", false).put("parentProviderId", "")

    @Test fun malformedProviderSchemaIsRejectedWithoutChangingExistingConfig() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        seedExistingConfig(context)
        val malformed = validConfig().put("enabled", "yes")

        val result = SecureStore(context).replaceAllConfigs(listOf(malformed))

        assertFalse(result)
        val prefs = context.getSharedPreferences("secure_provider_store", Context.MODE_PRIVATE)
        assertEquals(setOf("existing"), prefs.getStringSet("provider_ids", emptySet()))
        assertEquals("unchanged-encrypted-value", prefs.getString("key_existing", null))
        assertFalse(prefs.contains("config_provider_one"))
    }

    @Test fun providerWithMissingParentIsRejectedBeforeMutation() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        seedExistingConfig(context)
        val child = validConfig().put("parentProviderId", "missing_parent")

        val result = SecureStore(context).replaceAllConfigs(listOf(child))

        assertFalse(result)
        val prefs = context.getSharedPreferences("secure_provider_store", Context.MODE_PRIVATE)
        assertEquals(setOf("existing"), prefs.getStringSet("provider_ids", emptySet()))
        assertEquals("unchanged-encrypted-value", prefs.getString("key_existing", null))
    }

    @Test fun cyclicProviderInheritanceIsRejectedWithoutChangingExistingConfig() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        seedExistingConfig(context)
        val first = validConfig("first").put("parentProviderId", "second")
        val second = validConfig("second").put("parentProviderId", "first")

        val result = SecureStore(context).replaceAllConfigs(listOf(first, second))

        assertFalse(result)
        val prefs = context.getSharedPreferences("secure_provider_store", Context.MODE_PRIVATE)
        assertEquals(setOf("existing"), prefs.getStringSet("provider_ids", emptySet()))
        assertEquals("unchanged-encrypted-value", prefs.getString("key_existing", null))
    }

    @Test fun truncatedGcmSecretIsRejectedBeforeKeystoreDecryption() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("secure_provider_store", Context.MODE_PRIVATE)
        prefs.edit().putString("key_truncated", android.util.Base64.encodeToString(ByteArray(27), android.util.Base64.NO_WRAP)).commit()

        val result = SecureStore(context).getSecret("key_truncated")

        assertEquals(null, result)
    }

    @Test fun providerIdWithPreferenceKeyInjectionCharactersIsRejected() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        seedExistingConfig(context)
        val invalid = validConfig("valid_id").put("id", "valid_id\\nprovider_ids")

        val result = SecureStore(context).replaceAllConfigs(listOf(invalid))

        assertFalse(result)
        val prefs = context.getSharedPreferences("secure_provider_store", Context.MODE_PRIVATE)
        assertEquals(setOf("existing"), prefs.getStringSet("provider_ids", emptySet()))
        assertEquals("unchanged-encrypted-value", prefs.getString("key_existing", null))
    }

    @Test fun individualSaveRejectsInvalidProviderSchemaWithoutMutation() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        seedExistingConfig(context)
        val invalid = validConfig("bad/id")

        val result = SecureStore(context).saveConfig(invalid)

        assertFalse(result)
        val prefs = context.getSharedPreferences("secure_provider_store", Context.MODE_PRIVATE)
        assertEquals(setOf("existing"), prefs.getStringSet("provider_ids", emptySet()))
        assertEquals("unchanged-encrypted-value", prefs.getString("key_existing", null))
        assertFalse(prefs.contains("config_bad/id"))
    }

    @Test fun unreadableExistingSecretIsMarkedAndNeverReturnedAsAnEmptyHealthySecret() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("secure_provider_store", Context.MODE_PRIVATE)
        prefs.edit()
            .putStringSet("provider_ids", setOf("provider_one"))
            .putString("config_provider_one", validConfig().apply { remove("apiKey") }.toString())
            .putString("key_provider_one", "!")
            .commit()

        val config = SecureStore(context).readConfig("provider_one")

        assertEquals(true, config?.optBoolean("_secretUnavailable", false))
        assertEquals("", config?.optString("apiKey"))
        assertEquals(listOf("provider_one"), SecureStore(context).unavailableSecretProviderIds())
    }

    @Test fun blankSaveCannotEraseExistingSecretThatCannotBeDecrypted() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("secure_provider_store", Context.MODE_PRIVATE)
        prefs.edit()
            .putStringSet("provider_ids", setOf("provider_one"))
            .putString("config_provider_one", validConfig().apply { remove("apiKey") }.toString())
            .putString("key_provider_one", "!")
            .commit()
        val beforeConfig = prefs.getString("config_provider_one", null)

        val result = SecureStore(context).saveConfig(validConfig())

        assertFalse(result)
        assertEquals(setOf("provider_one"), prefs.getStringSet("provider_ids", emptySet()))
        assertEquals("!", prefs.getString("key_provider_one", null))
        assertEquals(beforeConfig, prefs.getString("config_provider_one", null))
    }

    @Test fun blankPutSecretCannotDeleteUnreadableCiphertext() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("secure_provider_store", Context.MODE_PRIVATE)
        prefs.edit().putString("key_provider_one", "!").commit()

        val result = SecureStore(context).putSecret("key_provider_one", "")

        assertFalse(result)
        assertEquals("!", prefs.getString("key_provider_one", null))
    }

}
