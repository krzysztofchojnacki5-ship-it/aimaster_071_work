package pl.aimasterpro.app.core

import org.junit.Assert.assertEquals
import org.junit.Test

class MasterProviderSelectionTest {
    @Test fun canonicalRoleAssignmentWinsWhenLegacyMirrorDisagrees() {
        assertEquals(
            "provider-role",
            MasterProviderSelection.resolve(
                mapOf("MASTER" to "provider-role"),
                "provider-legacy"
            )
        )
    }

    @Test fun legacyMirrorIsUsedOnlyWhenCanonicalAssignmentIsMissing() {
        assertEquals(
            "provider-legacy",
            MasterProviderSelection.resolve(emptyMap(), "provider-legacy")
        )
    }

    @Test fun blankCanonicalAssignmentFallsBackToNonBlankLegacyValue() {
        assertEquals(
            "provider-legacy",
            MasterProviderSelection.resolve(mapOf("MASTER" to "  "), "provider-legacy")
        )
    }

    @Test fun noUsableSelectionReturnsNull() {
        assertEquals(null, MasterProviderSelection.resolve(mapOf("MASTER" to ""), " "))
    }
}
