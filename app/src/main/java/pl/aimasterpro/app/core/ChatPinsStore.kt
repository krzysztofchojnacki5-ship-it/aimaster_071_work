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
    private const val MAX_PINS = 100
    private const val MAX_CHAT_ID_CHARS = 200

    private fun readValidated(context: Context): Set<String>? {
        val values = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getStringSet(KEY, emptySet()) ?: emptySet()
        if (values.size > MAX_PINS || values.any { it.isBlank() || it.length > MAX_CHAT_ID_CHARS }) return null
        return values.toSet()
    }

    fun read(context: Context): Set<String>? = runCatching {
        SharedPreferencesWriteHealth.withHealthyAccess(PREFERENCES) { readValidated(context) }
    }.getOrNull()

    fun toggle(context: Context, chatId: String): Boolean {
        if (chatId.isBlank() || chatId.length > MAX_CHAT_ID_CHARS) return false
        return runCatching {
            SharedPreferencesWriteHealth.withHealthyAccess(PREFERENCES) {
                val prefs = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                val current = readValidated(context) ?: return@withHealthyAccess false
                val next = current.toMutableSet()
                if (!next.add(chatId)) next.remove(chatId)
                else if (next.size > MAX_PINS) return@withHealthyAccess false
                SharedPreferencesWriteHealth.commit(PREFERENCES, prefs.edit().putStringSet(KEY, next))
            }
        }.getOrDefault(false)
    }

    fun remove(context: Context, chatId: String): Boolean {
        if (chatId.isBlank()) return true
        if (chatId.length > MAX_CHAT_ID_CHARS) return false
        return runCatching {
            SharedPreferencesWriteHealth.withHealthyAccess(PREFERENCES) {
                val prefs = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                val current = (readValidated(context) ?: return@withHealthyAccess false).toMutableSet()
                if (!current.remove(chatId)) true
                else SharedPreferencesWriteHealth.commit(PREFERENCES, prefs.edit().putStringSet(KEY, current))
            }
        }.getOrDefault(false)
    }
}
