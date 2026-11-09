package pl.aimasterpro.app.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Append-only, bounded incident history. A status change is a new record linked to the
 * previous record; historical evidence is never edited in place.
 *
 * Only explicitly redacted diagnostic text may be stored. Credentials, access tokens,
 * raw prompts, and unredacted provider payloads must never be passed to this API.
 */
object ErrorKnowledgeBaseStore {
    private const val PREFS = "aiforteca_error_knowledge_v1"
    private const val KEY = "records"
    private const val MAX_RECORDS = 2_000
    private const val MAX_LIST = 40

    enum class ReadStatus { AVAILABLE, UNAVAILABLE, CORRUPT }
    enum class CaseStatus {
        OPEN, INVESTIGATING, ROOT_CAUSE_CONFIRMED, FIXED_UNVERIFIED, VERIFIED, WONT_FIX
    }

    data class Record(
        val recordId: String,
        val errorId: String,
        val timestampUtc: Long,
        val project: String,
        val repository: String,
        val branch: String,
        val commit: String,
        val environment: String,
        val operation: String,
        val exactCommand: String,
        val redactedErrorOutput: String,
        val reproductionSteps: String,
        val reproducibility: String,
        val observedFacts: List<String>,
        val hypotheses: List<String>,
        val confirmedRootCause: String,
        val attemptedFixes: List<String>,
        val linkedTaskIds: List<String>,
        val linkedCommits: List<String>,
        val linkedTests: List<String>,
        val evidenceRefs: List<String>,
        val status: CaseStatus,
        val redactionConfirmed: Boolean,
        val fixSummary: String = "",
        val verificationEnvironment: String = "",
        val supersedesRecordId: String = ""
    )

    data class Snapshot(val status: ReadStatus, val records: List<Record>)

