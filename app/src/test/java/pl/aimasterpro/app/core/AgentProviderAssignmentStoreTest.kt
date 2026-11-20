package pl.aimasterpro.app.core

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import pl.aimasterpro.app.data.SecureStore
import pl.aimasterpro.app.data.SecureStoreHealth
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AgentProviderAssignmentStoreTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("chat_state", Context.MODE_PRIVATE)
    private val defaults = linkedMapOf("MASTER" to "groq", "CODER" to "groq", "RESEARCHER" to "groq")

    private fun mutateSave(
        role: String,
        providerId: String,
        targetContext: Context = context,
        actor: MemoryGovernance.Actor = MemoryGovernance.Actor.USER,
        approvalRef: String = "u:${java.util.UUID.randomUUID()}:$providerId"
    ): Boolean {
        val request = MemoryGovernance.Request(
            actor = actor,
            mutation = MemoryGovernance.Mutation.SET_AGENT_PROVIDER,
            objectId = role,
            reason = "Test przypisania profilu $providerId do roli $role",
            approvalRef = approvalRef
        )
        return MemoryMutationGate.execute(targetContext, request) { permit ->
            AgentProviderAssignmentStore.save(targetContext, role, providerId, permit)
        }
    }

    @Before fun reset() {
        prefs.edit().clear().commit()
        SharedPreferencesWriteHealth.resetForTests("chat_state")
        context.getSharedPreferences("ai_memory_audit_log_v1", Context.MODE_PRIVATE).edit().clear().commit()
        SharedPreferencesWriteHealth.resetForTests("ai_memory_audit_log_v1")
        SecureStoreHealth.resetForTests()
    }

    @After fun cleanup() {
        SharedPreferencesWriteHealth.resetForTests("chat_state")
        SecureStoreHealth.resetForTests()
    }

    @Test fun saveMasterCommitsBothLegacyKeysTogether() {
        assertTrue(mutateSave("MASTER", "gemini"))
        assertEquals("gemini", prefs.getString("agent_provider_MASTER", null))
        assertEquals("gemini", prefs.getString("selected_provider", null))
    }

    @Test fun providerAssignmentRequiresUserGovernanceAndIsAudited() {
        assertTrue(mutateSave("CODER", "groq"))
        val records = MemoryAuditLog.readVerified(context)!!
            .filter { it.mutation == MemoryGovernance.Mutation.SET_AGENT_PROVIDER.name }
        assertEquals(listOf(MemoryAuditLog.Phase.INTENT, MemoryAuditLog.Phase.COMMITTED),
            records.map { it.phase })
        assertEquals("CODER", records.first().objectId)
        assertTrue(records.first().reason.contains("groq"))
        assertEquals("groq", prefs.getString("agent_provider_CODER", null))
    }

    @Test fun agentCannotChangeProviderAssignmentEvenWithForgedApprovalReference() {
        assertFalse(mutateSave(
            "CODER", "provider-attacker", actor = MemoryGovernance.Actor.AGENT,
            approvalRef = "agent-forged-confirmation"
        ))
        assertNull(prefs.getString("agent_provider_CODER", null))
        assertEquals("DENIED_AGENT_READ_ONLY",
            MemoryAuditLog.readVerified(context)!!.single().outcome)
    }

    @Test fun interruptedAssignmentAuditIsClosedOnlyAfterStartupVerifiesDurableState() {
        val failingAuditContext = object : android.content.ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
                val delegate = context.getSharedPreferences(name, mode)
                if (name != "ai_memory_audit_log_v1") return delegate
                return object : android.content.SharedPreferences by delegate {
                    override fun edit(): android.content.SharedPreferences.Editor {
                        val editor = delegate.edit()
                        return object : android.content.SharedPreferences.Editor by editor {
                            override fun commit(): Boolean {
                                // Persist INTENT, but fail the terminal COMMITTED audit append.
                                return if (delegate.getString("events", null)?.contains("\\"phase\\":\\"INTENT\\"") == true) {
                                    false
                                } else editor.commit()
                            }
                        }
                    }
                }
            }
        }

        assertFalse(mutateSave("CODER", "provider-new", targetContext = failingAuditContext))
        assertEquals("provider-new", prefs.getString("agent_provider_CODER", null))
        assertTrue(SharedPreferencesWriteHealth.isUncertain("ai_memory_audit_log_v1"))

        // A process restart clears only in-memory uncertainty. Startup reads the durable
        // assignment snapshot and then closes the unmatched audit intent without replaying it.
        SharedPreferencesWriteHealth.resetForTests("ai_memory_audit_log_v1")
        val verified = AgentProviderAssignmentStore.reconcileExistingAssignments(
            context, defaults, setOf("provider-new", "groq"), "groq"
        )
        assertNotNull(verified)
        assertEquals(1, AgentProviderAssignmentStore.resolvePendingGovernance(context, verified!!))
        assertFalse(MemoryAuditLog.hasUnresolvedIntent(
            context, "USER", "SET_AGENT_PROVIDER", "CODER"
        ))
        val rows = MemoryAuditLog.readVerified(context)!!
        assertEquals("RECOVERED_STATE", rows.last().outcome)
        assertTrue(rows.last().reason.contains("provider-new"))
        assertEquals("provider-new", prefs.getString("agent_provider_CODER", null))
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

    @Test fun startupRecoveryFailsClosedAndPreservesWronglyTypedAssignment() {
        prefs.edit().putString("agent_provider_MASTER", "gemini")
            .putInt("agent_provider_CODER", 42).putString("selected_provider", "gemini").commit()
        assertNull(AgentProviderAssignmentStore.reconcileExistingAssignments(
            context, defaults, setOf("gemini", "groq"), "gemini"
        ))
        assertEquals(42, prefs.getInt("agent_provider_CODER", -1))
        assertEquals("gemini", prefs.getString("agent_provider_MASTER", null))
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

    @Test fun explicitImportReconciliationRepairsWrongStoredAssignmentType() {
        prefs.edit().putInt("agent_provider_CODER", 42).putString("agent_provider_MASTER", "gemini").commit()
        val result = AgentProviderAssignmentStore.reconcileAfterProviderImport(
            context, defaults, setOf("gemini", "groq"), "gemini"
        )
        assertNotNull(result)
        assertEquals("groq", result!!["CODER"])
        assertEquals("groq", prefs.getString("agent_provider_CODER", null))
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
        assertFalse(mutateSave("MASTER", " "))
        assertFalse(mutateSave(" ", "gemini"))
        assertNull(prefs.getString("agent_provider_MASTER", null))
    }

    @Test fun saveWaitsForAssignmentReconciliationReadLockBeforeCommitting() {
        val enteredRead = java.util.concurrent.CountDownLatch(1)
        val releaseRead = java.util.concurrent.CountDownLatch(1)
        val saveFinished = java.util.concurrent.CountDownLatch(1)
        val saveResult = java.util.concurrent.atomic.AtomicBoolean(false)

        val reader = Thread {
            SharedPreferencesWriteHealth.withHealthyAccess("chat_state") {
                enteredRead.countDown()
                releaseRead.await(2, java.util.concurrent.TimeUnit.SECONDS)
                prefs.getString("agent_provider_MASTER", null)
            }
        }
        val writer = Thread {
            saveResult.set(mutateSave("MASTER", "gemini"))
            saveFinished.countDown()
        }

        try {
            reader.start()
            assertTrue("reader did not acquire the preference lock", enteredRead.await(2, java.util.concurrent.TimeUnit.SECONDS))
            writer.start()
            assertFalse("provider save must wait for the in-flight assignment read/reconciliation section",
                saveFinished.await(100, java.util.concurrent.TimeUnit.MILLISECONDS))
        } finally {
            releaseRead.countDown()
        }

        assertTrue("provider save did not finish", saveFinished.await(2, java.util.concurrent.TimeUnit.SECONDS))
        reader.join(2_000)
        writer.join(2_000)
        assertTrue(saveResult.get())
        assertEquals("gemini", prefs.getString("agent_provider_MASTER", null))
        assertEquals("gemini", prefs.getString("selected_provider", null))
    }

    @Test fun ambiguousAssignmentCommitQuarantinesRoutingState() {
        val failing = AmbiguousCommitContext(context, "chat_state")
        assertFalse(mutateSave("MASTER", "provider-new", targetContext = failing))
        assertTrue(runCatching { AgentProviderAssignmentStore.read(context, defaults) }.isFailure)
        assertNull(AgentProviderAssignmentStore.reconcileExistingAssignments(
            context, defaults, setOf("gemini", "groq"), "gemini"
        ))
    }
    @Test fun ambiguousImportReconciliationCommitQuarantinesAllRoutingAssignments() {
        prefs.edit().putString("agent_provider_MASTER", "old-master")
            .putString("selected_provider", "old-master")
            .putString("agent_provider_CODER", "old-coder").commit()
        val failing = AmbiguousCommitContext(context, "chat_state")

        assertNull(AgentProviderAssignmentStore.reconcileAfterProviderImport(
            failing, defaults, setOf("gemini", "groq"), "gemini"
        ))
        assertTrue(runCatching { AgentProviderAssignmentStore.read(context, defaults) }.isFailure)
        assertEquals("gemini", prefs.getString("agent_provider_MASTER", null))
        assertEquals("gemini", prefs.getString("selected_provider", null))
        assertEquals("groq", prefs.getString("agent_provider_CODER", null))
    }


    @Test fun startupRecoveryRepairsAssignmentsAfterImportReconciliationCommitFailure() {
        val base = context
        val providerPrefs = base.getSharedPreferences("secure_provider_store", Context.MODE_PRIVATE)
        providerPrefs.edit().clear().commit()
        SecureStoreHealth.resetForTests()

        fun provider(id: String) = JSONObject()
            .put("id", id)
            .put("name", id)
            .put("kind", "OPENAI_COMPATIBLE")
            .put("baseUrl", "https://example.invalid/v1")
            .put("model", "test-model")
            .put("enabled", true)
            .put("parentProviderId", "")
            .put("apiKey", "")

        // First durable boundary: the complete provider replacement succeeds.
        assertTrue(SecureStore(base).replaceAllConfigs(listOf(provider("new-master"), provider("new-coder"))))
        assertEquals(setOf("new-master", "new-coder"), providerPrefs.getStringSet("provider_ids", emptySet()))

        // Simulate process death after the second boundary has an ambiguous commit outcome.
        prefs.edit()
            .putString("agent_provider_MASTER", "old-master")
            .putString("selected_provider", "old-master")
            .putString("agent_provider_CODER", "old-coder")
            .putString("agent_provider_RESEARCHER", "old-researcher")
            .commit()
        val failingContext = AmbiguousCommitContext(base, "chat_state")
        assertNull(
            AgentProviderAssignmentStore.reconcileAfterProviderImport(
                failingContext,
                defaults,
                setOf("new-master", "new-coder"),
                "new-master"
            )
        )

        // A process restart clears only in-memory uncertainty latches. Durable preferences may
        // contain either the old or new assignment snapshot; startup must reconcile either one.
        SharedPreferencesWriteHealth.resetForTests("chat_state")
        SecureStoreHealth.resetForTests()
        val recoveredCatalog = SecureStore(base).allConfigs().map { it.optString("id") }.toSet()
        assertEquals(setOf("new-master", "new-coder"), recoveredCatalog)

        val recovered = AgentProviderAssignmentStore.reconcileExistingAssignments(
            base,
            defaults,
            recoveredCatalog,
            "old-master"
        )

        assertNotNull(recovered)
        assertEquals("new-master", recovered!!["MASTER"])
        assertEquals("new-master", recovered["CODER"])
        assertEquals("new-master", recovered["RESEARCHER"])
        assertEquals("new-master", prefs.getString("agent_provider_MASTER", null))
        assertEquals("new-master", prefs.getString("selected_provider", null))
        assertEquals("new-master", prefs.getString("agent_provider_CODER", null))
        assertEquals("new-master", prefs.getString("agent_provider_RESEARCHER", null))
    }


    @Test fun startupRecoveryRepairsOldDurableAssignmentsWhenImportReconciliationCommitDidNotPersist() {
        val base = context
        val providerPrefs = base.getSharedPreferences("secure_provider_store", Context.MODE_PRIVATE)
        providerPrefs.edit().clear().commit()
        SecureStoreHealth.resetForTests()

        fun provider(id: String) = JSONObject()
            .put("id", id)
            .put("name", id)
            .put("kind", "OPENAI_COMPATIBLE")
            .put("baseUrl", "https://example.invalid/v1")
            .put("model", "test-model")
            .put("enabled", true)
            .put("parentProviderId", "")
            .put("apiKey", "")

        assertTrue(SecureStore(base).replaceAllConfigs(listOf(
            provider("new-master"), provider("new-coder"), provider("gemini"), provider("groq")
        )))
        prefs.edit()
            .putString("agent_provider_MASTER", "old-master")
            .putString("selected_provider", "old-master")
            .putString("agent_provider_CODER", "old-coder")
            .putString("agent_provider_RESEARCHER", "old-researcher")
            .commit()

        // Model commit(false) where the previous durable assignment snapshot remains intact.
        val failingContext = AmbiguousCommitContext(base, "chat_state", applyBeforeFailure = false)
        assertNull(
            AgentProviderAssignmentStore.reconcileAfterProviderImport(
                failingContext,
                defaults,
                setOf("new-master", "new-coder"),
                "new-master"
            )
        )
        assertEquals("old-master", prefs.getString("agent_provider_MASTER", null))
        assertEquals("old-master", prefs.getString("selected_provider", null))

        // Restart: no import preference survives in chat_state, so recovery must use only
        // the current provider catalog and the persisted assignment snapshot.
        SharedPreferencesWriteHealth.resetForTests("chat_state")
        SecureStoreHealth.resetForTests()
        val recoveredCatalog = SecureStore(base).allConfigs().map { it.optString("id") }.toSet()
        assertEquals(setOf("new-master", "new-coder", "gemini", "groq"), recoveredCatalog)
        val recovered = AgentProviderAssignmentStore.reconcileExistingAssignments(
            base, defaults, recoveredCatalog, "old-master"
        )

        assertNotNull(recovered)
        assertTrue(recovered!!.values.all { it in recoveredCatalog })
        assertEquals("groq", recovered["MASTER"]) // role default is Groq; intended import preference was not durable
        assertEquals("groq", recovered["CODER"])
        assertEquals("groq", recovered["RESEARCHER"])
        assertEquals("groq", prefs.getString("selected_provider", null))
    }

    @Test fun oversizedRoleAndProviderIdsAreRejectedWithoutMutation() {
        assertFalse(mutateSave("R".repeat(81), "gemini"))
        assertFalse(mutateSave("MASTER", "P".repeat(161)))
        assertNull(prefs.getString("agent_provider_MASTER", null))
        assertNull(AgentProviderAssignmentStore.reconcileExistingAssignments(
            context, defaults, setOf("P".repeat(161)), "gemini"
        ))
        assertTrue(runCatching { AgentProviderAssignmentStore.read(context, mapOf("R".repeat(81) to "gemini")) }.isFailure)
    }

}
