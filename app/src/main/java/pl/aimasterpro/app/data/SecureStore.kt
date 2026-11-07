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
        val plainBytes = value.toByteArray(Charsets.UTF_8)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val encrypted = cipher.doFinal(plainBytes)
            val packed = cipher.iv + encrypted
            try {
                Base64.encodeToString(packed, Base64.NO_WRAP)
            } finally {
                packed.fill(0)
                encrypted.fill(0)
            }
        } finally {
            plainBytes.fill(0)
        }
    }

    /** Returns false when the secret could not be stored; nothing is written in that case. */
    @Synchronized fun putSecret(name: String, value: String): Boolean {
        return try {
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
    }

    @Synchronized fun getSecret(name: String): String? = runCatching {
        val packed = Base64.decode(prefs.getString(name, null), Base64.NO_WRAP)
        try {
            require(packed.size >= 28) { "Zaszyfrowany klucz jest niekompletny." }
            val iv = packed.copyOfRange(0, 12)
            val ciphertext = packed.copyOfRange(12, packed.size)
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
                val plainBytes = cipher.doFinal(ciphertext)
                try {
                    String(plainBytes, Charsets.UTF_8)
                } finally {
                    plainBytes.fill(0)
                }
            } finally {
                iv.fill(0)
                ciphertext.fill(0)
            }
        } finally {
            packed.fill(0)
        }
    }.getOrNull()

    /** Stores the encrypted key and its metadata in one preferences transaction. */
    @Synchronized fun saveConfig(config: JSONObject): Boolean {
        return try {
            validateImportedConfig(config)
            val id = config.getString("id")
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
    }

    @Synchronized fun allConfigs(): List<JSONObject> {
        val ids = (prefs.getStringSet("provider_ids", emptySet()) ?: emptySet()).toList().sorted()
        return ids.mapNotNull { readConfig(it) }
    }

    private fun validateImportedConfig(config: JSONObject) {
        fun requiredString(name: String, max: Int, allowBlank: Boolean = true): String {
            require(config.has(name) && !config.isNull(name)) { "Brak pola konfiguracji: $name." }
            val value = config.opt(name)
            require(value is String && value.length <= max && (allowBlank || value.isNotBlank())) {
                "Nieprawidłowe pole konfiguracji: $name."
            }
            return value as String
        }
        val id = requiredString("id", 128, allowBlank = false)
        require(id.matches(Regex("[A-Za-z0-9._:-]+"))) { "Id dostawcy zawiera niedozwolone znaki." }
        requiredString("name", 160, allowBlank = false)
        val kind = requiredString("kind", 40, allowBlank = false)
        require(kind in setOf("OPENAI_COMPATIBLE", "GEMINI", "ANTHROPIC")) { "Nieobsługiwany typ dostawcy." }
        requiredString("baseUrl", 2048)
        requiredString("model", 512)
        if (config.has("apiKey") && !config.isNull("apiKey")) {
            val apiKey = config.opt("apiKey")
            require(apiKey is String && apiKey.length <= 8192) { "Nieprawidłowy klucz API w kopii." }
        }
        if (config.has("enabled")) require(config.opt("enabled") is Boolean) { "Nieprawidłowy status dostawcy." }
        if (config.has("parentProviderId") && !config.isNull("parentProviderId")) {
            val parent = config.opt("parentProviderId")
            require(parent is String && parent.length <= 128) { "Nieprawidłowy identyfikator profilu nadrzędnego." }
            require(parent.isEmpty() || parent.matches(Regex("[A-Za-z0-9._:-]+"))) { "Id profilu nadrzędnego zawiera niedozwolone znaki." }
            require(parent != id) { "Profil nie może być własnym profilem nadrzędnym." }
        }
    }

    /**
     * Pre-encrypts and validates every entry before changing preferences. The final replacement,
     * including provider IDs, metadata and encrypted API keys, is committed with one Editor commit.
     * A preparation failure therefore leaves the previous configuration untouched.
     */
    @Synchronized fun replaceAllConfigs(configs: List<JSONObject>): Boolean {
        return try {
            if (configs.isEmpty()) return false
            val copied = configs.map { JSONObject(it.toString()) }
            val newIds = copied.map { it.optString("id", "default") }
            require(newIds.all { it.isNotBlank() }) { "Id dostawcy nie może być pusty." }
            require(newIds.size == newIds.toSet().size) { "Kopia zawiera powtórzone identyfikatory dostawców." }
            copied.forEach(::validateImportedConfig)
            val idSet = newIds.toSet()
            val parentById = copied.associate { it.optString("id") to it.optString("parentProviderId", "") }
            parentById.forEach { (id, parent) ->
                require(parent.isEmpty() || parent in idSet) { "Profil wskazuje na nieistniejącego dostawcę nadrzędnego." }
                val visited = mutableSetOf<String>()
                var cursor = id
                while (cursor.isNotEmpty()) {
                    require(visited.add(cursor)) { "Wykryto cykl w relacjach profili dostawców." }
                    cursor = parentById[cursor].orEmpty()
                }
            }
            val prepared = copied.map { config ->
                val id = config.optString("id", "default")
                val encrypted = encryptSecret(config.optString("apiKey"))
                val safe = JSONObject(config.toString()).apply { remove("apiKey") }
                Triple(id, safe, encrypted)
            }

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
    }

    fun readConfig(id: String): JSONObject? = runCatching {
        JSONObject(prefs.getString("config_$id", null) ?: return null).apply {
            put("apiKey", getSecret("key_$id") ?: "")
        }
    }.getOrNull()
}
