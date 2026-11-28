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
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class AgentManagerStoreTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("aiforteca_agent_manager_v1", Context.MODE_PRIVATE).edit().clear().commit()
        AgentManagerStore.resetStorageHealthForTests()
        context.getSharedPreferences("ai_memory_audit_log_v1", Context.MODE_PRIVATE).edit().clear().commit()
        SharedPreferencesWriteHealth.resetForTests("ai_memory_audit_log_v1")
    }

    private fun approvalRef() = "test-confirmation:${java.util.UUID.randomUUID()}"

    private fun mutateSetEnabled(id: String, enabled: Boolean): Boolean {
        val mutation = if (enabled) MemoryGovernance.Mutation.SET_AGENT_ENABLED else MemoryGovernance.Mutation.SET_AGENT_DISABLED
        val request = MemoryGovernance.Request(MemoryGovernance.Actor.USER, mutation, id, "Test user action", approvalRef(), "test:agent-manager")
        return MemoryMutationGate.execute(context, request) { permit -> AgentManagerStore.setEnabled(context, id, enabled, permit) }
    }
    private fun mutateAdd(agent: AgentSpec): Boolean {
        val request = MemoryGovernance.Request(MemoryGovernance.Actor.USER, MemoryGovernance.Mutation.ADD_CUSTOM_AGENT, agent.id, "Test add", approvalRef(), "test:agent-manager")
        return MemoryMutationGate.execute(context, request) { permit -> AgentManagerStore.addCustomAgent(context, agent, permit) }
    }
    private fun mutateUpdate(agent: AgentSpec): Boolean {
        val request = MemoryGovernance.Request(MemoryGovernance.Actor.USER, MemoryGovernance.Mutation.UPDATE_CUSTOM_AGENT, agent.id, "Test update", approvalRef(), "test:agent-manager")
        return MemoryMutationGate.execute(context, request) { permit -> AgentManagerStore.updateCustomAgent(context, agent, permit) }
    }
    private fun mutateRemove(id: String): Boolean {
        val request = MemoryGovernance.Request(MemoryGovernance.Actor.USER, MemoryGovernance.Mutation.REMOVE_CUSTOM_AGENT, id, "Test remove", approvalRef(), "test:agent-manager")
        return MemoryMutationGate.execute(context, request) { permit -> AgentManagerStore.removeCustomAgent(context, id, permit) }
    }

    private fun custom(id: String = "custom-log-auditor", name: String = "Log Auditor") = AgentSpec(
        id = id,
        name = name,
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
        assertTrue(mutateSetEnabled(id, false))
        assertFalse(AgentManagerStore.isEnabled(context, id))
        assertFalse(AgentManagerStore.enabledCatalog(context).any { it.id == id })
        assertTrue(mutateSetEnabled(id, true))
        assertTrue(AgentManagerStore.isEnabled(context, id))
    }

    @Test fun customAgentCanBeAddedRoutedAndRemoved() {
        val agent = custom()
        assertTrue(mutateAdd(agent))
        assertTrue(AgentManagerStore.enabledCatalog(context).any { it.id == agent.id })
        val routedPrompt = AgentManagerStore.enabledCatalog(context).first { it.id == agent.id }.systemPrompt
        assertTrue(routedPrompt.contains(agent.systemPromptOverride))
        assertTrue(routedPrompt.contains("Nie zmyślaj odczytanych plików"))
        assertTrue(routedPrompt.contains("Nie ujawniaj kluczy API"))
        assertTrue(mutateRemove(agent.id))
        assertFalse(AgentManagerStore.enabledCatalog(context).any { it.id == agent.id })
    }

    @Test fun customAgentCanBeEditedWithoutChangingItsIdentityOrDisabledState() {
        val original = custom()
        assertTrue(mutateAdd(original))
        assertTrue(mutateSetEnabled(original.id, false))

        val updated = original.copy(
            name = "Audytor logów i bezpieczeństwa",
            responsibility = "Sprawdza logi, uprawnienia i wycieki danych.",
            keywords = listOf("logi", "bezpieczeństwo", "wycieki"),
            systemPromptOverride = "Sprawdzasz logi i nigdy nie ujawniasz sekretów."
        )
        assertTrue(mutateUpdate(updated))
        val snapshot = AgentManagerStore.snapshot(context)
        assertEquals(updated, snapshot.customAgents.single())
        assertTrue("Editing must preserve the disabled state", original.id in snapshot.disabledIds)
        assertFalse(AgentManagerStore.enabledCatalog(context).any { it.id == original.id })
    }

    @Test fun builtInAndMissingAgentsCannotBeUpdatedAsCustomProfiles() {
        assertFalse(mutateUpdate(custom(id = "critical-reviewer")))
        assertFalse(mutateUpdate(custom(id = "custom-not-saved")))
    }

    @Test fun removedCustomAgentIdIsRememberedAndCannotBeReimported() {
        val removed = custom(id = "custom-removed-auditor")
        assertTrue(mutateAdd(removed))
        assertTrue(mutateRemove(removed.id))
        val snapshot = AgentManagerStore.snapshot(context)
        assertTrue(removed.id in snapshot.removedCustomIds)
        assertFalse(mutateAdd(removed))
        assertTrue(mutateAdd(custom(id = "custom-new-auditor")))
    }

    @Test fun invalidOrDuplicateCustomAgentIsRejected() {
        assertFalse(mutateAdd(custom(id = "bad id")))
        assertFalse(mutateAdd(custom(id = "custom-gsk_12345678901234567890")))
        assertFalse(mutateAdd(custom(id = "custom-safe-name", name = "Agent api_key=secret")))
        assertTrue(mutateAdd(custom()))
        assertFalse(mutateAdd(custom()))
    }

    @Test fun concurrentCustomAgentAddsDoNotLoseCatalogUpdates() {
        val startGate = CountDownLatch(1)
        val finished = CountDownLatch(2)
        val failures = Collections.synchronizedList(mutableListOf<String>())

        for (worker in 0..1) {
            Thread {
                try {
                    startGate.await()
                    repeat(12) { index ->
                        val id = "custom-parallel-$worker-${index.toString().padStart(2, '0')}"
                        if (!mutateAdd(custom(id))) failures.add(id)
                    }
                } catch (error: Throwable) {
                    failures.add("worker-$worker:${error.javaClass.simpleName}")
                } finally {
                    finished.countDown()
                }
            }.apply {
                isDaemon = true
                start()
            }
        }

        startGate.countDown()
        assertTrue("Concurrent catalog updates did not finish", finished.await(10, TimeUnit.SECONDS))
        assertTrue("Catalog updates failed: $failures", failures.isEmpty())
        assertEquals(24, AgentManagerStore.snapshot(context).customAgents.size)
    }

    @Test fun agentCannotMutateCatalogEvenWhenItSuppliesAnApprovalReference() {
        val agent = custom(id = "custom-agent-denied")
        var mutationInvoked = false
        val request = MemoryGovernance.Request(
            MemoryGovernance.Actor.AGENT,
            MemoryGovernance.Mutation.ADD_CUSTOM_AGENT,
            agent.id,
            "Agent attempted catalog mutation",
            "agent-forged-approval"
        )
        val result = MemoryMutationGate.execute(context, request) { permit ->
            mutationInvoked = true
            AgentManagerStore.addCustomAgent(context, agent, permit)
        }
        assertFalse(result)
        assertFalse(mutationInvoked)
        assertFalse(AgentManagerStore.snapshot(context).customAgents.any { it.id == agent.id })
    }

    @Test fun successfulCatalogMutationIsRecordedAsIntentAndCommitted() {
        val agent = custom(id = "custom-audited-agent")
        assertTrue(mutateAdd(agent))
        val records = MemoryAuditLog.readVerified(context)!!
            .filter { it.objectId == agent.id && it.mutation == MemoryGovernance.Mutation.ADD_CUSTOM_AGENT.name }
        assertEquals(listOf(MemoryAuditLog.Phase.INTENT, MemoryAuditLog.Phase.COMMITTED), records.map { it.phase })
        assertEquals("SUCCESS", records.last().outcome)
    }

    @Test fun preferenceAccessFailureIsUnavailableNotCorrupt() {
        val failingContext = object : android.content.ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
                throw IllegalStateException("controlled agent catalog preference access failure")
            }
        }
        val state = AgentManagerStore.snapshot(failingContext)
        assertEquals(AgentManagerStore.ReadStatus.UNAVAILABLE, state.status)
        assertTrue(state.errorCode.startsWith("AGENT_MANAGER_STORAGE_UNAVAILABLE:"))
    }

    @Test fun wrongJsonTypesInAgentStateFailClosedInsteadOfDroppingDisabledOrRemovedIds() {
        val prefs = context.getSharedPreferences("aiforteca_agent_manager_v1", Context.MODE_PRIVATE)
        val corrupt = """{"version":1,"disabled":"critical-reviewer","customAgents":[],"removedCustomIds":[]}"""
        assertTrue(prefs.edit().putString("state", corrupt).commit())
        val state = AgentManagerStore.snapshot(context)
        assertEquals(AgentManagerStore.ReadStatus.CORRUPT, state.status)
        assertTrue(AgentManagerStore.enabledCatalog(context).isEmpty())
        assertFalse(mutateSetEnabled("critical-reviewer", true))
        assertEquals(corrupt, prefs.getString("state", null))
    }

    @Test fun fractionalAndOverflowingVersionsAreRejectedWithoutOverwriting() {
        val prefs = context.getSharedPreferences("aiforteca_agent_manager_v1", Context.MODE_PRIVATE)
        listOf(
            """{"version":1.5,"disabled":[],"customAgents":[],"removedCustomIds":[]}""",
            """{"version":4294967297,"disabled":[],"customAgents":[],"removedCustomIds":[]}"""
        ).forEach { raw ->
            assertTrue(prefs.edit().putString("state", raw).commit())
            assertEquals(AgentManagerStore.ReadStatus.CORRUPT, AgentManagerStore.snapshot(context).status)
            assertTrue(AgentManagerStore.enabledCatalog(context).isEmpty())
            assertEquals(raw, prefs.getString("state", null))
        }
    }

    @Test fun corruptCatalogDoesNotSilentlyReenableAgentsOrOverwriteState() {
        val prefs = context.getSharedPreferences("aiforteca_agent_manager_v1", Context.MODE_PRIVATE)
        prefs.edit().putString("state", "{broken").commit()
        val state = AgentManagerStore.snapshot(context)
        assertEquals(AgentManagerStore.ReadStatus.CORRUPT, state.status)
        assertTrue(AgentManagerStore.enabledCatalog(context).isEmpty())
        assertFalse(mutateSetEnabled("critical-reviewer", true))
        assertEquals("{broken", prefs.getString("state", null))
    }
}