    @Synchronized
    fun append(context: Context, record: Record): Boolean {
        if (!valid(record) || !record.redactionConfirmed ||
            SharedPreferencesWriteHealth.isUncertain(PREFS)) return false

        return runCatching {
            SharedPreferencesWriteHealth.withHealthyAccess(PREFS) {
                val current = readStrict(context) ?: return@withHealthyAccess false
                if (current.any { it.recordId == record.recordId }) return@withHealthyAccess false

                if (record.supersedesRecordId.isBlank()) {
                    if (current.any { it.errorId == record.errorId }) return@withHealthyAccess false
                    if (record.status != CaseStatus.OPEN &&
                        record.status != CaseStatus.INVESTIGATING) return@withHealthyAccess false
                } else {
                    val previous = current.firstOrNull {
                        it.recordId == record.supersedesRecordId
                    } ?: return@withHealthyAccess false
                    if (previous.errorId != record.errorId ||
                        !allowed(previous.status, record.status)) return@withHealthyAccess false
                }

                val next = (listOf(record) + current).take(MAX_RECORDS)
                SharedPreferencesWriteHealth.commit(
                    PREFS,
                    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                        .putString(KEY, encode(next).toString())
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
                val records = readStrict(context)
                if (records == null) Snapshot(ReadStatus.CORRUPT, emptyList())
                else Snapshot(ReadStatus.AVAILABLE, records)
            }
        }.getOrElse { Snapshot(ReadStatus.UNAVAILABLE, emptyList()) }
    }

    @Synchronized
    fun latestForError(context: Context, errorId: String): Record? {
        val result = snapshot(context)
        if (result.status != ReadStatus.AVAILABLE) return null
        val ids = result.records.filter { it.errorId == errorId }.map { it.recordId }.toSet()
        val superseded = result.records.mapNotNull { it.supersedesRecordId.takeIf(String::isNotBlank) }.toSet()
        return result.records.firstOrNull { it.recordId in ids && it.recordId !in superseded }
    }

    internal fun resetStorageHealthForTests() {
        SharedPreferencesWriteHealth.resetForTests(PREFS)
    }

    private fun allowed(from: CaseStatus, to: CaseStatus): Boolean = when (from) {
        CaseStatus.OPEN -> to in setOf(CaseStatus.INVESTIGATING, CaseStatus.WONT_FIX)
        CaseStatus.INVESTIGATING -> to in setOf(
            CaseStatus.ROOT_CAUSE_CONFIRMED, CaseStatus.WONT_FIX
        )
        CaseStatus.ROOT_CAUSE_CONFIRMED -> to in setOf(
            CaseStatus.INVESTIGATING, CaseStatus.FIXED_UNVERIFIED, CaseStatus.WONT_FIX
        )
        CaseStatus.FIXED_UNVERIFIED -> to in setOf(
            CaseStatus.VERIFIED, CaseStatus.INVESTIGATING
        )
        CaseStatus.VERIFIED, CaseStatus.WONT_FIX -> false
    }

    private fun valid(r: Record): Boolean =
        r.recordId.isNotBlank() && r.recordId.length <= 160 &&
        r.errorId.isNotBlank() && r.errorId.length <= 160 && r.timestampUtc >= 0L &&
        r.project.length <= 160 && r.repository.length <= 240 && r.branch.length <= 240 &&
        r.commit.length <= 160 && r.environment.length <= 1_000 &&
        r.operation.isNotBlank() && r.operation.length <= 300 &&
        r.exactCommand.length <= 1_000 && r.redactedErrorOutput.isNotBlank() &&
        r.redactedErrorOutput.length <= 8_000 && r.reproductionSteps.length <= 3_000 &&
        r.reproducibility in setOf("REPRODUCED", "DOCUMENTED", "NOT_REPRODUCED", "UNKNOWN") &&
        listValid(r.observedFacts) && listValid(r.hypotheses) &&
        r.confirmedRootCause.length <= 3_000 &&
        (r.status != CaseStatus.ROOT_CAUSE_CONFIRMED || r.confirmedRootCause.isNotBlank()) &&
        listValid(r.attemptedFixes) &&
        listValid(r.linkedTaskIds) && listValid(r.linkedCommits) &&
        listValid(r.linkedTests) && listValid(r.evidenceRefs) &&
        r.fixSummary.length <= 3_000 && r.verificationEnvironment.length <= 1_000 &&
        r.supersedesRecordId.length <= 160 &&
        (r.status != CaseStatus.FIXED_UNVERIFIED ||
            (r.fixSummary.isNotBlank() && r.linkedCommits.isNotEmpty())) &&
        (r.status != CaseStatus.VERIFIED ||
            (r.fixSummary.isNotBlank() && r.linkedCommits.isNotEmpty() &&
                r.linkedTests.isNotEmpty() && r.evidenceRefs.isNotEmpty() &&
                r.verificationEnvironment.isNotBlank()))

    private fun listValid(values: List<String>): Boolean =
        values.size <= MAX_LIST && values.all { it.isNotBlank() && it.length <= 1_000 }

    private fun readStrict(context: Context): List<Record>? = runCatching {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: "[]"
        val array = JSONArray(raw)
        require(array.length() <= MAX_RECORDS)
        val recordIds = hashSetOf<String>()
        val decoded = (0 until array.length()).map { index ->
            val o = array.getJSONObject(index)
            fun str(key: String): String {
                val v = o.opt(key)
                require(v is String) { "Error record field '$key' is not a string." }
                return v
            }
            fun long(key: String): Long {
                val v = o.opt(key)
                require(v is Int || v is Long) { "Error record field '$key' is not an exact integer." }
                return (v as Number).toLong()
            }
            fun strings(key: String): List<String> {
                val a = o.optJSONArray(key) ?: error("Error record field '$key' is not an array.")
                require(a.length() <= MAX_LIST)
                return (0 until a.length()).map { i ->
                    val v = a.opt(i)
                    require(v is String && v.isNotBlank() && v.length <= 1_000)
                    v
                }
            }
            val redacted = o.opt("redactionConfirmed")
            require(redacted is Boolean)
            Record(
                recordId = str("recordId"), errorId = str("errorId"),
                timestampUtc = long("timestampUtc"), project = str("project"),
                repository = str("repository"), branch = str("branch"), commit = str("commit"),
                environment = str("environment"), operation = str("operation"),
                exactCommand = str("exactCommand"), redactedErrorOutput = str("redactedErrorOutput"),
                reproductionSteps = str("reproductionSteps"), reproducibility = str("reproducibility"),
                observedFacts = strings("observedFacts"), hypotheses = strings("hypotheses"),
                confirmedRootCause = str("confirmedRootCause"), attemptedFixes = strings("attemptedFixes"),
                linkedTaskIds = strings("linkedTaskIds"), linkedCommits = strings("linkedCommits"),
                linkedTests = strings("linkedTests"), evidenceRefs = strings("evidenceRefs"),
                status = CaseStatus.valueOf(str("status")), redactionConfirmed = redacted,
                fixSummary = str("fixSummary"), verificationEnvironment = str("verificationEnvironment"),
                supersedesRecordId = str("supersedesRecordId")
            ).also {
                require(recordIds.add(it.recordId) && valid(it) && it.redactionConfirmed)
            }
        }
        val byId = decoded.associateBy { it.recordId }
        decoded.forEach { record ->
            if (record.supersedesRecordId.isNotBlank()) {
                val previous = byId[record.supersedesRecordId]
                    ?: error("Missing superseded error record.")
                require(previous.errorId == record.errorId && allowed(previous.status, record.status))
            }
        }
        decoded
    }.getOrNull()

    private fun encode(records: List<Record>): JSONArray = JSONArray().apply {
        records.forEach { r ->
            put(JSONObject()
                .put("recordId", r.recordId).put("errorId", r.errorId)
                .put("timestampUtc", r.timestampUtc).put("project", r.project)
                .put("repository", r.repository).put("branch", r.branch).put("commit", r.commit)
                .put("environment", r.environment).put("operation", r.operation)
                .put("exactCommand", r.exactCommand).put("redactedErrorOutput", r.redactedErrorOutput)
                .put("reproductionSteps", r.reproductionSteps).put("reproducibility", r.reproducibility)
                .put("observedFacts", JSONArray(r.observedFacts)).put("hypotheses", JSONArray(r.hypotheses))
                .put("confirmedRootCause", r.confirmedRootCause)
                .put("attemptedFixes", JSONArray(r.attemptedFixes))
                .put("linkedTaskIds", JSONArray(r.linkedTaskIds))
                .put("linkedCommits", JSONArray(r.linkedCommits))
                .put("linkedTests", JSONArray(r.linkedTests)).put("evidenceRefs", JSONArray(r.evidenceRefs))
                .put("status", r.status.name).put("redactionConfirmed", r.redactionConfirmed)
                .put("fixSummary", r.fixSummary).put("verificationEnvironment", r.verificationEnvironment)
                .put("supersedesRecordId", r.supersedesRecordId))
        }
    }

    fun newRecordId(): String = UUID.randomUUID().toString()
}
