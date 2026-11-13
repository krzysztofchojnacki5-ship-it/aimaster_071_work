package pl.aimasterpro.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionPeerPinSetTest {
    private val current = CompanionSpkiSha256Pin.parse("a".repeat(64))
    private val next = CompanionSpkiSha256Pin.parse("b".repeat(64))
    private val unrelated = CompanionSpkiSha256Pin.parse("c".repeat(64))

    @Test fun acceptsCurrentAndStagedPinOnly() {
        val set = CompanionPeerPinSet(current).stage(next)
        assertTrue(set.accepts(current))
        assertTrue(set.accepts(next))
        assertFalse(set.accepts(unrelated))
    }

    @Test fun promotionDropsOldPinAndClearsStagedSlot() {
        val promoted = CompanionPeerPinSet(current).stage(next).promoteNext()
        assertEquals(next, promoted.current)
        assertEquals(null, promoted.next)
        assertFalse(promoted.accepts(current))
        assertTrue(promoted.accepts(next))
    }

    @Test fun cannotStageCurrentOrPromoteWithoutNext() {
        runCatching { CompanionPeerPinSet(current).stage(current) }
            .onSuccess { error("Staging the current pin must fail.") }
        runCatching { CompanionPeerPinSet(current).promoteNext() }
            .onSuccess { error("Promotion without a staged pin must fail.") }
    }

    @Test fun stagedPinCannotBeSilentlyReplaced() {
        val replacement = CompanionSpkiSha256Pin.parse("d".repeat(64))
        runCatching { CompanionPeerPinSet(current).stage(next).stage(replacement) }
            .onSuccess { error("An existing staged pin must not be silently replaced.") }
    }

    @Test fun stagedPinCanBeCancelledBeforeAReplacementIsStaged() {
        val replacement = CompanionSpkiSha256Pin.parse("d".repeat(64))
        val set = CompanionPeerPinSet(current).stage(next).cancelStaged().stage(replacement)
        assertFalse(set.accepts(next))
        assertTrue(set.accepts(replacement))
    }
}
