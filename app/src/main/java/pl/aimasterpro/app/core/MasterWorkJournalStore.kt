package pl.aimasterpro.app.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Durable, bounded work memory for MASTER orchestration.
 * Stores plans, decisions and resumable progress only; never prompts, responses or credentials.
 */
object MasterWorkJournalStore {
    private const val PREFS = "ai_master_work_journal_v1"
    private const val KEY = "records"
    private const val MAX_RECORDS = 60
    private const val MAX_STEPS = 40
    private const val MAX_DECISIONS = 40

    data class Record(
        val taskId: String,
        val chatId: String,
        val title: String,
        val objective: String,
        val branch: String,
        val headSha: String,
        val status: String,
        val currentStep: String,
        val completedSteps: List<String>,
        val decisions: List<String>,
        val nextSteps: List<String>,
        val updatedAt: Long
    )

    private val statuses = setOf("PLANNED", "ACTIVE", "BLOCKED", "PAUSED", "COMPLETED")

    fun get(context: Context, taskId: String): Record? = transaction(context) { rows ->
        rows.firstOrNull { it.taskId == taskId }
    }

    fun latest(context: Context, limit: Int = MAX_RECORDS): List<Record> = transaction(context) { rows ->
        rows.take(limit.coerceIn(0, MAX_RECORDS))
    } ?: emptyList()

    /** Inserts or replaces one task record atomically, preserving other active work. */
    fun save(context: Context, record: Record): Boolean = transaction(context) { rows ->
        if (!valid(record)) return@transaction false
        val updated = listOf(record) + rows.filterNot { it.taskId == record.taskId }
        write(context, updated.take(MAX_RECORDS))
    } ?: false

    /** Atomic progress update; null indicates missing/corrupt data or a rejected mutation. */
    fun update(context: Context, taskId: String, transform: (Record) -> Record?): Record? =
        transaction(context) { rows ->
            val current = rows.firstOrNull { it.taskId == taskId } ?: return@transaction null
            val next = runCatching { transform(current) }.getOrNull() ?: return@transaction null
            if (next.taskId != current.taskId || !valid(next)) return@transaction null
            if (!write(context, listOf(next) + rows.filterNot { it.taskId == taskId })) return@transaction null
            next
        }

    fun remove(context: Context, taskId: String): Boolean = transaction(context) { rows ->
        if (rows.none { it.taskId == taskId }) return@transaction false
        write(context, rows.filterNot { it.taskId == taskId })
    } ?: false

    /**
     * Applies progress/finalization only while a task is ACTIVE. Delayed callbacks cannot
     * reopen PAUSED/BLOCKED work or overwrite a terminal COMPLETED record.
     */
    fun updateActive(context: Context, taskId: String, transform: (Record) -> Record?): Record? =
        transaction(context) { rows ->
            val current = rows.firstOrNull { it.taskId == taskId } ?: return@transaction null
            if (current.status != "ACTIVE") return@transaction current
            val next = runCatching { transform(current) }.getOrNull() ?: return@transaction null
            if (next.taskId != current.taskId || !valid(next)) return@transaction null
            if (!write(context, listOf(next) + rows.filterNot { it.taskId == taskId })) return@transaction null
            next
        }

    /** Pauses one known task without touching unrelated work or terminal states. */
    fun pauseActiveTask(context: Context, taskId: String, now: Long = System.currentTimeMillis()): Record? =
        updateActive(context, taskId) { current ->
            current.copy(
                status = "PAUSED",
                currentStep = "Przerwano przez użytkownika lub zamknięcie Activity; wznowienie wymaga jawnej decyzji koordynatora.",
                nextSteps = (current.nextSteps + "Sprawdź checkpointy i zdecyduj o wznowieniu.").distinct().take(MAX_STEPS),
                updatedAt = now.coerceAtLeast(0L)
            )
        }

