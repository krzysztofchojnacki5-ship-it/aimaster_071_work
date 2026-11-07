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

/**
 * Process-wide latch: SharedPreferences may update its in-memory map before a failed disk commit.
 * Once a commit fails, this process must not read or mutate that potentially divergent state.
 * A process restart reloads the durable preferences state and clears this in-memory latch.
 */
internal object SecureStoreHealth {
    private val sharedLock = Any()
    @Volatile private var uncertain = false
    fun isUncertain(): Boolean = uncertain
    fun markUncertain() { uncertain = true }
    internal fun resetForTests() { uncertain = false }
    internal fun lock(): Any = sharedLock
}

/** Stores provider secrets with Android Keystore AES-GCM and fails closed if an existing key disappears. */
internal fun shouldCreateFreshSecureStoreKey(aliasExists: Boolean, hasPersistedCiphertexts: Boolean): Boolean =
    !aliasExists && !hasPersistedCiphertexts

internal fun hasUnreadableExistingSecrets(existingSecretIds: Set<String>, readableSecretIds: Set<String>): Boolean =
    existingSecretIds.any { it !in readableSecretIds }

internal fun canReplaceUnreadableSecret(aliasExists: Boolean, keyIsSecretKey: Boolean): Boolean =
    aliasExists && keyIsSecretKey

class SecureStore(context: Context) {
    private val prefs = context.getSharedPreferences("secure_provider_store", Context.MODE_PRIVATE)
    private val alias = "ai_master_provider_key_v1"

    /** True after a failed preferences commit; only a process restart may clear this state. */
    fun isStorageUncertain(): Boolean = SecureStoreHealth.isUncertain()

    private fun commitOrMarkUncertain(editor: android.content.SharedPreferences.Editor): Boolean {
        return try {
            val committed = editor.commit()
            if (!committed) SecureStoreHealth.markUncertain()
            committed
        } catch (_: Exception) {
            SecureStoreHealth.markUncertain()
            false
        }
    }

    private fun hasStoredCiphertext(name: String): Boolean = runCatching {
        !prefs.getString(name, null).isNullOrBlank()
    }.getOrElse { prefs.contains(name) }

