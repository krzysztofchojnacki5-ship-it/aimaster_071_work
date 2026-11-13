package pl.aimasterpro.app.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Append-only, bounded decision history for MASTER.
 *
 * Entries are never edited in place. A changed/reversed decision is a new entry that references
 * the earlier decision through supersedesDecisionId. This store intentionally excludes prompts,
 * model responses, credentials and arbitrary attachments.
 */
object DecisionLedgerStore {
    private const val PREFS = "aiforteca_decision_ledger_v1"
    private const val KEY = "entries"
    private const val MAX_ENTRIES = 1_000
    private const val MAX_OPTIONS = 12
    private const val MAX_EVIDENCE = 40
    private const val MAX_RAW_CHARS = 4_000_000

    enum class ReadStatus { AVAILABLE, UNAVAILABLE, CORRUPT }

    data class Entry(
        val decisionId: String,
        val taskId: String,
        val timestampUtc: Long,
        val actor: String,
        val context: String,
        val objective: String,
        val optionsConsidered: List<String>,
        val selectedOption: String,
        val rationale: String,
        val evidenceRefs: List<String>,
        val assumptions: String,
        val confidence: String,
        val riskImpact: String,
        val approvalRequired: Boolean,
        val approvalReference: String,
        val outcome: String,
        val reviewTrigger: String,
        val supersedesDecisionId: String = ""
    )

    data class Snapshot(val status: ReadStatus, val entries: List<Entry>)

    @Synchronized
    fun append(context: Context, entry: Entry): Boolean {
        if (!valid(entry) || SharedPreferencesWriteHealth.isUncertain(PREFS)) return false
        return runCatching {
            SharedPreferencesWriteHealth.withHealthyAccess(PREFS) {
                val current = readStrict(context) ?: return@withHealthyAccess false
                if (current.any { it.decisionId == entry.decisionId }) return@withHealthyAccess false
                if (entry.supersedesDecisionId.isNotBlank() &&
                    current.none { it.decisionId == entry.supersedesDecisionId }) {
                    return@withHealthyAccess false
                }
                val next = (listOf(entry) + current).take(MAX_ENTRIES)
                val serialized = encode(next).toString()
                if (serialized.length > MAX_RAW_CHARS) return@withHealthyAccess false
                SharedPreferencesWriteHealth.commit(
                    PREFS,
                    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                        .putString(KEY, serialized)
                )
            }
        }.getOrDefault(false)
    }

    @Synchronized
    fun snapshot(context: Context): Snapshot {
        if (SharedPreferencesWriteHealth.isUncertain(PREFS)) {
            return Snapshot(ReadStatus.UNAVAILABLE, emptyList())
        }
        return runCatching {
            SharedPreferencesWriteHealth.withHealthyAccess(PREFS) {
                val entries = readStrict(context)
                if (entries == null) Snapshot(ReadStatus.CORRUPT, emptyList())
                else Snapshot(ReadStatus.AVAILABLE, entries)
            }
        }.getOrElse { Snapshot(ReadStatus.UNAVAILABLE, emptyList()) }
    }

    @Synchronized
    fun get(context: Context, decisionId: String): Entry? =
        snapshot(context).takeIf { it.status == ReadStatus.AVAILABLE }
            ?.entries?.firstOrNull { it.decisionId == decisionId }

    /**
     * Compact, bounded context for MASTER. It exposes only decision metadata, never raw prompts,
     * model responses, credentials or attachments. An unreadable ledger is reported explicitly.
     */
    fun contextString(context: Context, maxEntries: Int = 8, maxChars: Int = 1_800): String {
        require(maxEntries in 1..20 && maxChars in 200..8_000)
        val state = snapshot(context)
        if (state.status != ReadStatus.AVAILABLE) {
            return "Rejestr decyzji MASTER-a: ${state.status.name}. Historia jest niedostępna; " +
                "nie zakładaj, że brak wpisów oznacza brak wcześniejszych decyzji."
        }
        if (state.entries.isEmpty()) return ""
        val rendered = buildString {
            append("Historia decyzji MASTER-a (najnowsze pierwsze; to zapis wcześniejszych wyborów, nie dowód aktualnej prawdziwości):")
            state.entries.take(maxEntries).forEach { entry ->
                append("\n- [").append(entry.outcome).append("] ")
                    .append(entry.objective).append("; wybór=").append(entry.selectedOption)
                    .append("; uzasadnienie=").append(entry.rationale)
                    .append("; pewność=").append(entry.confidence)
                    .append("; ryzyko=").append(entry.riskImpact)
            }
        }
        return if (rendered.length <= maxChars) rendered else rendered.take(maxChars - 1).trimEnd() + "…"
    }

