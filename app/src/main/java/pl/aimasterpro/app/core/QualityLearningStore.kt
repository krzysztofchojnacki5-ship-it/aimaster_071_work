package pl.aimasterpro.app.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Local quality feedback and human-approved learning proposals. No network calls are made here. */
object QualityLearningStore {
    private const val PREFS = "ai_quality_learning_v1"
    private const val FEEDBACK_KEY = "feedback"
    private const val PROPOSALS_KEY = "proposals"
    private const val MAX_FEEDBACK = 2000
    private const val MAX_PROPOSALS = 500

    data class RatingChange(val rating: Int, val comment: String, val timestamp: Long)

    data class Feedback(
        val id: String,
        val targetId: String,
        val targetType: String,
        val rating: Int,
        val comment: String,
        val timestamp: Long,
        val agentId: String,
        val providerId: String,
        val model: String,
        val category: String,
        val history: List<RatingChange> = emptyList()
    )

    data class Proposal(
        val id: String,
        val text: String,
        val status: String,
        val createdAt: Long,
        val updatedAt: Long,
        val evidenceCount: Int,
        val confidence: String,
        val sourceTaskIds: List<String>,
        val affectedAgents: List<String>,
        val affectedModels: List<String>
    )

    @Synchronized fun feedback(context: Context): List<Feedback> = readArray(context, FEEDBACK_KEY).mapNotNull { o ->
        runCatching {
            Feedback(o.getString("id"), o.getString("targetId"), o.getString("targetType"), o.getInt("rating"),
                o.optString("comment"), o.optLong("timestamp"), o.optString("agent"), o.optString("provider"),
                o.optString("model"), o.optString("category"), historyRows(o.optJSONArray("history")))
        }.getOrNull()
    }

    /** One current rating per target. Re-rating updates the current value and preserves an audit trail. */
    @Synchronized fun rate(context: Context, targetId: String, targetType: String, rating: Int, comment: String,
                           agentId: String = "", providerId: String = "", model: String = "", category: String = "") {
        require(targetId.isNotBlank()) { "Brak identyfikatora ocenianego elementu" }
        require(rating == -1 || rating == 1) { "Ocena musi wynosić +1 albo -1" }
        val normalizedType = targetType.ifBlank { "UNKNOWN" }.take(30)
        val all = feedback(context).toMutableList()
        // A target ID is unique only inside its target type.
        val old = all.firstOrNull { it.targetId == targetId && it.targetType == normalizedType }
        val item = Feedback(old?.id ?: UUID.randomUUID().toString(), targetId, normalizedType, rating,
            comment.take(1000), System.currentTimeMillis(), agentId.ifBlank { old?.agentId.orEmpty() }.take(100),
            providerId.ifBlank { old?.providerId.orEmpty() }.take(100), model.ifBlank { old?.model.orEmpty() }.take(160),
            category.ifBlank { old?.category.orEmpty() }.take(100),
            (old?.history.orEmpty() + listOfNotNull(old?.let { RatingChange(it.rating, it.comment, it.timestamp) })).takeLast(50))
        all.removeAll { it.targetId == targetId && it.targetType == normalizedType }
        all.add(0, item)
        writeArray(context, FEEDBACK_KEY, all.take(MAX_FEEDBACK).map { f ->
            JSONObject().put("id", f.id).put("targetId", f.targetId).put("targetType", f.targetType)
                .put("rating", f.rating).put("comment", f.comment).put("timestamp", f.timestamp)
                .put("agent", f.agentId).put("provider", f.providerId).put("model", f.model)
                .put("category", f.category).put("history", JSONArray(f.history.map { h -> JSONObject().put("rating", h.rating).put("comment", h.comment).put("timestamp", h.timestamp) }))
        })
    }

    @Synchronized fun proposals(context: Context): List<Proposal> = readArray(context, PROPOSALS_KEY).mapNotNull { o ->
        runCatching { Proposal(o.getString("id"), o.getString("text"), o.getString("status"), o.optLong("created"),
            o.optLong("updated"), o.optInt("evidence"), o.optString("confidence"), jsonStrings(o.optJSONArray("tasks")),
            jsonStrings(o.optJSONArray("agents")), jsonStrings(o.optJSONArray("models"))) }.getOrNull()
    }

