package pl.aimasterpro.app.core

import android.content.SharedPreferences
import java.util.concurrent.ConcurrentHashMap

/**
 * SharedPreferences.commit() may update the process-local map before returning false.
 * Reads and writes for one preference file share a process-wide critical section. Once an
 * ambiguous commit is observed, that file remains quarantined until process restart.
 */
internal object SharedPreferencesWriteHealth {
    private val uncertainFiles = ConcurrentHashMap.newKeySet<String>()
    private val fileLocks = ConcurrentHashMap<String, Any>()

    private fun lockFor(preferencesName: String): Any =
        fileLocks.computeIfAbsent(preferencesName) { Any() }

    fun isUncertain(preferencesName: String): Boolean = preferencesName in uncertainFiles

    /** Runs a preference read under the same lock used by commit, excluding ambiguous in-flight writes. */
    fun <T> withHealthyAccess(preferencesName: String, operation: () -> T): T =
        synchronized(lockFor(preferencesName)) {
            check(!isUncertain(preferencesName)) {
                "Preference storage '$preferencesName' is unavailable after an ambiguous durable write."
            }
            operation()
        }

    fun commit(preferencesName: String, editor: SharedPreferences.Editor): Boolean =
        synchronized(lockFor(preferencesName)) {
            if (isUncertain(preferencesName)) return@synchronized false
            val committed = runCatching { editor.commit() }.getOrDefault(false)
            if (!committed) uncertainFiles.add(preferencesName)
            committed
        }

    internal fun resetForTests(preferencesName: String) {
        synchronized(lockFor(preferencesName)) {
            uncertainFiles.remove(preferencesName)
        }
    }
}
