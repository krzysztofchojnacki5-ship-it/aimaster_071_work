package pl.aimasterpro.app.core

import android.content.Context
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** Durable host authority for the one change set currently selected for evidence in a workspace/chat. */
object HostActiveChangeSetStore {
    private const val PREFS = "ai_host_active_change_set_v1"
    private const val KEY = "active"

    data class Active(
        val chatId: String,
        val workspaceIdentity: String,
        val changeSetId: String,
        val taskId: String,
        val snapshotId: String,
        val changeStatus: String,
        val selectedAt: Long
    )

    enum class ReadStatus { ACTIVE, NONE, INVALID }
    data class ReadResult(val status: ReadStatus, val active: Active? = null, val reason: String = "")

    /** A failed commit can leave the process-local SharedPreferences map changed. Quarantine that
     * exact record until a later confirmed write/removal; a process restart is reconciled through
     * the still-pending workspace patch receipt before evidence can be selected. */
    private val ambiguousRecords = ConcurrentHashMap.newKeySet<Active>()

    @Synchronized
    fun setActive(context: Context, chatId: String, workspaceIdentity: String, changeSetId: String,
        storage: StringPreferenceStore = AndroidStringPreferenceStore(context, PREFS),
        ledgerStorage: StringPreferenceStore = AndroidStringPreferenceStore(context, ChangeSetStore.PREFERENCES_NAME)): Boolean {
        require(chatId.isNotBlank() && workspaceIdentity.isNotBlank() && changeSetId.isNotBlank())
        if (SharedPreferencesWriteHealth.isUncertain(PREFS)) return false
        val ledger = ChangeSetStore.readResult(context, ledgerStorage)
        if (ledger.status != ChangeSetStore.LedgerStatus.VALID) return false
        val change = ledger.items.singleOrNull { it.id == changeSetId } ?: return false
        if (change.chatId != chatId || change.taskId.isBlank() || change.snapshotId.isBlank() ||
            change.status !in ELIGIBLE_STATUSES) return false
        val record = Active(chatId, workspaceIdentity, change.id, change.taskId, change.snapshotId, change.status, System.currentTimeMillis())
        val committed = runCatching { storage.write(KEY, encode(record).toString()) }.getOrDefault(false)
        if (committed) {
            ambiguousRecords.remove(quarantineKey(record))
        } else {
            ambiguousRecords.add(quarantineKey(record))
            // The failed write may have changed the in-memory value to either the attempted
            // record or a compensating prior record. Neither view is trusted until a confirmed
            // retry/removal; quarantine whichever record the adapter currently exposes.
            val visibleRecord = runCatching { storage.read(KEY) }.getOrNull()
                ?.let { runCatching { decode(JSONObject(it)) }.getOrNull() }
            if (visibleRecord != null) ambiguousRecords.add(quarantineKey(visibleRecord))
        }
        return committed
    }

