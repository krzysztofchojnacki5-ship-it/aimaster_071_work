package pl.aimasterpro.app.core

import org.junit.Test

class CompanionPeerPinSetRotationTest {
    private val oldPin = CompanionSpkiSha256Pin.parse("0".repeat(64))
    private val nextPin = CompanionSpkiSha256Pin.parse("1".repeat(64))

    @Test fun stagingKeepsOldAndNewPinsValidDuringOverlap() {
        val staged = CompanionPeerPinSet(oldPin).stage(nextPin)
        check(staged.accepts(oldPin))
        check(staged.accepts(nextPin))
        check(!staged.accepts(CompanionSpkiSha256Pin.parse("2".repeat(64))))
    }

    @Test fun promotionMakesOldPinInvalidAndKeepsOnlyTheExplicitlyStagedPin() {
        val promoted = CompanionPeerPinSet(oldPin, nextPin).promoteNext()
        check(promoted.current == nextPin)
        check(promoted.next == null)
        check(promoted.accepts(nextPin))
        check(!promoted.accepts(oldPin))
    }

    @Test fun cancellingStagedRotationPreservesCurrentTrust() {
        val cancelled = CompanionPeerPinSet(oldPin, nextPin).cancelStaged()
        check(cancelled.current == oldPin)
        check(cancelled.next == null)
        check(cancelled.accepts(oldPin))
        check(!cancelled.accepts(nextPin))
    }
}
