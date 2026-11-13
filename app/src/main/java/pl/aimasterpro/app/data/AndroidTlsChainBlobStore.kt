package pl.aimasterpro.app.data

import android.content.Context
import android.util.Base64
import pl.aimasterpro.app.core.CompanionTlsBlobStore

/**
 * SharedPreferences-backed blob for the public TLS chain record. Uses commit() so the write result
 * is known before the caller treats the chain as stored. NOT RUN: requires an Android device.
 */
class AndroidTlsChainBlobStore(
    context: Context,
    prefsName: String = PREFS_NAME,
    private val key: String = KEY
) : CompanionTlsBlobStore {
    private val prefs = context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    override fun read(): ByteArray? {
        val encoded = prefs.getString(key, null) ?: return null
        return Base64.decode(encoded, Base64.NO_WRAP)
    }

    override fun clear(): Boolean = prefs.edit().remove(key).commit()

    override fun write(bytes: ByteArray): Boolean =
        prefs.edit().putString(key, Base64.encodeToString(bytes, Base64.NO_WRAP)).commit()

    private companion object {
        const val PREFS_NAME = "companion_tls_chain_v1"
        const val KEY = "chain_record_v1"
        // Separate blob for the pairing PSK record (see AndroidPskBlobFactory); never shared with the TLS chain.
    }
}
