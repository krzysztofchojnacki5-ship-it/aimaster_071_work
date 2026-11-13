package pl.aimasterpro.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class EvidenceTrackedToolAdapterTest {
    private class MemoryBackend(var allowWrite: Boolean = true) : VerificationEvidenceBackend {
        var value: String? = null
        override fun read(): String? = value
        override fun write(value: String): Boolean {
            if (!allowWrite) return false
            this.value = value
            return true
        }
    }

    private fun setup(backend: MemoryBackend = MemoryBackend()): Pair<EvidenceTrackedToolAdapter, VerificationEvidenceStore> {
        val store = VerificationEvidenceStore(backend)
        val ids = AtomicInteger()
        val registry = ToolExecutionRegistry(
            sink = ToolExecutionRegistry.EvidenceSink(store::appendAll),
            clock = { 100L + ids.get() },
            executionIdFactory = { "host-adapter-${ids.incrementAndGet()}" }
        )
        return EvidenceTrackedToolAdapter(registry) to store
    }

    @Test fun operationIsNotInvokedWhenNotRunCannotBePersisted() {
        val backend = MemoryBackend(allowWrite = false)
        val (adapter, _) = setup(backend)
        var invoked = false
        val result = runCatching {
            adapter.execute("task", "chat", "snapshot", VerificationEvidenceKind.OTHER, "workspace.verify") {
                invoked = true
                "done"
            }
        }
        assertTrue(result.isFailure)
        assertFalse(invoked)
    }

    @Test fun successfulHostOperationGetsHostGeneratedExecutionEvidence() {
        val (adapter, store) = setup()
        val result = adapter.execute("task", "chat", "snapshot", VerificationEvidenceKind.OTHER, "workspace.verify") { "NOT_RUN: project tests were not executed" }
        assertEquals("NOT_RUN: project tests were not executed", result.value)
        assertNull(result.failure)
        assertEquals(ToolExecutionRegistry.CompletionStatus.PERSISTED, result.completion.status)
        assertEquals("host-adapter-1", result.executionId)
        val rows = store.forScope("task", "chat", "snapshot").entries
        assertEquals(2, rows.size)
        val pending = rows.single { it.status == VerificationEvidenceStatus.NOT_RUN }
        val completed = rows.single { it.status == VerificationEvidenceStatus.PASS }
        assertEquals(VerificationEvidenceOrigin.NOT_RUN, pending.origin)
        assertTrue(completed.summary.contains("nie wyniku testów projektu"))
        assertEquals(VerificationEvidenceOrigin.TOOL_EXECUTION, completed.origin)
        assertEquals(VerificationAssessmentStatus.INCOMPLETE,
            VerificationAssessment.assess(rows, setOf(VerificationEvidenceKind.PROJECT_TESTS)).status)
    }

    @Test fun thrownOperationRecordsFailAndReturnsOriginalFailure() {
        val (adapter, store) = setup()
        val result = adapter.execute("task", "chat", "snapshot", VerificationEvidenceKind.OTHER, "workspace.verify") {
            throw IllegalStateException("workspace unavailable")
        }
        assertNull(result.value)
        assertNotNull(result.failure)
        assertEquals("workspace unavailable", result.failure?.message)
        assertEquals(ToolExecutionRegistry.CompletionStatus.PERSISTED, result.completion.status)
        val rows = store.forScope("task", "chat", "snapshot").entries
        assertEquals(2, rows.size)
        assertEquals(VerificationEvidenceStatus.NOT_RUN, rows.single { it.status == VerificationEvidenceStatus.NOT_RUN }.status)
        val failed = rows.single { it.status == VerificationEvidenceStatus.FAIL }
        assertEquals(VerificationEvidenceOrigin.TOOL_EXECUTION, failed.origin)
    }

    @Test fun cancellationKeepsNotRunInsteadOfClaimingFailure() {
        val (adapter, store) = setup()
        val result = adapter.execute("task", "chat", "snapshot", VerificationEvidenceKind.OTHER, "workspace.verify") {
            throw java.util.concurrent.CancellationException("cancelled")
        }
        assertNull(result.value)
        assertNotNull(result.failure)
        assertEquals(ToolExecutionRegistry.CompletionStatus.PERSISTED, result.completion.status)
        val rows = store.forScope("task", "chat", "snapshot").entries
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.status == VerificationEvidenceStatus.NOT_RUN })
    }

    @Test fun completionWriteFailureKeepsPendingMarkerAndDoesNotInventPass() {
        val backend = MemoryBackend()
        val (adapter, store) = setup(backend)
        // Flip the backend only after the initial NOT_RUN record is persisted.
        val result = adapter.execute("task", "chat", "snapshot", VerificationEvidenceKind.OTHER, "workspace.verify") {
            backend.allowWrite = false
            "completed"
        }
        assertEquals("completed", result.value)
        assertEquals(ToolExecutionRegistry.CompletionStatus.LEDGER_WRITE_FAILED, result.completion.status)
        backend.allowWrite = true
        val rows = store.forScope("task", "chat", "snapshot").entries
        assertEquals(1, rows.size)
        assertEquals(VerificationEvidenceStatus.NOT_RUN, rows.single().status)
    }
}
