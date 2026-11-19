package pl.aimasterpro.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskAgentAvailabilityTest {
    private fun classify(
        configured: Boolean = true,
        ready: Boolean = true,
        free: Boolean = false,
        unknown: Boolean = false,
        approved: Boolean = false
    ) = TaskAgentAvailability.classify(
        agentId = "researcher",
        configured = configured, ready = ready, verifiedFree = free,
        unknownPrice = unknown, unknownPriceApproved = approved
    )

    @Test fun onlyVerifiedZeroPriceIsFreeAndRunnable() {
        assertEquals(TaskAgentAvailability.Status.FREE_READY, classify(free = true))
        assertTrue(TaskAgentAvailability.Status.FREE_READY.canRun)
    }

    @Test fun unknownPriceRequiresExplicitApprovalAndNeverAppearsFree() {
        assertEquals(TaskAgentAvailability.Status.UNKNOWN_PRICE_UNAPPROVED, classify(unknown = true))
        assertFalse(TaskAgentAvailability.Status.UNKNOWN_PRICE_UNAPPROVED.canRun)
        assertEquals(TaskAgentAvailability.Status.UNKNOWN_PRICE_APPROVED, classify(unknown = true, approved = true))
        assertTrue(TaskAgentAvailability.Status.UNKNOWN_PRICE_APPROVED.canRun)
    }

    @Test fun missingOrIncompleteProfilesAreBlocked() {
        assertEquals(TaskAgentAvailability.Status.NOT_CONFIGURED, classify(configured = false))
        assertEquals(TaskAgentAvailability.Status.NOT_READY, classify(ready = false))
        assertFalse(TaskAgentAvailability.Status.NOT_READY.canRun)
    }

    @Test fun paidOrUnverifiedPricingIsBlocked() {
        assertEquals(TaskAgentAvailability.Status.PAID_OR_UNVERIFIED, classify())
        assertFalse(TaskAgentAvailability.Status.PAID_OR_UNVERIFIED.canRun)
    }
}
