package pl.aimasterpro.app.core

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AgentProviderAssignmentStoreTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("chat_state", Context.MODE_PRIVATE)
    private val defaults = linkedMapOf("MASTER" to "groq", "CODER" to "groq", "RESEARCHER" to "groq")

    @Before fun reset() {
        prefs.edit().clear().commit()
    }

    @Test fun saveMasterCommitsBothLegacyKeysTogether() {
        assertTrue(AgentProviderAssignmentStore.save(context, "MASTER", "gemini"))
        assertEquals("gemini", prefs.getString("agent_provider_MASTER", null))
        assertEquals("gemini", prefs.getString("selected_provider", null))
    }

    @Test fun readUsesDefaultsOnlyForMissingOrBlankAssignments() {
        prefs.edit().putString("agent_provider_CODER", "mistral")
            .putString("agent_provider_RESEARCHER", " ").commit()
        val values = AgentProviderAssignmentStore.read(context, defaults)
        assertEquals("groq", values["MASTER"])
        assertEquals("mistral", values["CODER"])
        assertEquals("groq", values["RESEARCHER"])
    }

    @Test fun readRejectsWrongStoredTypeInsteadOfSilentlyUsingDefault() {
        prefs.edit().putInt("agent_provider_CODER", 42).commit()
        try {
            AgentProviderAssignmentStore.read(context, defaults)
            fail("Expected a type mismatch to be surfaced")
        } catch (_: ClassCastException) {
            assertEquals(42, prefs.getInt("agent_provider_CODER", -1))
        }
    }

    @Test fun importReconciliationPreservesValidAssignmentsAndRepairsMissingIds() {
        prefs.edit().putString("agent_provider_MASTER", "old-provider")
            .putString("selected_provider", "old-provider")
            .putString("agent_provider_CODER", "mistral")
            .putString("agent_provider_RESEARCHER", "removed-provider").commit()

        val result = AgentProviderAssignmentStore.reconcileAfterProviderImport(
            context, defaults, setOf("gemini", "mistral"), "gemini"
        )

        assertNotNull(result)
        assertEquals("gemini", result!!["MASTER"])
        assertEquals("mistral", result["CODER"])
        assertEquals("gemini", result["RESEARCHER"])
        assertEquals("gemini", prefs.getString("selected_provider", null))
        assertEquals("gemini", prefs.getString("agent_provider_MASTER", null))
    }

    @Test fun startupRecoveryPreservesValidAssignmentsAndRepairsStaleIds() {
        prefs.edit().putString("agent_provider_MASTER", "removed-master")
            .putString("selected_provider", "legacy-valid")
            .putString("agent_provider_CODER", "mistral")
            .putString("agent_provider_RESEARCHER", "removed-researcher").commit()

        val result = AgentProviderAssignmentStore.reconcileExistingAssignments(
            context, defaults, setOf("legacy-valid", "mistral", "gemini"), "legacy-valid"
        )

        assertNotNull(result)
        assertEquals("legacy-valid", result!!["MASTER"])
        assertEquals("mistral", result["CODER"])
        assertEquals("groq".takeIf { it in setOf("legacy-valid", "mistral", "gemini") } ?: "legacy-valid", result["RESEARCHER"])
        assertEquals("legacy-valid", prefs.getString("agent_provider_MASTER", null))
        assertEquals("legacy-valid", prefs.getString("selected_provider", null))
    }

    @Test fun startupRecoveryPrefersValidCanonicalMasterOverLegacyMirror() {
        prefs.edit().putString("agent_provider_MASTER", "mistral")
            .putString("selected_provider", "gemini").commit()

        val result = AgentProviderAssignmentStore.reconcileExistingAssignments(
            context, defaults, setOf("mistral", "gemini"), "gemini"
        )

        assertEquals("mistral", result?.get("MASTER"))
        assertEquals("mistral", prefs.getString("selected_provider", null))
    }

    @Test fun startupRecoveryDoesNotWriteWhenProviderCatalogIsEmpty() {
        prefs.edit().putString("agent_provider_MASTER", "keep-me")
            .putString("selected_provider", "keep-me").commit()

        assertNull(AgentProviderAssignmentStore.reconcileExistingAssignments(
            context, defaults, emptySet(), "missing"
        ))
        assertEquals("keep-me", prefs.getString("agent_provider_MASTER", null))
        assertEquals("keep-me", prefs.getString("selected_provider", null))
    }

    @Test fun invalidEmptyProviderSetDoesNotModifyPersistedAssignments() {
        prefs.edit().putString("agent_provider_MASTER", "keep-me")
            .putString("selected_provider", "keep-me").commit()
        assertNull(AgentProviderAssignmentStore.reconcileAfterProviderImport(
            context, defaults, emptySet(), "missing"
        ))
        assertEquals("keep-me", prefs.getString("agent_provider_MASTER", null))
        assertEquals("keep-me", prefs.getString("selected_provider", null))
    }

    @Test fun invalidRoleOrProviderIdIsRejectedWithoutMutation() {
        assertFalse(AgentProviderAssignmentStore.save(context, "MASTER", " "))
        assertFalse(AgentProviderAssignmentStore.save(context, " ", "gemini"))
        assertNull(prefs.getString("agent_provider_MASTER", null))
    }
}
