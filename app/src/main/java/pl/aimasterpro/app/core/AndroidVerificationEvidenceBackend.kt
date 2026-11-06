package pl.aimasterpro.app.core

import android.content.Context

/** Android persistence adapter. Synchronous commit exposes durable-write failure to the caller. */
class AndroidVerificationEvidenceBackend(context: Context) : VerificationEvidenceBackend {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun read(): String? = preferences.getString(KEY, null)

    override fun write(value: String): Boolean = preferences.edit().putString(KEY, value).commit()

    private companion object {
        const val PREFS_NAME = "verification_evidence_v1"
        const val KEY = "ledger"
    }
}