    @Synchronized fun addProposal(context: Context, text: String, evidenceCount: Int, confidence: String,
                                  sourceTaskIds: List<String>, agents: List<String>, models: List<String>): Proposal {
        require(text.isNotBlank())
        val now = System.currentTimeMillis()
        val p = Proposal(UUID.randomUUID().toString(), text.take(3000), "PROPOSAL", now, now,
            evidenceCount.coerceAtLeast(0), confidence.take(40), sourceTaskIds.distinct().take(100),
            agents.distinct().take(50), models.distinct().take(50))
        saveProposals(context, listOf(p) + proposals(context))
        return p
    }

    /** Only APPROVED proposals enter shared model memory. Rejected proposals remain auditable. */
    @Synchronized fun setProposalStatus(context: Context, id: String, status: String): Boolean {
        require(status in setOf("APPROVED", "REJECTED", "PROPOSAL"))
        val all = proposals(context).toMutableList()
        val index = all.indexOfFirst { it.id == id }
        if (index < 0) return false
        val old = all[index]
        if (old.status == status) return true
        all[index] = old.copy(status = status, updatedAt = System.currentTimeMillis())
        saveProposals(context, all)
        val memoryEntry = "[UCZENIE ZATWIERDZONE ${old.id.take(8)}; dowody=${old.evidenceCount}; pewność=${old.confidence}] ${old.text}".take(1000)
        if (status == "APPROVED") {
            AiMemoryStore.addMemory(context, memoryEntry)
            // Preserve agent scope: approved specialist lessons are not broadcast to unrelated agents.
            old.affectedAgents.forEach { agentId ->
                AgentOperationalMemoryStore.addApproved(context, agentId, old.text,
                    category = "wniosek jakościowy", sourceTaskIds = listOf("proposal:${old.id}"), confidence = old.confidence)
            }
        } else if (old.status == "APPROVED") {
            AiMemoryStore.removeMemory(context, memoryEntry)
            AgentOperationalMemoryStore.removeBySource(context, "proposal:${old.id}")
        }
        return true
    }

    /** Idempotently backfills agent-scoped memory from already-approved proposals after upgrades. */
    @Synchronized fun syncApprovedAgentMemories(context: Context): Int {
        var synchronized = 0
        proposals(context).filter { it.status == "APPROVED" }.forEach { proposal ->
            proposal.affectedAgents.forEach { agentId ->
                if (AgentOperationalMemoryStore.addApproved(context, agentId, proposal.text,
                    category = "wniosek jakościowy", sourceTaskIds = listOf("proposal:${proposal.id}"),
                    confidence = proposal.confidence) != null) synchronized++
            }
        }
        return synchronized
    }

    /** Returns a smoothed local quality score only after enough independent task ratings. */
    fun qualityScore(rows: List<Feedback>, agentId: String, category: String, minSamples: Int = 5): Double? {
        val evidence = rows.asSequence()
            .filter { it.targetType == "AGENT" && it.agentId.equals(agentId, true) }
            .map { AgentQualityRanker.Evidence(it.targetId, it.rating, it.category) }
            .toList()
        return AgentQualityRanker.score(evidence, category, minSamples)
    }

    data class Summary(val total: Int, val positive: Int, val negative: Int, val ratedAgents: Int,
                       val proposals: Int, val approved: Int, val technicalErrors: Int, val avgLatencyMs: Long)
    fun summary(context: Context): Summary {
        val f = feedback(context)
        val p = proposals(context)
        val health = ModelHealthTracker.load(context)
        val failures = health.filterNot { it.success }
        return Summary(f.size, f.count { it.rating > 0 }, f.count { it.rating < 0 },
            f.filter { it.targetType == "AGENT" }.map { it.agentId }.filter { it.isNotBlank() }.distinct().size,
            p.size, p.count { it.status == "APPROVED" }, failures.size,
            if (health.isEmpty()) 0L else health.sumOf { it.latencyMs.coerceAtLeast(0L) } / health.size)
    }

