package pl.aimasterpro.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HostActiveEvidenceStartupGateTest {
    @Test fun pendingReceiptNeverReadsActivePointer() {
        var reads = 0
        val result = HostActiveEvidenceStartupGate.resolve(
            HostActiveEvidenceStartupGate.WorkspaceTransactionState.PENDING
        ) { reads++; error("must not read") }
        assertEquals(HostActiveChangeSetStore.ReadStatus.INVALID, result.status)
        assertNull(result.active)
        assertEquals(0, reads)
    }

    @Test fun unknownReceiptStateNeverReadsActivePointer() {
        var reads = 0
        val result = HostActiveEvidenceStartupGate.resolve(
            HostActiveEvidenceStartupGate.WorkspaceTransactionState.UNKNOWN
        ) { reads++; error("must not read") }
        assertEquals(HostActiveChangeSetStore.ReadStatus.INVALID, result.status)
        assertNull(result.active)
        assertEquals(0, reads)
    }

    @Test fun clearReceiptStateReadsActivePointerOnce() {
        var reads = 0
        val expected = HostActiveChangeSetStore.ReadResult(HostActiveChangeSetStore.ReadStatus.NONE)
        val result = HostActiveEvidenceStartupGate.resolve(
            HostActiveEvidenceStartupGate.WorkspaceTransactionState.CLEAR
        ) { reads++; expected }
        assertSame(expected, result)
        assertEquals(1, reads)
    }

    @Test fun activeReadFailureFailsClosed() {
        val result = HostActiveEvidenceStartupGate.resolve(
            HostActiveEvidenceStartupGate.WorkspaceTransactionState.CLEAR
        ) { error("injected") }
        assertEquals(HostActiveChangeSetStore.ReadStatus.INVALID, result.status)
        assertTrue(result.reason.isNotBlank())
        assertNull(result.active)
    }
}
