package pl.aimasterpro.app.core

import android.content.Context

/**
 * Durable access to the chat pin set stored in chat_state.
 * Reads and read-modify-write operations share the same file lock and uncertainty latch
 * as the canonical chat history document.
 */
object ChatPinsStore {
    private const val PREFERENCES = "chat_state"
    private const val KEY = "pinned_chats"

    fun read(context: Context): Set<String>? = runCatching {
        SharedPreferencesWriteHealth.withHealthyAccess(PREFERENCES) {
            val values = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getStringSet(KEY, emptySet()) ?: emptySet()
            values.toSet()
        }
    }.getOrNull()

    fun toggle(context: Context, chatId: String): Boolean {
        if (chatId.isBlank()) return false
        return runCatching {
            SharedPreferencesWriteHealth.withHealthyAccess(PREFERENCES) {
                val prefs = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                val next = (prefs.getStringSet(KEY, emptySet()) ?: emptySet()).toMutableSet()
                if (!next.add(chatId)) next.remove(chatId)
                SharedPreferencesWriteHealth.commit(PREFERENCES, prefs.edit().putStringSet(KEY, next))
            }
        }.getOrDefault(false)
    }

    fun remove(context: Context, chatId: String): Boolean {
        if (chatId.isBlank()) return true
        return runCatching {
            SharedPreferencesWriteHealth.withHealthyAccess(PREFERENCES) {
                val prefs = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                val current = (prefs.getStringSet(KEY, emptySet()) ?: emptySet()).toMutableSet()
                if (!current.remove(chatId)) true
                else SharedPreferencesWriteHealth.commit(PREFERENCES, prefs.edit().putStringSet(KEY, current))
            }
        }.getOrDefault(false)
    }
}
