package pl.aimasterpro.app.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Audit ledger for proposed changes. It records approvals/tests but deliberately does not apply patches. */
object ChangeSetStore {
    internal const val PREFERENCES_NAME = "ai_change_set_ledger_v1"
    private const val KEY = "changes"
    private const val MAX_ITEMS = 100
    private val statuses = setOf("PROPOSED", "APPROVED", "APPLIED_UNVERIFIED", "REJECTED", "APPLIED", "ROLLED_BACK")

    enum class LedgerStatus { VALID, CORRUPT }
    data class LedgerRead(val status: LedgerStatus, val items: List<ChangeSet> = emptyList(), val error: String = "")

    data class ChangeSet(
        val id: String, val taskId: String, val summary: String, val paths: List<String>,
        val status: String, val testStatus: String, val testOutput: String,
        val createdAt: Long, val updatedAt: Long, val validationCodes: List<String>, val snapshotId: String = "", val testLevel: String = "NONE", val chatId: String = ""
    )

    /** Compatibility view. Use readResult() when an empty ledger must be distinguished from corruption. */
    @Synchronized fun all(
        context: Context,
        storage: StringPreferenceStore = AndroidStringPreferenceStore(context, PREFERENCES_NAME)
    ): List<ChangeSet> = readResult(context, storage).items

    @Synchronized fun readResult(
        context: Context,
        storage: StringPreferenceStore = AndroidStringPreferenceStore(context, PREFERENCES_NAME)
    ): LedgerRead = try {
        if (SharedPreferencesWriteHealth.isUncertain(PREFERENCES_NAME)) {
            return LedgerRead(LedgerStatus.CORRUPT, error = "LEDGER_STORAGE_UNCERTAIN")
        }
        val raw = storage.read(KEY) ?: "[]"
        require(raw.isNotBlank() && raw.length <= 2_000_000) { "LEDGER_EMPTY_OR_OVERSIZED" }
        val array = JSONArray(raw)
        require(array.length() <= MAX_ITEMS) { "LEDGER_ITEM_LIMIT" }
        val seenIds = mutableSetOf<String>()
        val rows = (0 until array.length()).map { index ->
            val item = array.optJSONObject(index) ?: error("LEDGER_ROW_NOT_OBJECT")
            val id = item.getString("id")
            val status = item.getString("status")
            require(id.isNotBlank() && seenIds.add(id) && status in statuses) { "LEDGER_ID_OR_STATUS_INVALID" }
            val storedLevel = item.optString("testLevel", "NONE")
            require(storedLevel in setOf("NONE", "WORKSPACE_INTEGRITY", "LEGACY_UNVERIFIED", "PROJECT_TESTS")) {
                "LEDGER_TEST_LEVEL_INVALID"
            }
            val storedTestStatus = item.optString("testStatus", "NOT_RUN")
            require(storedTestStatus in setOf("PASS", "FAIL", "NOT_RUN")) { "LEDGER_TEST_STATUS_INVALID" }
            require(!item.has("paths") || item.optJSONArray("paths") != null) { "LEDGER_PATHS_INVALID" }
            require(!item.has("validationCodes") || item.optJSONArray("validationCodes") != null) { "LEDGER_VALIDATION_CODES_INVALID" }
            val snapshotId = item.optString("snapshotId")
            require(status !in setOf("APPLIED_UNVERIFIED", "APPLIED", "ROLLED_BACK") || snapshotId.isNotBlank()) {
                "LEDGER_APPLIED_WITHOUT_SNAPSHOT"
            }
            require(status != "APPLIED" || storedLevel == "PROJECT_TESTS") { "LEDGER_APPLIED_WITHOUT_PROJECT_TESTS_LEVEL" }
            // Legacy PROJECT_TESTS labels are downgraded; old releases had no isolated executor receipt.
            val safeLevel = if (storedLevel == "PROJECT_TESTS") "LEGACY_UNVERIFIED" else storedLevel
            val safeTestStatus = if (storedLevel == "PROJECT_TESTS") "NOT_RUN" else storedTestStatus
            val safeChangeStatus = if (status == "APPLIED" && storedLevel == "PROJECT_TESTS") "APPLIED_UNVERIFIED" else status
            ChangeSet(id, item.optString("taskId"), item.optString("summary"),
                strings(item.optJSONArray("paths")), safeChangeStatus, safeTestStatus, item.optString("testOutput"),
                item.optLong("createdAt"), item.optLong("updatedAt"), strings(item.optJSONArray("validationCodes")),
                snapshotId, safeLevel, item.optString("chatId"))
        }
        LedgerRead(LedgerStatus.VALID, rows)
    } catch (error: Exception) {
        LedgerRead(LedgerStatus.CORRUPT, error = "LEDGER_READ_FAILED:${error.javaClass.simpleName}")
    }

