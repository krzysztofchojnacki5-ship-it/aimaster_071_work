package pl.aimasterpro.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class RawSnapshotCacheTest {
    @Test fun unchangedRawSnapshotIsParsedOnlyOnce() {
        val cache = RawSnapshotCache<List<String>>()
        var parses = 0
        val first = cache.get("""[{"id":"one"}]""") {
            parses++
            listOf("one")
        }
        val second = cache.get("""[{"id":"one"}]""") {
            parses++
            listOf("unexpected")
        }

        assertSame(first, second)
        assertEquals(listOf("one"), second)
        assertEquals(1, parses)
        assertEquals(1, cache.buildCountForTests())
    }

    @Test fun changedRawSnapshotRebuildsTheParsedValue() {
        val cache = RawSnapshotCache<List<String>>()
        var parses = 0
        val first = cache.get("""[{"id":"one"}]""") {
            parses++
            listOf("one")
        }
        val second = cache.get("""[{"id":"two"}]""") {
            parses++
            listOf("two")
        }

        assertEquals(listOf("one"), first)
        assertEquals(listOf("two"), second)
        assertEquals(2, parses)
        assertEquals(2, cache.buildCountForTests())
    }

    @Test fun failedParseDoesNotPoisonCacheOrAdvanceSnapshotKey() {
        val cache = RawSnapshotCache<List<String>>()
        cache.get("valid") { listOf("valid") }
        try {
            cache.get("broken") { error("invalid JSON") }
        } catch (_: IllegalStateException) {
            // Expected: the last successful snapshot remains intact.
        }

        assertEquals(listOf("valid"), cache.get("valid") { error("must reuse valid snapshot") })
        assertEquals(1, cache.buildCountForTests())
    }
}
