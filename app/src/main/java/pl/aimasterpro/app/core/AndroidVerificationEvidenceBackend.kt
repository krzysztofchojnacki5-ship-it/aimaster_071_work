package pl.aimasterpro.app.core

import android.content.Context

/** Android persistence adapter. Synchronous commit exposes durable-write failure to the caller. */
class AndroidVerificationEvidenceBackend(context: Context) : VerificationEvidenceBackend {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun <T> withTransaction(operation: () -> T): T =
        SharedPreferencesWriteHealth.withHealthyAccess(PREFS_NAME, operation)

    override fun read(): String? = SharedPreferencesWriteHealth.withHealthyAccess(PREFS_NAME) {
        preferences.getString(KEY, null)
    }

    override fun write(value: String): Boolean = SharedPreferencesWriteHealth.commit(
        PREFS_NAME, preferences.edit().putString(KEY, value)
    )

    private companion object {
        const val PREFS_NAME = "verification_evidence_v1"
        const val KEY = "ledger"
    }
}
