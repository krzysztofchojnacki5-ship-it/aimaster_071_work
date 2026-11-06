package pl.aimasterpro.app.core

import android.content.Context

private class FaultStore(initial: String? = null) : StringPreferenceStore {
    var value: String? = initial
    var failRead = false
    var throwRead = false
    var failWrite = false
    var mutateThenFailWrite = false
    var throwWrite = false
    var failRemove = false
    var throwRemove = false
    var writes = 0
    var removes = 0
    override fun read(key: String): String? { if (throwRead) error("read exception"); if (failRead) throw IllegalStateException("read failure"); return value }
    override fun write(key: String, value: String): Boolean { writes++; if (throwWrite) error("write exception"); if (mutateThenFailWrite) { this.value = value; return false }; if (failWrite) return false; this.value = value; return true }
    override fun remove(key: String): Boolean { removes++; if (throwRemove) error("remove exception"); if (failRemove) return false; value = null; return true }
}

private fun validation() = ChangeSafetyGuard.Validation(true, listOf("app/src/main/java/Sample.kt"), emptyList())
private fun seed(context: Context, ledger: FaultStore): ChangeSetStore.ChangeSet {
    val item = ChangeSetStore.propose(context, "task-1", "Fault matrix", listOf("app/src/main/java/Sample.kt"), validation(), "chat-1", ledger)
    check(ChangeSetStore.transition(context, item.id, "APPROVED", ledger))
    check(ChangeSetStore.recordSnapshot(context, item.id, "snapshot-1", ledger))
    check(ChangeSetStore.transition(context, item.id, "APPLIED_UNVERIFIED", ledger))
    return ChangeSetStore.readResult(context, ledger).items.single()
}

