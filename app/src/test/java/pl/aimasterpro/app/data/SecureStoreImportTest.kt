package pl.aimasterpro.app.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
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

    @Test fun failedPreferencesCommitPoisonsAllStoreInstances() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val failingStore = SecureStore(context) { editor ->
            // SharedPreferences.apply() mutates the in-memory map immediately; report the
            // simulated disk failure to exercise the same fail-closed policy as commit() == false.
            editor.apply()
            false
        }
        val otherStore = SecureStore(context)
        try {
            assertFalse(failingStore.saveConfig(validConfig("commit_failure")))

            assertTrue(failingStore.isStorageUncertain())
            assertTrue(otherStore.isStorageUncertain())
            assertTrue(otherStore.allConfigs().isEmpty())
            assertEquals(null, otherStore.readConfig("commit_failure"))
            assertFalse(otherStore.saveConfig(validConfig("must_stay_blocked")))
            val prefs = context.getSharedPreferences("secure_provider_store", Context.MODE_PRIVATE)
            assertTrue(prefs.contains("config_commit_failure"))
            assertFalse(prefs.contains("config_must_stay_blocked"))
        } finally {
            SecureStoreHealth.resetForTests()
        }
    }

    @Test fun thrownPreferencesCommitPoisonsAllStoreInstances() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val throwingStore = SecureStore(context) { editor ->
            // Model a runtime failure after the in-memory SharedPreferences map has been updated.
            editor.apply()
            throw IllegalStateException("simulated commit failure")
        }
        val otherStore = SecureStore(context)
        try {
            assertFalse(throwingStore.saveConfig(validConfig("commit_exception")))
            assertTrue(throwingStore.isStorageUncertain())
            assertTrue(otherStore.isStorageUncertain())
            assertTrue(otherStore.allConfigs().isEmpty())
            assertFalse(otherStore.saveConfig(validConfig("must_stay_blocked")))
            val prefs = context.getSharedPreferences("secure_provider_store", Context.MODE_PRIVATE)
            assertTrue(prefs.contains("config_commit_exception"))
            assertFalse(prefs.contains("config_must_stay_blocked"))
        } finally {
            SecureStoreHealth.resetForTests()
        }
    }

    @Test fun uncertainStorageStateBlocksReadsAndWritesAcrossStoreInstances() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        seedExistingConfig(context)
        val first = SecureStore(context)
        val second = SecureStore(context)
        try {
            SecureStoreHealth.markUncertain()
            assertTrue(first.isStorageUncertain())
            assertTrue(second.isStorageUncertain())
            assertTrue(second.allConfigs().isEmpty())
            assertEquals(null, first.readConfig("existing"))
            assertEquals(null, first.getSecret("key_existing"))
            assertFalse(second.saveConfig(validConfig("blocked")))
            assertFalse(second.putSecret("key_blocked", "must-not-be-written"))
            assertFalse(second.replaceAllConfigs(listOf(validConfig("blocked"))))
            assertFalse(second.canReplaceUnreadableSecrets())
            assertTrue(second.unavailableSecretProviderIds().isEmpty())
            val prefs = context.getSharedPreferences("secure_provider_store", Context.MODE_PRIVATE)
            assertEquals("unchanged-encrypted-value", prefs.getString("key_existing", null))
            assertFalse(prefs.contains("config_blocked"))
            assertFalse(prefs.contains("key_blocked"))
        } finally { SecureStoreHealth.resetForTests() }
    }

    @Test fun preferenceReadFailurePoisonsStoreAndDiscardsPartialConfigList() {
        val base = org.robolectric.RuntimeEnvironment.getApplication()
        val delegate = base.getSharedPreferences("secure_provider_store", Context.MODE_PRIVATE)
        delegate.edit()
            .putStringSet("provider_ids", setOf("a_first", "z_broken"))
            .putString("config_a_first", validConfig("a_first").toString())
            .putString("config_z_broken", validConfig("z_broken").toString())
            .commit()
        val failingPreferences = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)
        ) { _, method, args ->
            if (method.name == "getString" && args?.getOrNull(0) == "config_z_broken") {
                throw IllegalStateException("simulated preference read failure")
            }
            method.invoke(delegate, *(args ?: emptyArray()))
        } as SharedPreferences
        val context = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                if (name == "secure_provider_store") failingPreferences else super.getSharedPreferences(name, mode)
        }
        val store = SecureStore(context)
        try {
            assertTrue(store.allConfigs().isEmpty())
            assertTrue(store.isStorageUncertain())
            assertEquals(null, store.readConfig("a_first"))
        } finally {
            SecureStoreHealth.resetForTests()
        }
    }

    @Test fun ciphertextPreferenceReadFailurePoisonsStoreWithoutDeletingSecret() {
        val base = org.robolectric.RuntimeEnvironment.getApplication()
        val delegate = base.getSharedPreferences("secure_provider_store", Context.MODE_PRIVATE)
        delegate.edit().putString("key_provider_one", "persisted-ciphertext").commit()
        val failingPreferences = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)
        ) { _, method, args ->
            if (method.name == "getString" && args?.getOrNull(0) == "key_provider_one") {
                throw IllegalStateException("simulated ciphertext read failure")
            }
            method.invoke(delegate, *(args ?: emptyArray()))
        } as SharedPreferences
        val context = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                if (name == "secure_provider_store") failingPreferences else super.getSharedPreferences(name, mode)
        }
        val store = SecureStore(context)
        try {
            assertEquals(null, store.getSecret("key_provider_one"))
            assertTrue(store.isStorageUncertain())
            assertEquals("persisted-ciphertext", delegate.getString("key_provider_one", null))
            assertFalse(store.putSecret("key_provider_one", ""))
            assertEquals("persisted-ciphertext", delegate.getString("key_provider_one", null))
        } finally {
            SecureStoreHealth.resetForTests()
        }
    }

    @Test fun providerIdReadFailureBlocksSaveBeforeAnyPreferenceCommit() {
        val base = org.robolectric.RuntimeEnvironment.getApplication()
        val delegate = base.getSharedPreferences("secure_provider_store", Context.MODE_PRIVATE)
        val failingPreferences = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)
        ) { _, method, args ->
            if (method.name == "getStringSet" && args?.getOrNull(0) == "provider_ids") {
                throw IllegalStateException("simulated provider id read failure")
            }
            method.invoke(delegate, *(args ?: emptyArray()))
        } as SharedPreferences
        val context = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                if (name == "secure_provider_store") failingPreferences else super.getSharedPreferences(name, mode)
        }
        val store = SecureStore(context)
        try {
            assertFalse(store.saveConfig(validConfig("must_not_commit")))
            assertTrue(store.isStorageUncertain())
            assertFalse(delegate.contains("config_must_not_commit"))
        } finally {
            SecureStoreHealth.resetForTests()
        }
    }

    @Test fun malformedPersistedProviderJsonPoisonsStoreAndDiscardsPartialConfigList() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("secure_provider_store", Context.MODE_PRIVATE)
        prefs.edit()
            .putStringSet("provider_ids", setOf("a_valid", "z_malformed"))
            .putString("config_a_valid", validConfig("a_valid").toString())
            .putString("config_z_malformed", "{not-valid-json")
            .commit()
        val store = SecureStore(context)

        assertTrue(store.allConfigs().isEmpty())
        assertTrue(store.isStorageUncertain())
        assertEquals(null, store.readConfig("a_valid"))
        assertFalse(store.saveConfig(validConfig("must_remain_blocked")))
        assertFalse(prefs.contains("config_must_remain_blocked"))
        assertEquals("{not-valid-json", prefs.getString("config_z_malformed", null))
    }

    @Test fun missingConfigForListedProviderPoisonsStoreAndDiscardsPartialConfigList() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("secure_provider_store", Context.MODE_PRIVATE)
        prefs.edit()
            .putStringSet("provider_ids", setOf("a_valid", "z_missing"))
            .putString("config_a_valid", validConfig("a_valid").toString())
            .commit()
        val store = SecureStore(context)

        assertTrue(store.allConfigs().isEmpty())
        assertTrue(store.isStorageUncertain())
        assertEquals(null, store.readConfig("a_valid"))
        assertFalse(store.saveConfig(validConfig("must_remain_blocked")))
        assertFalse(prefs.contains("config_must_remain_blocked"))
        assertFalse(prefs.contains("config_z_missing"))
    }

    @Test fun persistedConfigIdMismatchPoisonsStoreAndDiscardsPartialConfigList() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("secure_provider_store", Context.MODE_PRIVATE)
        prefs.edit()
            .putStringSet("provider_ids", setOf("a_valid", "z_mismatched"))
            .putString("config_a_valid", validConfig("a_valid").toString())
            .putString("config_z_mismatched", validConfig("different_provider").toString())
            .commit()
        val store = SecureStore(context)

        assertTrue(store.allConfigs().isEmpty())
        assertTrue(store.isStorageUncertain())
        assertEquals(null, store.readConfig("a_valid"))
        assertFalse(store.saveConfig(validConfig("must_remain_blocked")))
        assertFalse(prefs.contains("config_must_remain_blocked"))
        assertEquals("different_provider", JSONObject(prefs.getString("config_z_mismatched", "{}")!!).getString("id"))
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

    @Test fun missingKeystoreAliasIsNotRecreatedWhileEncryptedSecretsRemain() {
        assertFalse(shouldCreateFreshSecureStoreKey(aliasExists = false, hasPersistedCiphertexts = true))
    }

    @Test fun missingKeystoreAliasCanBeCreatedForFreshStoreOnly() {
        assertTrue(shouldCreateFreshSecureStoreKey(aliasExists = false, hasPersistedCiphertexts = false))
        assertFalse(shouldCreateFreshSecureStoreKey(aliasExists = true, hasPersistedCiphertexts = false))
    }

    @Test fun fullReplacementRejectsWhenAnyExistingSecretIsUnreadable() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("secure_provider_store", Context.MODE_PRIVATE)
        prefs.edit()
            .putStringSet("provider_ids", setOf("provider_one"))
            .putString("config_provider_one", validConfig().toString())
            .putString("key_provider_one", "!")
            .commit()
        val beforeConfig = prefs.getString("config_provider_one", null)

        val result = SecureStore(context).replaceAllConfigs(listOf(validConfig("replacement")))

        assertFalse(result)
        assertEquals(setOf("provider_one"), prefs.getStringSet("provider_ids", emptySet()))
        assertEquals("!", prefs.getString("key_provider_one", null))
        assertEquals(beforeConfig, prefs.getString("config_provider_one", null))
        assertFalse(prefs.contains("config_replacement"))
    }

    @Test fun unreadableSecretSetDetectionIsFailClosed() {
        assertTrue(hasUnreadableExistingSecrets(setOf("key_a", "key_b"), setOf("key_a")))
        assertFalse(hasUnreadableExistingSecrets(setOf("key_a"), setOf("key_a")))
        assertFalse(hasUnreadableExistingSecrets(emptySet(), emptySet()))
    }

    @Test fun unreadableSecretReplacementRequiresExistingUsableKeystoreAlias() {
        assertTrue(canReplaceUnreadableSecret(aliasExists = true, keyIsSecretKey = true))
        assertFalse(canReplaceUnreadableSecret(aliasExists = false, keyIsSecretKey = false))
        assertFalse(canReplaceUnreadableSecret(aliasExists = true, keyIsSecretKey = false))
        assertFalse(canReplaceUnreadableSecret(aliasExists = false, keyIsSecretKey = true))
    }

    @Test fun blankLegacyCiphertextIsTreatedAsMissingSecretNotKeystoreFailure() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("secure_provider_store", Context.MODE_PRIVATE)
        prefs.edit()
            .putStringSet("provider_ids", setOf("provider_one"))
            .putString("config_provider_one", validConfig().toString())
            .putString("key_provider_one", "")
            .commit()

        val config = SecureStore(context).readConfig("provider_one")

        assertEquals(false, config?.optBoolean("_secretUnavailable", true))
        assertTrue(SecureStore(context).unavailableSecretProviderIds().isEmpty())
    }

}
