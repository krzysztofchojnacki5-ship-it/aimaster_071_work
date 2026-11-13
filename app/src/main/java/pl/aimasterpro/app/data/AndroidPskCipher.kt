package pl.aimasterpro.app.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.content.Context
import pl.aimasterpro.app.core.CompanionPskRepository
import pl.aimasterpro.app.core.CompanionTlsBlobStore
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-GCM over an Android Keystore key that never leaves Keystore. NOT RUN: requires a device.
 * The key is created only when absent; it is never deleted or replaced here, so a missing or
 * replaced key makes stored PSK records fail decryption (fail-closed) instead of being recreated.
 */
class AndroidPskCipher : CompanionPskRepository.CompanionPskCipher {
    private fun key(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        if (!store.containsAlias(ALIAS)) {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
            generator.init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
            return generator.generateKey()
        }
        return store.getKey(ALIAS, null) as? SecretKey
            ?: throw IllegalStateException("Klucz PSK ma nieprawidłowy typ.")
    }

    override fun encrypt(plain: ByteArray, aad: ByteArray): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(aad)
        return Pair(cipher.iv, cipher.doFinal(plain))
    }

    override fun decrypt(iv: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext)
    }

    companion object {
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val ALIAS = "ai_master_companion_psk_aes_v1"

        /** Dedicated preferences blob so the PSK never shares storage with the TLS chain record. */
        fun blobStore(context: Context): CompanionTlsBlobStore =
            AndroidTlsChainBlobStore(context, prefsName = "companion_psk_v1", key = "psk_record_v1")
    }
}
