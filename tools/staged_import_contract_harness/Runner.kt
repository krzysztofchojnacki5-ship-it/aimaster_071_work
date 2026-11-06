import java.util.UUID

private fun expect(value: Boolean, message: String) { check(value) { message } }

/** Executable contract model only; it does not call Android or ProjectWorkspaceStore. */
private class Protocol {
    data class Prepared(val id: String, val owner: String, val manifest: String, val treeDigest: String, val createdAt: Long)
    private val prepared = linkedMapOf<String, Prepared>()
    private val consumed = mutableSetOf<String>()
    var currentIdentity: String? = "workspace-A"
    var snapshotCount = 0
    var replaceCount = 0

    fun prepare(owner: String, manifest: String, digest: String, now: Long): Prepared {
        require(owner.isNotBlank() && manifest.isNotBlank() && digest.matches(Regex("[0-9a-f]{64}")))
        val p = Prepared(UUID.randomUUID().toString(), owner, manifest, digest, now)
        prepared[p.id] = p
        return p
    }
    fun commit(p: Prepared, caller: String, verifiedManifest: String, verifiedDigest: String): Boolean {
        require(caller == p.owner) { "wrong staging owner" }
        require(p.id !in consumed) { "replay" }
        require(prepared[p.id] == p) { "unknown or altered token" }
        require(verifiedManifest == p.manifest && verifiedDigest == p.treeDigest) { "staging integrity mismatch" }
        // The current workspace is deliberately read at commit time, not captured by prepare().
        val commitBase = currentIdentity
        if (commitBase != null) snapshotCount++
        replaceCount++
        currentIdentity = "import-${p.id}"
        consumed += p.id
        prepared.remove(p.id)
        return true
    }
    fun abort(p: Prepared): Boolean = prepared.remove(p.id) != null
    fun cleanupOrphans(olderThan: Long): Int {
        val old = prepared.values.filter { it.createdAt < olderThan }.map { it.id }
        old.forEach { prepared.remove(it) }
        return old.size
    }
    fun pending() = prepared.size
}


/** Durable journal/restart model. This is not production filesystem recovery. */
private class DurableLifecycle {
    enum class Stage { PREPARED, COMMITTING, SWAPPED_AWAITING_EVIDENCE, COMMITTED, ABORTED }
    enum class Swap { NONE, OLD_CURRENT, NEW_CURRENT }
    data class Record(var stage: Stage, var swap: Swap = Swap.NONE, var evidenceInvalidated: Boolean = false)
    private val records = linkedMapOf<String, Record>()
    var cleanupCount = 0
        private set
    var evidenceInvalidationCount = 0
        private set
    fun create(id: String) { check(id !in records); records[id] = Record(Stage.PREPARED) }
    fun beginCommit(id: String) { records.getValue(id).also { check(it.stage == Stage.PREPARED); it.stage = Stage.COMMITTING } }
    fun recordSwap(id: String, outcome: Swap) { require(outcome != Swap.NONE); records.getValue(id).also { check(it.stage == Stage.COMMITTING); it.swap = outcome } }
    fun markSwapped(id: String) { records.getValue(id).also { check(it.stage == Stage.COMMITTING && it.swap == Swap.NEW_CURRENT); it.stage = Stage.SWAPPED_AWAITING_EVIDENCE } }
    fun invalidateEvidence(id: String) { records.getValue(id).also { check(it.stage == Stage.SWAPPED_AWAITING_EVIDENCE); if (!it.evidenceInvalidated) { it.evidenceInvalidated = true; evidenceInvalidationCount++ } } }
    fun finish(id: String) { records.getValue(id).also { check(it.stage == Stage.SWAPPED_AWAITING_EVIDENCE && it.evidenceInvalidated); it.stage = Stage.COMMITTED; cleanupCount++ } }
    fun abort(id: String) { records.getValue(id).also { check(it.stage == Stage.PREPARED); it.stage = Stage.ABORTED; cleanupCount++ } }
    fun record(id: String) = records.getValue(id).copy()
    fun recover(id: String) {
        val r = records.getValue(id)
        when (r.stage) {
            Stage.PREPARED -> { r.stage = Stage.ABORTED; cleanupCount++ }
            Stage.COMMITTING -> when (r.swap) {
                Swap.NONE, Swap.OLD_CURRENT -> { r.stage = Stage.ABORTED; cleanupCount++ }
                Swap.NEW_CURRENT -> r.stage = Stage.SWAPPED_AWAITING_EVIDENCE
            }
            Stage.SWAPPED_AWAITING_EVIDENCE -> { if (!r.evidenceInvalidated) invalidateEvidence(id); finish(id) }
            Stage.COMMITTED, Stage.ABORTED -> Unit
        }
    }
}

