package pl.aimasterpro.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderCatalogSyncGenerationTest {
    @Test
    fun invalidatedSnapshotCannotCommit() {
        val gate = ProviderCatalogSyncGeneration()
        val snapshot = gate.snapshot()
        gate.invalidate()
        var writes = 0

        val committed = gate.commitIfCurrent(snapshot) { writes++ }

        assertFalse(committed)
        assertEquals(0, writes)
    }

    @Test
    fun currentSnapshotCanCommitExactlyOncePerInvocation() {
        val gate = ProviderCatalogSyncGeneration()
        val snapshot = gate.snapshot()
        var writes = 0

        val first = gate.commitIfCurrent(snapshot) { writes++ }
        val second = gate.commitIfCurrent(snapshot) { writes++ }

        assertTrue(first)
        assertTrue(second)
        assertEquals(2, writes)
    }

    @Test
    fun invalidationProducesANewCurrentSnapshot() {
        val gate = ProviderCatalogSyncGeneration()
        val oldSnapshot = gate.snapshot()
        val newSnapshot = gate.invalidate()

        assertFalse(gate.isCurrent(oldSnapshot))
        assertTrue(gate.isCurrent(newSnapshot))
    }
}