    internal fun resetStorageHealthForTests() {
        SharedPreferencesWriteHealth.resetForTests(PREFS)
    }

    private fun valid(e: Entry): Boolean =
        e.decisionId.isNotBlank() && e.decisionId.length <= 160 &&
        e.taskId.length <= 160 && e.timestampUtc >= 0L &&
        e.actor.isNotBlank() && e.actor.length <= 120 &&
        e.context.length <= 2_000 && e.objective.isNotBlank() && e.objective.length <= 1_200 &&
        e.optionsConsidered.size <= MAX_OPTIONS &&
        e.optionsConsidered.all { it.isNotBlank() && it.length <= 500 } &&
        e.selectedOption.isNotBlank() && e.selectedOption.length <= 500 &&
        e.rationale.isNotBlank() && e.rationale.length <= 2_000 &&
        e.evidenceRefs.size <= MAX_EVIDENCE &&
        e.evidenceRefs.all { it.isNotBlank() && it.length <= 500 } &&
        e.assumptions.length <= 1_000 && e.confidence.length <= 80 &&
        e.riskImpact.length <= 1_000 && e.approvalReference.length <= 240 &&
        e.outcome in setOf("PROPOSED", "APPROVED", "REJECTED", "IMPLEMENTED", "SUPERSEDED") &&
        e.reviewTrigger.length <= 500 && e.supersedesDecisionId.length <= 160

    private fun readStrict(context: Context): List<Entry>? = runCatching {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: "[]"
        require(raw.length <= MAX_RAW_CHARS) { "Decision ledger exceeds its supported raw size." }
        val array = JSONArray(raw)
        require(array.length() <= MAX_ENTRIES)
        val ids = hashSetOf<String>()
        (0 until array.length()).map { index ->
            val o = array.getJSONObject(index)
            fun string(key: String): String {
                val value = o.opt(key)
                require(value is String) { "Decision field '$key' is not a string." }
                return value
            }
            fun long(key: String): Long {
                val value = o.opt(key)
                require(value is Int || value is Long) { "Decision field '$key' is not an exact integer." }
                return (value as Number).toLong()
            }
            fun strings(key: String, max: Int): List<String> {
                val a = o.optJSONArray(key) ?: JSONArray()
                require(a.length() <= max)
                return (0 until a.length()).map { i ->
                    val value = a.opt(i)
                    require(value is String && value.isNotBlank() && value.length <= 500)
                    value
                }
            }
            val approval = o.opt("approvalRequired")
            require(approval is Boolean)
            val entry = Entry(
                decisionId = string("decisionId"), taskId = string("taskId"),
                timestampUtc = long("timestampUtc"), actor = string("actor"),
                context = string("context"), objective = string("objective"),
                optionsConsidered = strings("optionsConsidered", MAX_OPTIONS),
                selectedOption = string("selectedOption"), rationale = string("rationale"),
                evidenceRefs = strings("evidenceRefs", MAX_EVIDENCE),
                assumptions = string("assumptions"), confidence = string("confidence"),
                riskImpact = string("riskImpact"), approvalRequired = approval,
                approvalReference = string("approvalReference"), outcome = string("outcome"),
                reviewTrigger = string("reviewTrigger"),
                supersedesDecisionId = string("supersedesDecisionId")
            )
            require(ids.add(entry.decisionId) && valid(entry))
            entry
        }
    }.getOrNull()

    private fun encode(entries: List<Entry>): JSONArray = JSONArray().apply {
        entries.forEach { e ->
            put(JSONObject()
                .put("decisionId", e.decisionId).put("taskId", e.taskId)
                .put("timestampUtc", e.timestampUtc).put("actor", e.actor)
                .put("context", e.context).put("objective", e.objective)
                .put("optionsConsidered", JSONArray(e.optionsConsidered))
                .put("selectedOption", e.selectedOption).put("rationale", e.rationale)
                .put("evidenceRefs", JSONArray(e.evidenceRefs))
                .put("assumptions", e.assumptions).put("confidence", e.confidence)
                .put("riskImpact", e.riskImpact).put("approvalRequired", e.approvalRequired)
                .put("approvalReference", e.approvalReference).put("outcome", e.outcome)
                .put("reviewTrigger", e.reviewTrigger)
                .put("supersedesDecisionId", e.supersedesDecisionId))
        }
    }

    fun newEntryId(): String = UUID.randomUUID().toString()
}
