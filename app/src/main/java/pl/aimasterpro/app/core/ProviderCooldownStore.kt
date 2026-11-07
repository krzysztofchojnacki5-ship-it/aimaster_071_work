package pl.aimasterpro.app.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.util.Locale

/** Persists observed 429 cooldowns across process restarts; stores provider metadata, never API keys. */
object ProviderCooldownStore {
    private const val PREFS = "provider_cooldowns_v1"
    private const val KEY = "states"
    private const val MAX_STATES = 100
    private const val MAX_COOLDOWN_MS = 15 * 60_000L

    data class State(val key: String, val consecutive429: Int, val until: Long, val updatedAt: Long)

    fun key(provider: ProviderConfig): String {
        val base = provider.parentProviderId.ifBlank { provider.id }
        val host = runCatching { URL(provider.baseUrl).host.lowercase(Locale.ROOT) }.getOrDefault(provider.baseUrl.lowercase(Locale.ROOT))
        return "$host|${base.lowercase(Locale.ROOT)}|${provider.model.trim().lowercase(Locale.ROOT)}"
    }

    @Synchronized
    fun remainingMs(context: Context, provider: ProviderConfig, now: Long = System.currentTimeMillis()): Long {
        val rows = load(context) ?: return MAX_COOLDOWN_MS
        return ((rows.firstOrNull { it.key == key(provider) }?.until ?: 0L) - now).coerceAtLeast(0L)
    }

    @Synchronized
    fun markRateLimited(context: Context, provider: ProviderConfig, now: Long = System.currentTimeMillis()): Long {
        val k = key(provider)
        val rows = load(context)?.toMutableList() ?: return MAX_COOLDOWN_MS
        val old = rows.firstOrNull { it.key == k }
        val count = ((old?.consecutive429 ?: 0) + 1).coerceAtMost(8)
        val delay = (60_000L * (1L shl (count - 1))).coerceAtMost(MAX_COOLDOWN_MS)
        val until = Math.addExact(now, delay)
        rows.removeAll { it.key == k }
        rows.add(0, State(k, count, until, now))
        check(save(context, rows)) { "Nie udało się trwale zapisać cooldownu dostawcy." }
        return delay
    }

    @Synchronized
    fun markSuccess(context: Context, provider: ProviderConfig, now: Long = System.currentTimeMillis()) {
        val k = key(provider)
        val rows = load(context)?.toMutableList() ?: return
        rows.removeAll { it.key == k }
        rows.add(0, State(k, 0, 0L, now))
        check(save(context, rows)) { "Nie udało się trwale zresetować cooldownu dostawcy." }
    }

    @Synchronized
    fun states(context: Context): List<State> =
        load(context) ?: throw IllegalStateException("Rejestr cooldownów dostawców jest uszkodzony.")

    /**
     * Null means the journal is malformed. Callers must fail closed: do not clear or
     * overwrite it, and temporarily treat every provider as rate-limited.
     */
    private fun load(context: Context): List<State>? = runCatching {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return@runCatching emptyList()
        val array = JSONArray(raw)
        require(array.length() <= MAX_STATES) { "Too many cooldown rows." }
        val seen = mutableSetOf<String>()
        buildList {
            for (index in 0 until array.length()) {
                val row = array.opt(index) as? JSONObject ?: error("Cooldown row is not an object.")
                val key = row.opt("key") as? String ?: error("Cooldown key is missing.")
                require(key.isNotBlank() && seen.add(key)) { "Cooldown key is blank or duplicated." }
                val count = exactLong(row, "count")?.takeIf { it in 0L..8L }
                    ?: error("Cooldown count is invalid.")
                val until = exactLong(row, "until")?.takeIf { it >= 0L }
                    ?: error("Cooldown expiry is invalid.")
                val updated = exactLong(row, "updated")?.takeIf { it >= 0L }
                    ?: error("Cooldown timestamp is invalid.")
                require((count == 0L && until == 0L) || (count > 0L && until >= updated)) {
                    "Cooldown state is inconsistent."
                }
                add(State(key, count.toInt(), until, updated))
            }
        }
    }.getOrNull()

    private fun exactLong(row: JSONObject, field: String): Long? = when (val value = row.opt(field)) {
        is Int -> value.toLong()
        is Long -> value
        else -> null
    }

    private fun save(context: Context, rows: List<State>): Boolean {
        val array = JSONArray()
        rows.distinctBy { it.key }.take(MAX_STATES).forEach { state ->
            array.put(JSONObject().put("key", state.key).put("count", state.consecutive429)
                .put("until", state.until).put("updated", state.updatedAt))
        }
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, array.toString()).commit()
    }
}