    @Synchronized
    fun readActive(context: Context, chatId: String, workspaceIdentity: String,
        storage: StringPreferenceStore = AndroidStringPreferenceStore(context, PREFS),
        ledgerStorage: StringPreferenceStore = AndroidStringPreferenceStore(context, ChangeSetStore.PREFERENCES_NAME)): ReadResult {
        if (chatId.isBlank() || workspaceIdentity.isBlank()) return ReadResult(ReadStatus.INVALID, reason = "Brak chatId/workspaceIdentity")
        if (SharedPreferencesWriteHealth.isUncertain(PREFS)) {
            return ReadResult(ReadStatus.INVALID, reason = "Magazyn aktywnego zakresu ma niejednoznaczny wynik zapisu; wymagany restart procesu i uzgodnienie stanu.")
        }
        val rawResult = runCatching {
            storage.read(KEY)
        }
        if (rawResult.isFailure) return ReadResult(ReadStatus.INVALID, reason = "Nie można odczytać aktywnego zakresu")
        val raw = rawResult.getOrNull() ?: return ReadResult(ReadStatus.NONE)
        val record = runCatching { decode(JSONObject(raw)) }.getOrElse {
            return ReadResult(ReadStatus.INVALID, reason = "Uszkodzony zapis aktywnego zakresu")
        }
        if (quarantineKey(record) in ambiguousRecords) {
            return ReadResult(ReadStatus.INVALID, reason = "Zapis aktywnego zakresu ma niejednoznaczny wynik commit; wymagane ponowienie lub uzgodnienie receipt")
        }
        if (record.chatId != chatId || record.workspaceIdentity != workspaceIdentity) {
            val cleared = runCatching {
                storage.remove(KEY)
            }.getOrDefault(false)
            if (cleared) ambiguousRecords.remove(quarantineKey(record)) else ambiguousRecords.add(quarantineKey(record))
            return if (cleared) ReadResult(ReadStatus.NONE, reason = "Aktywny zakres unieważniono po zmianie czatu/workspace")
            else ReadResult(ReadStatus.INVALID, reason = "Nie udało się trwale unieważnić zakresu po zmianie czatu/workspace")
        }
        val ledger = ChangeSetStore.readResult(context, ledgerStorage)
        if (ledger.status != ChangeSetStore.LedgerStatus.VALID) {
            return ReadResult(ReadStatus.INVALID, reason = "Rejestr ChangeSet jest uszkodzony: ${ledger.error}")
        }
        val matches = ledger.items.filter { it.id == record.changeSetId }
        val change = matches.singleOrNull() ?: return invalidateInvalidPointer(
            storage, "ChangeSet nie istnieje lub nie jest jednoznaczny", record)
        if (change.chatId != record.chatId || change.taskId != record.taskId || change.snapshotId != record.snapshotId ||
            change.status != record.changeStatus || change.status !in ELIGIBLE_STATUSES) {
            return invalidateInvalidPointer(storage, "Aktywny zakres jest niezgodny z rejestrem zmian", record)
        }
        return ReadResult(ReadStatus.ACTIVE, record)
    }

    private fun invalidateInvalidPointer(storage: StringPreferenceStore, reason: String, record: Active? = null): ReadResult {
        val removed = runCatching { storage.remove(KEY) }.getOrDefault(false)
        if (record != null) {
            if (removed) ambiguousRecords.remove(quarantineKey(record)) else ambiguousRecords.add(quarantineKey(record))
        }
        return if (removed) ReadResult(ReadStatus.INVALID, reason = "$reason; nieaktualny wskaźnik unieważniono")
        else ReadResult(ReadStatus.INVALID, reason = "$reason; nie udało się trwale unieważnić wskaźnika")
    }

    @Synchronized
    fun invalidate(context: Context, changeSetId: String? = null,
        storage: StringPreferenceStore = AndroidStringPreferenceStore(context, PREFS)): Boolean {
        if (SharedPreferencesWriteHealth.isUncertain(PREFS)) return false
        val rawResult = runCatching { storage.read(KEY) }
        if (rawResult.isFailure) return false
        val raw = rawResult.getOrNull() ?: return true
        val record = runCatching { decode(JSONObject(raw)) }.getOrNull()
        if (changeSetId != null) {
            if (record == null) return runCatching { storage.remove(KEY) }.getOrDefault(false)
            if (record.changeSetId != changeSetId) return true
        }
        val removed = runCatching { storage.remove(KEY) }.getOrDefault(false)
        if (record != null) {
            if (removed) ambiguousRecords.remove(quarantineKey(record))
            else ambiguousRecords.add(quarantineKey(record))
        }
        return removed
    }

    private fun quarantineKey(record: Active): Active = record.copy(selectedAt = 0L)

    private fun encode(a: Active) = JSONObject().put("chatId", a.chatId)
        .put("workspaceIdentity", a.workspaceIdentity).put("changeSetId", a.changeSetId)
        .put("taskId", a.taskId).put("snapshotId", a.snapshotId).put("changeStatus", a.changeStatus)
        .put("selectedAt", a.selectedAt)

    private fun decode(o: JSONObject): Active {
        val result = Active(o.getString("chatId"), o.getString("workspaceIdentity"),
            o.getString("changeSetId"), o.getString("taskId"), o.getString("snapshotId"),
            o.getString("changeStatus"), o.getLong("selectedAt"))
        require(result.chatId.isNotBlank() && result.workspaceIdentity.isNotBlank() && result.changeSetId.isNotBlank() &&
            result.taskId.isNotBlank() && result.snapshotId.isNotBlank() && result.changeStatus in ELIGIBLE_STATUSES && result.selectedAt > 0)
        return result
    }

    private val ELIGIBLE_STATUSES = setOf("APPLIED_UNVERIFIED", "APPLIED")
}