    /** Marks work left ACTIVE by a previous process as interrupted; never claims it resumed. */
    fun pauseActive(context: Context, now: Long = System.currentTimeMillis()): Int =
        transaction(context) { rows ->
            val active = rows.filter { it.status == "ACTIVE" }
            if (active.isEmpty()) return@transaction 0
            val paused = rows.map { record ->
                if (record.status != "ACTIVE") record else record.copy(
                    status = "PAUSED",
                    currentStep = "Przerwano po zamknięciu procesu; wznowienie wymaga jawnej decyzji koordynatora.",
                    nextSteps = (record.nextSteps + "Sprawdź checkpointy i zdecyduj o wznowieniu.").distinct().take(MAX_STEPS),
                    updatedAt = now.coerceAtLeast(0L)
                )
            }
            if (write(context, paused)) active.size else 0
        } ?: 0

    private fun valid(r: Record): Boolean =
        r.taskId.isNotBlank() && r.taskId.length <= 160 &&
            r.chatId.length <= 160 && r.title.isNotBlank() && r.title.length <= 180 &&
            r.objective.isNotBlank() && r.objective.length <= 1200 &&
            r.branch.length <= 180 && (r.headSha.isEmpty() || r.headSha.matches(Regex("[0-9a-fA-F]{7,64}"))) &&
            r.status in statuses && r.currentStep.length <= 300 &&
            r.completedSteps.size <= MAX_STEPS && r.decisions.size <= MAX_DECISIONS &&
            r.nextSteps.size <= MAX_STEPS &&
            (r.completedSteps + r.decisions + r.nextSteps).all { it.isNotBlank() && it.length <= 500 } &&
            r.updatedAt >= 0L

    private fun <T> transaction(context: Context, operation: (List<Record>) -> T): T? =
        runCatching {
            SharedPreferencesWriteHealth.withHealthyAccess(PREFS) {
                val rows = read(context) ?: return@withHealthyAccess null
                operation(rows)
            }
        }.getOrNull()

    private fun read(context: Context): List<Record>? = runCatching {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: "[]"
        val array = JSONArray(raw)
        require(array.length() <= MAX_RECORDS)
        val ids = hashSetOf<String>()
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            fun text(name: String): String {
                val v = o.opt(name)
                require(v is String)
                return v
            }
            fun list(name: String, max: Int): List<String> {
                val a = o.optJSONArray(name) ?: JSONArray()
                require(a.length() <= max)
                return (0 until a.length()).map { index ->
                    val v = a.opt(index)
                    require(v is String && v.isNotBlank() && v.length <= 500)
                    v
                }
            }
            val record = Record(
                taskId = text("taskId"), chatId = text("chatId"), title = text("title"),
                objective = text("objective"), branch = text("branch"), headSha = text("headSha"),
                status = text("status"), currentStep = text("currentStep"),
                completedSteps = list("completedSteps", MAX_STEPS),
                decisions = list("decisions", MAX_DECISIONS),
                nextSteps = list("nextSteps", MAX_STEPS),
                updatedAt = run {
                    val value = o.opt("updatedAt")
                    require(value is Int || value is Long)
                    (value as Number).toLong()
                }
            )
            require(ids.add(record.taskId) && valid(record))
            record
        }
    }.getOrNull()

    private fun write(context: Context, records: List<Record>): Boolean = runCatching {
        if (records.size > MAX_RECORDS || records.any { !valid(it) } ||
            records.map { it.taskId }.distinct().size != records.size) return@runCatching false
        val array = JSONArray()
        records.forEach { r ->
            array.put(JSONObject()
                .put("taskId", r.taskId).put("chatId", r.chatId).put("title", r.title)
                .put("objective", r.objective).put("branch", r.branch).put("headSha", r.headSha)
                .put("status", r.status).put("currentStep", r.currentStep)
                .put("completedSteps", JSONArray(r.completedSteps))
                .put("decisions", JSONArray(r.decisions)).put("nextSteps", JSONArray(r.nextSteps))
                .put("updatedAt", r.updatedAt))
        }
        SharedPreferencesWriteHealth.commit(PREFS,
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, array.toString()))
    }.getOrDefault(false)
}