    private fun readOrThrow(context: Context, storage: StringPreferenceStore): List<ChangeSet> {
        val result = readResult(context, storage)
        check(result.status == LedgerStatus.VALID) { "Rejestr zmian jest uszkodzony (${result.error}); zapis zablokowany, aby nie nadpisać danych." }
        return result.items
    }

    @Synchronized fun propose(context: Context, taskId: String, summary: String, paths: List<String>, validation: ChangeSafetyGuard.Validation, chatId: String = "",
        storage: StringPreferenceStore = AndroidStringPreferenceStore(context, PREFERENCES_NAME)): ChangeSet {
        require(taskId.isNotBlank()) { "Brak identyfikatora zadania" }
        require(summary.isNotBlank()) { "Brak opisu zmian" }
        require(validation.allowed) { "Nie można zarejestrować zestawu z blokującymi błędami walidacji" }
        val suppliedPaths = paths.map { it.trim().replace('\\', '/') }.toSet()
        require(suppliedPaths.isNotEmpty() && suppliedPaths == validation.normalizedPaths.toSet() && suppliedPaths.size == paths.size) {
            "Ścieżki rejestru muszą dokładnie odpowiadać ścieżkom zweryfikowanym przez ChangeSafetyGuard."
        }
        val now = System.currentTimeMillis()
        val item = ChangeSet(UUID.randomUUID().toString(), taskId.take(160), summary.take(2000),
            validation.normalizedPaths.distinct().take(50), "PROPOSED", "NOT_RUN", "", now, now,
            validation.findings.map { it.code }.distinct().take(50), chatId = chatId.take(160))
        write(storage, (listOf(item) + readOrThrow(context, storage)).take(MAX_ITEMS))
        return item
    }

    @Synchronized fun transition(context: Context, id: String, next: String,
        storage: StringPreferenceStore = AndroidStringPreferenceStore(context, PREFERENCES_NAME)): Boolean {
        require(next in statuses) { "Nieznany status zestawu zmian" }
        val rows = readOrThrow(context, storage).toMutableList()
        val index = rows.indexOfFirst { it.id == id }
        if (index < 0) return false
        val current = rows[index].status
        val allowed = when (current) {
            "PROPOSED" -> setOf("APPROVED", "REJECTED")
            "APPROVED" -> setOf("APPLIED_UNVERIFIED", "REJECTED")
            "APPLIED_UNVERIFIED" -> setOf("APPLIED", "ROLLED_BACK")
            "APPLIED" -> setOf("ROLLED_BACK")
            else -> emptySet()
        }
        if (next == current) return true
        require(next in allowed) { "Niedozwolona zmiana statusu: $current → $next" }
        if (next == "APPLIED_UNVERIFIED" || next == "APPLIED") {
            require(rows[index].snapshotId.isNotBlank()) { "Nie można oznaczyć zmiany jako zastosowanej bez powiązanego snapshotu." }
        }
        if (next == "APPLIED") {
            require(rows[index].testStatus == "PASS" && rows[index].testLevel == "PROJECT_TESTS") {
                "Nie można oznaczyć zmiany jako APPLIED bez PASS rzeczywistych testów projektu w izolowanym executorze."
            }
        }
        rows[index] = rows[index].copy(status = next, updatedAt = System.currentTimeMillis())
        write(storage, rows)
        return true
    }

