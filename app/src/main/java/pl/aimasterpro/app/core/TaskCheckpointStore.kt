package pl.aimasterpro.app.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Persistent task-state journal for crash diagnosis/recovery. It never stores prompts, answers or keys. */
object TaskCheckpointStore {
    private const val PREFS = "task_checkpoints_v1"
    private const val KEY = "events"
    private const val MAX_EVENTS = 800
    private val TERMINAL_STATUSES = setOf("COMPLETED", "FAILED", "BLOCKED", "TIMED_OUT", "CANCELLED", "INTERRUPTED")
    private val processId = java.util.UUID.randomUUID().toString()
    /** An ambiguous commit can leave the process-local preference map ahead of durable storage. */
    fun isStorageUncertain(): Boolean = SharedPreferencesWriteHealth.isUncertain(PREFS)

    internal fun resetStorageHealthForTests() {
        SharedPreferencesWriteHealth.resetForTests(PREFS)
    }

    data class Event(
        val taskId: String,
        val agentId: String,
        val status: String,
        val detail: String,
        val timestamp: Long,
        val providerId: String,
        val model: String,
        val attempts: Int,
        val processId: String = "",
        val estimatedInputTokens: Long = 0L,
        val estimatedOutputTokens: Long = 0L,
        val idempotencyKey: String = "",
        val baseUrl: String = ""
    )

    /**
     * Returns false when SharedPreferences could not confirm a durable write.
     * Events are prepended, so journal order—not wall-clock precision—defines the latest state.
     */
    @Synchronized
    fun record(context: Context, event: Event): Boolean {
        if (SharedPreferencesWriteHealth.isUncertain(PREFS)) return false
        if (event.taskId.isBlank() || event.agentId.isBlank() || event.status.isBlank() ||
            event.timestamp < 0L || event.attempts < 0 ||
            event.estimatedInputTokens < 0L || event.estimatedOutputTokens < 0L) return false
        val rows = readEvents(context)?.toMutableList() ?: return false
        // A late worker callback must not overwrite a terminal result already recorded by
        // the coordinator (for example TIMED_OUT followed by the worker's CANCELLED callback).
        val current = rows.firstOrNull { it.taskId == event.taskId }
        // REQUESTING means the provider may already have processed and billed the POST.
        // Do not let UI/worker finalization hide a failed USAGE_RECORDED/USAGE_PENDING write
        // behind a terminal state. Startup recovery is the only path allowed to close it.
        if (current?.status == "REQUESTING" &&
            event.status in setOf("COMPLETED", "FAILED", "BLOCKED")) {
            val isRecoveryTransition = current.processId != processId && event.processId == processId
            if (!isRecoveryTransition) return true
        }
        if (current?.status?.let { it in TERMINAL_STATUSES } == true) {
            // A timeout/cancellation can race with a worker that has already committed its report.
            // Permit only archive-backed reconciliation for that exact task; never trust a late
            // callback alone to rewrite a terminal checkpoint.
            val archivedResultExists = runCatching {
                TaskResultArchive.get(context, event.taskId) != null
            }.getOrDefault(false)
            val mergedUsageMetadata = event.copy(
                providerId = event.providerId.ifBlank { current.providerId },
                model = event.model.ifBlank { current.model },
                estimatedInputTokens = event.estimatedInputTokens.takeIf { it > 0L }
                    ?: current.estimatedInputTokens,
                estimatedOutputTokens = event.estimatedOutputTokens.takeIf { it > 0L }
                    ?: current.estimatedOutputTokens,
                idempotencyKey = event.idempotencyKey.ifBlank { current.idempotencyKey }
            )
            val usagePendingCanBeRecovered = event.status in setOf(
                "USAGE_PENDING", "INTERRUPTED_USAGE_PENDING"
            ) && hasRecoverableUsageMetadata(mergedUsageMetadata)
            val usageRecoveryCompleted = current.status in setOf("TIMED_OUT", "CANCELLED") &&
                event.status == "INTERRUPTED" && hasRecoverableUsageMetadata(mergedUsageMetadata)
            val isArchiveBackedReconciliation = archivedResultExists &&
                event.status in setOf("COMPLETED", "INTERRUPTED_USAGE_PENDING")
            if (!isArchiveBackedReconciliation && !usagePendingCanBeRecovered && !usageRecoveryCompleted) return true
        }
        // Later lifecycle states often omit request-only metadata. Preserve the latest
        // REQUESTING details so a USAGE_PENDING checkpoint can still be reconciled after restart.
        rows.add(
            0,
            event.copy(
                agentId = event.agentId.take(100),
                status = event.status.take(30),
                detail = event.detail.take(500),
                providerId = event.providerId.ifBlank { current?.providerId.orEmpty() }.take(100),
                model = event.model.ifBlank { current?.model.orEmpty() }.take(160),
                attempts = event.attempts.coerceAtLeast(0),
                processId = event.processId.ifBlank { processId },
                estimatedInputTokens = event.estimatedInputTokens.coerceAtLeast(0L)
                    .takeIf { it > 0L } ?: current?.estimatedInputTokens ?: 0L,
                estimatedOutputTokens = event.estimatedOutputTokens.coerceAtLeast(0L)
                    .takeIf { it > 0L } ?: current?.estimatedOutputTokens ?: 0L,
                idempotencyKey = event.idempotencyKey.ifBlank { current?.idempotencyKey.orEmpty() }.take(240),
                baseUrl = event.baseUrl.ifBlank { current?.baseUrl.orEmpty() }.take(300)
            )
        )
        return save(context, rows.take(MAX_EVENTS))
    }

