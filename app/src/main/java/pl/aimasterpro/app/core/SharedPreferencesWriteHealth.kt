package pl.aimasterpro.app.core

import android.content.SharedPreferences
import java.util.concurrent.ConcurrentHashMap

/**
 * SharedPreferences.commit() may update the process-local map before returning false.
 * Once that outcome is observed, reads and writes for that preference file are quarantined
 * until process restart; the durable on-disk value can no longer be inferred safely.
 */
internal object SharedPreferencesWriteHealth {
    private val uncertainFiles = ConcurrentHashMap.newKeySet<String>()

    fun isUncertain(preferencesName: String): Boolean = preferencesName in uncertainFiles

    fun commit(preferencesName: String, editor: SharedPreferences.Editor): Boolean {
        if (isUncertain(preferencesName)) return false
        val committed = runCatching { editor.commit() }.getOrDefault(false)
        if (!committed) uncertainFiles.add(preferencesName)
        return committed
    }

    internal fun resetForTests(preferencesName: String) {
        uncertainFiles.remove(preferencesName)
    }
}