    /**
     * Returns the AES key, creating it only when no entry exists. An existing entry is never deleted
     * here: a transient Keystore failure must not destroy the key that protects saved provider keys.
     */
    @Synchronized private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias(alias)) {
            val persistedCiphertexts = prefs.all.keys.any { key ->
                key.startsWith("key_") && hasStoredCiphertext(key)
            }
            check(shouldCreateFreshSecureStoreKey(aliasExists = false, hasPersistedCiphertexts = persistedCiphertexts)) {
                "Brak klucza Android Keystore dla istniejących sekretów. Nie utworzono nowego klucza, aby nie nadpisać możliwości odzyskania danych."
            }
            return generateNewKey()
        }
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
    fun putSecret(name: String, value: String): Boolean = synchronized(SecureStoreHealth.lock()) {
        if (SecureStoreHealth.isUncertain()) return@synchronized false
        try {
            val editor = prefs.edit()
            if (value.isBlank()) {
                if (hasStoredCiphertext(name) && getSecret(name) == null) return@synchronized false
                editor.remove(name)
            } else {
                val encrypted = encryptSecret(value) ?: return@synchronized false
                editor.putString(name, encrypted)
            }
            commitOrMarkUncertain(editor)
        } catch (_: Exception) { false }
    }

    fun getSecret(name: String): String? = synchronized(SecureStoreHealth.lock()) {
        if (SecureStoreHealth.isUncertain()) return@synchronized null
        runCatching {
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
    }

    /** Stores the encrypted key and its metadata in one preferences transaction. */
    fun saveConfig(config: JSONObject): Boolean = synchronized(SecureStoreHealth.lock()) {
        if (SecureStoreHealth.isUncertain()) return@synchronized false
        try {
            validateImportedConfig(config)
            val id = config.getString("id")
            val incomingSecret = config.optString("apiKey")
            val existingSecretPresent = hasStoredCiphertext("key_$id")
            if (existingSecretPresent && incomingSecret.isBlank() && getSecret("key_$id") == null) {
                throw IllegalStateException("Istniejący klucz API jest niedostępny. Nie nadpisano go pustą wartością.")
            }
            val encrypted = encryptSecret(incomingSecret)
            val safe = JSONObject(config.toString()).apply { remove("apiKey"); remove("_secretUnavailable") }
            val ids = (prefs.getStringSet("provider_ids", emptySet()) ?: emptySet()).toMutableSet()
            ids.add(id)
            val editor = prefs.edit().putString("config_$id", safe.toString()).putStringSet("provider_ids", ids)
            if (encrypted == null) editor.remove("key_$id") else editor.putString("key_$id", encrypted)
            commitOrMarkUncertain(editor)
        } catch (_: Exception) { false }
    }

    fun allConfigs(): List<JSONObject> = synchronized(SecureStoreHealth.lock()) {
        if (SecureStoreHealth.isUncertain()) return@synchronized emptyList()
        val ids = (prefs.getStringSet("provider_ids", emptySet()) ?: emptySet()).toList().sorted()
        ids.mapNotNull { readConfig(it) }
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
    fun replaceAllConfigs(configs: List<JSONObject>): Boolean = synchronized(SecureStoreHealth.lock()) {
        if (SecureStoreHealth.isUncertain()) return@synchronized false
        try {
            if (configs.isEmpty()) return@synchronized false
            val copied = configs.map { JSONObject(it.toString()) }
            val newIds = copied.map { it.optString("id", "default") }
            require(newIds.all { it.isNotBlank() }) { "Id dostawcy nie może być pusty." }
            require(newIds.size == newIds.toSet().size) { "Kopia zawiera powtórzone identyfikatory dostawców." }
            copied.forEach(::validateImportedConfig)
            val existingSecretIds = prefs.all.keys.filter { key ->
                key.startsWith("key_") && hasStoredCiphertext(key)
            }.toSet()
            val readableSecretIds = existingSecretIds.filter { getSecret(it) != null }.toSet()
            require(!hasUnreadableExistingSecrets(existingSecretIds, readableSecretIds)) {
                "Import przerwany: co najmniej jeden istniejący klucz API jest niedostępny. Nie zmieniono żadnej konfiguracji."
            }
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
                val safe = JSONObject(config.toString()).apply { remove("apiKey"); remove("_secretUnavailable") }
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
            editor.putStringSet("provider_ids", newIds.toSet())
            commitOrMarkUncertain(editor)
        } catch (_: Exception) { false }
    }

    fun readConfig(id: String): JSONObject? = synchronized(SecureStoreHealth.lock()) {
        if (SecureStoreHealth.isUncertain()) return@synchronized null
        runCatching {
        JSONObject(prefs.getString("config_$id", null) ?: return null).apply {
            val secretKey = "key_$id"
            if (hasStoredCiphertext(secretKey)) {
                val decrypted = getSecret(secretKey)
                if (decrypted == null) {
                    put("apiKey", "")
                    put("_secretUnavailable", true)
                } else {
                    put("apiKey", decrypted)
                    put("_secretUnavailable", false)
                }
            } else {
                put("apiKey", "")
                put("_secretUnavailable", false)
            }
        }
        }.getOrNull()
    }

    /** True only when the current Android Keystore alias is present and contains an AES SecretKey. */
    fun canReplaceUnreadableSecrets(): Boolean = synchronized(SecureStoreHealth.lock()) {
        if (SecureStoreHealth.isUncertain()) return@synchronized false
        runCatching {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        canReplaceUnreadableSecret(
            aliasExists = store.containsAlias(alias),
            keyIsSecretKey = store.containsAlias(alias) && store.getKey(alias, null) is SecretKey
        )
        }.getOrDefault(false)
    }

    /** IDs whose encrypted API keys exist but cannot currently be decrypted. */
    fun unavailableSecretProviderIds(): List<String> = synchronized(SecureStoreHealth.lock()) {
        if (SecureStoreHealth.isUncertain()) return@synchronized emptyList()
        (prefs.getStringSet("provider_ids", emptySet()) ?: emptySet()).filter { id ->
            hasStoredCiphertext("key_$id") && getSecret("key_$id") == null
        }.sorted()
    }
}
