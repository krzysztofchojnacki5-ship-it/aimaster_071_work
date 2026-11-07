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

    /** Encrypts without changing preferences, allowing a complete import to be prepared before commit. */
    private fun encryptSecret(value: String): String? {
        if (value.isBlank()) return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    /** Returns false when the secret could not be stored; nothing is written in that case. */
    @Synchronized fun putSecret(name: String, value: String): Boolean = try {
        val editor = prefs.edit()
        if (value.isBlank()) {
            editor.remove(name)
        } else {
            val encrypted = encryptSecret(value) ?: return false
            editor.putString(name, encrypted)
        }
        editor.commit()
    } catch (_: Exception) {
        false
    }

    @Synchronized fun getSecret(name: String): String? = runCatching {
        val packed = Base64.decode(prefs.getString(name, null), Base64.NO_WRAP)
        require(packed.size > 12) { "Zaszyfrowany klucz jest niekompletny." }
        val iv = packed.copyOfRange(0, 12)
        val ciphertext = packed.copyOfRange(12, packed.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }.getOrNull()

    /** Stores the encrypted key and its metadata in one preferences transaction. */
    @Synchronized fun saveConfig(config: JSONObject): Boolean = try {
        val id = config.optString("id", "default")
        if (id.isBlank()) return false
        val encrypted = encryptSecret(config.optString("apiKey")) 
        val safe = JSONObject(config.toString()).apply { remove("apiKey") }
        val ids = (prefs.getStringSet("provider_ids", emptySet()) ?: emptySet()).toMutableSet()
        ids.add(id)
        val editor = prefs.edit().putString("config_$id", safe.toString()).putStringSet("provider_ids", ids)
        if (encrypted == null) editor.remove("key_$id") else editor.putString("key_$id", encrypted)
        editor.commit()
    } catch (_: Exception) {
        false
    }

    @Synchronized fun allConfigs(): List<JSONObject> {
        val ids = (prefs.getStringSet("provider_ids", emptySet()) ?: emptySet()).toList().sorted()
        return ids.mapNotNull { readConfig(it) }
    }

    /**
     * Pre-encrypts and validates every entry before changing preferences. The final replacement,
     * including provider IDs, metadata and encrypted API keys, is committed with one Editor commit.
     * A preparation failure therefore leaves the previous configuration untouched.
     */
    @Synchronized fun replaceAllConfigs(configs: List<JSONObject>): Boolean = try {
        if (configs.isEmpty()) return false
        val prepared = configs.map { source ->
            val config = JSONObject(source.toString())
            val id = config.optString("id", "default")
            require(id.isNotBlank()) { "Id dostawcy nie może być pusty." }
            val encrypted = encryptSecret(config.optString("apiKey"))
            val safe = JSONObject(config.toString()).apply { remove("apiKey") }
            Triple(id, safe, encrypted)
        }
        val newIds = prepared.map { it.first }
        require(newIds.size == newIds.toSet().size) { "Kopia zawiera powtórzone identyfikatory dostawców." }

        val oldIds = (prefs.getStringSet("provider_ids", emptySet()) ?: emptySet()).toSet()
        val editor = prefs.edit()
        oldIds.filterNot { it in newIds }.forEach { id ->
            editor.remove("config_$id").remove("key_$id")
        }
        prepared.forEach { (id, safe, encrypted) ->
            editor.putString("config_$id", safe.toString())
            if (encrypted == null) editor.remove("key_$id") else editor.putString("key_$id", encrypted)
        }
        editor.putStringSet("provider_ids", newIds.toSet()).commit()
    } catch (_: Exception) {
        false
    }

    fun readConfig(id: String): JSONObject? = runCatching {
        JSONObject(prefs.getString("config_$id", null) ?: return null).apply {
            put("apiKey", getSecret("key_$id") ?: "")
        }
    }.getOrNull()
}
