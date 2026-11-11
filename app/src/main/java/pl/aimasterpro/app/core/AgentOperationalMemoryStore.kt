package pl.aimasterpro.app.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.math.BigDecimal
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

    /** Null means the operational-memory ledger is unreadable. */
    @Synchronized
    internal fun hasApprovedSource(context: Context, agentId: String, text: String, sourceTaskId: String): Boolean? {
        val cleanAgent = agentId.trim()
        val cleanText = text.trim().replace(Regex("[\\t ]+"), " ").take(MAX_ENTRY_CHARS)
        return read(context)?.any { entry ->
            entry.agentId.equals(cleanAgent, true) && entry.text == cleanText && sourceTaskId in entry.sourceTaskIds
        }
    }

    /** Checks for any lesson still carrying a proposal provenance marker. */
    @Synchronized
    internal fun hasAnySource(context: Context, sourceTaskId: String): Boolean? =
        read(context)?.any { sourceTaskId in it.sourceTaskIds }

    @Synchronized internal fun addApproved(
        context: Context, agentId: String, text: String, permit: MemoryMutationPermit,
        category: String = "", sourceTaskIds: List<String> = emptyList(), confidence: String = "zatwierdzona"
    ): Entry? {
        if (!MemoryMutationGate.isValid(permit, setOf(
                MemoryGovernance.Mutation.ADD_AGENT_LESSON,
                MemoryGovernance.Mutation.APPROVE_PROPOSAL,
                MemoryGovernance.Mutation.RECONCILE_APPROVED_PROPOSAL
            ))) return null
        return addApprovedRaw(context, agentId, text, category, sourceTaskIds, confidence)
    }

    @Synchronized private fun addApprovedRaw(
        context: Context, agentId: String, text: String, category: String,
        sourceTaskIds: List<String>, confidence: String
    ): Entry? = runCatching {
        SharedPreferencesWriteHealth.withHealthyAccess(PREFS) {

        val agent = agentId.trim().take(100)
        val clean = text.trim().replace(Regex("[\\t ]+"), " ").take(MAX_ENTRY_CHARS)
        if (agent.isBlank() || clean.length < 8) return@withHealthyAccess null
        val entries = read(context)?.toMutableList() ?: return@withHealthyAccess null
        val duplicateIndex = entries.indexOfFirst { it.agentId.equals(agent, true) && it.text.equals(clean, true) }
        if (duplicateIndex >= 0) {
            val duplicate = entries[duplicateIndex]
            if (sourceTaskIds.all { it in duplicate.sourceTaskIds }) return@withHealthyAccess duplicate
            val merged = duplicate.copy(
                sourceTaskIds = (duplicate.sourceTaskIds + sourceTaskIds).filter(String::isNotBlank).distinct().take(30),
                confidence = if (duplicate.confidence == confidence) confidence.take(40) else "wielokrotnie zatwierdzona"
            )
            entries[duplicateIndex] = merged
            return@withHealthyAccess if (write(context, entries)) merged else null
        }
        val item = Entry(UUID.randomUUID().toString(), agent, clean, category.trim().take(80),
            System.currentTimeMillis(), sourceTaskIds.filter(String::isNotBlank).distinct().take(30), confidence.take(40))
        entries.add(0, item)
        return@withHealthyAccess if (write(context, entries.take(MAX_ENTRIES))) item else null
    
        }
    }.getOrNull()

    @Synchronized internal fun remove(context: Context, id: String, permit: MemoryMutationPermit): Boolean {
        if (!MemoryMutationGate.isValid(permit, setOf(MemoryGovernance.Mutation.REMOVE_AGENT_LESSON), id)) return false
        return removeRaw(context, id)
    }

    @Synchronized private fun removeRaw(context: Context, id: String): Boolean = runCatching {
        SharedPreferencesWriteHealth.withHealthyAccess(PREFS) {

        val entries = read(context) ?: return@withHealthyAccess false
        val remaining = entries.filterNot { it.id == id }
        if (remaining.size == entries.size) return@withHealthyAccess false
        return@withHealthyAccess write(context, remaining)
    
        }
    }.getOrDefault(false)

    @Synchronized internal fun removeBySource(context: Context, sourceTaskId: String, permit: MemoryMutationPermit): Boolean {
        if (!MemoryMutationGate.isValid(permit, setOf(
                MemoryGovernance.Mutation.REMOVE_AGENT_LESSON,
                MemoryGovernance.Mutation.APPROVE_PROPOSAL,
                MemoryGovernance.Mutation.REJECT_PROPOSAL,
                MemoryGovernance.Mutation.RECONCILE_APPROVED_PROPOSAL,
                MemoryGovernance.Mutation.RECONCILE_REVOKED_PROPOSAL
            ))) return false
        return removeBySourceRaw(context, sourceTaskId)
    }

    @Synchronized private fun removeBySourceRaw(context: Context, sourceTaskId: String): Boolean = runCatching {
        SharedPreferencesWriteHealth.withHealthyAccess(PREFS) {

        if (sourceTaskId.isBlank()) return@withHealthyAccess false
        val entries = read(context) ?: return@withHealthyAccess false
        val updated = entries.mapNotNull { entry ->
            if (sourceTaskId !in entry.sourceTaskIds) return@mapNotNull entry
            val remainingSources = entry.sourceTaskIds.filterNot { it == sourceTaskId }
            if (remainingSources.isEmpty()) null
            else entry.copy(sourceTaskIds = remainingSources)
        }
        return@withHealthyAccess if (updated == entries) true else write(context, updated)
    
        }
    }.getOrDefault(false)

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
        check(!SharedPreferencesWriteHealth.isUncertain(PREFS)) {
            "Operational memory is unavailable after an ambiguous durable write."
        }
        val raw = SharedPreferencesWriteHealth.withHealthyAccess(PREFS) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        } ?: "[]"
        val array = JSONArray(raw)
        require(array.length() <= MAX_ENTRIES) { "Operational memory exceeds its supported size." }
        val seenIds = hashSetOf<String>()
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            fun requiredText(name: String): String {
                val value = o.opt(name)
                require(value is String) { "Operational memory text field is invalid: $name." }
                return value
            }
            fun optionalText(name: String): String {
                if (!o.has(name)) return ""
                val value = o.opt(name)
                require(value is String) { "Operational memory text field is invalid: $name." }
                return value
            }
            val id = requiredText("id").trim()
            val agent = requiredText("agentId").trim()
            val text = requiredText("text").trim()
            require(id.isNotBlank() && seenIds.add(id) && agent.isNotBlank() && text.isNotBlank()) {
                "Operational memory entry is missing unique identity or content."
            }
            val createdValue = o.opt("createdAt")
            require(createdValue is Int || createdValue is Long || createdValue is Double || createdValue is Float) {
                "Operational memory timestamp is invalid."
            }
            val createdAt = if (createdValue is Int || createdValue is Long) (createdValue as Number).toLong()
                else BigDecimal.valueOf((createdValue as Number).toDouble()).longValueExact()
            require(createdAt >= 0L) { "Operational memory timestamp cannot be negative." }
            val sources = if (!o.has("sourceTaskIds")) emptyList() else {
                val sourceArray = o.optJSONArray("sourceTaskIds") ?: error("Operational memory provenance is invalid.")
                require(sourceArray.length() <= 30)
                (0 until sourceArray.length()).map { index ->
                    val value = sourceArray.opt(index)
                    require(value is String && value.isNotBlank()) { "Operational memory provenance contains an invalid ID." }
                    value
                }.also { require(it.distinct().size == it.size) }
            }
            Entry(id, agent, text.take(MAX_ENTRY_CHARS), optionalText("category").take(80),
                createdAt, sources, optionalText("confidence").take(40))
        }
    }.getOrNull()

    private fun write(context: Context, entries: List<Entry>): Boolean = runCatching {
        if (SharedPreferencesWriteHealth.isUncertain(PREFS)) return@runCatching false
        val array = JSONArray()
        entries.take(MAX_ENTRIES).forEach { e ->
            array.put(JSONObject().put("id", e.id).put("agentId", e.agentId).put("text", e.text)
                .put("category", e.category).put("createdAt", e.createdAt)
                .put("sourceTaskIds", JSONArray(e.sourceTaskIds)).put("confidence", e.confidence))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, array.toString()).let { SharedPreferencesWriteHealth.commit(PREFS, it) }
    }.getOrDefault(false)

    private fun strings(array: JSONArray?): List<String> = if (array == null) emptyList() else
        (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
}
