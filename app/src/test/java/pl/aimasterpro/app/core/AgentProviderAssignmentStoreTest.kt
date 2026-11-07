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