    @Synchronized
    fun load(context: Context): List<Event> = if (SharedPreferencesWriteHealth.isUncertain(PREFS)) emptyList() else readEvents(context) ?: emptyList()

    /** Distinguishes an empty journal from unreadable persisted recovery data. */
    @Synchronized
    fun isJournalReadable(context: Context): Boolean = !SharedPreferencesWriteHealth.isUncertain(PREFS) && readEvents(context) != null

    /**
     * A corrupt journal is distinct from an empty journal. Callers that append must not
     * replace unreadable persisted data with a new empty history.
     */
    private fun readEvents(context: Context): List<Event>? {
        if (SharedPreferencesWriteHealth.isUncertain(PREFS)) return null
        return runCatching {
            SharedPreferencesWriteHealth.withHealthyAccess(PREFS) {
                val a = JSONArray(
                    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
                )
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            fun exactString(key: String, default: String = ""): String {
                if (!o.has(key)) return default
                val value = o.opt(key)
                require(value is String) { "Checkpoint field '$key' is not a string." }
                return value
            }
            fun exactLong(key: String, default: Long = 0L): Long {
                if (!o.has(key)) return default
                val value = o.opt(key)
                require(value is Int || value is Long) { "Checkpoint field '$key' is not an exact integer." }
                return (value as Number).toLong()
            }
            val id = exactString("task")
            require(id.isNotBlank()) { "Checkpoint row has no task id." }
            val estimatedInput = exactLong("estimatedInputTokens")
            val estimatedOutput = exactLong("estimatedOutputTokens")
            val attemptsLong = exactLong("attempts")
            val timestamp = exactLong("ts")
            require(estimatedInput >= 0L && estimatedOutput >= 0L &&
                attemptsLong in 0L..Int.MAX_VALUE.toLong() && timestamp >= 0L) {
                "Checkpoint row has invalid token estimates, timestamp or attempt count."
            }
            val agentId = exactString("agent")
            val status = exactString("status")
            require(agentId.isNotBlank() && status.isNotBlank()) {
                "Checkpoint row has no agent or lifecycle status."
            }
            Event(
                id,
                agentId,
                status,
                exactString("detail"),
                timestamp,
                exactString("provider"),
                exactString("model"),
                attemptsLong.toInt(),
                exactString("processId"),
                estimatedInput,
                estimatedOutput,
                exactString("idempotencyKey"),
                exactString("baseUrl")
            )
                }
            }
        }.getOrNull()
    }

    /** At process startup, queued/running work from another process cannot be resumed safely. */
    @Synchronized
    fun recoverStaleRunning(
        context: Context,
        now: Long = System.currentTimeMillis(),
        onAmbiguousRequest: (Event) -> Boolean = { true }
    ): Int {
        if (SharedPreferencesWriteHealth.isUncertain(PREFS)) return 0
        val stale = latest(context).filter { event ->
            val archivedResultExists = runCatching {
                TaskResultArchive.get(context, event.taskId) != null
            }.getOrDefault(false)
            val terminalRequestNeedsReconciliation =
                event.status in setOf("TIMED_OUT", "CANCELLED") &&
                    (archivedResultExists || hasRecoverableUsageMetadata(event))
            (event.status in setOf("QUEUED", "RUNNING", "REQUESTING", "USAGE_RECORDED", "USAGE_PENDING", "RESULT_PERSIST_PENDING") && event.processId != processId) ||
                event.status == "INTERRUPTED_USAGE_PENDING" ||
                terminalRequestNeedsReconciliation
        }
        var recovered = 0
        stale.forEach { event ->
            val archivedResultExists = runCatching {
                TaskResultArchive.get(context, event.taskId) != null
            }.getOrDefault(false)
            val needsUsageRecovery = event.status in setOf(
                "REQUESTING", "USAGE_PENDING", "INTERRUPTED_USAGE_PENDING", "TIMED_OUT", "CANCELLED"
            ) && (event.status !in setOf("TIMED_OUT", "CANCELLED") ||
                archivedResultExists || hasRecoverableUsageMetadata(event))
            val usageRecovered = !needsUsageRecovery || runCatching { onAmbiguousRequest(event) }.getOrDefault(false)
            // A crash can occur after the report archive commits but before COMPLETED is journaled.
            // Reconcile that durable report without repeating the provider request or billing usage.
            // The report may be archived before the final checkpoint, including while usage
            // accounting is pending. Always reconcile the archive independently of the last
            // lifecycle status so a crash in that narrow window cannot hide a durable report.
            val resultRecovered = runCatching {
                TaskResultArchive.get(context, event.taskId) != null
            }.getOrDefault(false)
            val nextStatus = when {
                needsUsageRecovery && !usageRecovered -> "INTERRUPTED_USAGE_PENDING"
                resultRecovered -> "COMPLETED"
                else -> "INTERRUPTED"
            }
            val detail = when {
                needsUsageRecovery && !usageRecovered && resultRecovered ->
                    "Raport odzyskano z archiwum; księgowanie UNAVAILABLE nadal oczekuje na ponowienie."
                needsUsageRecovery && !usageRecovered ->
                    "Proces zakończył się po rozpoczęciu żądania; księgowanie UNAVAILABLE oczekuje na ponowienie."
                resultRecovered && needsUsageRecovery ->
                    "Raport odzyskano z trwałego archiwum; zużycie API zabezpieczono jako UNAVAILABLE bez ponawiania żądania."
                resultRecovered ->
                    "Raport odzyskano z trwałego archiwum; nie ponawiano żądania API."
                needsUsageRecovery ->
                    "Proces zakończył się po rozpoczęciu żądania; nieznane zużycie API zabezpieczono jako UNAVAILABLE."
                event.status == "RESULT_PERSIST_PENDING" ->
                    "Proces zakończył się po odebraniu raportu; nie znaleziono potwierdzonego zapisu w archiwum wyników."
                else -> "Proces aplikacji zakończył się przed zapisaniem wyniku."
            }
            val saved = record(context, event.copy(
                status = nextStatus, detail = detail, timestamp = now, processId = processId
            ))
            if (saved) recovered++ else {
                android.util.Log.e("AIMasterRecovery", "Nie udało się trwale zapisać stanu odzyskiwania zadania.")
            }
        }
        return recovered
    }

    /** Stores only provider origin in the checkpoint; credentials, path and query are excluded. */
    fun safeBaseUrlForCheckpoint(raw: String): String = runCatching {
        java.net.URL(raw).let { url ->
            require(url.protocol.equals("http", true) || url.protocol.equals("https", true))
            require(url.host.isNotBlank())
            "${url.protocol}://${url.host}" + if (url.port >= 0) ":${url.port}" else ""
        }
    }.getOrDefault("")

    /** Only complete request metadata can authorize automatic UNAVAILABLE reconciliation. */
    fun hasRecoverableUsageMetadata(event: Event): Boolean =
        event.taskId.isNotBlank() &&
            event.agentId.isNotBlank() &&
            event.providerId.isNotBlank() &&
            event.model.isNotBlank() &&
            event.estimatedInputTokens > 0L &&
            event.estimatedOutputTokens > 0L &&
            event.idempotencyKey.isNotBlank()

    /** The journal is newest-first; first occurrence is authoritative regardless of wall-clock changes. */
    @Synchronized
    fun latest(context: Context): List<Event> {
        val seen = hashSetOf<String>()
        return load(context).filter { seen.add(it.taskId) }
    }

    @Synchronized
    fun clear(context: Context): Boolean {
        if (SharedPreferencesWriteHealth.isUncertain(PREFS)) return false
        return SharedPreferencesWriteHealth.commit(
            PREFS, context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY)
        )
    }

    private fun save(context: Context, rows: List<Event>): Boolean {
        if (SharedPreferencesWriteHealth.isUncertain(PREFS)) return false
        val a = JSONArray()
        rows.forEach { e ->
            a.put(
                JSONObject()
                    .put("task", e.taskId)
                    .put("agent", e.agentId)
                    .put("status", e.status)
                    .put("detail", e.detail)
                    .put("ts", e.timestamp)
                    .put("provider", e.providerId)
                    .put("model", e.model)
                    .put("attempts", e.attempts)
                    .put("processId", e.processId)
                    .put("estimatedInputTokens", e.estimatedInputTokens)
                    .put("estimatedOutputTokens", e.estimatedOutputTokens)
                    .put("idempotencyKey", e.idempotencyKey)
                    .put("baseUrl", e.baseUrl)
            )
        }
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, a.toString())
        return SharedPreferencesWriteHealth.commit(PREFS, editor)
    }
}
