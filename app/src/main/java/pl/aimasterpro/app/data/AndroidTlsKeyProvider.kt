package pl.aimasterpro.app.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import pl.aimasterpro.app.core.CompanionTlsIssuanceFlow
import java.security.KeyPairGenerator
import java.util.Collections
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * Android Keystore backing for [CompanionTlsIssuanceFlow.KeyProvider].
 * Private keys are generated inside Keystore and never exported. NOT RUN: requires an Android
 * device or emulator; JVM tests use a fake provider instead.
 */
class AndroidTlsKeyProvider : CompanionTlsIssuanceFlow.KeyProvider {
    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

    override fun aliasExists(alias: String): Boolean = keyStore().containsAlias(alias)

    override fun generateEcP256(alias: String): PublicKey {
        check(!aliasExists(alias)) { "Alias klucza TLS już istnieje; nie nadpisujemy." }
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEY_STORE)
        generator.initialize(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build()
        )
        return generator.generateKeyPair().public
    }

    override fun sign(alias: String, data: ByteArray): ByteArray {
        val key = keyStore().getKey(alias, null) as? PrivateKey
            ?: throw IllegalStateException("Brak klucza TLS o podanym aliasie.")
        return Signature.getInstance("SHA256withECDSA").run {
            initSign(key)
            update(data)
            sign()
        }
    }

    override fun delete(alias: String) {
        keyStore().deleteEntry(alias)
    }

    /** True when any companion TLS key alias exists (checks the prefix only, never the key material). */
    fun hasAnyTlsKey(): Boolean =
        Collections.list(keyStore().aliases()).any { it.startsWith(TLS_ALIAS_PREFIX) }

    /** Explicit recovery only: deletes every companion TLS key alias. */
    fun deleteAllTlsKeys() {
        val store = keyStore()
        Collections.list(store.aliases()).filter { it.startsWith(TLS_ALIAS_PREFIX) }.forEach { store.deleteEntry(it) }
    }

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val TLS_ALIAS_PREFIX = "ai_master_companion_tls_v"
    }
}
