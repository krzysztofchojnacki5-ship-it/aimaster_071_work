package pl.aimasterpro.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VerificationEvidenceStoreTest {
    private class MemoryBackend(var value: String? = null, var allowWrite: Boolean = true) : VerificationEvidenceBackend {
        override fun read(): String? = value
        override fun write(value: String): Boolean {
            if (!allowWrite) return false
            this.value = value
            return true
        }
    }

    private fun pass(id: String, task: String, time: Long) = VerificationEvidence.toolExecution(
        task, VerificationEvidenceKind.UNIT_TESTS, VerificationEvidenceStatus.PASS,
        "Testy przeszły ✓", "run-$id", time
    ).copy(id = id)

    @Test fun roundTripPreservesUnicodeAndAllEvidenceFields() {
        val backend = MemoryBackend()
        val store = VerificationEvidenceStore(backend)
        val expected = pass("evidence-1", "task-1", 100)
        assertTrue(store.appendAll(listOf(expected)))
        val loaded = store.load()
        assertTrue(loaded.valid)
        assertEquals(listOf(expected), loaded.entries)
    }

    @Test fun appendIsIdempotentAndConflictingDuplicateIsRejected() {
        val backend = MemoryBackend()
        val store = VerificationEvidenceStore(backend)
        val original = pass("same-id", "task-1", 100)
        assertTrue(store.appendAll(listOf(original)))
        assertTrue(store.appendAll(listOf(original)))
        assertEquals(1, store.load().entries.size)
        assertFalse(store.appendAll(listOf(original.copy(summary = "different content"))))
        assertEquals(listOf(original), store.load().entries)
    }

    @Test fun corruptLedgerFailsClosedAndIsNotOverwritten() {
        val backend = MemoryBackend("CORRUPT\nnot-a-record")
        val store = VerificationEvidenceStore(backend)
        val before = backend.value
        val loaded = store.load()
        assertFalse(loaded.valid)
        assertEquals("UNKNOWN_FORMAT", loaded.errorCode)
        assertFalse(store.appendAll(listOf(pass("e1", "task-1", 1))))
        assertEquals(before, backend.value)
    }

    @Test fun emptyStoredValueIsTreatedAsCorruptionNotAsFreshLedger() {
        val backend = MemoryBackend("")
        val store = VerificationEvidenceStore(backend)
        assertFalse(store.load().valid)
        assertEquals("EMPTY_LEDGER", store.load().errorCode)
        assertFalse(store.appendAll(listOf(pass("e1", "task-1", 1))))
        assertEquals("", backend.value)
    }

    @Test fun taskFilteringAndRemovalDoNotLeakOtherTasks() {
        val store = VerificationEvidenceStore(MemoryBackend())
        val a = pass("a", "task-a", 10)
        val b = pass("b", "task-b", 20)
        assertTrue(store.appendAll(listOf(a, b)))
        assertEquals(listOf(a), store.forTask("task-a").entries)
        assertTrue(store.removeTask("task-a"))
        assertEquals(listOf(b), store.load().entries)
    }

    @Test fun storageFailureIsReportedInsteadOfClaimingPersistence() {
        val store = VerificationEvidenceStore(MemoryBackend(allowWrite = false))
        assertFalse(store.appendAll(listOf(pass("e1", "task-1", 1))))
        assertTrue(store.load().entries.isEmpty())
    }

    @Test fun concurrentWritersAcrossStoreInstancesDoNotLoseRecords() {
        val backend = MemoryBackend()
        val stores = (0 until 4).map { VerificationEvidenceStore(backend) }
        val workers = java.util.concurrent.Executors.newFixedThreadPool(8)
        val start = java.util.concurrent.CountDownLatch(1)
        val futures = (0 until 64).map { index ->
            workers.submit<Boolean> {
                check(start.await(2, java.util.concurrent.TimeUnit.SECONDS))
                stores[index % stores.size].appendAll(listOf(pass("parallel-$index", "task-$index", index.toLong())))
            }
        }
        start.countDown()
        try {
            assertTrue(futures.all { it.get(5, java.util.concurrent.TimeUnit.SECONDS) })
            assertEquals(64, stores.first().load().entries.size)
        } finally {
            workers.shutdownNow()
        }
    }

    @Test fun exactScopeLookupNeverLeaksEvidenceFromAnotherChatOrSnapshot() {
        val store = VerificationEvidenceStore(MemoryBackend())
        val wanted = VerificationEvidence.runtimeCheck(
            "task-1", VerificationEvidenceKind.SNAPSHOT_INTEGRITY, VerificationEvidenceStatus.PASS,
            "snapshot ok", 10, chatId = "chat-a", snapshotId = "snapshot-a"
        )
        val otherChat = wanted.copy(id = "other-chat", chatId = "chat-b", snapshotId = "snapshot-b")
        val otherSnapshot = wanted.copy(id = "other-snapshot", snapshotId = "snapshot-b")
        assertTrue(store.appendAll(listOf(wanted, otherChat, otherSnapshot)))
        assertEquals(listOf(wanted), store.forScope("task-1", "chat-a", "snapshot-a").entries)
        assertTrue(store.forScope("task-1", "chat-b", "snapshot-a").entries.isEmpty())
        assertEquals("SCOPE_ID_EMPTY", store.forScope("task-1", "", "snapshot-a").errorCode)
    }

    @Test fun legacyV1LedgerLoadsAsUnscopedAndMigratesWithoutLosingRecords() {
        fun field(value: String): String = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(value.toByteArray(Charsets.UTF_8))
        val values = listOf("legacy-id", "legacy-task", "UNIT_TESTS", "PASS", "TOOL_EXECUTION", "legacy pass", "10", "legacy-run")
        val v1 = "AIMASTER_VERIFICATION_EVIDENCE_V1\n" + values.joinToString(".", transform = ::field)
        val backend = MemoryBackend(v1)
        val store = VerificationEvidenceStore(backend)
        val loaded = store.load()
        assertTrue(loaded.valid)
        assertEquals("", loaded.entries.single().chatId)
        assertEquals("", loaded.entries.single().snapshotId)
        val scoped = VerificationEvidence.runtimeCheck("new-task", VerificationEvidenceKind.WORKSPACE_INTEGRITY,
            VerificationEvidenceStatus.PASS, "new pass", 20, chatId = "chat-new", snapshotId = "snapshot-new")
        assertTrue(store.appendAll(listOf(scoped)))
        assertTrue(backend.value!!.startsWith("AIMASTER_VERIFICATION_EVIDENCE_V2\n"))
        assertEquals(2, store.load().entries.size)
        assertTrue(store.forScope("legacy-task", "chat-new", "snapshot-new").entries.isEmpty())
    }

    @Test fun ledgerIsBoundedAndRetainsNewestEvidence() {
        val store = VerificationEvidenceStore(MemoryBackend())
        val entries = (0..820).map { index -> pass("e$index", "task-$index", index.toLong()) }
        assertTrue(store.appendAll(entries))
        val loaded = store.load()
        assertTrue(loaded.valid)
        assertEquals(800, loaded.entries.size)
        assertEquals("e820", loaded.entries.first().id)
        assertFalse(loaded.entries.any { it.id == "e0" })
    }
    @Test fun concurrentWritersSerializeWholeBackendTransactionsAcrossStoreInstances() {
        class LockingBackend : VerificationEvidenceBackend {
            private var raw: String? = null
            private val lock = Any()
            override fun <T> withTransaction(operation: () -> T): T = synchronized(lock) { operation() }
            override fun read(): String? = raw
            override fun write(value: String): Boolean { raw = value; return true }
        }
        val backend = LockingBackend()
        val stores = (0 until 4).map { VerificationEvidenceStore(backend) }
        val workers = java.util.concurrent.Executors.newFixedThreadPool(8)
        val start = java.util.concurrent.CountDownLatch(1)
        val futures = (0 until 64).map { index ->
            workers.submit<Boolean> {
                check(start.await(2, java.util.concurrent.TimeUnit.SECONDS))
                stores[index % stores.size].appendAll(listOf(pass("tx-$index", "task-$index", index.toLong())))
            }
        }
        start.countDown()
        try {
            assertTrue(futures.all { it.get(5, java.util.concurrent.TimeUnit.SECONDS) })
            assertEquals(64, stores.first().load().entries.size)
        } finally {
            workers.shutdownNow()
        }
    }

}
