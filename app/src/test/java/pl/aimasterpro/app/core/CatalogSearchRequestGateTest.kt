package pl.aimasterpro.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogSearchRequestGateTest {
    @Test
    fun beginNormalizesQueryAndPreservesFilter() {
        val gate = CatalogSearchRequestGate()

        val request = gate.begin("  PROVIDER B  ", 2)

        assertEquals("provider b", request.query)
        assertEquals(2, request.filter)
        assertTrue(gate.isCurrent(request))
    }

    @Test
    fun newerRequestMakesPreviousRequestStale() {
        val gate = CatalogSearchRequestGate()

        val previous = gate.begin("provider", 0)
        val latest = gate.begin("provider b", 0)

        assertFalse(gate.isCurrent(previous))
        assertTrue(gate.isCurrent(latest))
    }

    @Test
    fun invalidationMakesPendingRequestStale() {
        val gate = CatalogSearchRequestGate()
        val request = gate.begin("model", 0)

        gate.invalidate()

        assertFalse(gate.isCurrent(request))
    }

    @Test
    fun changingOnlyFilterCreatesANewCurrentRequest() {
        val gate = CatalogSearchRequestGate()

        val all = gate.begin("model", 0)
        val freeOnly = gate.begin("model", 1)

        assertFalse(gate.isCurrent(all))
        assertTrue(gate.isCurrent(freeOnly))
        assertEquals("model", freeOnly.query)
        assertEquals(1, freeOnly.filter)
    }
}
