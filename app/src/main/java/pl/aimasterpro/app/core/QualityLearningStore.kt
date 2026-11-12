package pl.aimasterpro.app.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.math.BigDecimal

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
        if (readArrayStrict(context, FEEDBACK_KEY) == null) {
            android.util.Log.e("AIMasterQuality", "Nie zapisano oceny: dziennik ocen jest uszkodzony.")
            return
        }
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
        }).also { saved ->
            if (!saved) android.util.Log.e("AIMasterQuality", "Nie udało się trwale zapisać oceny.")
        }
    }

    @Synchronized fun proposals(context: Context): List<Proposal> = readArray(context, PROPOSALS_KEY).mapNotNull { o ->
        runCatching { Proposal(o.getString("id"), o.getString("text"), o.getString("status"), o.optLong("created"),
            o.optLong("updated"), o.optInt("evidence"), o.optString("confidence"), jsonStrings(o.optJSONArray("tasks")),
            jsonStrings(o.optJSONArray("agents")), jsonStrings(o.optJSONArray("models"))) }.getOrNull()
    }

    @Synchronized fun addProposal(context: Context, text: String, evidenceCount: Int, confidence: String,
                                  sourceTaskIds: List<String>, agents: List<String>, models: List<String>): Proposal {
        require(text.isNotBlank())
        check(readArrayStrict(context, PROPOSALS_KEY) != null) {
            "Rejestr propozycji jest uszkodzony; zapis został wstrzymany bez nadpisywania danych."
        }
        val now = System.currentTimeMillis()
        val p = Proposal(UUID.randomUUID().toString(), text.take(3000), "PROPOSAL", now, now,
            evidenceCount.coerceAtLeast(0), confidence.take(40), sourceTaskIds.filter(String::isNotBlank).distinct().take(100),
            agents.filter(String::isNotBlank).distinct().take(50), models.filter(String::isNotBlank).distinct().take(50))
        check(saveProposals(context, listOf(p) + proposals(context))) {
            "Nie udało się trwale zapisać propozycji uczenia."
        }
        return p
    }

    /** User-confirmed proposal decision; all related memory writes share one audited gate. */
    @Synchronized fun setProposalStatus(
        context: Context, id: String, status: String, approvalRef: String
    ): Boolean {
        val mutation = when (status) {
            "APPROVED" -> MemoryGovernance.Mutation.APPROVE_PROPOSAL
            "REJECTED" -> MemoryGovernance.Mutation.REJECT_PROPOSAL
            else -> return false
        }
        val request = MemoryGovernance.Request(
            actor = MemoryGovernance.Actor.USER,
            mutation = mutation,
            objectId = id,
            reason = if (status == "APPROVED") "Użytkownik zatwierdził propozycję uczenia."
                else "Użytkownik odrzucił propozycję uczenia.",
            approvalRef = approvalRef
        )
        return MemoryMutationGate.execute(context, request) { permit ->
            setProposalStatusRaw(context, id, status, permit)
        }
    }

    /** Only APPROVED proposals enter shared model memory. Rejected proposals remain auditable. */
    @Synchronized private fun setProposalStatusRaw(context: Context, id: String, status: String, permit: MemoryMutationPermit): Boolean {
        require(status in setOf("APPROVED", "REJECTED", "PROPOSAL"))
        if (readArrayStrict(context, PROPOSALS_KEY) == null) return false
        val all = proposals(context).toMutableList()
        val index = all.indexOfFirst { it.id == id }
        if (index < 0) return false
        val old = all[index]
        if (old.status == status) return true
        all[index] = old.copy(status = status, updatedAt = System.currentTimeMillis())
        if (!saveProposals(context, all)) return false
        val memoryEntry = "[UCZENIE ZATWIERDZONE ${old.id.take(8)}; dowody=${old.evidenceCount}; pewność=${old.confidence}] ${old.text}".take(1000)
        if (status == "APPROVED") {
            val sharedSaved = AiMemoryStore.addMemory(context, memoryEntry, permit)
            // Preserve agent scope: approved specialist lessons are not broadcast to unrelated agents.
            val sourceRef = "proposal:${old.id}"
            val agentSaves = old.affectedAgents.filter(String::isNotBlank).map { agentId ->
                AgentOperationalMemoryStore.addApproved(context, agentId, old.text, permit,
                    category = "wniosek jakościowy", sourceTaskIds = listOf(sourceRef), confidence = old.confidence) != null
            }
            if (!sharedSaved || agentSaves.any { !it }) {
                AiMemoryStore.removeMemory(context, memoryEntry, permit)
                AgentOperationalMemoryStore.removeBySource(context, sourceRef, permit)
                all[index] = old
                if (!saveProposals(context, all)) {
                    android.util.Log.e("AIMasterQuality", "Nie udało się wycofać statusu APPROVED po błędzie zapisu pamięci.")
                }
                return false
            }
        } else if (old.status == "APPROVED") {
            val sharedRemoved = AiMemoryStore.removeMemory(context, memoryEntry, permit)
            val agentRemoved = AgentOperationalMemoryStore.removeBySource(context, "proposal:${old.id}", permit)
            if (!sharedRemoved || !agentRemoved) {
                all[index] = old
                if (!saveProposals(context, all)) {
                    android.util.Log.e("AIMasterQuality", "Nie udało się przywrócić statusu APPROVED po błędzie usuwania pamięci.")
                }
                return false
            }
        }
        return true
    }

    /**
     * Reconciles both sides of proposal approval after process death. A corrupt proposal ledger
     * is not an empty ledger: in that case no memory is added or removed.
     */
    @Synchronized fun syncApprovedAgentMemories(context: Context): Int {
        if (readArrayStrict(context, PROPOSALS_KEY) == null) {
            android.util.Log.e("AIMasterQuality", "Pominięto uzgadnianie pamięci: rejestr propozycji jest niedostępny lub uszkodzony.")
            return 0
        }
        var synchronized = 0
        proposals(context).forEach { proposal ->
            val memoryEntry = "[UCZENIE ZATWIERDZONE ${proposal.id.take(8)}; dowody=${proposal.evidenceCount}; pewność=${proposal.confidence}] ${proposal.text}".take(1000)
            val sourceRef = "proposal:${proposal.id}"
            val approved = proposal.status == "APPROVED"
            val sharedPresent = AiMemoryStore.containsExactMemory(context, memoryEntry)
            val agentStates = proposal.affectedAgents.map { agentId ->
                AgentOperationalMemoryStore.hasApprovedSource(context, agentId, proposal.text, sourceRef)
            }
            val anyAgentSource = AgentOperationalMemoryStore.hasAnySource(context, sourceRef)
            val agentsWithSource = AgentOperationalMemoryStore.agentsWithSource(context, sourceRef)
            if (sharedPresent == null || agentStates.any { it == null } ||
                anyAgentSource == null || agentsWithSource == null) {
                android.util.Log.e("AIMasterQuality", "Pominięto uzgadnianie propozycji ${proposal.id}: co najmniej jeden magazyn pamięci jest nieczytelny.")
                return@forEach
            }
            val expectedAgents = proposal.affectedAgents.map { it.trim().lowercase(java.util.Locale.ROOT) }.filter(String::isNotEmpty).toSet()
            val hasUnexpectedAgentSource = agentsWithSource.any { it !in expectedAgents }
            val needsRepair = if (approved) {
                sharedPresent != true || agentStates.any { it != true } || hasUnexpectedAgentSource
            } else {
                sharedPresent == true || anyAgentSource == true
            }
            val unresolved = MemoryAuditLog.unresolvedOperations(context, proposal.id)
            if (unresolved == null) {
                android.util.Log.e("AIMasterQuality", "Pominięto uzgadnianie propozycji ${proposal.id}: dziennik audytu jest nieczytelny.")
                return@forEach
            }
            val pendingUser = unresolved.filter {
                it.actor == MemoryGovernance.Actor.USER.name &&
                    it.mutation in setOf(
                        MemoryGovernance.Mutation.APPROVE_PROPOSAL.name,
                        MemoryGovernance.Mutation.REJECT_PROPOSAL.name
                    )
            }
            val pendingRecovery = unresolved.filter { it.actor == MemoryGovernance.Actor.SYSTEM_RECOVERY.name }

            // If a prior recovery itself was interrupted, never replay it automatically.
            // It may only be closed when the durable stores already match the proposal ledger.
            if (pendingRecovery.isNotEmpty()) {
                if (!needsRepair) {
                    pendingRecovery.forEach { pending ->
                        if (!MemoryAuditLog.resolveUnresolved(context, pending,
                                "Odzyskiwanie zweryfikowane względem trwałego rejestru propozycji.")) {
                            android.util.Log.e("AIMasterQuality", "Nie udało się zamknąć audytu odzyskiwania dla propozycji ${proposal.id}.")
                        }
                    }
                    pendingUser.forEach { pending ->
                        MemoryAuditLog.resolveUnresolved(context, pending,
                            "Stan propozycji i magazynów został zweryfikowany po odzyskiwaniu.")
                    }
                } else {
                    android.util.Log.e("AIMasterQuality", "Propozycja ${proposal.id} wymaga ręcznej kontroli: poprzednie odzyskiwanie pozostało nierozstrzygnięte.")
                }
                return@forEach
            }
            if (!needsRepair && pendingUser.isEmpty()) return@forEach

            val request = MemoryGovernance.Request(
                actor = MemoryGovernance.Actor.SYSTEM_RECOVERY,
                mutation = if (approved) MemoryGovernance.Mutation.RECONCILE_APPROVED_PROPOSAL
                    else MemoryGovernance.Mutation.RECONCILE_REVOKED_PROPOSAL,
                objectId = proposal.id,
                reason = if (approved) "Uzgodnienie pamięci z propozycją trwale zatwierdzoną."
                    else "Usunięcie pamięci powiązanej z propozycją niezatwierdzoną lub cofniętą.",
                approvalRef = sourceRef
            )
            val recovered = MemoryMutationGate.execute(context, request) { permit ->
                if (approved) {
                    // Remove this proposal's provenance from every agent first. Rebuild only
                    // the explicitly approved scope, so stale/foreign agent copies cannot survive.
                    val staleAgentSourcesRemoved = AgentOperationalMemoryStore.removeBySource(context, sourceRef, permit)
                    val sharedSaved = AiMemoryStore.addMemory(context, memoryEntry, permit)
                    val agentSaves = proposal.affectedAgents.map { agentId ->
                        AgentOperationalMemoryStore.addApproved(context, agentId, proposal.text, permit,
                            category = "wniosek jakościowy", sourceTaskIds = listOf(sourceRef),
                            confidence = proposal.confidence) != null
                    }
                    staleAgentSourcesRemoved && sharedSaved && agentSaves.all { it } &&
                        AiMemoryStore.containsExactMemory(context, memoryEntry) == true &&
                        proposal.affectedAgents.all { agentId ->
                            AgentOperationalMemoryStore.hasApprovedSource(context, agentId, proposal.text, sourceRef) == true
                        }
                } else {
                    val sharedRemoved = AiMemoryStore.removeMemory(context, memoryEntry, permit)
                    val agentRemoved = AgentOperationalMemoryStore.removeBySource(context, sourceRef, permit)
                    sharedRemoved && agentRemoved &&
                        AiMemoryStore.containsExactMemory(context, memoryEntry) == false &&
                        AgentOperationalMemoryStore.hasAnySource(context, sourceRef) == false
                }
            }
            if (recovered) {
                if (approved && needsRepair) synchronized++
                pendingUser.forEach { pending ->
                    if (!MemoryAuditLog.resolveUnresolved(context, pending,
                            "Stan pamięci został uzgodniony i zweryfikowany względem trwałego rejestru propozycji.")) {
                        android.util.Log.e("AIMasterQuality", "Nie udało się zamknąć pierwotnej operacji ${pending.mutation} dla propozycji ${proposal.id}.")
                    }
                }
            } else {
                android.util.Log.e("AIMasterQuality", "Nie udało się uzgodnić pamięci dla propozycji ${proposal.id}; operacja pozostaje nierozstrzygnięta.")
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

    private fun readArray(c: Context, key: String): List<JSONObject> = readArrayStrict(c, key) ?: emptyList()

    /** A malformed row invalidates mutation of that journal; never silently drop unknown evidence. */
    private fun readArrayStrict(c: Context, key: String): List<JSONObject>? {
        if (SharedPreferencesWriteHealth.isUncertain(PREFS)) return null
        val rawResult = runCatching {
            SharedPreferencesWriteHealth.withHealthyAccess(PREFS) {
                c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, null)
            }
        }
        if (rawResult.isFailure) return null
        val raw = rawResult.getOrNull() ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            val maxRows = if (key == FEEDBACK_KEY) MAX_FEEDBACK else MAX_PROPOSALS
            require(array.length() <= maxRows) { "Quality journal exceeds its supported size." }
            val seenIds = hashSetOf<String>()
            val seenFeedbackTargets = hashSetOf<List<String>>()
            fun exactLong(value: Any?): Long {
                require(value is Int || value is Long || value is Double || value is Float) { "Quality journal counter has an invalid type." }
                return if (value is Int || value is Long) (value as Number).toLong()
                    else BigDecimal.valueOf((value as Number).toDouble()).longValueExact()
            }
            (0 until array.length()).map { index ->
                val row = array.getJSONObject(index)
                if (key == FEEDBACK_KEY) {
                    require(listOf("id", "targetId", "targetType", "comment", "agent", "provider", "model", "category")
                        .all { row.opt(it) is String })
                    val id = row.getString("id")
                    val targetId = row.getString("targetId")
                    val targetType = row.getString("targetType")
                    require(id.isNotBlank() && seenIds.add(id) && targetId.isNotBlank() && targetType.isNotBlank())
                    require(seenFeedbackTargets.add(listOf(targetType, targetId))) {
                        "Quality journal contains more than one current rating for the same target."
                    }
                    require(exactLong(row.opt("rating")) in setOf(-1L, 1L))
                    require(exactLong(row.opt("timestamp")) >= 0L)
                    if (row.has("history")) {
                        val history = row.optJSONArray("history") ?: error("Invalid feedback audit history.")
                        require(history.length() <= 50)
                        for (i in 0 until history.length()) {
                            val item = history.getJSONObject(i)
                            require(item.opt("comment") is String)
                            require(exactLong(item.opt("rating")) in setOf(-1L, 1L))
                            require(exactLong(item.opt("timestamp")) >= 0L)
                        }
                    }
                } else if (key == PROPOSALS_KEY) {
                    require(listOf("id", "text", "status", "confidence").all { row.opt(it) is String })
                    val id = row.getString("id")
                    require(id.isNotBlank() && seenIds.add(id) && row.getString("text").isNotBlank())
                    require(row.getString("status") in setOf("PROPOSAL", "APPROVED", "REJECTED"))
                    require(exactLong(row.opt("created")) >= 0L && exactLong(row.opt("updated")) >= 0L)
                    require(exactLong(row.opt("evidence")) in 0..Int.MAX_VALUE.toLong())
                    listOf("tasks", "agents", "models").forEach { field ->
                        val values = row.optJSONArray(field) ?: error("Invalid proposal provenance.")
                        require(values.length() <= 100)
                        val seenValues = hashSetOf<String>()
                        for (i in 0 until values.length()) {
                            val value = values.opt(i)
                            require(value is String && value.isNotBlank() && seenValues.add(value))
                        }
                    }
                } else error("Unknown quality journal key.")
                row
            }
        }.getOrNull()
    }

    private fun writeArray(c: Context, key: String, rows: List<JSONObject>): Boolean {
        if (SharedPreferencesWriteHealth.isUncertain(PREFS)) return false
        val editor = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(key, JSONArray(rows).toString())
        return SharedPreferencesWriteHealth.commit(PREFS, editor)
    }

    private fun saveProposals(c: Context, rows: List<Proposal>): Boolean = writeArray(c, PROPOSALS_KEY, rows.take(MAX_PROPOSALS).map {
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
