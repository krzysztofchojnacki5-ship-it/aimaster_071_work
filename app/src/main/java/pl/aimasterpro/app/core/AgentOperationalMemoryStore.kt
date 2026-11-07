package pl.aimasterpro.app.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

/** Agent-scoped, locally persisted lessons. Only explicitly approved learning is stored here. */
object AgentOperationalMemoryStore {
    private const val PREFS = "ai_agent_operational_memory_v1"
    private const val KEY = "entries"
    private const val MAX_ENTRIES = 300
    private const val MAX_ENTRY_CHARS = 1200
    private val termRegex = Regex("[\\p{L}\\p{N}]{3,}")
    private val stopWords = setOf("oraz", "który", "która", "które", "jest", "być", "dla", "the", "and", "for", "with", "that", "this")

    data class Entry(
        val id: String,
        val agentId: String,
        val text: String,
        val category: String,
        val createdAt: Long,
        val sourceTaskIds: List<String>,
        val confidence: String
    )

    @Synchronized fun all(context: Context): List<Entry> = read(context) ?: emptyList()

    @Synchronized fun addApproved(
        context: Context, agentId: String, text: String, category: String = "",
        sourceTaskIds: List<String> = emptyList(), confidence: String = "zatwierdzona"
    ): Entry? {
        val agent = agentId.trim().take(100)
        val clean = text.trim().replace(Regex("[\\t ]+"), " ").take(MAX_ENTRY_CHARS)
        if (agent.isBlank() || clean.length < 8) return null
        val entries = read(context)?.toMutableList() ?: return null
        val duplicateIndex = entries.indexOfFirst { it.agentId.equals(agent, true) && it.text.equals(clean, true) }
        if (duplicateIndex >= 0) {
            val duplicate = entries[duplicateIndex]
            if (sourceTaskIds.all { it in duplicate.sourceTaskIds }) return duplicate
            val merged = duplicate.copy(
                sourceTaskIds = (duplicate.sourceTaskIds + sourceTaskIds).filter(String::isNotBlank).distinct().take(30),
                confidence = if (duplicate.confidence == confidence) confidence.take(40) else "wielokrotnie zatwierdzona"
            )
            entries[duplicateIndex] = merged
            return if (write(context, entries)) merged else null
        }
        val item = Entry(UUID.randomUUID().toString(), agent, clean, category.trim().take(80),
            System.currentTimeMillis(), sourceTaskIds.filter(String::isNotBlank).distinct().take(30), confidence.take(40))
        entries.add(0, item)
        return if (write(context, entries.take(MAX_ENTRIES))) item else null
    }

    @Synchronized fun remove(context: Context, id: String): Boolean {
        val entries = read(context) ?: return false
        val remaining = entries.filterNot { it.id == id }
        if (remaining.size == entries.size) return false
        return write(context, remaining)
    }

    @Synchronized fun removeBySource(context: Context, sourceTaskId: String): Boolean {
        if (sourceTaskId.isBlank()) return false
        val entries = read(context) ?: return false
        val updated = entries.mapNotNull { entry ->
            if (sourceTaskId !in entry.sourceTaskIds) return@mapNotNull entry
            val remainingSources = entry.sourceTaskIds.filterNot { it == sourceTaskId }
            if (remainingSources.isEmpty()) null
            else entry.copy(sourceTaskIds = remainingSources)
        }
        return if (updated == entries) true else write(context, updated)
    }

    /** Bounded relevance retrieval for one agent; never mixes another agent's private lessons. */
    fun contextString(context: Context, agentId: String, query: String, maxChars: Int = 900, maxItems: Int = 4): String {
        if (agentId.isBlank() || maxChars <= 0 || maxItems <= 0) return ""
        val candidates = all(context).filter { it.agentId.equals(agentId, true) }
        if (candidates.isEmpty()) return ""
        val queryTerms = terms(query)
        val ranked = candidates.mapIndexed { index, entry ->
            val overlap = terms(entry.text).count { it in queryTerms }
            Triple(index, entry, overlap)
        }.sortedWith(compareByDescending<Triple<Int, Entry, Int>> { it.third }.thenByDescending { it.second.createdAt }.thenBy { it.first })
        val selected = if (queryTerms.isEmpty()) ranked.take(maxItems) else ranked.filter { it.third > 0 }.take(maxItems)
        if (selected.isEmpty()) return ""
        val out = StringBuilder("PAMIĘĆ OPERACYJNA AGENTA (lokalne, zatwierdzone wnioski; traktuj jako wskazówki, weryfikuj z aktualnymi danymi):")
        selected.forEach { row ->
            val line = "\n• [${row.second.category.ifBlank { "ogólne" }}] ${row.second.text}"
            if (out.length + line.length <= maxChars) out.append(line)
        }
        return if (out.length == "PAMIĘĆ OPERACYJNA AGENTA (lokalne, zatwierdzone wnioski; traktuj jako wskazówki, weryfikuj z aktualnymi danymi):".length) "" else out.toString()
    }

    private fun terms(value: String): Set<String> = termRegex.findAll(value.lowercase(Locale.ROOT)).map { it.value }.filterNot { it in stopWords }.toSet()

    /** Null means malformed persisted data; mutators must not treat it as an empty library. */
    private fun read(context: Context): List<Entry>? = runCatching {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: "[]"
        val array = JSONArray(raw)
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            val id = o.optString("id").trim()
            val agent = o.optString("agentId").trim()
            val text = o.optString("text").trim()
            require(id.isNotBlank() && agent.isNotBlank() && text.isNotBlank()) {
                "Operational memory entry is missing its identity or content."
            }
            require(o.opt("createdAt") is Number) { "Operational memory timestamp is invalid." }
            Entry(
                id, agent, text.take(MAX_ENTRY_CHARS), o.optString("category").take(80),
                o.optLong("createdAt"), strings(o.optJSONArray("sourceTaskIds")),
                o.optString("confidence").take(40)
            )
        }
    }.getOrNull()

    private fun write(context: Context, entries: List<Entry>): Boolean = runCatching {
        val array = JSONArray()
        entries.take(MAX_ENTRIES).forEach { e ->
            array.put(JSONObject().put("id", e.id).put("agentId", e.agentId).put("text", e.text)
                .put("category", e.category).put("createdAt", e.createdAt)
                .put("sourceTaskIds", JSONArray(e.sourceTaskIds)).put("confidence", e.confidence))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, array.toString()).commit()
    }.getOrDefault(false)

    private fun strings(array: JSONArray?): List<String> = if (array == null) emptyList() else
        (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
}
