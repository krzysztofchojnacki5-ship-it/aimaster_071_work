package pl.aimasterpro.app.core

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class QualityLearningStoreTest {
    private lateinit var context: Context

    @Before fun setUp() {
        SharedPreferencesWriteHealth.resetForTests("ai_quality_learning_v1")
        SharedPreferencesWriteHealth.resetForTests("ai_memory_audit_log_v1")
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("ai_memory_audit_log_v1", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("ai_quality_learning_v1", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("ai_master_memory_store_v1", Context.MODE_PRIVATE).edit().remove("memories").commit()
    }
    @After fun tearDown() {
        SharedPreferencesWriteHealth.resetForTests("ai_quality_learning_v1")
        SharedPreferencesWriteHealth.resetForTests("ai_memory_audit_log_v1")
        context.getSharedPreferences("ai_memory_audit_log_v1", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("ai_quality_learning_v1", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("ai_master_memory_store_v1", Context.MODE_PRIVATE).edit().remove("memories").commit()
    }

    @Test fun oversizedJournalFieldsFailClosedAndAreNotOverwritten() {
        val prefs = context.getSharedPreferences("ai_quality_learning_v1", Context.MODE_PRIVATE)
        val feedbackRaw = org.json.JSONArray().put(org.json.JSONObject()
            .put("id", "feedback-1").put("targetId", "task-1").put("targetType", "TASK")
            .put("rating", 1).put("comment", "x".repeat(1001)).put("timestamp", 10L)
            .put("agent", "").put("provider", "").put("model", "").put("category", "")
            .put("history", org.json.JSONArray())).toString()
        assertTrue(prefs.edit().putString("feedback", feedbackRaw).commit())
        QualityLearningStore.rate(context, "task-2", "TASK", 1, "Nowa ocena")
        assertEquals(feedbackRaw, prefs.getString("feedback", null))

        val proposalRaw = org.json.JSONArray().put(org.json.JSONObject()
            .put("id", "proposal-1").put("text", "x".repeat(3001)).put("status", "PROPOSAL")
            .put("created", 10L).put("updated", 10L).put("evidence", 1).put("confidence", "wstępna")
            .put("tasks", org.json.JSONArray()).put("agents", org.json.JSONArray()).put("models", org.json.JSONArray()))
            .toString()
        assertTrue(prefs.edit().putString("proposals", proposalRaw).commit())
        try {
            QualityLearningStore.addProposal(context, "Nowa propozycja", 1, "wstępna", emptyList(), emptyList(), emptyList())
            fail("Corrupt proposal journal must reject writes")
        } catch (_: IllegalStateException) { }
        assertEquals(proposalRaw, prefs.getString("proposals", null))
    }

    @Test fun ratingCanBeChangedWithoutDoubleCountingAndKeepsAuditHistory() {
        QualityLearningStore.rate(context, "task-1", "AGENT", 1, "Pomógł", "coder", "groq", "model-a")
        QualityLearningStore.rate(context, "task-1", "AGENT", -1, "Po poprawce jednak błędny", "coder", "groq", "model-a")
        val rows = QualityLearningStore.feedback(context)
        assertEquals(1, rows.size)
        assertEquals(-1, rows.single().rating)
        assertEquals(1, rows.single().history.size)
        assertEquals("Pomógł", rows.single().history.single().comment)
        assertEquals(1, rows.single().history.single().rating)
        assertEquals(1, QualityLearningStore.summary(context).total)
    }

    @Test fun proposalsOnlyEnterSharedMemoryAfterApproval() {
        val proposal = QualityLearningStore.addProposal(context, "Preferuj model A dla kodu", 8, "wstępna", listOf("t1"), listOf("coder"), listOf("groq/model-a"))
        assertFalse(AiMemoryStore.getMemories(context).any { it.contains("Preferuj model A") })
        assertTrue(QualityLearningStore.setProposalStatus(context, proposal.id, "APPROVED", "test-approval:${proposal.id}"))
        val audit = MemoryAuditLog.readVerified(context)
        assertTrue(audit != null && audit.any {
            it.mutation == "APPROVE_PROPOSAL" && it.objectId == proposal.id &&
                it.phase == MemoryAuditLog.Phase.COMMITTED && it.outcome == "SUCCESS"
        })
        assertTrue(AiMemoryStore.getMemories(context).any { it.contains("Preferuj model A") })
        assertEquals("APPROVED", QualityLearningStore.proposals(context).single().status)
    }

    @Test fun recoveryIsAuditedAndBoundToExactProposalReference() {
        val proposal = QualityLearningStore.addProposal(
            context, "Reguła do kontrolowanego odzyskania", 7, "wstępna",
            listOf("task-recovery-audit"), listOf("coder"), emptyList()
        )
        assertTrue(QualityLearningStore.setProposalStatus(
            context, proposal.id, "APPROVED", "test-approval:${proposal.id}"
        ))
        val memoryEntry = "[UCZENIE ZATWIERDZONE ${proposal.id.take(8)}; dowody=${proposal.evidenceCount}; pewność=${proposal.confidence}] ${proposal.text}".take(1000)
        // Simulate process death after the proposal status commit but before shared-memory persistence.
        assertTrue(context.getSharedPreferences("ai_master_memory_store_v1", Context.MODE_PRIVATE)
            .edit().putString("memories", "[]").commit())
        assertEquals(1, QualityLearningStore.syncApprovedAgentMemories(context))
        val records = MemoryAuditLog.readVerified(context)!!
        val recovery = records.filter { it.actor == "SYSTEM_RECOVERY" && it.objectId == proposal.id }
        assertEquals(2, recovery.size)
        assertTrue(recovery.all { it.approvalRef == "proposal:${proposal.id}" })
        assertEquals(listOf(MemoryAuditLog.Phase.INTENT, MemoryAuditLog.Phase.COMMITTED), recovery.map { it.phase })
    }

    @Test fun approvedSharedMemoryIsReconciledAfterInterruptedApprovalWrite() {
        val proposal = QualityLearningStore.addProposal(
            context, "Odtwarzalna zatwierdzona reguła pamięci", 9, "wstępna",
            listOf("task-recovery"), listOf("coder"), emptyList()
        )
        assertTrue(QualityLearningStore.setProposalStatus(context, proposal.id, "APPROVED", "test-approval:${proposal.id}"))
        val memoryEntry = "[UCZENIE ZATWIERDZONE ${proposal.id.take(8)}; dowody=${proposal.evidenceCount}; pewność=${proposal.confidence}] ${proposal.text}".take(1000)
        val removeRequest = MemoryGovernance.Request(MemoryGovernance.Actor.SYSTEM_RECOVERY,
            MemoryGovernance.Mutation.RECONCILE_APPROVED_PROPOSAL, proposal.id,
            "Test simulates an interrupted approval write", "proposal:${proposal.id}")
        assertTrue(MemoryMutationGate.execute(context, removeRequest) { permit ->
            AiMemoryStore.removeMemory(context, memoryEntry, permit, objectId = proposal.id)
        })
        assertFalse(AiMemoryStore.getMemories(context).contains(memoryEntry))
        QualityLearningStore.syncApprovedAgentMemories(context)
        assertTrue(AiMemoryStore.getMemories(context).contains(memoryEntry))
        assertTrue(AgentOperationalMemoryStore.all(context).any { "proposal:${proposal.id}" in it.sourceTaskIds })
    }

    @Test fun recoveryClosesOriginalInterruptedUserIntentAndDoesNotRepeatOnNextStartup() {
        val proposal = QualityLearningStore.addProposal(
            context, "Reguła zatwierdzona przed przerwaniem procesu", 9, "wstępna",
            listOf("task-original-intent"), listOf("coder"), emptyList()
        )
        val intent = MemoryAuditLog.Event(
            actor = MemoryGovernance.Actor.USER.name,
            mutation = MemoryGovernance.Mutation.APPROVE_PROPOSAL.name,
            objectId = proposal.id,
            reason = "Użytkownik zatwierdził propozycję przed przerwaniem procesu.",
            approvalRef = "ui-confirmation:${proposal.id}",
            phase = MemoryAuditLog.Phase.INTENT,
            outcome = "AUTHORIZED"
        )
        assertTrue(MemoryAuditLog.append(context, intent))

        // Simulate a crash after the proposal ledger was updated but before memory was written.
        val prefs = context.getSharedPreferences("ai_quality_learning_v1", Context.MODE_PRIVATE)
        val raw = prefs.getString("proposals", null)!!
        assertTrue(prefs.edit().putString("proposals", raw.replace("\"status\":\"PROPOSAL\"", "\"status\":\"APPROVED\"")).commit())

        assertEquals(1, QualityLearningStore.syncApprovedAgentMemories(context))
        assertFalse(MemoryAuditLog.hasUnresolvedIntent(context, "USER", "APPROVE_PROPOSAL", proposal.id))
        val resolved = MemoryAuditLog.readVerified(context)!!.last()
        assertEquals("USER", resolved.actor)
        assertEquals("APPROVE_PROPOSAL", resolved.mutation)
        assertEquals(MemoryAuditLog.Phase.COMMITTED, resolved.phase)
        assertEquals("RECOVERED_STATE", resolved.outcome)

        val countAfterRecovery = MemoryAuditLog.readVerified(context)!!.size
        assertEquals(0, QualityLearningStore.syncApprovedAgentMemories(context))
        assertEquals(countAfterRecovery, MemoryAuditLog.readVerified(context)!!.size)
    }

    @Test fun reconciliationRemovesRevokedMemoriesAfterInterruptedRejection() {
        val proposal = QualityLearningStore.addProposal(
            context, "Reguła cofnięta po przerwaniu procesu", 8, "wysoka",
            listOf("task-revoked"), listOf("coder"), emptyList()
        )
        assertTrue(QualityLearningStore.setProposalStatus(context, proposal.id, "APPROVED", "test-approval:${proposal.id}"))
        val memoryEntry = "[UCZENIE ZATWIERDZONE ${proposal.id.take(8)}; dowody=${proposal.evidenceCount}; pewność=${proposal.confidence}] ${proposal.text}".take(1000)
        assertTrue(AiMemoryStore.getMemories(context).contains(memoryEntry))
        assertTrue(AgentOperationalMemoryStore.all(context).any { "proposal:${proposal.id}" in it.sourceTaskIds })

        // Simulate process death after the REJECTED ledger commit but before memory cleanup.
        val prefs = context.getSharedPreferences("ai_quality_learning_v1", Context.MODE_PRIVATE)
        val persisted = prefs.getString("proposals", null)!!
        assertTrue(prefs.edit().putString("proposals", persisted.replace("\"status\":\"APPROVED\"", "\"status\":\"REJECTED\"")).commit())

        QualityLearningStore.syncApprovedAgentMemories(context)
        assertFalse(AiMemoryStore.getMemories(context).contains(memoryEntry))
        assertFalse(AgentOperationalMemoryStore.all(context).any { "proposal:${proposal.id}" in it.sourceTaskIds })
    }

    @Test fun approvedProposalRecoveryRemovesUnexpectedAgentCopies() {
        val proposal = QualityLearningStore.addProposal(
            context, "Lekcja tylko dla wskazanego agenta", 8, "wysoka",
            listOf("task-agent-scope"), listOf("coder"), emptyList()
        )
        assertTrue(QualityLearningStore.setProposalStatus(
            context, proposal.id, "APPROVED", "test-approval:${proposal.id}"
        ))
        val sourceRef = "proposal:${proposal.id}"
        val request = MemoryGovernance.Request(
            MemoryGovernance.Actor.SYSTEM_RECOVERY,
            MemoryGovernance.Mutation.RECONCILE_APPROVED_PROPOSAL,
            proposal.id,
            "Test wprowadza błędną kopię u innego agenta.",
            sourceRef
        )
        assertTrue(MemoryMutationGate.execute(context, request) { permit ->
            AgentOperationalMemoryStore.addApproved(
                context, "writer", proposal.text, permit, objectId = proposal.id,
                category = "test", sourceTaskIds = listOf(sourceRef), confidence = proposal.confidence
            ) != null
        })
        assertTrue(AgentOperationalMemoryStore.hasApprovedSource(context, "writer", proposal.text, sourceRef) == true)
        assertEquals(1, QualityLearningStore.syncApprovedAgentMemories(context))
        assertTrue(AgentOperationalMemoryStore.hasApprovedSource(context, "coder", proposal.text, sourceRef) == true)
        assertFalse(AgentOperationalMemoryStore.hasApprovedSource(context, "writer", proposal.text, sourceRef) == true)
    }

    @Test fun approvedProposalScopeNormalizationDoesNotTriggerRepeatedRecovery() {
        val proposal = QualityLearningStore.addProposal(
            context, "Lekcja dla agenta z nieznormalizowanym identyfikatorem", 8, "wysoka",
            listOf("task-agent-normalization"), listOf(" coder "), emptyList()
        )
        assertTrue(QualityLearningStore.setProposalStatus(
            context, proposal.id, "APPROVED", "test-approval:${proposal.id}"
        ))
        assertTrue(AgentOperationalMemoryStore.hasApprovedSource(
            context, "coder", proposal.text, "proposal:${proposal.id}"
        ) == true)

        val auditSizeBeforeSync = MemoryAuditLog.readVerified(context)!!.size
        assertEquals(0, QualityLearningStore.syncApprovedAgentMemories(context))
        assertEquals(auditSizeBeforeSync, MemoryAuditLog.readVerified(context)!!.size)
        assertEquals(0, QualityLearningStore.syncApprovedAgentMemories(context))
        assertEquals(auditSizeBeforeSync, MemoryAuditLog.readVerified(context)!!.size)
    }

    @Test fun unresolvedRecoveryWithAmbiguousStoreStateIsNotReplayed() {
        val proposal = QualityLearningStore.addProposal(
            context, "Reguła po przerwanym odzyskiwaniu", 8, "wysoka",
            listOf("task-ambiguous-recovery"), listOf("coder"), emptyList()
        )
        assertTrue(QualityLearningStore.setProposalStatus(context, proposal.id, "APPROVED", "test-approval:${proposal.id}"))
        val memoryEntry = "[UCZENIE ZATWIERDZONE ${proposal.id.take(8)}; dowody=${proposal.evidenceCount}; pewność=${proposal.confidence}] ${proposal.text}".take(1000)
        val sharedPrefs = context.getSharedPreferences("ai_master_memory_store_v1", Context.MODE_PRIVATE)
        assertTrue(sharedPrefs.edit().putString("memories", "[]").commit())
        assertFalse(AiMemoryStore.getMemories(context).contains(memoryEntry))
        assertTrue(AgentOperationalMemoryStore.all(context).any { "proposal:${proposal.id}" in it.sourceTaskIds })

        assertTrue(MemoryAuditLog.append(context, MemoryAuditLog.Event(
            actor = MemoryGovernance.Actor.SYSTEM_RECOVERY.name,
            mutation = MemoryGovernance.Mutation.RECONCILE_APPROVED_PROPOSAL.name,
            objectId = proposal.id,
            reason = "Symulacja przerwania odzyskiwania przed zakończeniem.",
            approvalRef = "proposal:${proposal.id}",
            phase = MemoryAuditLog.Phase.INTENT,
            outcome = "AUTHORIZED"
        )))
        val before = MemoryAuditLog.readVerified(context)!!.size

        assertEquals(0, QualityLearningStore.syncApprovedAgentMemories(context))
        assertFalse(AiMemoryStore.getMemories(context).contains(memoryEntry))
        assertTrue(AgentOperationalMemoryStore.all(context).any { "proposal:${proposal.id}" in it.sourceTaskIds })
        assertEquals(before, MemoryAuditLog.readVerified(context)!!.size)
        assertTrue(MemoryAuditLog.hasUnresolvedIntent(context, "SYSTEM_RECOVERY",
            "RECONCILE_APPROVED_PROPOSAL", proposal.id))
    }

    @Test fun corruptProposalLedgerDoesNotCauseReconciliationToDeleteExistingMemories() {
        val proposal = QualityLearningStore.addProposal(
            context, "Nie usuwaj przy nieczytelnym rejestrze", 6, "wstępna",
            listOf("task-corrupt"), listOf("coder"), emptyList()
        )
        assertTrue(QualityLearningStore.setProposalStatus(context, proposal.id, "APPROVED", "test-approval:${proposal.id}"))
        val memoryEntry = "[UCZENIE ZATWIERDZONE ${proposal.id.take(8)}; dowody=${proposal.evidenceCount}; pewność=${proposal.confidence}] ${proposal.text}".take(1000)
        val prefs = context.getSharedPreferences("ai_quality_learning_v1", Context.MODE_PRIVATE)
        val corrupt = """[{"id":"broken","text":"missing required fields"}]"""
        assertTrue(prefs.edit().putString("proposals", corrupt).commit())

        assertEquals(0, QualityLearningStore.syncApprovedAgentMemories(context))
        assertTrue(AiMemoryStore.getMemories(context).contains(memoryEntry))
        assertTrue(AgentOperationalMemoryStore.all(context).any { "proposal:${proposal.id}" in it.sourceTaskIds })
        assertEquals(corrupt, prefs.getString("proposals", null))
    }

    @Test fun proposalCannotRemainApprovedWhenDurableSharedMemoryWriteFails() {
        val proposal = QualityLearningStore.addProposal(
            context, "Reguła wymagająca trwałego zapisu", 7, "wstępna", emptyList(), emptyList(), emptyList()
        )
        val memoryPrefs = context.getSharedPreferences("ai_master_memory_store_v1", Context.MODE_PRIVATE)
        val corrupt = """["valid entry",null]"""
        assertTrue(memoryPrefs.edit().putString("memories", corrupt).commit())
        assertFalse(QualityLearningStore.setProposalStatus(context, proposal.id, "APPROVED", "test-approval:${proposal.id}"))
        assertEquals("PROPOSAL", QualityLearningStore.proposals(context).single().status)
        assertEquals(corrupt, memoryPrefs.getString("memories", null))
    }

    @Test fun ambiguousAgentApprovalWriteIsReconciledAfterPreferenceStoreRepair() {
        val proposal = QualityLearningStore.addProposal(
            context, "Reguła po niejednoznacznym zapisie agenta", 9, "wysoka",
            listOf("task-ambiguous-agent-write"), listOf("coder"), emptyList()
        )
        val agentPrefsName = "ai_agent_operational_memory_v1"
        val failingContext = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val delegatePrefs = context.getSharedPreferences(name, mode)
                if (name != agentPrefsName) return delegatePrefs
                return object : SharedPreferences by delegatePrefs {
                    override fun edit(): SharedPreferences.Editor {
                        val delegateEditor = delegatePrefs.edit()
                        return object : SharedPreferences.Editor by delegateEditor {
                            // Editor mutators normally return the delegated editor instance.
                            // Override putString so the subsequent commit is routed through this
                            // fault-injecting wrapper instead of bypassing it.
                            override fun putString(key: String, value: String?): SharedPreferences.Editor {
                                delegateEditor.putString(key, value)
                                return this
                            }

                            override fun commit(): Boolean {
                                // Persist the write, then report failure: this is the ambiguous
                                // outcome SharedPreferencesWriteHealth must quarantine.
                                delegateEditor.commit()
                                return false
                            }
                        }
                    }
                }
            }
        }

        assertFalse(QualityLearningStore.setProposalStatus(
            failingContext, proposal.id, "APPROVED", "test-ambiguous-agent:" + proposal.id
        ))

        // Agent memory may already contain the approval marker even though commit returned
        // false. The file is quarantined, so immediate compensation cannot safely inspect it.
        assertEquals("PROPOSAL", QualityLearningStore.proposals(context).single().status)
        assertTrue(SharedPreferencesWriteHealth.isUncertain(agentPrefsName))
        assertTrue(MemoryAuditLog.hasUnresolvedIntent(
            context, "USER", "APPROVE_PROPOSAL", proposal.id
        ))

        // Model a process restart/store repair: clear only the in-process quarantine, then
        // reconcile against the durable PROPOSAL ledger. No stale agent lesson may survive.
        SharedPreferencesWriteHealth.resetForTests(agentPrefsName)
        assertEquals(true, AgentOperationalMemoryStore.hasAnySource(
            context, "proposal:" + proposal.id
        ))
        assertEquals(0, QualityLearningStore.syncApprovedAgentMemories(context))
        assertEquals("PROPOSAL", QualityLearningStore.proposals(context).single().status)
        assertFalse(AiMemoryStore.getMemories(context).any { it.contains("Reguła po niejednoznacznym zapisie agenta") })
        assertEquals(false, AgentOperationalMemoryStore.hasAnySource(
            context, "proposal:" + proposal.id
        ))
        assertFalse(MemoryAuditLog.hasUnresolvedIntent(
            context, "USER", "APPROVE_PROPOSAL", proposal.id
        ))
        assertEquals(0, QualityLearningStore.syncApprovedAgentMemories(context))
    }

    @Test fun rejectionPartialCleanupFailureIsRecoveredFromApprovedLedgerAfterStoreRepair() {
        val proposal = QualityLearningStore.addProposal(
            context, "Reguła odzyskiwana po niepełnym cofnięciu", 8, "wysoka",
            listOf("task-reject-compensation"), listOf("coder"), emptyList()
        )
        assertTrue(QualityLearningStore.setProposalStatus(
            context, proposal.id, "APPROVED", "test-approve:${proposal.id}"
        ))
        val memoryEntry = "[UCZENIE ZATWIERDZONE ${proposal.id.take(8)}; dowody=${proposal.evidenceCount}; pewność=${proposal.confidence}] ${proposal.text}".take(1000)
        val agentPrefs = context.getSharedPreferences("ai_agent_operational_memory_v1", Context.MODE_PRIVATE)
        val originalAgentLedger = agentPrefs.getString("entries", null)!!
        assertTrue(AiMemoryStore.containsExactMemory(context, memoryEntry) == true)
        assertTrue(AgentOperationalMemoryStore.hasApprovedSource(
            context, "coder", proposal.text, "proposal:${proposal.id}"
        ) == true)

        // Make the agent ledger unreadable after approval. Rejection can remove shared memory,
        // but cannot safely determine or remove agent provenance from the damaged store.
        assertTrue(agentPrefs.edit().putString("entries", "not-json").commit())
        assertFalse(QualityLearningStore.setProposalStatus(
            context, proposal.id, "REJECTED", "test-reject:${proposal.id}"
        ))

        // Compensation restores APPROVED status, but shared-memory removal already succeeded.
        // The failed USER rejection remains unresolved until recovery.
        assertEquals("APPROVED", QualityLearningStore.proposals(context).single().status)
        assertFalse(AiMemoryStore.containsExactMemory(context, memoryEntry) == true)
        assertTrue(MemoryAuditLog.hasUnresolvedIntent(
            context, "USER", "REJECT_PROPOSAL", proposal.id
        ))

        // Repair the damaged agent store, then verify startup reconciliation restores
        // the approved state and closes the original failed USER operation.
        assertTrue(agentPrefs.edit().putString("entries", originalAgentLedger).commit())
        assertEquals(1, QualityLearningStore.syncApprovedAgentMemories(context))
        assertTrue(AiMemoryStore.containsExactMemory(context, memoryEntry) == true)
        assertTrue(AgentOperationalMemoryStore.hasApprovedSource(
            context, "coder", proposal.text, "proposal:${proposal.id}"
        ) == true)
        assertFalse(MemoryAuditLog.hasUnresolvedIntent(
            context, "USER", "REJECT_PROPOSAL", proposal.id
        ))
        assertEquals(0, QualityLearningStore.syncApprovedAgentMemories(context))
    }
    @Test fun revokingApprovalRemovesThePreviouslyInjectedRule() {
        val proposal = QualityLearningStore.addProposal(context, "Reguła do cofnięcia", 6, "wstępna", emptyList(), emptyList(), emptyList())
        QualityLearningStore.setProposalStatus(context, proposal.id, "APPROVED", "test-approval:${proposal.id}")
        assertTrue(AiMemoryStore.getMemories(context).any { it.contains("Reguła do cofnięcia") })
        QualityLearningStore.setProposalStatus(context, proposal.id, "REJECTED", "test-approval:${proposal.id}")
        assertFalse(AiMemoryStore.getMemories(context).any { it.contains("Reguła do cofnięcia") })
    }

    @Test fun rejectedProposalNeverEntersMemory() {
        val proposal = QualityLearningStore.addProposal(context, "Zła reguła", 2, "niska", emptyList(), emptyList(), emptyList())
        QualityLearningStore.setProposalStatus(context, proposal.id, "REJECTED", "test-approval:${proposal.id}")
        assertFalse(AiMemoryStore.getMemories(context).any { it.contains("Zła reguła") })
    }
    @Test fun malformedFeedbackJournalIsNotOverwrittenByNewRating() {
        val prefs = context.getSharedPreferences("ai_quality_learning_v1", Context.MODE_PRIVATE)
        val corrupt = """[{"id":"rating-1","targetId":"task-1","targetType":"AGENT"},null]"""
        assertTrue(prefs.edit().putString("feedback", corrupt).commit())
        QualityLearningStore.rate(context, "task-2", "AGENT", 1, "Nowa ocena", "coder")
        assertEquals(corrupt, prefs.getString("feedback", null))
    }

    @Test fun malformedProposalJournalBlocksMutationWithoutReplacingEvidence() {
        val prefs = context.getSharedPreferences("ai_quality_learning_v1", Context.MODE_PRIVATE)
        val corrupt = """[{"id":"proposal-1","text":"old proposal"},null]"""
        assertTrue(prefs.edit().putString("proposals", corrupt).commit())
        assertFalse(QualityLearningStore.setProposalStatus(context, "proposal-1", "APPROVED", ""))
        assertTrue(runCatching {
            QualityLearningStore.addProposal(context, "new proposal", 1, "low", emptyList(), emptyList(), emptyList())
        }.isFailure)
        assertEquals(corrupt, prefs.getString("proposals", null))
    }

    @Test fun fractionalRatingAndNestedAuditCountersInvalidateFeedbackJournal() {
        val prefs = context.getSharedPreferences("ai_quality_learning_v1", Context.MODE_PRIVATE)
        val fractional = """[{"id":"r1","targetId":"task-1","targetType":"AGENT","rating":1.5,"comment":"","timestamp":1,"agent":"coder","provider":"groq","model":"m","category":""}]"""
        assertTrue(prefs.edit().putString("feedback", fractional).commit())
        assertTrue(QualityLearningStore.feedback(context).isEmpty())
        QualityLearningStore.rate(context, "task-2", "AGENT", 1, "Nowa")
        assertEquals(fractional, prefs.getString("feedback", null))
        val badHistory = """[{"id":"r2","targetId":"task-1","targetType":"AGENT","rating":1,"comment":"","timestamp":1,"agent":"coder","provider":"groq","model":"m","category":"","history":[{"rating":-1.5,"comment":"old","timestamp":1}]}]"""
        assertTrue(prefs.edit().putString("feedback", badHistory).commit())
        assertTrue(QualityLearningStore.feedback(context).isEmpty())
        assertEquals(badHistory, prefs.getString("feedback", null))
    }

    @Test fun duplicateCurrentRatingsForOneTargetInvalidateFeedbackJournal() {
        val prefs = context.getSharedPreferences("ai_quality_learning_v1", Context.MODE_PRIVATE)
        val first = """{"id":"r1","targetId":"same-task","targetType":"AGENT","rating":1,"comment":"","timestamp":1,"agent":"coder","provider":"groq","model":"m","category":""}"""
        val second = first.replace("\"id\":\"r1\"", "\"id\":\"r2\"").replace("\"rating\":1", "\"rating\":-1")
        val corrupt = "[$first,$second]"
        assertTrue(prefs.edit().putString("feedback", corrupt).commit())
        assertTrue(QualityLearningStore.feedback(context).isEmpty())
        QualityLearningStore.rate(context, "other-task", "AGENT", 1, "new")
        assertEquals(corrupt, prefs.getString("feedback", null))
    }

    @Test fun fractionalProposalEvidenceCounterBlocksProposalMutation() {
        val prefs = context.getSharedPreferences("ai_quality_learning_v1", Context.MODE_PRIVATE)
        val corrupt = """[{"id":"p1","text":"proposal","status":"PROPOSAL","created":1,"updated":1,"evidence":2.5,"confidence":"low","tasks":[],"agents":[],"models":[]}]"""
        assertTrue(prefs.edit().putString("proposals", corrupt).commit())
        assertTrue(QualityLearningStore.proposals(context).isEmpty())
        assertFalse(QualityLearningStore.setProposalStatus(context, "p1", "APPROVED", ""))
        assertEquals(corrupt, prefs.getString("proposals", null))
    }

    @Test fun sameTargetIdCanHaveDifferentTargetTypes() {
        QualityLearningStore.rate(context, "shared-id", "AGENT", 1, "Raport agenta", "coder", "groq", "model-a")
        QualityLearningStore.rate(context, "shared-id", "CHAT_ANSWER", -1, "Odpowiedź końcowa", "MASTER", "openrouter", "model-b")
        val rows = QualityLearningStore.feedback(context)
        assertEquals(2, rows.size)
        assertTrue(rows.any { it.targetType == "AGENT" && it.rating == 1 })
        assertTrue(rows.any { it.targetType == "CHAT_ANSWER" && it.rating == -1 })
    }

    @Test fun reratingWithoutMetadataPreservesOriginalAgentAndModel() {
        QualityLearningStore.rate(context, "task-meta", "AGENT", 1, "Dobra odpowiedź", "coder", "groq", "model-a", "code")
        QualityLearningStore.rate(context, "task-meta", "AGENT", -1, "Po weryfikacji błąd")
        val row = QualityLearningStore.feedback(context).single()
        assertEquals("coder", row.agentId)
        assertEquals("groq", row.providerId)
        assertEquals("model-a", row.model)
        assertEquals("code", row.category)
    }

    @Test fun analysisContextIsBoundedAndDoesNotIncludeFullLongComments() {
        repeat(30) { index ->
            QualityLearningStore.rate(context, "task-$index", "AGENT", if (index % 2 == 0) 1 else -1,
                "X".repeat(1000), "coder", "groq", "model-a")
        }
        val analysis = QualityLearningStore.analysisContext(context)
        assertTrue(analysis.length <= 12000)
        assertFalse(analysis.contains("X".repeat(500)))
    }

    @Test fun learnedQualityRequiresFiveIndependentAgentTasks() {
        repeat(4) { index ->
            QualityLearningStore.rate(context, "cold-$index", "AGENT", -1, "", "coder", "groq", "model-a", "Inżynieria")
        }
        var rows = QualityLearningStore.feedback(context)
        assertNull(QualityLearningStore.qualityScore(rows, "coder", "Inżynieria"))
        QualityLearningStore.rate(context, "cold-4", "AGENT", -1, "", "coder", "groq", "model-a", "Inżynieria")
        rows = QualityLearningStore.feedback(context)
        assertNotNull(QualityLearningStore.qualityScore(rows, "coder", "Inżynieria"))
        assertTrue(QualityLearningStore.qualityScore(rows, "coder", "Inżynieria")!! < 0.5)
    }

    @Test fun duplicateRatingForSameTaskDoesNotIncreaseLearningSampleCount() {
        repeat(4) { index ->
            QualityLearningStore.rate(context, "same-$index", "AGENT", 1, "", "coder", "groq", "model-a", "Inżynieria")
        }
        QualityLearningStore.rate(context, "same-0", "AGENT", -1, "Zmiana oceny", "coder", "groq", "model-a", "Inżynieria")
        assertNull(QualityLearningStore.qualityScore(QualityLearningStore.feedback(context), "coder", "Inżynieria"))
    }

    @Test fun chatAnswerRatingsDoNotPolluteSpecialistAgentRanking() {
        QualityLearningStore.rate(context, "agent-task", "AGENT", 1, "Raport dobry", "coder", "groq", "model-a", "Inżynieria")
        QualityLearningStore.rate(context, "chat-task", "CHAT_ANSWER", -1, "Synteza słaba", "MASTER", "openrouter", "model-b", "Inżynieria")
        val summary = QualityLearningStore.summary(context)
        assertEquals(2, summary.total)
        assertEquals(1, summary.ratedAgents)
        val analysis = QualityLearningStore.analysisContext(context)
        val agentSection = analysis.substringAfter("OCENY UŻYTKOWNIKA — AGENT:").substringBefore("OCENY UŻYTKOWNIKA — MODEL/PROVIDER:")
        assertTrue(agentSection.contains("coder"))
        assertFalse(agentSection.contains("MASTER"))
    }

    @Test fun ambiguousCommitQuarantinesBothQualityLedgers() {
        val prefs = context.getSharedPreferences("ai_quality_learning_v1", Context.MODE_PRIVATE)
        val originalFeedback = "[]"
        val originalProposals = "[]"
        assertTrue(prefs.edit().putString("feedback", originalFeedback).putString("proposals", originalProposals).commit())
        val failingEditor = object : android.content.SharedPreferences.Editor by prefs.edit().putString("feedback", "[ambiguous]") {
            override fun commit(): Boolean = false
        }
        assertFalse(SharedPreferencesWriteHealth.commit("ai_quality_learning_v1", failingEditor))

        QualityLearningStore.rate(context, "task-1", "AGENT", 1, "Nie zapisuj")
        assertTrue(QualityLearningStore.feedback(context).isEmpty())
        assertTrue(QualityLearningStore.proposals(context).isEmpty())
        assertFalse(QualityLearningStore.setProposalStatus(context, "missing", "APPROVED", ""))
        assertTrue(runCatching {
            QualityLearningStore.addProposal(context, "Nie zapisuj", 1, "low", emptyList(), emptyList(), emptyList())
        }.isFailure)
        assertEquals(originalFeedback, prefs.getString("feedback", null))
        assertEquals(originalProposals, prefs.getString("proposals", null))
    }

    @Test fun ambiguousSharedApprovalWriteIsRemovedByStartupReconciliation() {
        val proposal = QualityLearningStore.addProposal(
            context, "Reguła po niejednoznacznym zapisie pamięci wspólnej", 8, "wysoka",
            listOf("task-ambiguous-shared-write"), listOf("coder"), emptyList()
        )
        val sharedPrefsName = "ai_master_memory_store_v1"
        val failingContext = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val delegatePrefs = context.getSharedPreferences(name, mode)
                if (name != sharedPrefsName) return delegatePrefs
                return object : SharedPreferences by delegatePrefs {
                    override fun edit(): SharedPreferences.Editor {
                        val delegateEditor = delegatePrefs.edit()
                        return object : SharedPreferences.Editor by delegateEditor {
                            override fun putString(key: String, value: String?): SharedPreferences.Editor {
                                delegateEditor.putString(key, value)
                                return this
                            }

                            override fun commit(): Boolean {
                                delegateEditor.commit()
                                return false
                            }
                        }
                    }
                }
            }
        }

        assertFalse(QualityLearningStore.setProposalStatus(
            failingContext, proposal.id, "APPROVED", "test-ambiguous-shared:" + proposal.id
        ))
        assertEquals("PROPOSAL", QualityLearningStore.proposals(context).single().status)
        assertTrue(SharedPreferencesWriteHealth.isUncertain(sharedPrefsName))
        assertTrue(MemoryAuditLog.hasUnresolvedIntent(
            context, "USER", "APPROVE_PROPOSAL", proposal.id
        ))

        // The shared write reached disk before reporting failure. Compensation could not
        // inspect that quarantined store; after repair, startup must remove the stale lesson.
        SharedPreferencesWriteHealth.resetForTests(sharedPrefsName)
        val memoryEntry = "[UCZENIE ZATWIERDZONE ${proposal.id.take(8)}; dowody=${proposal.evidenceCount}; pewność=${proposal.confidence}] ${proposal.text}".take(1000)
        assertTrue(AiMemoryStore.containsExactMemory(context, memoryEntry) == true)
        assertFalse(AgentOperationalMemoryStore.hasAnySource(context, "proposal:" + proposal.id) == true)
        assertEquals(0, QualityLearningStore.syncApprovedAgentMemories(context))
        assertFalse(AiMemoryStore.containsExactMemory(context, memoryEntry) == true)
        assertEquals("PROPOSAL", QualityLearningStore.proposals(context).single().status)
        assertFalse(MemoryAuditLog.hasUnresolvedIntent(
            context, "USER", "APPROVE_PROPOSAL", proposal.id
        ))
        assertEquals(0, QualityLearningStore.syncApprovedAgentMemories(context))
    }

    @Test fun failedAgentApprovalWriteWithoutDurableCommitCompensatesSharedMemory() {
        val proposal = QualityLearningStore.addProposal(
            context, "Reguła po niezapisanym błędzie agenta", 8, "wysoka",
            listOf("task-agent-write-not-persisted"), listOf("coder"), emptyList()
        )
        val agentPrefsName = "ai_agent_operational_memory_v1"
        val failingContext = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val delegatePrefs = context.getSharedPreferences(name, mode)
                if (name != agentPrefsName) return delegatePrefs
                return object : SharedPreferences by delegatePrefs {
                    override fun edit(): SharedPreferences.Editor {
                        val delegateEditor = delegatePrefs.edit()
                        return object : SharedPreferences.Editor by delegateEditor {
                            override fun putString(key: String, value: String?): SharedPreferences.Editor {
                                delegateEditor.putString(key, value)
                                return this
                            }

                            override fun commit(): Boolean = false
                        }
                    }
                }
            }
        }

        assertFalse(QualityLearningStore.setProposalStatus(
            failingContext, proposal.id, "APPROVED", "test-agent-no-persist:" + proposal.id
        ))
        assertEquals("PROPOSAL", QualityLearningStore.proposals(context).single().status)
        assertTrue(SharedPreferencesWriteHealth.isUncertain(agentPrefsName))
        val memoryEntry = "[UCZENIE ZATWIERDZONE ${proposal.id.take(8)}; dowody=${proposal.evidenceCount}; pewność=${proposal.confidence}] ${proposal.text}".take(1000)
        assertFalse(AiMemoryStore.containsExactMemory(context, memoryEntry) == true)
        assertTrue(MemoryAuditLog.hasUnresolvedIntent(
            context, "USER", "APPROVE_PROPOSAL", proposal.id
        ))

        // The failed agent commit did not persist. Once its quarantine is cleared, the
        // proposal ledger and both memory stores agree on the original PROPOSAL state.
        SharedPreferencesWriteHealth.resetForTests(agentPrefsName)
        assertFalse(AgentOperationalMemoryStore.hasAnySource(context, "proposal:" + proposal.id) == true)
        val beforeSync = MemoryAuditLog.readVerified(context)!!.size
        assertEquals(0, QualityLearningStore.syncApprovedAgentMemories(context))
        assertEquals(beforeSync + 1, MemoryAuditLog.readVerified(context)!!.size)
        assertFalse(MemoryAuditLog.hasUnresolvedIntent(
            context, "USER", "APPROVE_PROPOSAL", proposal.id
        ))
        assertFalse(MemoryAuditLog.readVerified(context)!!.any {
            it.actor == "SYSTEM_RECOVERY" && it.objectId == proposal.id
        })
    }

    @Test fun failedStatusRollbackWithConsistentApprovedMemoriesClosesIntentWithoutRecovery() {
        val proposal = QualityLearningStore.addProposal(
            context, "Reguła po nieudanym cofnięciu statusu", 8, "wysoka",
            listOf("task-status-rollback-only"), listOf("coder"), emptyList()
        )
        val qualityPrefsName = "ai_quality_learning_v1"
        val sharedPrefsName = "ai_master_memory_store_v1"
        val agentPrefsName = "ai_agent_operational_memory_v1"
        var qualityCommitCount = 0
        val failingContext = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val delegatePrefs = context.getSharedPreferences(name, mode)
                if (name !in setOf(qualityPrefsName, sharedPrefsName, agentPrefsName)) return delegatePrefs
                return object : SharedPreferences by delegatePrefs {
                    override fun edit(): SharedPreferences.Editor {
                        val delegateEditor = delegatePrefs.edit()
                        return object : SharedPreferences.Editor by delegateEditor {
                            override fun putString(key: String, value: String?): SharedPreferences.Editor {
                                delegateEditor.putString(key, value)
                                return this
                            }

                            override fun commit(): Boolean {
                                return when (name) {
                                    qualityPrefsName -> {
                                        qualityCommitCount++
                                        if (qualityCommitCount == 1) delegateEditor.commit() else false
                                    }
                                    // Both memory writes reach durable storage but report an
                                    // ambiguous failure. Quarantine prevents compensation from
                                    // inspecting/removing either write during this process.
                                    sharedPrefsName, agentPrefsName -> {
                                        delegateEditor.commit()
                                        false
                                    }
                                    else -> false
                                }
                            }
                        }
                    }
                }
            }
        }

        assertFalse(QualityLearningStore.setProposalStatus(
            failingContext, proposal.id, "APPROVED", "test-status-rollback-only:" + proposal.id
        ))
        assertEquals(2, qualityCommitCount)
        assertTrue(SharedPreferencesWriteHealth.isUncertain(qualityPrefsName))
        assertTrue(SharedPreferencesWriteHealth.isUncertain(sharedPrefsName))
        assertTrue(SharedPreferencesWriteHealth.isUncertain(agentPrefsName))
        assertTrue(MemoryAuditLog.hasUnresolvedIntent(
            context, "USER", "APPROVE_PROPOSAL", proposal.id
        ))

        // All three durable writes reached storage, but each reported an ambiguous failure.
        // After simulating process restart, the proposal ledger and both memory stores agree.
        SharedPreferencesWriteHealth.resetForTests(qualityPrefsName)
        SharedPreferencesWriteHealth.resetForTests(sharedPrefsName)
        SharedPreferencesWriteHealth.resetForTests(agentPrefsName)
        assertEquals("APPROVED", QualityLearningStore.proposals(context).single().status)
        val memoryEntry = "[UCZENIE ZATWIERDZONE ${proposal.id.take(8)}; dowody=${proposal.evidenceCount}; pewność=${proposal.confidence}] ${proposal.text}".take(1000)
        assertTrue(AiMemoryStore.containsExactMemory(context, memoryEntry) == true)
        assertTrue(AgentOperationalMemoryStore.hasApprovedSource(
            context, "coder", proposal.text, "proposal:" + proposal.id
        ) == true)
        val beforeSync = MemoryAuditLog.readVerified(context)!!.size
        assertEquals(0, QualityLearningStore.syncApprovedAgentMemories(context))
        assertEquals(beforeSync + 1, MemoryAuditLog.readVerified(context)!!.size)
        assertFalse(MemoryAuditLog.hasUnresolvedIntent(
            context, "USER", "APPROVE_PROPOSAL", proposal.id
        ))
        assertFalse(MemoryAuditLog.readVerified(context)!!.any {
            it.actor == "SYSTEM_RECOVERY" && it.objectId == proposal.id
        })
    }

    @Test fun failedApprovalCompensationLeavesDurableLedgerForExplicitStartupRecovery() {
        val proposal = QualityLearningStore.addProposal(
            context, "Reguła po nieudanym rollbacku statusu", 9, "wysoka",
            listOf("task-approval-rollback"), listOf("coder"), emptyList()
        )
        val qualityPrefsName = "ai_quality_learning_v1"
        val agentPrefsName = "ai_agent_operational_memory_v1"
        val agentPrefs = context.getSharedPreferences(agentPrefsName, Context.MODE_PRIVATE)
        assertTrue(agentPrefs.edit().putString("entries", "not-json").commit())

        // First quality-ledger commit persists APPROVED; the second (compensating rollback)
        // fails. The wrapper returns itself from putString so commit interception is reliable.
        var qualityCommitCount = 0
        val failingContext = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val delegatePrefs = context.getSharedPreferences(name, mode)
                if (name != qualityPrefsName) return delegatePrefs
                return object : SharedPreferences by delegatePrefs {
                    override fun edit(): SharedPreferences.Editor {
                        val delegateEditor = delegatePrefs.edit()
                        return object : SharedPreferences.Editor by delegateEditor {
                            override fun putString(key: String, value: String?): SharedPreferences.Editor {
                                delegateEditor.putString(key, value)
                                return this
                            }

                            override fun commit(): Boolean {
                                qualityCommitCount++
                                return if (qualityCommitCount == 1) {
                                    delegateEditor.commit()
                                } else {
                                    false
                                }
                            }
                        }
                    }
                }
            }
        }

        assertFalse(QualityLearningStore.setProposalStatus(
            failingContext, proposal.id, "APPROVED", "test-rollback-failure:${proposal.id}"
        ))
        assertEquals(2, qualityCommitCount)
        assertTrue(SharedPreferencesWriteHealth.isUncertain(qualityPrefsName))
        assertTrue(MemoryAuditLog.hasUnresolvedIntent(
            context, "USER", "APPROVE_PROPOSAL", proposal.id
        ))

        // The status write was durable although the API correctly returned false.
        // After repairing the unreadable agent ledger, startup reconciliation follows the
        // durable proposal ledger and completes the explicitly user-authorized approval.
        SharedPreferencesWriteHealth.resetForTests(qualityPrefsName)
        assertEquals("APPROVED", QualityLearningStore.proposals(context).single().status)
        assertTrue(agentPrefs.edit().putString("entries", "[]").commit())
        assertEquals(1, QualityLearningStore.syncApprovedAgentMemories(context))
        val memoryEntry = "[UCZENIE ZATWIERDZONE ${proposal.id.take(8)}; dowody=${proposal.evidenceCount}; pewność=${proposal.confidence}] ${proposal.text}".take(1000)
        assertTrue(AiMemoryStore.containsExactMemory(context, memoryEntry) == true)
        assertTrue(AgentOperationalMemoryStore.hasApprovedSource(
            context, "coder", proposal.text, "proposal:${proposal.id}"
        ) == true)
        assertFalse(MemoryAuditLog.hasUnresolvedIntent(
            context, "USER", "APPROVE_PROPOSAL", proposal.id
        ))
        val audit = MemoryAuditLog.readVerified(context)!!
        assertTrue(audit.any {
            it.actor == "SYSTEM_RECOVERY" && it.objectId == proposal.id &&
                it.phase == MemoryAuditLog.Phase.COMMITTED && it.outcome == "SUCCESS"
        })
        assertEquals(0, QualityLearningStore.syncApprovedAgentMemories(context))
    }


    @Test fun consistentStoresClosePendingUserIntentWithoutStartingRecovery() {
        val proposal = QualityLearningStore.addProposal(
            context, "Spójny stan bez ponownej naprawy", 7, "wstępna",
            listOf("task-consistent-user-close"), listOf("coder"), emptyList()
        )
        assertTrue(MemoryAuditLog.append(context, MemoryAuditLog.Event(
            actor = "USER", mutation = "APPROVE_PROPOSAL", objectId = proposal.id,
            reason = "Test przerwania przed zmianą trwałego rejestru.",
            approvalRef = "test:${proposal.id}", phase = MemoryAuditLog.Phase.INTENT,
            outcome = "AUTHORIZED"
        )))

        val before = MemoryAuditLog.readVerified(context)!!.size
        assertEquals(0, QualityLearningStore.syncApprovedAgentMemories(context))
        val rows = MemoryAuditLog.readVerified(context)!!
        assertEquals(before + 1, rows.size)
        assertEquals("RECOVERED_STATE", rows.last().outcome)
        assertEquals("USER", rows.last().actor)
        assertFalse(rows.any { it.actor == "SYSTEM_RECOVERY" && it.objectId == proposal.id })
        assertFalse(MemoryAuditLog.hasUnresolvedIntent(context, "USER", "APPROVE_PROPOSAL", proposal.id))
    }

    @Test fun failedRecoveryAuditClosureKeepsUserIntentOpenUntilRecoveryIsClosed() {
        val proposal = QualityLearningStore.addProposal(
            context, "Odzyskiwanie wymaga zamknięcia audytu", 9, "wstępna",
            listOf("task-recovery-close-order"), listOf("coder"), emptyList()
        )
        assertTrue(QualityLearningStore.setProposalStatus(
            context, proposal.id, "APPROVED", "test-approval:${proposal.id}"
        ))
        assertTrue(MemoryAuditLog.append(context, MemoryAuditLog.Event(
            actor = "USER", mutation = "REJECT_PROPOSAL", objectId = proposal.id,
            reason = "Test nierozstrzygniętego odrzucenia.", approvalRef = "test-reject:${proposal.id}",
            phase = MemoryAuditLog.Phase.INTENT, outcome = "AUTHORIZED"
        )))
        assertTrue(MemoryAuditLog.append(context, MemoryAuditLog.Event(
            actor = "SYSTEM_RECOVERY", mutation = "RECONCILE_APPROVED_PROPOSAL",
            objectId = proposal.id, reason = "Test przerwanego zamknięcia odzyskiwania.",
            approvalRef = "proposal:${proposal.id}", phase = MemoryAuditLog.Phase.INTENT,
            outcome = "AUTHORIZED"
        )))

        val failingContext = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val delegate = context.getSharedPreferences(name, mode)
                if (name != "ai_memory_audit_log_v1") return delegate
                return object : SharedPreferences by delegate {
                    override fun edit(): SharedPreferences.Editor {
                        val editor = delegate.edit()
                        return object : SharedPreferences.Editor by editor {
                            override fun putString(key: String, value: String?): SharedPreferences.Editor {
                                editor.putString(key, value)
                                return this
                            }
                            override fun commit(): Boolean = false
                        }
                    }
                }
            }
        }

        assertEquals(0, QualityLearningStore.syncApprovedAgentMemories(failingContext))
        assertTrue(MemoryAuditLog.hasUnresolvedIntent(
            context, "SYSTEM_RECOVERY", "RECONCILE_APPROVED_PROPOSAL", proposal.id
        ))
        assertTrue(MemoryAuditLog.hasUnresolvedIntent(
            context, "USER", "REJECT_PROPOSAL", proposal.id
        ))

        SharedPreferencesWriteHealth.resetForTests("ai_memory_audit_log_v1")
        assertEquals(0, QualityLearningStore.syncApprovedAgentMemories(context))
        val rows = MemoryAuditLog.readVerified(context)!!
        assertFalse(MemoryAuditLog.hasUnresolvedIntent(
            context, "SYSTEM_RECOVERY", "RECONCILE_APPROVED_PROPOSAL", proposal.id
        ))
        assertFalse(MemoryAuditLog.hasUnresolvedIntent(
            context, "USER", "REJECT_PROPOSAL", proposal.id
        ))
        assertEquals(1, rows.count {
            it.actor == "SYSTEM_RECOVERY" && it.mutation == "RECONCILE_APPROVED_PROPOSAL" &&
                it.objectId == proposal.id && it.phase == MemoryAuditLog.Phase.INTENT
        })
    }

    @Test fun failedUserAuditClosureDoesNotTriggerRedundantRecoveryOnNextStartup() {
        val proposal = QualityLearningStore.addProposal(
            context, "Ponowienie samego zamknięcia audytu", 7, "wstępna",
            listOf("task-user-close-retry"), listOf("coder"), emptyList()
        )
        assertTrue(MemoryAuditLog.append(context, MemoryAuditLog.Event(
            actor = "USER", mutation = "APPROVE_PROPOSAL", objectId = proposal.id,
            reason = "Test przerwania przed zmianą trwałego rejestru.",
            approvalRef = "test-user-close:${proposal.id}", phase = MemoryAuditLog.Phase.INTENT,
            outcome = "AUTHORIZED"
        )))
        val failingContext = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val delegate = context.getSharedPreferences(name, mode)
                if (name != "ai_memory_audit_log_v1") return delegate
                return object : SharedPreferences by delegate {
                    override fun edit(): SharedPreferences.Editor {
                        val editor = delegate.edit()
                        return object : SharedPreferences.Editor by editor {
                            override fun putString(key: String, value: String?): SharedPreferences.Editor {
                                editor.putString(key, value)
                                return this
                            }
                            override fun commit(): Boolean = false
                        }
                    }
                }
            }
        }

        assertEquals(0, QualityLearningStore.syncApprovedAgentMemories(failingContext))
        assertTrue(MemoryAuditLog.hasUnresolvedIntent(
            context, "USER", "APPROVE_PROPOSAL", proposal.id
        ))
        SharedPreferencesWriteHealth.resetForTests("ai_memory_audit_log_v1")
        assertEquals(0, QualityLearningStore.syncApprovedAgentMemories(context))
        val rows = MemoryAuditLog.readVerified(context)!!
        assertFalse(MemoryAuditLog.hasUnresolvedIntent(
            context, "USER", "APPROVE_PROPOSAL", proposal.id
        ))
        assertFalse(rows.any { it.actor == "SYSTEM_RECOVERY" && it.objectId == proposal.id })
    }


    @Test fun ambiguousProposalLedgerApprovalCommitIsRecoveredExactlyOnce() {
        val proposal = QualityLearningStore.addProposal(
            context, "Reguła po niejednoznacznym zapisie rejestru propozycji", 9, "wysoka",
            listOf("task-ambiguous-proposal-ledger"), listOf("coder"), emptyList()
        )
        val qualityPrefsName = "ai_quality_learning_v1"
        var proposalCommitCalls = 0
        val failingContext = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val delegate = context.getSharedPreferences(name, mode)
                if (name != qualityPrefsName) return delegate
                return object : SharedPreferences by delegate {
                    override fun edit(): SharedPreferences.Editor {
                        val editor = delegate.edit()
                        return object : SharedPreferences.Editor by editor {
                            override fun putString(key: String, value: String?): SharedPreferences.Editor {
                                editor.putString(key, value)
                                return this
                            }

                            override fun commit(): Boolean {
                                proposalCommitCalls++
                                // Persist APPROVED, but report failure to model an ambiguous durable write.
                                return if (proposalCommitCalls == 1) {
                                    editor.commit()
                                    false
                                } else {
                                    editor.commit()
                                }
                            }
                        }
                    }
                }
            }
        }

        assertFalse(QualityLearningStore.setProposalStatus(
            failingContext, proposal.id, "APPROVED", "test-ambiguous-proposal-ledger:${proposal.id}"
        ))
        assertEquals(1, proposalCommitCalls)
        assertTrue(SharedPreferencesWriteHealth.isUncertain(qualityPrefsName))
        assertTrue(MemoryAuditLog.hasUnresolvedIntent(
            context, "USER", "APPROVE_PROPOSAL", proposal.id
        ))

        // Simulate process restart: the durable proposal ledger says APPROVED although
        // the caller received false. Reconciliation must finish the authorized operation.
        SharedPreferencesWriteHealth.resetForTests(qualityPrefsName)
        assertEquals("APPROVED", QualityLearningStore.proposals(context).single().status)
        val memoryEntry = "[UCZENIE ZATWIERDZONE ${proposal.id.take(8)}; dowody=${proposal.evidenceCount}; pewność=${proposal.confidence}] ${proposal.text}".take(1000)
        assertFalse(AiMemoryStore.containsExactMemory(context, memoryEntry) == true)
        assertEquals(1, QualityLearningStore.syncApprovedAgentMemories(context))
        assertTrue(AiMemoryStore.containsExactMemory(context, memoryEntry) == true)
        assertTrue(AgentOperationalMemoryStore.hasApprovedSource(
            context, "coder", proposal.text, "proposal:${proposal.id}"
        ) == true)
        assertFalse(MemoryAuditLog.hasUnresolvedIntent(
            context, "USER", "APPROVE_PROPOSAL", proposal.id
        ))

        val afterRecovery = MemoryAuditLog.readVerified(context)!!
        assertEquals(1, afterRecovery.count {
            it.actor == "SYSTEM_RECOVERY" && it.mutation == "RECONCILE_APPROVED_PROPOSAL" &&
                it.objectId == proposal.id && it.phase == MemoryAuditLog.Phase.INTENT
        })
        val auditSize = afterRecovery.size
        assertEquals(0, QualityLearningStore.syncApprovedAgentMemories(context))
        assertEquals(auditSize, MemoryAuditLog.readVerified(context)!!.size)
    }


    @Test fun ambiguousProposalLedgerRejectionCommitIsRecoveredExactlyOnce() {
        val proposal = QualityLearningStore.addProposal(
            context, "Reguła po niejednoznacznym cofnięciu zatwierdzenia", 9, "wysoka",
            listOf("task-ambiguous-proposal-rejection"), listOf("coder"), emptyList()
        )
        assertTrue(QualityLearningStore.setProposalStatus(
            context, proposal.id, "APPROVED", "test-initial-approval:${proposal.id}"
        ))
        val memoryEntry = "[UCZENIE ZATWIERDZONE ${proposal.id.take(8)}; dowody=${proposal.evidenceCount}; pewność=${proposal.confidence}] ${proposal.text}".take(1000)
        assertTrue(AiMemoryStore.containsExactMemory(context, memoryEntry) == true)
        assertTrue(AgentOperationalMemoryStore.hasApprovedSource(
            context, "coder", proposal.text, "proposal:${proposal.id}"
        ) == true)

        val qualityPrefsName = "ai_quality_learning_v1"
        var proposalCommitCalls = 0
        val failingContext = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val delegate = context.getSharedPreferences(name, mode)
                if (name != qualityPrefsName) return delegate
                return object : SharedPreferences by delegate {
                    override fun edit(): SharedPreferences.Editor {
                        val editor = delegate.edit()
                        return object : SharedPreferences.Editor by editor {
                            override fun putString(key: String, value: String?): SharedPreferences.Editor {
                                editor.putString(key, value)
                                return this
                            }

                            override fun commit(): Boolean {
                                proposalCommitCalls++
                                // Persist REJECTED, but report failure to model an ambiguous write.
                                return if (proposalCommitCalls == 1) {
                                    editor.commit()
                                    false
                                } else {
                                    editor.commit()
                                }
                            }
                        }
                    }
                }
            }
        }

        assertFalse(QualityLearningStore.setProposalStatus(
            failingContext, proposal.id, "REJECTED", "test-ambiguous-proposal-rejection:${proposal.id}"
        ))
        assertEquals(1, proposalCommitCalls)
        assertTrue(SharedPreferencesWriteHealth.isUncertain(qualityPrefsName))
        assertTrue(MemoryAuditLog.hasUnresolvedIntent(
            context, "USER", "REJECT_PROPOSAL", proposal.id
        ))

        // After restart, REJECTED is authoritative: both memory stores must be cleaned.
        SharedPreferencesWriteHealth.resetForTests(qualityPrefsName)
        assertEquals("REJECTED", QualityLearningStore.proposals(context).single().status)
        assertEquals(0, QualityLearningStore.syncApprovedAgentMemories(context))
        assertFalse(AiMemoryStore.containsExactMemory(context, memoryEntry) == true)
        assertFalse(AgentOperationalMemoryStore.hasAnySource(context, "proposal:${proposal.id}") == true)
        assertFalse(MemoryAuditLog.hasUnresolvedIntent(
            context, "USER", "REJECT_PROPOSAL", proposal.id
        ))

        val afterRecovery = MemoryAuditLog.readVerified(context)!!
        assertEquals(1, afterRecovery.count {
            it.actor == "SYSTEM_RECOVERY" && it.mutation == "RECONCILE_REVOKED_PROPOSAL" &&
                it.objectId == proposal.id && it.phase == MemoryAuditLog.Phase.INTENT
        })
        val auditSize = afterRecovery.size
        assertEquals(0, QualityLearningStore.syncApprovedAgentMemories(context))
        assertEquals(auditSize, MemoryAuditLog.readVerified(context)!!.size)
    }


    @Test fun recoveryTerminalPersistedButReportedFailedIsNotReplayedAfterRestart() {
        val proposal = QualityLearningStore.addProposal(
            context, "Reguła po niejednoznacznym terminalu odzyskiwania", 9, "wysoka",
            listOf("task-recovery-terminal-ambiguous"), listOf("coder"), emptyList()
        )
        assertTrue(QualityLearningStore.setProposalStatus(
            context, proposal.id, "APPROVED", "test-initial-approval:${proposal.id}"
        ))
        val memoryEntry = "[UCZENIE ZATWIERDZONE ${proposal.id.take(8)}; dowody=${proposal.evidenceCount}; pewność=${proposal.confidence}] ${proposal.text}".take(1000)

        // Preserve the approved agent copy but simulate process death before shared-memory persistence.
        assertTrue(context.getSharedPreferences("ai_master_memory_store_v1", Context.MODE_PRIVATE)
            .edit().putString("memories", "[]").commit())
        assertFalse(AiMemoryStore.containsExactMemory(context, memoryEntry) == true)
        assertTrue(AgentOperationalMemoryStore.hasApprovedSource(
            context, "coder", proposal.text, "proposal:${proposal.id}"
        ) == true)
        assertTrue(MemoryAuditLog.append(context, MemoryAuditLog.Event(
            actor = "USER", mutation = "APPROVE_PROPOSAL", objectId = proposal.id,
            reason = "Użytkownik zatwierdził propozycję przed przerwaniem procesu.",
            approvalRef = "test-recovery-terminal:${proposal.id}",
            phase = MemoryAuditLog.Phase.INTENT, outcome = "AUTHORIZED"
        )))

        val auditPrefsName = "ai_memory_audit_log_v1"
        var auditCommitCalls = 0
        val failingContext = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val delegate = context.getSharedPreferences(name, mode)
                if (name != auditPrefsName) return delegate
                return object : SharedPreferences by delegate {
                    override fun edit(): SharedPreferences.Editor {
                        val editor = delegate.edit()
                        return object : SharedPreferences.Editor by editor {
                            override fun putString(key: String, value: String?): SharedPreferences.Editor {
                                editor.putString(key, value)
                                return this
                            }

                            override fun commit(): Boolean {
                                auditCommitCalls++
                                return if (auditCommitCalls == 2) {
                                    // The SYSTEM_RECOVERY COMMITTED record is durable, but the
                                    // failed return value quarantines the audit store in-process.
                                    editor.commit()
                                    false
                                } else editor.commit()
                            }
                        }
                    }
                }
            }
        }

        assertEquals(0, QualityLearningStore.syncApprovedAgentMemories(failingContext))
        assertEquals(2, auditCommitCalls) // recovery INTENT, then ambiguous COMMITTED terminal
        assertTrue(SharedPreferencesWriteHealth.isUncertain(auditPrefsName))
        assertTrue(AiMemoryStore.containsExactMemory(context, memoryEntry) == true)
        assertTrue(AgentOperationalMemoryStore.hasApprovedSource(
            context, "coder", proposal.text, "proposal:${proposal.id}"
        ) == true)
        assertTrue(MemoryAuditLog.hasUnresolvedIntent(
            context, "USER", "APPROVE_PROPOSAL", proposal.id
        ))

        // Restart: the persisted recovery terminal must be recognized, not replayed.
        SharedPreferencesWriteHealth.resetForTests(auditPrefsName)
        val afterAmbiguousCommit = MemoryAuditLog.readVerified(context)!!
        val recoveryRows = afterAmbiguousCommit.filter {
            it.actor == "SYSTEM_RECOVERY" &&
                it.mutation == "RECONCILE_APPROVED_PROPOSAL" && it.objectId == proposal.id
        }
        assertEquals(listOf(MemoryAuditLog.Phase.INTENT, MemoryAuditLog.Phase.COMMITTED),
            recoveryRows.map { it.phase })
        assertEquals(0, QualityLearningStore.syncApprovedAgentMemories(context))

        val afterRestart = MemoryAuditLog.readVerified(context)!!
        assertEquals(afterAmbiguousCommit.size + 1, afterRestart.size)
        assertEquals(2, afterRestart.count {
            it.actor == "SYSTEM_RECOVERY" &&
                it.mutation == "RECONCILE_APPROVED_PROPOSAL" && it.objectId == proposal.id
        })
        assertEquals(1, afterRestart.count {
            it.actor == "USER" && it.mutation == "APPROVE_PROPOSAL" &&
                it.objectId == proposal.id && it.phase == MemoryAuditLog.Phase.COMMITTED &&
                it.outcome == "RECOVERED_STATE"
        })
        assertFalse(MemoryAuditLog.hasUnresolvedIntent(
            context, "SYSTEM_RECOVERY", "RECONCILE_APPROVED_PROPOSAL", proposal.id
        ))
        assertFalse(MemoryAuditLog.hasUnresolvedIntent(
            context, "USER", "APPROVE_PROPOSAL", proposal.id
        ))
        assertEquals(1, AiMemoryStore.getMemories(context).count { it == memoryEntry })
        assertEquals(1, AgentOperationalMemoryStore.all(context).count {
            it.sourceTaskIds.contains("proposal:${proposal.id}")
        })
        val closedJournalSize = afterRestart.size
        assertEquals(0, QualityLearningStore.syncApprovedAgentMemories(context))
        assertEquals(closedJournalSize, MemoryAuditLog.readVerified(context)!!.size)
    }


    @Test fun proposalApprovalWithPersistedButReportedFailedTerminalAuditIsNotReplayed() {
        val proposal = QualityLearningStore.addProposal(
            context, "Reguła po niejednoznacznym terminalnym audycie", 9, "wysoka",
            listOf("task-terminal-audit-ambiguous"), listOf("coder"), emptyList()
        )
        val auditPrefsName = "ai_memory_audit_log_v1"
        var auditCommitCalls = 0
        var qualityCommitCalls = 0
        val failingContext = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val delegate = context.getSharedPreferences(name, mode)
                if (name !in setOf(auditPrefsName, "ai_quality_learning_v1")) return delegate
                return object : SharedPreferences by delegate {
                    override fun edit(): SharedPreferences.Editor {
                        val editor = delegate.edit()
                        return object : SharedPreferences.Editor by editor {
                            override fun putString(key: String, value: String?): SharedPreferences.Editor {
                                editor.putString(key, value)
                                return this
                            }

                            override fun commit(): Boolean {
                                return when (name) {
                                    auditPrefsName -> {
                                        auditCommitCalls++
                                        if (auditCommitCalls == 2) {
                                            // The COMMITTED terminal is durable, but the API reports
                                            // failure so the audit store must be quarantined.
                                            editor.commit()
                                            false
                                        } else editor.commit()
                                    }
                                    "ai_quality_learning_v1" -> {
                                        qualityCommitCalls++
                                        editor.commit()
                                    }
                                    else -> false
                                }
                            }
                        }
                    }
                }
            }
        }

        assertFalse(QualityLearningStore.setProposalStatus(
            failingContext, proposal.id, "APPROVED", "test-terminal-ambiguous:${proposal.id}"
        ))
        assertEquals(2, auditCommitCalls)
        assertEquals(1, qualityCommitCalls)
        assertTrue(SharedPreferencesWriteHealth.isUncertain(auditPrefsName))

        // Simulate process restart: the terminal COMMITTED record is present and verified.
        SharedPreferencesWriteHealth.resetForTests(auditPrefsName)
        val memoryEntry = "[UCZENIE ZATWIERDZONE ${proposal.id.take(8)}; dowody=${proposal.evidenceCount}; pewność=${proposal.confidence}] ${proposal.text}".take(1000)
        assertEquals("APPROVED", QualityLearningStore.proposals(context).single().status)
        assertTrue(AiMemoryStore.containsExactMemory(context, memoryEntry) == true)
        assertTrue(AgentOperationalMemoryStore.hasApprovedSource(
            context, "coder", proposal.text, "proposal:${proposal.id}"
        ) == true)
        val beforeSync = MemoryAuditLog.readVerified(context)!!
        assertEquals(listOf("AUTHORIZED", "SUCCESS"), beforeSync.map { it.outcome })
        assertFalse(MemoryAuditLog.hasUnresolvedIntent(
            context, "USER", "APPROVE_PROPOSAL", proposal.id
        ))

        assertEquals(0, QualityLearningStore.syncApprovedAgentMemories(context))
        assertEquals(beforeSync.size, MemoryAuditLog.readVerified(context)!!.size)
        assertFalse(QualityLearningStore.setProposalStatus(
            context, proposal.id, "APPROVED", "test-terminal-ambiguous:${proposal.id}"
        ))
        assertEquals(1, QualityLearningStore.proposals(context).size)
        assertEquals(beforeSync.size + 1, MemoryAuditLog.readVerified(context)!!.size)
        assertEquals(1, AiMemoryStore.getMemories(context).count { it == memoryEntry })
        assertEquals(1, AgentOperationalMemoryStore.all(context).count {
            it.sourceTaskIds.contains("proposal:${proposal.id}")
        })
    }


    @Test fun proposalRecoveryTreatsAlreadyAbsentSharedMemoryAsSuccessButManualRemovalRemainsStrict() {
        val absentEntry = "Reguła, której nie ma już we wspólnej pamięci."
        val recoveryRequest = MemoryGovernance.Request(
            actor = MemoryGovernance.Actor.SYSTEM_RECOVERY,
            mutation = MemoryGovernance.Mutation.RECONCILE_REVOKED_PROPOSAL,
            objectId = "missing-proposal",
            reason = "Test idempotentnego odzyskiwania cofniętej propozycji.",
            approvalRef = "proposal:missing-proposal"
        )
        assertTrue(MemoryMutationGate.execute(context, recoveryRequest) { permit ->
            AiMemoryStore.removeMemory(context, absentEntry, permit, objectId = recoveryRequest.objectId)
        })
        assertEquals(false, AiMemoryStore.containsExactMemory(context, absentEntry))

        val manualRequest = MemoryGovernance.Request(
            actor = MemoryGovernance.Actor.USER,
            mutation = MemoryGovernance.Mutation.REMOVE_SHARED_RULE,
            objectId = "missing-rule",
            reason = "Test ścisłego usuwania ręcznego.",
            approvalRef = "manual-remove:missing-rule"
        )
        assertFalse(MemoryMutationGate.execute(context, manualRequest) { permit ->
            AiMemoryStore.removeMemory(context, absentEntry, permit, objectId = manualRequest.objectId)
        })
    }

    @Test fun failedProposalStatusCompensationIsRecoveredFromDurableRejectedLedger() {
        val proposal = QualityLearningStore.addProposal(
            context, "Reguła po nieudanym przywróceniu statusu", 8, "wysoka",
            listOf("task-failed-status-compensation"), listOf("coder"), emptyList()
        )
        assertTrue(QualityLearningStore.setProposalStatus(
            context, proposal.id, "APPROVED", "test-initial-approval:${proposal.id}"
        ))
        val memoryEntry = "[UCZENIE ZATWIERDZONE ${proposal.id.take(8)}; dowody=${proposal.evidenceCount}; pewność=${proposal.confidence}] ${proposal.text}".take(1000)
        val agentPrefs = context.getSharedPreferences("ai_agent_operational_memory_v1", Context.MODE_PRIVATE)
        val originalAgentLedger = agentPrefs.getString("entries", null)!!
        assertTrue(AiMemoryStore.containsExactMemory(context, memoryEntry) == true)
        assertTrue(AgentOperationalMemoryStore.hasApprovedSource(
            context, "coder", proposal.text, "proposal:${proposal.id}"
        ) == true)

        // Force rejection cleanup to fail after shared memory has already been removed.
        assertTrue(agentPrefs.edit().putString("entries", "not-json").commit())
        var proposalCommitCalls = 0
        val failingContext = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val delegate = context.getSharedPreferences(name, mode)
                if (name != "ai_quality_learning_v1") return delegate
                return object : SharedPreferences by delegate {
                    override fun edit(): SharedPreferences.Editor {
                        val editor = delegate.edit()
                        return object : SharedPreferences.Editor by editor {
                            override fun putString(key: String, value: String?): SharedPreferences.Editor {
                                editor.putString(key, value)
                                return this
                            }

                            override fun commit(): Boolean {
                                proposalCommitCalls++
                                // Persist REJECTED; fail the compensation write so the durable
                                // proposal ledger remains authoritative after process restart.
                                return if (proposalCommitCalls == 1) editor.commit() else false
                            }
                        }
                    }
                }
            }
        }

        assertFalse(QualityLearningStore.setProposalStatus(
            failingContext, proposal.id, "REJECTED", "test-rejection-compensation:${proposal.id}"
        ))
        assertEquals(2, proposalCommitCalls)
        assertTrue(SharedPreferencesWriteHealth.isUncertain("ai_quality_learning_v1"))
        // The write-health guard intentionally hides the proposal list until restart.
        // Inspect the persisted ledger directly to prove REJECTED reached durable storage.
        val persistedProposalLedger = context.getSharedPreferences("ai_quality_learning_v1", Context.MODE_PRIVATE)
            .getString("proposals", null)!!
        assertTrue(persistedProposalLedger.contains(proposal.id))
        assertTrue(persistedProposalLedger.contains("REJECTED"))
        assertFalse(AiMemoryStore.containsExactMemory(context, memoryEntry) == true)
        assertTrue(MemoryAuditLog.hasUnresolvedIntent(
            context, "USER", "REJECT_PROPOSAL", proposal.id
        ))

        // Simulate restart and repair of the agent ledger. REJECTED is the durable decision,
        // so reconciliation must finish removing the remaining provenance without replaying
        // the failed APPROVED-status compensation.
        SharedPreferencesWriteHealth.resetForTests("ai_quality_learning_v1")
        SharedPreferencesWriteHealth.resetForTests("ai_agent_operational_memory_v1")
        assertTrue(agentPrefs.edit().putString("entries", originalAgentLedger).commit())
        assertEquals(true, AgentOperationalMemoryStore.hasAnySource(context, "proposal:${proposal.id}"))
        assertEquals(0, QualityLearningStore.syncApprovedAgentMemories(context))
        assertEquals("REJECTED", QualityLearningStore.proposals(context).single().status)
        assertFalse(AiMemoryStore.containsExactMemory(context, memoryEntry) == true)
        assertEquals(false, AgentOperationalMemoryStore.hasAnySource(context, "proposal:${proposal.id}"))
        assertFalse(MemoryAuditLog.hasUnresolvedIntent(
            context, "USER", "REJECT_PROPOSAL", proposal.id
        ))
        val auditSize = MemoryAuditLog.readVerified(context)!!.size
        assertEquals(0, QualityLearningStore.syncApprovedAgentMemories(context))
        assertEquals(auditSize, MemoryAuditLog.readVerified(context)!!.size)
    }

}
