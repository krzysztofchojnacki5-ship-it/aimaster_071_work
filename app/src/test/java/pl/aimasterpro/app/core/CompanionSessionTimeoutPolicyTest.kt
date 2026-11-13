package pl.aimasterpro.app.core

import org.junit.Test

class CompanionSessionTimeoutPolicyTest {
    private class FakeClock(var now: Long = 0L) { fun read(): Long = now }

    private fun policy(clock: FakeClock) = CompanionSessionTimeoutPolicy(
        handshakeTimeoutNanos = 10L,
        idleTimeoutNanos = 20L,
        transferTimeoutNanos = 30L,
        hardLifetimeNanos = 100L,
        clockNanos = clock::read
    )

    @Test fun handshakeExpiresAtExactBoundary() {
        val clock = FakeClock()
        val policy = policy(clock)
        clock.now = 9L
        check(policy.currentTimeout() == CompanionSessionTimeoutPolicy.Timeout.NONE)
        clock.now = 10L
        check(policy.currentTimeout() == CompanionSessionTimeoutPolicy.Timeout.HANDSHAKE)
        policy.markHandshakeFinished()
        check(policy.currentTimeout() == CompanionSessionTimeoutPolicy.Timeout.HANDSHAKE)
    }

    @Test fun idleClockDoesNotPreemptLongerHandshakeWindow() {
        val clock = FakeClock()
        val policy = CompanionSessionTimeoutPolicy(30L, 10L, 40L, 100L, clock::read)
        clock.now = 20L
        check(policy.currentTimeout() == CompanionSessionTimeoutPolicy.Timeout.NONE)
        policy.recordAuthenticatedActivity() // Ignored before handshake completion.
        clock.now = 30L
        check(policy.currentTimeout() == CompanionSessionTimeoutPolicy.Timeout.HANDSHAKE)
    }

    @Test fun repeatedHandshakeMarkerDoesNotRefreshIdleClock() {
        val clock = FakeClock()
        val policy = policy(clock)
        policy.markHandshakeFinished()
        clock.now = 19L
        policy.markHandshakeFinished()
        clock.now = 20L
        check(policy.currentTimeout() == CompanionSessionTimeoutPolicy.Timeout.IDLE)
    }

    @Test fun authenticatedActivityResetsIdleButNotHardLifetime() {
        val clock = FakeClock()
        val policy = policy(clock)
        policy.markHandshakeFinished()
        clock.now = 19L
        policy.recordAuthenticatedActivity()
        clock.now = 38L
        check(policy.currentTimeout() == CompanionSessionTimeoutPolicy.Timeout.NONE)
        clock.now = 100L
        policy.recordAuthenticatedActivity()
        check(policy.currentTimeout() == CompanionSessionTimeoutPolicy.Timeout.HARD_LIFETIME)
    }

    @Test fun transferHasIndependentDeadline() {
        val clock = FakeClock()
        val policy = policy(clock)
        policy.markHandshakeFinished()
        policy.beginTransfer()
        clock.now = 29L
        policy.recordAuthenticatedActivity()
        clock.now = 30L
        check(policy.currentTimeout() == CompanionSessionTimeoutPolicy.Timeout.TRANSFER)
        check(runCatching { policy.finishTransfer() }.isFailure)
    }

    @Test fun idleTimeoutAndTransferLifecycleAreBounded() {
        val clock = FakeClock()
        val policy = policy(clock)
        policy.markHandshakeFinished()
        clock.now = 19L
        check(policy.currentTimeout() == CompanionSessionTimeoutPolicy.Timeout.NONE)
        clock.now = 20L
        check(policy.currentTimeout() == CompanionSessionTimeoutPolicy.Timeout.IDLE)
        check(runCatching { policy.beginTransfer() }.isFailure)

        val activeClock = FakeClock()
        val active = policy(activeClock)
        active.markHandshakeFinished()
        active.beginTransfer()
        activeClock.now = 5L
        active.finishTransfer()
        check(active.currentTimeout() == CompanionSessionTimeoutPolicy.Timeout.NONE)
        check(runCatching { active.finishTransfer() }.isFailure)
    }

    @Test fun elapsedTimeRemainsCorrectAcrossNanoTimeWraparound() {
        val clock = FakeClock(Long.MAX_VALUE - 5L)
        val policy = policy(clock)
        policy.markHandshakeFinished()
        clock.now = Long.MIN_VALUE + 14L // 20 ns elapsed across signed wraparound.
        check(policy.currentTimeout() == CompanionSessionTimeoutPolicy.Timeout.IDLE)
    }

    @Test fun stateTransitionUsesOneClockSampleAndCannotRefreshAfterDeadlineCheck() {
        val samples = ArrayDeque(listOf(0L, 9L, 10L))
        val policy = CompanionSessionTimeoutPolicy(
            handshakeTimeoutNanos = 10L,
            idleTimeoutNanos = 1L,
            transferTimeoutNanos = 30L,
            hardLifetimeNanos = 100L,
            clockNanos = { samples.removeFirst() }
        )
        // The transition is timestamped at 9 ns. A second clock read must not
        // silently move last activity to 10 ns and hide the inclusive idle deadline.
        policy.markHandshakeFinished()
        check(policy.currentTimeout() == CompanionSessionTimeoutPolicy.Timeout.IDLE)
    }

    @Test fun rejectsNonPositiveTimeoutConfiguration() {
        val clock = FakeClock()
        check(runCatching {
            CompanionSessionTimeoutPolicy(0L, 1L, 1L, 1L, clock::read)
        }.isFailure)
        check(runCatching {
            CompanionSessionTimeoutPolicy(1L, 1L, 1L, -1L, clock::read)
        }.isFailure)
    }
}
