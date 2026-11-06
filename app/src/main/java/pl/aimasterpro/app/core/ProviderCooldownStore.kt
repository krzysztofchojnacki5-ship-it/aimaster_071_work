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
    data class State(val key: String, val consecutive429: Int, val until: Long, val updatedAt: Long)

    fun key(provider: ProviderConfig): String {
        val base = provider.parentProviderId.ifBlank { provider.id }
        val host = runCatching { URL(provider.baseUrl).host.lowercase(Locale.ROOT) }.getOrDefault(provider.baseUrl.lowercase(Locale.ROOT))
        return "$host|${base.lowercase(Locale.ROOT)}|${provider.model.trim().lowercase(Locale.ROOT)}"
    }

    @Synchronized fun remainingMs(context: Context, provider: ProviderConfig, now: Long = System.currentTimeMillis()): Long =
        ((load(context).firstOrNull { it.key == key(provider) }?.until ?: 0L) - now).coerceAtLeast(0L)

    @Synchronized fun markRateLimited(context: Context, provider: ProviderConfig, now: Long = System.currentTimeMillis()): Long {
        val k = key(provider)
        val rows = load(context).toMutableList()
        val old = rows.firstOrNull { it.key == k }
        val count = ((old?.consecutive429 ?: 0) + 1).coerceAtMost(8)
        val delay = (60_000L * (1L shl (count - 1))).coerceAtMost(15 * 60_000L)
        rows.removeAll { it.key == k }
        rows.add(0, State(k, count, now + delay, now))
        save(context, rows)
        return delay
    }

    @Synchronized fun markSuccess(context: Context, provider: ProviderConfig, now: Long = System.currentTimeMillis()) {
        val k = key(provider)
        val rows = load(context).toMutableList()
        rows.removeAll { it.key == k }
        rows.add(0, State(k, 0, 0L, now))
        save(context, rows)
    }

    @Synchronized fun states(context: Context): List<State> = load(context)

    private fun load(context: Context): List<State> = runCatching {
        val a = JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]")
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val key = o.optString("key")
            if (key.isBlank()) null else State(key, o.optInt("count").coerceIn(0, 8), o.optLong("until"), o.optLong("updated"))
        }
    }.getOrDefault(emptyList())

    private fun save(context: Context, rows: List<State>) {
        val a = JSONArray()
        rows.distinctBy { it.key }.take(MAX_STATES).forEach { s ->
            a.put(JSONObject().put("key", s.key).put("count", s.consecutive429).put("until", s.until).put("updated", s.updatedAt))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, a.toString()).commit()
    }
}