    @Synchronized fun recordSnapshot(context: Context, id: String, snapshotId: String,
        storage: StringPreferenceStore = AndroidStringPreferenceStore(context, PREFERENCES_NAME)): Boolean {
        require(snapshotId.isNotBlank()) { "Brak identyfikatora snapshotu" }
        val rows = readOrThrow(context, storage).toMutableList()
        val index = rows.indexOfFirst { it.id == id }
        if (index < 0) return false
        val item = rows[index]
        require(item.status == "APPROVED" || item.status == "APPLIED_UNVERIFIED") {
            "Snapshot można przypisać wyłącznie do zmiany zatwierdzonej lub oczekującej na weryfikację."
        }
        rows[index] = item.copy(snapshotId = snapshotId.take(160), updatedAt = System.currentTimeMillis())
        write(storage, rows)
        return true
    }

    /** Legacy/manual reports are never sufficient evidence for APPLIED. */
    @Synchronized fun recordTests(context: Context, id: String, passed: Boolean, output: String,
        storage: StringPreferenceStore = AndroidStringPreferenceStore(context, PREFERENCES_NAME)): Boolean =
        recordVerificationInternal(context, id, if (passed) "PASS" else "FAIL", "LEGACY_UNVERIFIED", output, storage)

    /**
     * Persists only local workspace-integrity results. Callers cannot label arbitrary output
     * as PROJECT_TESTS; that level is reserved for a future verified executor receipt.
     */
    @Synchronized fun recordWorkspaceVerification(context: Context, id: String, status: String, output: String,
        storage: StringPreferenceStore = AndroidStringPreferenceStore(context, PREFERENCES_NAME)): Boolean =
        recordVerificationInternal(context, id, status, "WORKSPACE_INTEGRITY", output, storage)

    @Synchronized private fun recordVerificationInternal(context: Context, id: String, status: String, level: String, output: String,
        storage: StringPreferenceStore): Boolean {
        require(status in setOf("PASS", "FAIL", "NOT_RUN")) { "Nieznany status testów" }
        require(level in setOf("WORKSPACE_INTEGRITY", "LEGACY_UNVERIFIED")) { "Poziom PROJECT_TESTS wymaga zweryfikowanego executor receipt." }
        val rows = readOrThrow(context, storage).toMutableList()
        val index = rows.indexOfFirst { it.id == id }
        if (index < 0) return false
        val item = rows[index]
        require(item.status == "APPROVED" || item.status == "APPLIED_UNVERIFIED") {
            "Wyniku weryfikacji nie można nadpisywać po końcowym statusie APPLIED."
        }
        rows[index] = item.copy(testStatus = status, testLevel = level, testOutput = output.take(8000), updatedAt = System.currentTimeMillis())
        write(storage, rows)
        return true
    }

    private fun write(storage: StringPreferenceStore, rows: List<ChangeSet>) {
        check(!SharedPreferencesWriteHealth.isUncertain(PREFERENCES_NAME)) { "LEDGER_STORAGE_UNCERTAIN" }
        val a = JSONArray()
        rows.take(MAX_ITEMS).forEach { c -> a.put(JSONObject().put("id", c.id).put("taskId", c.taskId)
            .put("summary", c.summary).put("paths", JSONArray(c.paths)).put("status", c.status)
            .put("testStatus", c.testStatus).put("testOutput", c.testOutput).put("createdAt", c.createdAt)
            .put("updatedAt", c.updatedAt).put("validationCodes", JSONArray(c.validationCodes)).put("snapshotId", c.snapshotId)
            .put("testLevel", c.testLevel).put("chatId", c.chatId)) }
        check(storage.write(KEY, a.toString())) {
            "Nie udało się trwale zapisać rejestru zmian."
        }
    }

    private fun strings(a: JSONArray?): List<String> = if (a == null) emptyList() else
        (0 until a.length()).map { index ->
            a.getString(index).also { require(it.isNotBlank()) { "LEDGER_ARRAY_ENTRY_BLANK" } }
        }
}
