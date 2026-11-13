package pl.aimasterpro.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.aimasterpro.app.core.CompanionTrustRecovery.Status

class CompanionTrustRecoveryTest {
    private fun snap(
        pinsReadable: Boolean = true,
        pinsPresent: Boolean = false,
        psk: Status = Status.ABSENT,
        chain: Status = Status.ABSENT,
        tlsKeyPresent: Boolean = false
    ) = CompanionTrustRecovery.Snapshot(pinsReadable, pinsPresent, psk, chain, tlsKeyPresent)

    private class RecordingTargets(val failStep: String? = null) : CompanionTrustRecovery.WipeTargets {
        val calls = mutableListOf<String>()
        private fun step(name: String) {
            calls.add(name)
            if (name == failStep) throw IllegalStateException("boom")
        }
        override fun clearPins() = step("PINS")
        override fun clearPsk() = step("PSK")
        override fun clearChain() = step("CHAIN")
        override fun deleteTlsKeys() = step("TLS_KEYS")
    }

    @Test fun emptyStateIsUnpairedAndAllowsNewPairing() {
        val state = CompanionTrustRecovery.evaluate(snap())
        assertEquals(CompanionTrustRecovery.State.Unpaired, state)
        assertTrue(CompanionTrustRecovery.canStartNewPairing(state))
    }

    @Test fun fullyPairedStateIsPaired() {
        val state = CompanionTrustRecovery.evaluate(snap(pinsPresent = true, psk = Status.OK, chain = Status.OK, tlsKeyPresent = true))
        assertEquals(CompanionTrustRecovery.State.Paired, state)
        assertFalse(CompanionTrustRecovery.canStartNewPairing(state))
    }

    @Test fun pairedWithoutTlsChainIsPendingNotRepair() {
        val state = CompanionTrustRecovery.evaluate(snap(pinsPresent = true, psk = Status.OK))
        assertEquals(CompanionTrustRecovery.State.PairedTlsPending, state)
    }

    @Test fun pinsWithoutPskRequiresRepairAndBlocksNewPairing() {
        val state = CompanionTrustRecovery.evaluate(snap(pinsPresent = true))
        assertTrue(state is CompanionTrustRecovery.State.RepairRequired)
        assertTrue((state as CompanionTrustRecovery.State.RepairRequired).reasons.contains("PINS_WITHOUT_PSK"))
        assertFalse(CompanionTrustRecovery.canStartNewPairing(state))
    }

    @Test fun pskWithoutPinsIsRepairNotSilentlyRebuilt() {
        val state = CompanionTrustRecovery.evaluate(snap(psk = Status.OK))
        assertTrue((state as CompanionTrustRecovery.State.RepairRequired).reasons.contains("PSK_WITHOUT_PINS"))
    }

    @Test fun chainWithoutKeyIsRepair() {
        val state = CompanionTrustRecovery.evaluate(snap(pinsPresent = true, psk = Status.OK, chain = Status.OK, tlsKeyPresent = false))
        assertTrue((state as CompanionTrustRecovery.State.RepairRequired).reasons.contains("CHAIN_WITHOUT_KEY"))
    }

    @Test fun unreadablePinsOrCorruptRecordsAreRepair() {
        assertTrue(CompanionTrustRecovery.evaluate(snap(pinsReadable = false)) is CompanionTrustRecovery.State.RepairRequired)
        assertTrue(CompanionTrustRecovery.evaluate(snap(pinsPresent = true, psk = Status.BAD)) is CompanionTrustRecovery.State.RepairRequired)
    }

    @Test fun recoveryRefusedWithoutExactConfirmationAndTouchesNothing() {
        val targets = RecordingTargets()
        val result = CompanionTrustRecovery.recover("ponowne parowanie", targets)
        assertEquals(CompanionTrustRecovery.WipeResult.Refused("CONFIRMATION_REQUIRED"), result)
        assertTrue(targets.calls.isEmpty())
    }

    @Test fun recoveryRunsInFixedOrderPinsFirst() {
        val targets = RecordingTargets()
        assertEquals(CompanionTrustRecovery.WipeResult.Wiped, CompanionTrustRecovery.recover(CompanionTrustRecovery.CONFIRMATION, targets))
        assertEquals(listOf("PINS", "PSK", "CHAIN", "TLS_KEYS"), targets.calls)
    }

    @Test fun interruptedRecoveryStopsAndRetryCompletesToUnpaired() {
        val failing = RecordingTargets(failStep = "PSK")
        assertEquals(
            CompanionTrustRecovery.WipeResult.Failed("PSK"),
            CompanionTrustRecovery.recover(CompanionTrustRecovery.CONFIRMATION, failing)
        )
        assertFalse(failing.calls.contains("CHAIN"))
        // After the interruption the state is inconsistent (PSK left without pins); retry is allowed.
        assertTrue(CompanionTrustRecovery.evaluate(snap(psk = Status.OK)) is CompanionTrustRecovery.State.RepairRequired)
        val retry = RecordingTargets()
        assertEquals(CompanionTrustRecovery.WipeResult.Wiped, CompanionTrustRecovery.recover(CompanionTrustRecovery.CONFIRMATION, retry))
        assertEquals(CompanionTrustRecovery.State.Unpaired, CompanionTrustRecovery.evaluate(snap()))
    }
}
