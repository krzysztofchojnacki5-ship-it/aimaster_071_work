package pl.aimasterpro.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StableIdentifierTest {
    @Test fun identifiersAreDeterministicAndUseEveryIdentityPart() {
        val longTask = "task-" + "x".repeat(300)
        val first = StableIdentifier.create("team", longTask, "provider-a")
        val repeated = StableIdentifier.create("team", longTask, "provider-a")
        val differentProvider = StableIdentifier.create("team", longTask, "provider-b")
        val differentSuffix = StableIdentifier.create("team", longTask + "-different", "provider-a")

        assertEquals(first, repeated)
        assertNotEquals(first, differentProvider)
        assertNotEquals(first, differentSuffix)
        assertTrue(first.length < 80)
    }

    @Test fun distinctPartsCannotAliasThroughAmbiguousConcatenation() {
        assertNotEquals(
            StableIdentifier.create("test", "ab", "c"),
            StableIdentifier.create("test", "a", "bc")
        )
    }
}
