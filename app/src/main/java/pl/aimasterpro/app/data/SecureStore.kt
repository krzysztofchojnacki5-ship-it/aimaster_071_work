package pl.aimasterpro.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores provider secrets encrypted with an Android Keystore AES-GCM key with auto-recovery on key invalidation. */
class SecureStore(context: Context) {
    private val prefs = context.getSharedPreferences("secure_provider_store", Context.MODE_PRIVATE)
    private val alias = "ai_master_provider_key_v1"

    /**
     * Returns the AES key, creating it only when no entry exists. An existing entry is never deleted
     * here: a transient Keystore failure must not destroy the key that protects saved provider keys.
     */
    @Synchronized private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias(alias)) return generateNewKey()
        return store.getKey(alias, null) as? SecretKey
            ?: throw IllegalStateException("Klucz SecureStore ma nieprawidłowy typ.")
    }

    private fun generateNewKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build())
        return generator.generateKey()
    }

    /** Returns false when the secret could not be stored; nothing is written in that case. */
    fun putSecret(name: String, value: String): Boolean {
        if (value.isBlank()) return prefs.edit().remove(name).commit()
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            val packed = cipher.iv + encrypted
            prefs.edit().putString(name, Base64.encodeToString(packed, Base64.NO_WRAP)).commit()
        } catch (_: Exception) {
            false
        }
    }

    fun getSecret(name: String): String? = runCatching {
        val packed = Base64.decode(prefs.getString(name, null), Base64.NO_WRAP)
        val iv = packed.copyOfRange(0, 12)
        val ciphertext = packed.copyOfRange(12, packed.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }.getOrNull()

    /** Returns false when the API key could not be stored; the configuration is then not saved. */
    fun saveConfig(config: JSONObject): Boolean {
        val id = config.optString("id", "default")
        if (!putSecret("key_$id", config.optString("apiKey"))) return false
        val safe = JSONObject(config.toString())
        safe.remove("apiKey")
        val ids = (prefs.getStringSet("provider_ids", emptySet()) ?: emptySet()).toMutableSet()
        ids.add(id)
        return prefs.edit().putString("config_$id", safe.toString()).putStringSet("provider_ids", ids).commit()
    }

    fun allConfigs(): List<JSONObject> {
        val ids = (prefs.getStringSet("provider_ids", emptySet()) ?: emptySet()).toList().sorted()
        return ids.mapNotNull { readConfig(it) }
    }

    /**
     * Saves all [configs] first and removes previous entries only after every save succeeded, so a
     * failed import never destroys the existing configuration. Returns false on any failure; in that
     * case the previous entries that were not overwritten remain in place.
     */
    fun replaceAllConfigs(configs: List<JSONObject>): Boolean {
        val oldIds = (prefs.getStringSet("provider_ids", emptySet()) ?: emptySet()).toSet()
        val newIds = configs.map { it.optString("id", "default") }.toSet()
        val allSaved = configs.all { saveConfig(JSONObject(it.toString())) }
        if (!allSaved) return false
        oldIds.filterNot { it in newIds }.forEach { id ->
            prefs.edit().remove("config_$id").commit()
            putSecret("key_$id", "")
        }
        return prefs.edit().putStringSet("provider_ids", newIds).commit()
    }

    fun readConfig(id: String): JSONObject? = runCatching {
        JSONObject(prefs.getString("config_$id", null) ?: return null).apply {
            put("apiKey", getSecret("key_$id") ?: "")
        }
    }.getOrNull()
}
