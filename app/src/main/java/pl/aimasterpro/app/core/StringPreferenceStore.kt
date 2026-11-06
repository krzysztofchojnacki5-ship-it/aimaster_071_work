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

    override fun read(key: String): String? = preferences().getString(key, null)

    /**
     * SharedPreferences.commit() can update the process-local map before reporting a disk failure.
     * On false, restore the prior value in memory and attempt a compensating durable commit.
     * The original operation still reports failure even if compensation succeeds.
     */
    override fun write(key: String, value: String): Boolean {
        val prefs = preferences()
        val previous = prefs.getString(key, null)
        val committed = runCatching { prefs.edit().putString(key, value).commit() }.getOrDefault(false)
        if (committed) return true
        runCatching {
            val editor = prefs.edit()
            if (previous == null) editor.remove(key) else editor.putString(key, previous)
            editor.commit()
        }
        return false
    }

    /** Compensates an ambiguous failed removal so a false result is never reported as success. */
    override fun remove(key: String): Boolean {
        val prefs = preferences()
        val previous = prefs.getString(key, null)
        val committed = runCatching { prefs.edit().remove(key).commit() }.getOrDefault(false)
        if (committed) return true
        if (previous != null) runCatching { prefs.edit().putString(key, previous).commit() }
        return false
    }
}
