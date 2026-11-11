package pl.aimasterpro.app.core

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import pl.aimasterpro.app.agents.AgentCatalog
import pl.aimasterpro.app.agents.AgentSpec

@RunWith(RobolectricTestRunner::class)
class AgentManagerStoreTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("aiforteca_agent_manager_v1", Context.MODE_PRIVATE).edit().clear().commit()
        AgentManagerStore.resetStorageHealthForTests()
    }

    private fun custom(id: String = "custom-log-auditor") = AgentSpec(
        id = id,
        name = "Log Auditor",
        category = "Jakość, bezpieczeństwo i zgodność",
        responsibility = "Analizuje logi bez ujawniania sekretów.",
        keywords = listOf("log", "diagnostyka", "sekret"),
        systemPromptOverride = "Jesteś agentem audytującym logi. Nie ujawniaj sekretów."
    )

    @Test fun defaultCatalogEnablesAllBuiltInAgents() {
        val state = AgentManagerStore.snapshot(context)
        assertEquals(AgentManagerStore.ReadStatus.AVAILABLE, state.status)
        assertTrue(state.disabledIds.isEmpty())
        assertEquals(AgentCatalog.all.size, AgentManagerStore.enabledCatalog(context).size)
    }

    @Test fun disablingAndReenablingAgentPersists() {
        val id = "critical-reviewer"
        assertTrue(AgentManagerStore.setEnabled(context, id, false))
        assertFalse(AgentManagerStore.isEnabled(context, id))
        assertFalse(AgentManagerStore.enabledCatalog(context).any { it.id == id })
        assertTrue(AgentManagerStore.setEnabled(context, id, true))
        assertTrue(AgentManagerStore.isEnabled(context, id))
    }

    @Test fun customAgentCanBeAddedRoutedAndRemoved() {
        val agent = custom()
        assertTrue(AgentManagerStore.addCustomAgent(context, agent))
        assertTrue(AgentManagerStore.enabledCatalog(context).any { it.id == agent.id })
        val routedPrompt = AgentManagerStore.enabledCatalog(context).first { it.id == agent.id }.systemPrompt
        assertTrue(routedPrompt.contains(agent.systemPromptOverride))
        assertTrue(routedPrompt.contains("Nie zmyślaj odczytanych plików"))
        assertTrue(routedPrompt.contains("Nie ujawniaj kluczy API"))
        assertTrue(AgentManagerStore.removeCustomAgent(context, agent.id))
        assertFalse(AgentManagerStore.enabledCatalog(context).any { it.id == agent.id })
    }

    @Test fun customAgentCanBeEditedWithoutChangingItsIdentityOrDisabledState() {
        val original = custom()
        assertTrue(AgentManagerStore.addCustomAgent(context, original))
        assertTrue(AgentManagerStore.setEnabled(context, original.id, false))

        val updated = original.copy(
            name = "Audytor logów i bezpieczeństwa",
            responsibility = "Sprawdza logi, uprawnienia i wycieki danych.",
            keywords = listOf("logi", "bezpieczeństwo", "wycieki"),
            systemPromptOverride = "Sprawdzasz logi i nigdy nie ujawniasz sekretów."
        )
        assertTrue(AgentManagerStore.updateCustomAgent(context, updated))
        val snapshot = AgentManagerStore.snapshot(context)
        assertEquals(updated, snapshot.customAgents.single())
        assertTrue("Editing must preserve the disabled state", original.id in snapshot.disabledIds)
        assertFalse(AgentManagerStore.enabledCatalog(context).any { it.id == original.id })
    }

    @Test fun builtInAndMissingAgentsCannotBeUpdatedAsCustomProfiles() {
        assertFalse(AgentManagerStore.updateCustomAgent(context, custom(id = "critical-reviewer")))
        assertFalse(AgentManagerStore.updateCustomAgent(context, custom(id = "custom-not-saved")))
    }

    @Test fun removedCustomAgentIdIsRememberedAndCannotBeReimported() {
        val removed = custom(id = "custom-removed-auditor")
        assertTrue(AgentManagerStore.addCustomAgent(context, removed))
        assertTrue(AgentManagerStore.removeCustomAgent(context, removed.id))
        val snapshot = AgentManagerStore.snapshot(context)
        assertTrue(removed.id in snapshot.removedCustomIds)
        assertFalse(AgentManagerStore.addCustomAgent(context, removed))
        assertTrue(AgentManagerStore.addCustomAgent(context, custom(id = "custom-new-auditor")))
    }

    @Test fun invalidOrDuplicateCustomAgentIsRejected() {
        assertFalse(AgentManagerStore.addCustomAgent(context, custom(id = "bad id")))
        assertTrue(AgentManagerStore.addCustomAgent(context, custom()))
        assertFalse(AgentManagerStore.addCustomAgent(context, custom()))
    }

    @Test fun wrongJsonTypesInAgentStateFailClosedInsteadOfDroppingDisabledOrRemovedIds() {
        val prefs = context.getSharedPreferences("aiforteca_agent_manager_v1", Context.MODE_PRIVATE)
        val corrupt = """{"version":1,"disabled":"critical-reviewer","customAgents":[],"removedCustomIds":[]}"""
        assertTrue(prefs.edit().putString("state", corrupt).commit())
        val state = AgentManagerStore.snapshot(context)
        assertEquals(AgentManagerStore.ReadStatus.CORRUPT, state.status)
        assertTrue(AgentManagerStore.enabledCatalog(context).isEmpty())
        assertFalse(AgentManagerStore.setEnabled(context, "critical-reviewer", true))
        assertEquals(corrupt, prefs.getString("state", null))
    }

    @Test fun corruptCatalogDoesNotSilentlyReenableAgentsOrOverwriteState() {
        val prefs = context.getSharedPreferences("aiforteca_agent_manager_v1", Context.MODE_PRIVATE)
        prefs.edit().putString("state", "{broken").commit()
        val state = AgentManagerStore.snapshot(context)
        assertEquals(AgentManagerStore.ReadStatus.CORRUPT, state.status)
        assertTrue(AgentManagerStore.enabledCatalog(context).isEmpty())
        assertFalse(AgentManagerStore.setEnabled(context, "critical-reviewer", true))
        assertEquals("{broken", prefs.getString("state", null))
    }
}