fun main() {
    var passed = 0
    fun test(name: String, block: () -> Unit) { block(); passed++; println("PASS $name") }

    test("prepare is side-effect-free for current workspace") {
        val p = Protocol(); val before = p.currentIdentity
        p.prepare("activity-1", "manifest-1", "a".repeat(64), 10)
        expect(p.currentIdentity == before && p.snapshotCount == 0 && p.replaceCount == 0, "prepare mutated live workspace")
    }
    test("commit validates token owner") {
        val p = Protocol(); val t = p.prepare("owner", "m", "b".repeat(64), 1)
        expect(runCatching { p.commit(t, "attacker", "m", "b".repeat(64)) }.isFailure, "foreign owner accepted")
        expect(p.currentIdentity == "workspace-A", "failed owner check mutated workspace")
    }
    test("commit rejects altered token") {
        val p = Protocol(); val t = p.prepare("owner", "m", "c".repeat(64), 1)
        val forged = t.copy(manifest = "other")
        expect(runCatching { p.commit(forged, "owner", "other", "c".repeat(64)) }.isFailure, "altered token accepted")
    }
    test("commit rejects staged-tree digest mismatch") {
        val p = Protocol(); val t = p.prepare("owner", "m", "d".repeat(64), 1)
        expect(runCatching { p.commit(t, "owner", "m", "e".repeat(64)) }.isFailure, "digest mismatch accepted")
        expect(p.replaceCount == 0, "mismatch reached replace")
    }
    test("successful commit consumes one-use token") {
        val p = Protocol(); val t = p.prepare("owner", "m", "f".repeat(64), 1)
        expect(p.commit(t, "owner", "m", "f".repeat(64)), "commit failed")
        expect(p.pending() == 0 && p.replaceCount == 1, "token not consumed exactly once")
    }
    test("replay is rejected") {
        val p = Protocol(); val t = p.prepare("owner", "m", "1".repeat(64), 1)
        p.commit(t, "owner", "m", "1".repeat(64))
        expect(runCatching { p.commit(t, "owner", "m", "1".repeat(64)) }.isFailure, "replay accepted")
        expect(p.replaceCount == 1, "replay replaced workspace twice")
    }
    test("abort removes prepared staging") {
        val p = Protocol(); val t = p.prepare("owner", "m", "2".repeat(64), 1)
        expect(p.abort(t) && p.pending() == 0, "abort did not remove token")
    }
    test("aborted token cannot commit") {
        val p = Protocol(); val t = p.prepare("owner", "m", "3".repeat(64), 1); p.abort(t)
        expect(runCatching { p.commit(t, "owner", "m", "3".repeat(64)) }.isFailure, "aborted token committed")
    }
    test("orphan cleanup removes only expired preparations") {
        val p = Protocol(); p.prepare("a", "m1", "4".repeat(64), 10); p.prepare("b", "m2", "5".repeat(64), 30)
        expect(p.cleanupOrphans(20) == 1 && p.pending() == 1, "cleanup removed wrong staging set")
    }
    test("workspace mutation during prepare uses latest commit base") {
        val p = Protocol(); val t = p.prepare("owner", "m", "6".repeat(64), 1)
        p.currentIdentity = "workspace-changed-by-patch"
        p.commit(t, "owner", "m", "6".repeat(64))
        expect(p.snapshotCount == 1 && p.currentIdentity == "import-${t.id}", "commit did not use latest current state")
    }
    test("failed validation leaves current untouched") {
        val p = Protocol(); val t = p.prepare("owner", "m", "7".repeat(64), 1); val before = p.currentIdentity
        runCatching { p.commit(t, "owner", "wrong", "7".repeat(64)) }
        expect(p.currentIdentity == before && p.snapshotCount == 0 && p.replaceCount == 0, "validation failure mutated live state")
    }
    test("independent preparations do not share tokens") {
        val p = Protocol(); val a = p.prepare("a", "ma", "8".repeat(64), 1); val b = p.prepare("b", "mb", "9".repeat(64), 1)
        p.commit(a, "a", "ma", "8".repeat(64))
        expect(p.pending() == 1 && runCatching { p.commit(b, "a", "mb", "9".repeat(64)) }.isFailure, "preparations shared ownership")
    }
    test("restart cleans PREPARED orphan without touching committed workspace") {
        val j = DurableLifecycle(); j.create("orphan"); j.recover("orphan")
        expect(j.record("orphan").stage == DurableLifecycle.Stage.ABORTED && j.cleanupCount == 1, "prepared orphan not cleaned")
    }
    test("restart resolves COMMITTING with no swap as abort") {
        val j = DurableLifecycle(); j.create("x"); j.beginCommit("x"); j.recover("x")
        expect(j.record("x").stage == DurableLifecycle.Stage.ABORTED, "no-swap commit not aborted")
    }
    test("restart resolves old-current journal outcome as abort") {
        val j = DurableLifecycle(); j.create("x"); j.beginCommit("x"); j.recordSwap("x", DurableLifecycle.Swap.OLD_CURRENT); j.recover("x")
        expect(j.record("x").stage == DurableLifecycle.Stage.ABORTED, "old current outcome not aborted")
    }
    test("restart recognizes new-current swap and defers evidence cleanup") {
        val j = DurableLifecycle(); j.create("x"); j.beginCommit("x"); j.recordSwap("x", DurableLifecycle.Swap.NEW_CURRENT); j.recover("x")
        expect(j.record("x").stage == DurableLifecycle.Stage.SWAPPED_AWAITING_EVIDENCE && j.evidenceInvalidationCount == 0, "swap state misclassified")
    }
    test("restart invalidates evidence before marking committed") {
        val j = DurableLifecycle(); j.create("x"); j.beginCommit("x"); j.recordSwap("x", DurableLifecycle.Swap.NEW_CURRENT); j.markSwapped("x"); j.recover("x")
        expect(j.record("x").stage == DurableLifecycle.Stage.COMMITTED && j.record("x").evidenceInvalidated, "evidence not reconciled")
    }
    test("recovery is idempotent after committed") {
        val j = DurableLifecycle(); j.create("x"); j.beginCommit("x"); j.recordSwap("x", DurableLifecycle.Swap.NEW_CURRENT); j.markSwapped("x"); j.recover("x"); j.recover("x")
        expect(j.evidenceInvalidationCount == 1 && j.cleanupCount == 1, "recovery repeated side effects")
    }
    test("evidence invalidation is idempotent before finish") {
        val j = DurableLifecycle(); j.create("x"); j.beginCommit("x"); j.recordSwap("x", DurableLifecycle.Swap.NEW_CURRENT); j.markSwapped("x"); j.invalidateEvidence("x"); j.invalidateEvidence("x"); j.finish("x")
        expect(j.evidenceInvalidationCount == 1 && j.record("x").stage == DurableLifecycle.Stage.COMMITTED, "duplicate invalidation")
    }
    test("committed and aborted journal records remain terminal") {
        val j = DurableLifecycle(); j.create("a"); j.abort("a"); j.recover("a")
        j.create("b"); j.beginCommit("b"); j.recordSwap("b", DurableLifecycle.Swap.NEW_CURRENT); j.markSwapped("b"); j.invalidateEvidence("b"); j.finish("b"); j.recover("b")
        expect(j.record("a").stage == DurableLifecycle.Stage.ABORTED && j.record("b").stage == DurableLifecycle.Stage.COMMITTED, "terminal state regressed")
    }
    println("STAGED_IMPORT_CONTRACT: $passed/20 PASS (contract + durable lifecycle model; production integration NOT RUN)")
}
