package pl.aimasterpro.app.core

import android.content.Context
import android.content.SharedPreferences

/**
 * Durable, validated persistence for agent -> provider assignments.
 * MASTER is mirrored to selected_provider for compatibility with legacy readers.
 */
object AgentProviderAssignmentStore {
    private const val PREFS = "chat_state"
    private const val MASTER = "MASTER"
    private const val SELECTED_KEY = "selected_provider"

    fun read(
        context: Context,
        roleDefaults: Map<String, String>
    ): Map<String, String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return roleDefaults.mapValues { (role, defaultId) ->
            prefs.getString(key(role), null)?.takeIf { it.isNotBlank() } ?: defaultId
        }
    }

    /** Returns false on a failed/throwing commit; callers must not mutate in-memory state then. */
    fun save(context: Context, role: String, providerId: String): Boolean {
        if (role.isBlank() || providerId.isBlank()) return false
        return runCatching {
            val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(key(role), providerId)
            if (role == MASTER) editor.putString(SELECTED_KEY, providerId)
            editor.commit()
        }.getOrDefault(false)
    }

    /**
     * Reconciles persisted assignments after a full provider import.
     * Valid existing assignments survive. Missing IDs are replaced deterministically.
     * MASTER and selected_provider are written in the same SharedPreferences commit.
     */
    fun reconcileAfterProviderImport(
        context: Context,
        roleDefaults: Map<String, String>,
        validProviderIds: Set<String>,
        preferredMasterId: String
    ): Map<String, String>? {
        if (validProviderIds.isEmpty() || validProviderIds.any { it.isBlank() }) return null
        return runCatching {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val masterFallback = preferredMasterId.takeIf { it in validProviderIds }
                ?: roleDefaults[MASTER]?.takeIf { it in validProviderIds }
                ?: validProviderIds.sorted().first()
            val resolved = roleDefaults.mapValues { (role, defaultId) ->
                val old = prefs.getString(key(role), null)
                when {
                    role == MASTER -> masterFallback
                    old != null && old in validProviderIds -> old
                    defaultId in validProviderIds -> defaultId
                    else -> masterFallback
                }
            }
            val editor = prefs.edit()
            resolved.forEach { (role, id) -> editor.putString(key(role), id) }
            editor.putString(SELECTED_KEY, resolved[MASTER] ?: masterFallback)
            if (!editor.commit()) return null
            resolved
        }.getOrNull()
    }

    private fun key(role: String): String = "agent_provider_$role"
}
