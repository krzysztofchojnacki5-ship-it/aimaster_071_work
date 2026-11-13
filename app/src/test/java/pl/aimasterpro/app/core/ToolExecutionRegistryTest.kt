package pl.aimasterpro.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ToolExecutionRegistryTest {
    private class MemoryBackend(var allowWrite: Boolean = true) : VerificationEvidenceBackend {
        var value: String? = null
        override fun read(): String? = value
        override fun write(value: String): Boolean {
            if (!allowWrite) return false
            this.value = value
            return true
        }
    }

    private fun registry(store: VerificationEvidenceStore, ids: AtomicInteger = AtomicInteger()) =
        ToolExecutionRegistry(
            sink = ToolExecutionRegistry.EvidenceSink(store::appendAll),
            clock = { 100L + ids.get().toLong() },
            executionIdFactory = { "host-run-${ids.incrementAndGet()}" }
        )

    @Test fun beginPersistsNotRunBeforeReturningHostGeneratedHandle() {
        val store = VerificationEvidenceStore(MemoryBackend())
        val registry = registry(store)
        val handle = registry.begin("task-1", "chat-1", "snapshot-1", VerificationEvidenceKind.UNIT_TESTS, "unit-tests")
        val rows = store.forScope("task-1", "chat-1", "snapshot-1").entries
        assertEquals(1, rows.size)
        assertEquals(VerificationEvidenceStatus.NOT_RUN, rows.single().status)
        assertEquals(VerificationEvidenceOrigin.NOT_RUN, rows.single().origin)
        assertEquals(handle.executionId, rows.single().executionId)
        assertEquals(1, registry.pendingCount())
        assertTrue(handle.executionId.startsWith("host-run-"))
    }

    @Test fun completedPassUsesHostIdAndReplacesPendingNotRunInAssessment() {
        val store = VerificationEvidenceStore(MemoryBackend())
        val registry = registry(store)
        val handle = registry.begin("task-1", "chat-1", "snapshot-1", VerificationEvidenceKind.UNIT_TESTS, "unit-tests")
        val result = registry.complete(handle, VerificationEvidenceStatus.PASS, "8 testów zakończonych powodzeniem")
        assertEquals(ToolExecutionRegistry.CompletionStatus.PERSISTED, result.status)
        val completed = result.evidence!!
        assertEquals("host-run-1", completed.executionId)
        assertEquals(VerificationEvidenceOrigin.TOOL_EXECUTION, completed.origin)
        assertEquals(VerificationEvidenceStatus.PASS, completed.status)
        assertEquals(VerificationAssessmentStatus.PASS,
            VerificationAssessment.assess(store.forScope("task-1", "chat-1", "snapshot-1").entries,
                setOf(VerificationEvidenceKind.UNIT_TESTS)).status)
        assertEquals(0, registry.pendingCount())
    }

    @Test fun foreignRegistryCannotCompleteAnotherRegistrysHandle() {
        val store = VerificationEvidenceStore(MemoryBackend())
        val first = registry(store)
        val second = registry(store)
        val handle = first.begin("task-1", "chat-1", "snapshot-1", VerificationEvidenceKind.UNIT_TESTS, "unit-tests")
        assertEquals(ToolExecutionRegistry.CompletionStatus.INVALID_HANDLE,
            second.complete(handle, VerificationEvidenceStatus.PASS, "forged" ).status)
        assertEquals(1, first.pendingCount())
    }

    @Test fun handleCanBeCompletedOnlyOnce() {
        val store = VerificationEvidenceStore(MemoryBackend())
        val registry = registry(store)
        val handle = registry.begin("task-1", "chat-1", "snapshot-1", VerificationEvidenceKind.UNIT_TESTS, "unit-tests")
        assertEquals(ToolExecutionRegistry.CompletionStatus.PERSISTED,
            registry.complete(handle, VerificationEvidenceStatus.PASS, "ok").status)
        assertEquals(ToolExecutionRegistry.CompletionStatus.INVALID_HANDLE,
            registry.complete(handle, VerificationEvidenceStatus.FAIL, "second result").status)
        assertEquals(2, store.forScope("task-1", "chat-1", "snapshot-1").entries.size)
    }

    @Test fun failedStartPersistencePreventsInvocationHandleFromBeingIssued() {
        val backend = MemoryBackend(allowWrite = false)
        val store = VerificationEvidenceStore(backend)
        val registry = registry(store)
        assertFalse(runCatching {
            registry.begin("task-1", "chat-1", "snapshot-1", VerificationEvidenceKind.UNIT_TESTS, "unit-tests")
        }.isSuccess)
        assertEquals(0, registry.pendingCount())
    }

    @Test fun failedCompletionPersistenceLeavesDurableNotRunMarker() {
        val backend = MemoryBackend()
        val store = VerificationEvidenceStore(backend)
        val registry = registry(store)
        val handle = registry.begin("task-1", "chat-1", "snapshot-1", VerificationEvidenceKind.UNIT_TESTS, "unit-tests")
        backend.allowWrite = false
        val result = registry.complete(handle, VerificationEvidenceStatus.PASS, "tool reported success")
        assertEquals(ToolExecutionRegistry.CompletionStatus.LEDGER_WRITE_FAILED, result.status)
        assertNull(result.evidence)
        backend.allowWrite = true
        val rows = store.forScope("task-1", "chat-1", "snapshot-1").entries
        assertEquals(1, rows.size)
        assertEquals(VerificationEvidenceStatus.NOT_RUN, rows.single().status)
    }

    @Test fun abandonPersistsNotRunAndNeverCreatesPass() {
        val store = VerificationEvidenceStore(MemoryBackend())
        val registry = registry(store)
        val handle = registry.begin("task-1", "chat-1", "snapshot-1", VerificationEvidenceKind.UNIT_TESTS, "unit-tests")
        assertTrue(registry.abandon(handle, "przerwano przez anulowanie"))
        val rows = store.forScope("task-1", "chat-1", "snapshot-1").entries
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.status == VerificationEvidenceStatus.NOT_RUN })
        assertEquals(VerificationAssessmentStatus.INCOMPLETE,
            VerificationAssessment.assess(rows, setOf(VerificationEvidenceKind.UNIT_TESTS)).status)
    }

    @Test fun concurrentCompleteAndAbandonHaveExactlyOneWinner() {
        val store = VerificationEvidenceStore(MemoryBackend())
        val registry = registry(store)
        val handle = registry.begin("task-1", "chat-1", "snapshot-1", VerificationEvidenceKind.UNIT_TESTS, "unit-tests")
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val complete = pool.submit<ToolExecutionRegistry.CompletionResult> {
                check(start.await(2, TimeUnit.SECONDS))
                registry.complete(handle, VerificationEvidenceStatus.PASS, "ok")
            }
            val abandon = pool.submit<Boolean> {
                check(start.await(2, TimeUnit.SECONDS))
                registry.abandon(handle, "cancel")
            }
            start.countDown()
            val completed = complete.get(5, TimeUnit.SECONDS)
            val abandoned = abandon.get(5, TimeUnit.SECONDS)
            val wins = (if (completed.status == ToolExecutionRegistry.CompletionStatus.PERSISTED) 1 else 0) +
                (if (abandoned) 1 else 0)
            assertEquals(1, wins)
            assertEquals(0, registry.pendingCount())
            assertEquals(2, store.forScope("task-1", "chat-1", "snapshot-1").entries.size)
        } finally {
            pool.shutdownNow()
        }
    }
}
