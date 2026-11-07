package pl.aimasterpro.app.core

import android.content.Context

/** Minimal durable string preference contract; injectable to exercise storage failures without Android. */
interface StringPreferenceStore {
    fun read(key: String): String?
    fun write(key: String, value: String): Boolean
    fun remove(key: String): Boolean
}

internal class AndroidStringPreferenceStore(
    private val context: Context,
    private val preferencesName: String
) : StringPreferenceStore {
    private fun preferences() = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    override fun read(key: String): String? {
        check(!SharedPreferencesWriteHealth.isUncertain(preferencesName)) {
            "Preference storage '$preferencesName' is unavailable after an ambiguous durable write."
        }
        return preferences().getString(key, null)
    }

    /**
     * A false/throwing commit is ambiguous: SharedPreferences may already expose the new
     * process-local value even though durable persistence failed. Do not attempt a compensating
     * write; quarantine the entire preference file until process restart.
     */
    override fun write(key: String, value: String): Boolean {
        if (SharedPreferencesWriteHealth.isUncertain(preferencesName)) return false
        val editor = preferences().edit().putString(key, value)
        return SharedPreferencesWriteHealth.commit(preferencesName, editor)
    }

    override fun remove(key: String): Boolean {
        if (SharedPreferencesWriteHealth.isUncertain(preferencesName)) return false
        val editor = preferences().edit().remove(key)
        return SharedPreferencesWriteHealth.commit(preferencesName, editor)
    }
}
