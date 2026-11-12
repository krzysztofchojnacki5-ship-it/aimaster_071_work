package pl.aimasterpro.app.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.BigInteger

/** Persistent, privacy-conscious execution journal. It stores operational metadata, never prompts, answers or secrets. */
object LearningJournalStore {
    private const val PREFS = "ai_learning_journal_v1"
    private const val KEY = "events"
    private const val MAX_EVENTS = 500
    private const val MAX_RAW_CHARS = 500_000

    data class Event(
        val timestamp: Long,
        val kind: String,
        val agentId: String,
        val providerId: String,
        val model: String,
        val status: String,
        val durationMs: Long,
        val attempts: Int,
        val category: String,
        val taskId: String = "",
        val inputTokens: Long = 0L,
        val outputTokens: Long = 0L
    )

    @Synchronized
    fun record(context: Context, event: Event) {
        if (event.timestamp < 0L || event.kind.isBlank() || event.agentId.isBlank() ||
            event.status.isBlank() || event.durationMs < 0L || event.attempts < 0 ||
            event.inputTokens < 0L || event.outputTokens < 0L) {
            android.util.Log.e("AIMasterLearning", "Nie zapisano zdarzenia: nieprawidłowa tożsamość lub liczniki.")
            return
        }
        val all = readStrict(context)?.toMutableList() ?: run {
            android.util.Log.e("AIMasterLearning", "Nie zapisano zdarzenia: dziennik operacyjny jest uszkodzony.")
            return
        }
        all.add(0, event.copy(
            kind = event.kind.take(40), agentId = event.agentId.take(80),
            providerId = event.providerId.take(80), model = event.model.take(120),
            status = event.status.take(40), category = event.category.take(80)
        ))
        val json = JSONArray()
        all.take(MAX_EVENTS).forEach { e ->
            json.put(JSONObject()
                .put("ts", e.timestamp).put("kind", e.kind).put("agent", e.agentId)
                .put("provider", e.providerId).put("model", e.model).put("status", e.status)
                .put("duration", e.durationMs).put("attempts", e.attempts)
                .put("category", e.category).put("taskId", e.taskId)
                .put("inputTokens", e.inputTokens).put("outputTokens", e.outputTokens))
        }
        val serialized = json.toString()
        if (serialized.length > MAX_RAW_CHARS) {
            android.util.Log.e("AIMasterLearning", "Nie zapisano zdarzenia: dziennik przekracza limit rozmiaru.")
            return
        }
        val saved = runCatching {
            if (SharedPreferencesWriteHealth.isUncertain(PREFS)) return@runCatching false
            SharedPreferencesWriteHealth.commit(
                PREFS, context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(KEY, serialized)
            )
        }.getOrDefault(false)
        if (!saved) android.util.Log.e("AIMasterLearning", "Nie udało się trwale zapisać dziennika operacyjnego.")
    }

    @Synchronized
    fun load(context: Context): List<Event> = readStrict(context) ?: emptyList()

    /** Corrupt rows are not dropped: writers must preserve the original journal for recovery. */
    private fun readStrict(context: Context): List<Event>? = runCatching {
        check(!SharedPreferencesWriteHealth.isUncertain(PREFS)) {
            "Learning journal is unavailable after an ambiguous durable write."
        }
        val raw = SharedPreferencesWriteHealth.withHealthyAccess(PREFS) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        } ?: return@runCatching emptyList()
        require(raw.length <= MAX_RAW_CHARS) { "Learning journal exceeds its supported raw size." }
        runCatching {
            val arr = JSONArray(raw)
            require(arr.length() <= MAX_EVENTS) { "Learning journal exceeds its supported size." }
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                fun exactLong(name: String, optional: Boolean = false): Long {
                    if (optional && !o.has(name)) return 0L
                    val value = o.opt(name)
                    require(value is Int || value is Long || value is Double || value is Float) {
                        "Learning journal numeric field is invalid: $name."
                    }
                    return if (value is Int || value is Long) (value as Number).toLong()
                    else BigDecimal.valueOf((value as Number).toDouble()).longValueExact()
                }
                fun requiredText(name: String): String {
                    val value = o.opt(name)
                    require(value is String) { "Learning journal text field is invalid: $name." }
                    return value
                }
                val timestamp = exactLong("ts")
                val duration = exactLong("duration")
                val attemptsLong = exactLong("attempts")
                val input = exactLong("inputTokens", optional = true)
                val output = exactLong("outputTokens", optional = true)
                require(timestamp >= 0L && duration >= 0L && attemptsLong in 0..Int.MAX_VALUE.toLong() && input >= 0L && output >= 0L) {
                    "Learning journal contains negative or out-of-range counters."
                }
                val kind = requiredText("kind")
                val agent = requiredText("agent")
                val provider = requiredText("provider")
                val model = requiredText("model")
                val status = requiredText("status")
                val category = requiredText("category")
                val taskId = if (o.has("taskId")) requiredText("taskId") else ""
                require(kind.isNotBlank() && agent.isNotBlank() && status.isNotBlank()) {
                    "Learning journal row is missing its event identity."
                }
                Event(timestamp, kind, agent, provider, model, status, duration, attemptsLong.toInt(), category,
                    taskId, input, output)
            }
        }.getOrNull()
    }.getOrNull()

    fun contextString(context: Context): String {
        val rows = load(context).filter { it.agentId.isNotBlank() && it.kind == "agent" }
            .groupBy { it.agentId }.map { (id, events) ->
                val completed = events.count { it.status.equals("COMPLETED", true) }
                val rate = if (events.isEmpty()) 0 else completed * 100 / events.size
                val avgAttempts = events.sumOf { it.attempts.toLong() }.toDouble() / events.size.coerceAtLeast(1)
                val durationTotal = events.asSequence().map { BigInteger.valueOf(it.durationMs) }
                    .fold(BigInteger.ZERO, BigInteger::add)
                val avgSeconds = durationTotal.divide(BigInteger.valueOf(events.size.coerceAtLeast(1).toLong()))
                    .divide(BigInteger.valueOf(1000L))
                "$id: $rate% ukończonych przebiegów (${events.size} zapisów), średnio ${avgSeconds}s, ${"%.1f".format(java.util.Locale.US, avgAttempts)} prób"
            }.sorted()
        if (rows.isEmpty()) return ""
        return "HISTORIA WYKONANIA AGENTÓW (metryki operacyjne, nie ocena merytorycznej jakości odpowiedzi):\n" + rows.joinToString("\n")
    }

    fun clear(context: Context): Boolean = runCatching {
        if (SharedPreferencesWriteHealth.isUncertain(PREFS)) return@runCatching false
        SharedPreferencesWriteHealth.commit(
            PREFS, context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY)
        )
    }.getOrDefault(false)
}