fun main() {
    val context = Context()
    run {
        val store = FaultStore("[]").apply { throwRead = true }
        val result = ChangeSetStore.readResult(context, store)
        check(result.status == ChangeSetStore.LedgerStatus.CORRUPT && result.items.isEmpty())
        check(runCatching { ChangeSetStore.transition(context, "missing", "REJECTED", store) }.isFailure)
        check(store.writes == 0)
        println("PASS storage read exception becomes CORRUPT and blocks writes")
    }
    run {
        val store = FaultStore("{broken")
        val result = ChangeSetStore.readResult(context, store)
        check(result.status == ChangeSetStore.LedgerStatus.CORRUPT && store.writes == 0)
        check(runCatching { ChangeSetStore.recordSnapshot(context, "cs", "snap", store) }.isFailure)
        println("PASS malformed JSON fails closed without overwriting source")
    }
    run {
        val store = FaultStore("[]")
        val item = ChangeSetStore.propose(context, "task", "summary", listOf("app/src/main/java/Sample.kt"), validation(), "chat", store)
        val before = store.value
        store.failWrite = true
        check(runCatching { ChangeSetStore.transition(context, item.id, "APPROVED", store) }.isFailure)
        check(store.value == before)
        println("PASS failed ledger commit leaves previous durable value unchanged")
    }
    run {
        val ledger = FaultStore("[]")
        val item = seed(context, ledger)
        val active = FaultStore().apply { failWrite = true }
        check(!HostActiveChangeSetStore.setActive(context, item.chatId, "workspace-1", item.id, active, ledger))
        check(active.value == null)
        active.failWrite = false
        check(HostActiveChangeSetStore.setActive(context, item.chatId, "workspace-1", item.id, active, ledger))
        check(HostActiveChangeSetStore.readActive(context, item.chatId, "workspace-1", active, ledger).status == HostActiveChangeSetStore.ReadStatus.ACTIVE)
        println("PASS active pointer commit failure is reported and retry succeeds")
    }
    run {
        val ledger = FaultStore("[]")
        val item = seed(context, ledger)
        val active = FaultStore()
        check(HostActiveChangeSetStore.setActive(context, item.chatId, "workspace-1", item.id, active, ledger))
        active.failRemove = true
        check(!HostActiveChangeSetStore.invalidate(context, item.id, active))
        val quarantined = HostActiveChangeSetStore.readActive(context, item.chatId, "workspace-1", active, ledger)
        check(quarantined.status == HostActiveChangeSetStore.ReadStatus.INVALID && quarantined.active == null)
        active.failRemove = false
        check(HostActiveChangeSetStore.invalidate(context, item.id, active))
        check(HostActiveChangeSetStore.readActive(context, item.chatId, "workspace-1", active, ledger).status == HostActiveChangeSetStore.ReadStatus.NONE)
        println("PASS failed active-scope invalidation quarantines pointer until retry clears it")
    }
    run {
        val ledger = FaultStore("[]")
        val item = seed(context, ledger)
        val active = FaultStore()
        check(HostActiveChangeSetStore.setActive(context, item.chatId, "workspace-1", item.id, active, ledger))
        ledger.throwRead = true
        check(HostActiveChangeSetStore.readActive(context, item.chatId, "workspace-1", active, ledger).status == HostActiveChangeSetStore.ReadStatus.INVALID)
        check(active.value != null)
        println("PASS ledger read exception invalidates evidence view without deleting durable pointer")
    }
    run {
        val store = FaultStore("[]").apply { throwWrite = true }
        val before = store.value
        check(runCatching { ChangeSetStore.propose(context, "task", "summary", listOf("app/src/main/java/Sample.kt"), validation(), "chat", store) }.isFailure)
        check(store.value == before)
        println("PASS thrown ledger commit is surfaced without changing prior value")
    }
    run {
        val ledger = FaultStore("[]")
        val item = seed(context, ledger)
        val active = FaultStore().apply { throwWrite = true }
        check(!HostActiveChangeSetStore.setActive(context, item.chatId, "workspace-1", item.id, active, ledger))
        check(active.value == null)
        println("PASS thrown active-pointer commit is converted to explicit failure")
    }
    run {
        val ledger = FaultStore("[]")
        val item = seed(context, ledger)
        val active = FaultStore()
        check(HostActiveChangeSetStore.setActive(context, item.chatId, "workspace-1", item.id, active, ledger))
        active.throwRemove = true
        check(!HostActiveChangeSetStore.invalidate(context, item.id, active))
        check(active.value != null)
        active.throwRemove = false
        check(HostActiveChangeSetStore.invalidate(context, item.id, active))
        println("PASS thrown pointer-removal failure remains retryable")
    }
    run {
        val ledger = FaultStore("{broken")
        val active = FaultStore()
        check(!HostActiveChangeSetStore.setActive(context, "chat-1", "workspace-1", "cs-1", active, ledger))
        check(active.value == null && active.writes == 0)
        println("PASS corrupt ledger cannot authorize a new active pointer")
    }
    run {
        val ledger = FaultStore("[]")
        val item = seed(context, ledger)
        val active = FaultStore()
        check(HostActiveChangeSetStore.setActive(context, item.chatId, "workspace-1", item.id, active, ledger))
        check(ChangeSetStore.transition(context, item.id, "ROLLED_BACK", ledger))
        val result = HostActiveChangeSetStore.readActive(context, item.chatId, "workspace-1", active, ledger)
        check(result.status == HostActiveChangeSetStore.ReadStatus.INVALID)
        check(active.value == null && active.removes == 1)
        println("PASS definitive ledger status mismatch durably invalidates stale active pointer")
    }
    run {
        val ledger = FaultStore("[]")
        val item = seed(context, ledger)
        val active = FaultStore()
        check(HostActiveChangeSetStore.setActive(context, item.chatId, "workspace-1", item.id, active, ledger))
        ledger.value = "[]"
        active.failRemove = true
        val result = HostActiveChangeSetStore.readActive(context, item.chatId, "workspace-1", active, ledger)
        check(result.status == HostActiveChangeSetStore.ReadStatus.INVALID)
        check(active.value != null && result.reason.contains("nie udało się trwale"))
        println("PASS stale pointer removal failure remains explicitly invalid and visible")
    }
    run {
        val ledger = FaultStore("[]")
        val item = seed(context, ledger)
        val active = FaultStore()
        check(HostActiveChangeSetStore.setActive(context, item.chatId, "workspace-1", item.id, active, ledger))
        ledger.throwRead = true
        check(HostActiveChangeSetStore.readActive(context, item.chatId, "workspace-1", active, ledger).status == HostActiveChangeSetStore.ReadStatus.INVALID)
        check(active.value != null && active.removes == 0)
        println("PASS transient ledger read failure does not destroy recoverable active pointer")
    }
    run {
        val ledger = FaultStore("[]")
        val item = seed(context, ledger)
        val active = FaultStore().apply { mutateThenFailWrite = true }
        check(!HostActiveChangeSetStore.setActive(context, item.chatId, "workspace-ambiguous", item.id, active, ledger))
        check(active.value != null) // Models SharedPreferences' process-local mutation before false.
        val quarantined = HostActiveChangeSetStore.readActive(context, item.chatId, "workspace-ambiguous", active, ledger)
        check(quarantined.status == HostActiveChangeSetStore.ReadStatus.INVALID)
        check(quarantined.active == null && quarantined.reason.contains("niejednoznaczny"))
        active.mutateThenFailWrite = false
        check(HostActiveChangeSetStore.setActive(context, item.chatId, "workspace-ambiguous", item.id, active, ledger))
        check(HostActiveChangeSetStore.readActive(context, item.chatId, "workspace-ambiguous", active, ledger).status == HostActiveChangeSetStore.ReadStatus.ACTIVE)
        println("PASS process-local pointer mutation after failed commit is quarantined until confirmed retry")
    }
    run {
        val contextWithPrefs = Context()
        val ledger = FaultStore("[]")
        val item = seed(context, ledger)
        val prefs = contextWithPrefs.getSharedPreferences("double-false-active", Context.MODE_PRIVATE) as android.content.MemorySharedPreferences
        val adapter = AndroidStringPreferenceStore(contextWithPrefs, "double-false-active")
        check(HostActiveChangeSetStore.setActive(context, item.chatId, "workspace-before-double-false", item.id, adapter, ledger))
        prefs.commitResults.addAll(listOf(false, false)) // replacement and compensation both fail
        check(!HostActiveChangeSetStore.setActive(context, item.chatId, "workspace-double-false", item.id, adapter, ledger))
        check(adapter.read("active") != null) // The compensating in-memory value may still be visible.
        val quarantined = HostActiveChangeSetStore.readActive(context, item.chatId, "workspace-before-double-false", adapter, ledger)
        check(quarantined.status == HostActiveChangeSetStore.ReadStatus.INVALID && quarantined.active == null)
        check(quarantined.reason.contains("niejednoznaczny"))
        println("PASS double-false SharedPreferences write remains quarantined despite residual in-memory pointer")
    }
    run {
        val contextWithPrefs = Context()
        val prefs = contextWithPrefs.getSharedPreferences("commit-false-write", Context.MODE_PRIVATE) as android.content.MemorySharedPreferences
        val adapter = AndroidStringPreferenceStore(contextWithPrefs, "commit-false-write")
        check(adapter.write("active", "old"))
        prefs.nextCommitResult = false
        check(!adapter.write("active", "new"))
        check(adapter.read("active") == "old")
        println("PASS ambiguous false write compensates process-local value to prior state")
    }
    run {
        val contextWithPrefs = Context()
        val prefs = contextWithPrefs.getSharedPreferences("commit-false-remove", Context.MODE_PRIVATE) as android.content.MemorySharedPreferences
        val adapter = AndroidStringPreferenceStore(contextWithPrefs, "commit-false-remove")
        check(adapter.write("active", "old"))
        prefs.nextCommitResult = false
        check(!adapter.remove("active"))
        check(adapter.read("active") == "old")
        println("PASS ambiguous false removal restores prior process-local pointer")
    }
    run {
        var activeReadCount = 0
        val blocked = HostActiveEvidenceStartupGate.resolve(
            HostActiveEvidenceStartupGate.WorkspaceTransactionState.PENDING
        ) {
            activeReadCount++
            error("active pointer must not be read while receipt is pending")
        }
        check(blocked.status == HostActiveChangeSetStore.ReadStatus.INVALID)
        check(blocked.active == null && activeReadCount == 0)
        println("PASS pending workspace receipt blocks active-pointer read lazily")
    }
    run {
        var activeReadCount = 0
        val blocked = HostActiveEvidenceStartupGate.resolve(
            HostActiveEvidenceStartupGate.WorkspaceTransactionState.UNKNOWN
        ) {
            activeReadCount++
            error("active pointer must not be read when transaction state is unknown")
        }
        check(blocked.status == HostActiveChangeSetStore.ReadStatus.INVALID)
        check(blocked.active == null && activeReadCount == 0)
        println("PASS unknown transaction state fails closed without active-pointer read")
    }
    run {
        var activeReadCount = 0
        val expected = HostActiveChangeSetStore.ReadResult(HostActiveChangeSetStore.ReadStatus.NONE)
        val resolved = HostActiveEvidenceStartupGate.resolve(
            HostActiveEvidenceStartupGate.WorkspaceTransactionState.CLEAR
        ) {
            activeReadCount++
            expected
        }
        check(resolved == expected && activeReadCount == 1)
        println("PASS clear transaction state permits exactly one active-pointer read")
    }
    run {
        val resolved = HostActiveEvidenceStartupGate.resolve(
            HostActiveEvidenceStartupGate.WorkspaceTransactionState.CLEAR
        ) { error("injected active-pointer read failure") }
        check(resolved.status == HostActiveChangeSetStore.ReadStatus.INVALID && resolved.active == null)
        println("PASS active-pointer read exception becomes INVALID")
    }
    println("CHANGESET_STORE_FAULT_MATRIX: 21/21 PASS")
}