    /** Compact evidence pack to keep manually-triggered MASTER audits token-efficient. */
    fun analysisContext(context: Context): String {
        val f = feedback(context)
        val events = LearningJournalStore.load(context)
        val health = ModelHealthTracker.load(context)
        fun confidence(n: Int) = when { n >= 10 -> "wyższa (n=$n)"; n >= 5 -> "wstępna (n=$n)"; else -> "za mała próba (n=$n)" }
        val agentLines = f.filter { it.targetType == "AGENT" && it.agentId.isNotBlank() }.groupBy { it.agentId }.map { (agent, rows) ->
            "$agent: ${rows.count { it.rating > 0 }}/${rows.size} pozytywnych; ${confidence(rows.size)}"
        }.sorted()
        val modelLines = f.filter { it.model.isNotBlank() }.groupBy { "${it.targetType}: ${it.providerId}/${it.model}" }.map { (model, rows) ->
            "$model: ${rows.count { it.rating > 0 }}/${rows.size} pozytywnych; ${confidence(rows.size)}"
        }.sorted()
        val categoryLines = f.filter { it.category.isNotBlank() }.groupBy { it.category }.map { (category, rows) ->
            "$category: ${rows.count { it.rating > 0 }}/${rows.size} pozytywnych (${rows.size} ocen)"
        }.sorted()
        val operational = events.filter { it.kind == "agent" }.groupBy { it.agentId }.map { (agent, rows) ->
            "$agent: ${rows.count { it.status.equals("COMPLETED", true) }}/${rows.size} ukończonych; średnio ${rows.sumOf { it.durationMs.coerceAtLeast(0) } / rows.size.coerceAtLeast(1)} ms"
        }.sorted()
        val technicalLines = health.groupBy { "${it.providerId}/${it.model}" }.map { (model, rows) ->
            val failures = rows.filterNot { it.success }
            val categories = failures.groupingBy { it.category.ifBlank { "UNKNOWN" } }.eachCount().entries
                .sortedByDescending { it.value }.take(4).joinToString(", ") { "${it.key}:${it.value}" }
            "$model: awarie ${failures.size}/${rows.size}; ${categories.ifBlank { "brak awarii" }}"
        }.sorted()
        // Never send complete responses or prompts; comments are capped and sampled.
        val comments = f.asSequence().filter { it.comment.isNotBlank() }.take(20).map {
            "${it.targetType}/${it.agentId}/${it.model} ${if (it.rating > 0) "+1" else "-1"}: ${it.comment.replace('\n', ' ').take(180)}"
        }.toList()
        return buildString {
            appendLine("METRYKI OPERACYJNE (nie są oceną merytoryczną):")
            appendLine(operational.joinToString("\n").ifBlank { "Brak" })
            appendLine("\nOCENY UŻYTKOWNIKA — AGENT:")
            appendLine(agentLines.joinToString("\n").ifBlank { "Brak" })
            appendLine("\nOCENY UŻYTKOWNIKA — MODEL/PROVIDER:")
            appendLine(modelLines.joinToString("\n").ifBlank { "Brak" })
            appendLine("\nOCENY WEDŁUG KATEGORII:")
            appendLine(categoryLines.joinToString("\n").ifBlank { "Brak" })
            appendLine("\nAWARIE TECHNICZNE (osobno od jakości odpowiedzi):")
            appendLine(technicalLines.joinToString("\n").ifBlank { "Brak" })
            if (comments.isNotEmpty()) { appendLine("\nKRÓTKIE KOMENTARZE UŻYTKOWNIKA (maks. 20):"); appendLine(comments.joinToString("\n")) }
            appendLine("\nZASADA: nie wyciągaj mocnych wniosków poniżej 5 ocen; 5-9 = wstępne, 10+ = większa pewność. Nie utożsamiaj sukcesu HTTP z jakością treści. Błędy sieci/API klasyfikuj osobno. Wskaż brak danych zamiast zgadywać.")
        }.take(12000)
    }

    private fun readArray(c: Context, key: String): List<JSONObject> = runCatching {
        val a = JSONArray(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, "[]"))
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    }.getOrDefault(emptyList())
    private fun writeArray(c: Context, key: String, rows: List<JSONObject>) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(key, JSONArray(rows).toString()).apply()
    }
    private fun saveProposals(c: Context, rows: List<Proposal>) = writeArray(c, PROPOSALS_KEY, rows.take(MAX_PROPOSALS).map {
        JSONObject().put("id", it.id).put("text", it.text).put("status", it.status).put("created", it.createdAt)
            .put("updated", it.updatedAt).put("evidence", it.evidenceCount).put("confidence", it.confidence)
            .put("tasks", JSONArray(it.sourceTaskIds)).put("agents", JSONArray(it.affectedAgents)).put("models", JSONArray(it.affectedModels))
    })
    private fun historyRows(a: JSONArray?): List<RatingChange> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { i ->
        val o = a.optJSONObject(i) ?: return@mapNotNull null
        runCatching { RatingChange(o.optInt("rating"), o.optString("comment"), o.optLong("timestamp")) }.getOrNull()
    }
    private fun jsonStrings(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optString(it).takeIf(String::isNotBlank) }
}
