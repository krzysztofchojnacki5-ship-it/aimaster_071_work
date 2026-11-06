package pl.aimasterpro.app.core

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.json.JSONArray
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
class ChangeSetStoreTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @Before fun reset() {
        context.getSharedPreferences("ai_change_set_ledger_v1", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("ai_host_active_change_set_v1", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun enforcesApprovalTestAndRollbackStateMachine() {
        val item = ChangeSetStore.propose(context, "task-1", "Zmiana ekranu", listOf("app/src/Main.kt"), ChangeSafetyGuard.validate(listOf("app/src/Main.kt")))
        assertEquals("PROPOSED", item.status)
        assertTrue(ChangeSetStore.transition(context, item.id, "APPROVED"))
        assertTrue(ChangeSetStore.recordSnapshot(context, item.id, "snapshot-1"))
        assertTrue(ChangeSetStore.transition(context, item.id, "APPLIED_UNVERIFIED"))
        assertTrue(ChangeSetStore.recordTests(context, item.id, true, "ręczny raport: 3 testy passed"))
        assertEquals("LEGACY_UNVERIFIED", ChangeSetStore.all(context).single().testLevel)
        assertThrows(IllegalArgumentException::class.java) { ChangeSetStore.transition(context, item.id, "APPLIED") }
        assertTrue(ChangeSetStore.transition(context, item.id, "ROLLED_BACK"))
        assertEquals("ROLLED_BACK", ChangeSetStore.all(context).single().status)
    }

    @Test(expected = IllegalArgumentException::class)
    fun blocksApplyingBeforeTestsPass() {
        val item = ChangeSetStore.propose(context, "task-no-tests", "Zmiana", listOf("app/src/A.kt"), ChangeSafetyGuard.validate(listOf("app/src/A.kt")))
        ChangeSetStore.transition(context, item.id, "APPROVED")
        ChangeSetStore.transition(context, item.id, "APPLIED")
    }

    @Test(expected = IllegalArgumentException::class)
    fun blocksApplyingWithoutApproval() {
        val item = ChangeSetStore.propose(context, "task-2", "Zmiana", listOf("app/src/A.kt"), ChangeSafetyGuard.validate(listOf("app/src/A.kt")))
        ChangeSetStore.transition(context, item.id, "APPLIED")
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesUnsafeProposal() {
        ChangeSetStore.propose(context, "task-3", "Unsafe", listOf("../outside"), ChangeSafetyGuard.validate(listOf("../outside")))
    }
    @Test fun allowsAppliedUnverifiedUntilIsolatedTestsFinish() {
        val item = ChangeSetStore.propose(context, "task-unverified", "Patch workspace", listOf("app/src/A.kt"), ChangeSafetyGuard.validate(listOf("app/src/A.kt")))
        assertTrue(ChangeSetStore.transition(context, item.id, "APPROVED"))
        assertTrue(ChangeSetStore.recordSnapshot(context, item.id, "snapshot-123"))
        assertTrue(ChangeSetStore.transition(context, item.id, "APPLIED_UNVERIFIED"))
        assertEquals("snapshot-123", ChangeSetStore.all(context).single().snapshotId)
        assertEquals("APPLIED_UNVERIFIED", ChangeSetStore.all(context).single().status)
        assertTrue(ChangeSetStore.recordTests(context, item.id, true, "manual claim: isolated project tests passed"))
        assertThrows(IllegalArgumentException::class.java) { ChangeSetStore.transition(context, item.id, "APPLIED") }
        assertEquals("APPLIED_UNVERIFIED", ChangeSetStore.all(context).single().status)
    }

    @Test fun workspaceIntegrityPassCannotPromoteChangeToApplied() {
        val item = ChangeSetStore.propose(context, "task-integrity-only", "Patch workspace", listOf("app/src/A.kt"), ChangeSafetyGuard.validate(listOf("app/src/A.kt")))
        ChangeSetStore.transition(context, item.id, "APPROVED")
        ChangeSetStore.recordSnapshot(context, item.id, "snapshot-integrity")
        ChangeSetStore.transition(context, item.id, "APPLIED_UNVERIFIED")
        ChangeSetStore.recordWorkspaceVerification(context, item.id, "PASS", "Manifest and snapshot match")
        assertThrows(IllegalArgumentException::class.java) { ChangeSetStore.transition(context, item.id, "APPLIED") }
    }

    @Test fun notRunIsPersistedWithoutClaimingSuccess() {
        val item = ChangeSetStore.propose(context, "task-not-run", "Patch workspace", listOf("app/src/A.kt"), ChangeSafetyGuard.validate(listOf("app/src/A.kt")))
        ChangeSetStore.transition(context, item.id, "APPROVED")
        ChangeSetStore.recordSnapshot(context, item.id, "snapshot-not-run")
        ChangeSetStore.transition(context, item.id, "APPLIED_UNVERIFIED")
        ChangeSetStore.recordWorkspaceVerification(context, item.id, "NOT_RUN", "Project tests unavailable")
        val saved = ChangeSetStore.all(context).single()
        assertEquals("NOT_RUN", saved.testStatus)
        assertEquals("WORKSPACE_INTEGRITY", saved.testLevel)
    }

    @Test fun unverifiedAppliedChangeCanBeRolledBack() {
        val item = ChangeSetStore.propose(context, "task-unverified-rollback", "Patch workspace", listOf("app/src/A.kt"), ChangeSafetyGuard.validate(listOf("app/src/A.kt")))
        ChangeSetStore.transition(context, item.id, "APPROVED")
        ChangeSetStore.recordSnapshot(context, item.id, "snapshot-rollback")
        ChangeSetStore.transition(context, item.id, "APPLIED_UNVERIFIED")
        assertTrue(ChangeSetStore.transition(context, item.id, "ROLLED_BACK"))
    }


    @Test fun downgradesLegacyProjectTestClaimFromPreExecutorVersion() {
        val item = ChangeSetStore.propose(context, "task-legacy-pass", "Patch", listOf("app/src/A.kt"), ChangeSafetyGuard.validate(listOf("app/src/A.kt")))
        ChangeSetStore.transition(context, item.id, "APPROVED")
        ChangeSetStore.recordSnapshot(context, item.id, "snapshot-legacy")
        ChangeSetStore.transition(context, item.id, "APPLIED_UNVERIFIED")
        val prefs = context.getSharedPreferences("ai_change_set_ledger_v1", Context.MODE_PRIVATE)
        val rows = JSONArray(prefs.getString("changes", "[]"))
        rows.getJSONObject(0).put("testStatus", "PASS").put("testLevel", "PROJECT_TESTS")
        prefs.edit().putString("changes", rows.toString()).commit()

        val loaded = ChangeSetStore.all(context).single()
        assertEquals("NOT_RUN", loaded.testStatus)
        assertEquals("LEGACY_UNVERIFIED", loaded.testLevel)
        assertThrows(IllegalArgumentException::class.java) { ChangeSetStore.transition(context, item.id, "APPLIED") }
    }

    private fun appliedForHostScope(chatId: String = "chat-active"): ChangeSetStore.ChangeSet {
        val item = ChangeSetStore.propose(context, "task-host-active", "Patch host scope", listOf("app/src/Host.kt"),
            ChangeSafetyGuard.validate(listOf("app/src/Host.kt")), chatId)
        assertTrue(ChangeSetStore.transition(context, item.id, "APPROVED"))
        assertTrue(ChangeSetStore.recordSnapshot(context, item.id, "snapshot-host-active"))
        assertTrue(ChangeSetStore.transition(context, item.id, "APPLIED_UNVERIFIED"))
        return ChangeSetStore.all(context).first { it.id == item.id }
    }

    @Test fun hostActiveScopePersistsAndValidatesExactLedgerTuple() {
        val item = appliedForHostScope()
        assertTrue(HostActiveChangeSetStore.setActive(context, "chat-active", "workspace-sha", item.id))
        val active = HostActiveChangeSetStore.readActive(context, "chat-active", "workspace-sha")
        assertEquals(HostActiveChangeSetStore.ReadStatus.ACTIVE, active.status)
        assertEquals(item.id, active.active?.changeSetId)
        assertEquals(item.taskId, active.active?.taskId)
        assertEquals(item.snapshotId, active.active?.snapshotId)
        assertEquals("APPLIED_UNVERIFIED", active.active?.changeStatus)
    }

    @Test fun hostActiveScopeIsNotReusedAcrossChatsOrWorkspaceRevisions() {
        val item = appliedForHostScope()
        assertTrue(HostActiveChangeSetStore.setActive(context, "chat-active", "workspace-sha-1", item.id))
        assertEquals(HostActiveChangeSetStore.ReadStatus.NONE,
            HostActiveChangeSetStore.readActive(context, "another-chat", "workspace-sha-1").status)
        assertEquals(HostActiveChangeSetStore.ReadStatus.NONE,
            HostActiveChangeSetStore.readActive(context, "chat-active", "workspace-sha-2").status)
    }

    @Test fun hostActiveScopeRejectsMissingChangeSetAndCorruptPointer() {
        val item = appliedForHostScope()
        assertTrue(HostActiveChangeSetStore.setActive(context, "chat-active", "workspace-sha", item.id))
        context.getSharedPreferences("ai_change_set_ledger_v1", Context.MODE_PRIVATE).edit().putString("changes", "[]").commit()
        assertEquals(HostActiveChangeSetStore.ReadStatus.INVALID,
            HostActiveChangeSetStore.readActive(context, "chat-active", "workspace-sha").status)
        context.getSharedPreferences("ai_host_active_change_set_v1", Context.MODE_PRIVATE).edit().putString("active", "{").commit()
        assertEquals(HostActiveChangeSetStore.ReadStatus.INVALID,
            HostActiveChangeSetStore.readActive(context, "chat-active", "workspace-sha").status)
    }

    @Test fun rollbackAutomaticallyInvalidatesHostActivePointer() {
        val item = appliedForHostScope()
        assertTrue(HostActiveChangeSetStore.setActive(context, "chat-active", "workspace-sha", item.id))
        assertTrue(ChangeSetStore.transition(context, item.id, "ROLLED_BACK"))
        assertEquals(HostActiveChangeSetStore.ReadStatus.INVALID,
            HostActiveChangeSetStore.readActive(context, "chat-active", "workspace-sha").status)
    }

    @Test fun hostActiveScopeCannotBeSetForWrongChatOrIneligibleStatus() {
        val item = appliedForHostScope()
        assertFalse(HostActiveChangeSetStore.setActive(context, "other-chat", "workspace-sha", item.id))
        assertTrue(ChangeSetStore.transition(context, item.id, "ROLLED_BACK"))
        assertFalse(HostActiveChangeSetStore.setActive(context, "chat-active", "workspace-sha", item.id))
    }

    @Test fun readingFromAnotherChatOrWorkspacePermanentlyClearsOldPointer() {
        val item = appliedForHostScope()
        assertTrue(HostActiveChangeSetStore.setActive(context, "chat-active", "workspace-sha-1", item.id))
        assertEquals(HostActiveChangeSetStore.ReadStatus.NONE,
            HostActiveChangeSetStore.readActive(context, "another-chat", "workspace-sha-1").status)
        assertEquals(HostActiveChangeSetStore.ReadStatus.NONE,
            HostActiveChangeSetStore.readActive(context, "chat-active", "workspace-sha-1").status)

        assertTrue(HostActiveChangeSetStore.setActive(context, "chat-active", "workspace-sha-1", item.id))
        assertEquals(HostActiveChangeSetStore.ReadStatus.NONE,
            HostActiveChangeSetStore.readActive(context, "chat-active", "workspace-sha-2").status)
        assertEquals(HostActiveChangeSetStore.ReadStatus.NONE,
            HostActiveChangeSetStore.readActive(context, "chat-active", "workspace-sha-1").status)
    }

    @Test fun corruptLedgerIsDistinguishedAndMutationsNeverOverwriteIt() {
        val prefs = context.getSharedPreferences("ai_change_set_ledger_v1", Context.MODE_PRIVATE)
        val corrupted = "{not-json"
        prefs.edit().putString("changes", corrupted).commit()
        val read = ChangeSetStore.readResult(context)
        assertEquals(ChangeSetStore.LedgerStatus.CORRUPT, read.status)
        assertTrue(read.items.isEmpty())
        assertThrows(IllegalStateException::class.java) {
            ChangeSetStore.propose(context, "task-corrupt", "Should not overwrite", listOf("app/src/A.kt"),
                ChangeSafetyGuard.validate(listOf("app/src/A.kt")))
        }
        assertEquals(corrupted, prefs.getString("changes", null))
    }

    @Test fun malformedRowsAndDuplicateIdsAreCorruptRatherThanSilentlyDropped() {
        val prefs = context.getSharedPreferences("ai_change_set_ledger_v1", Context.MODE_PRIVATE)
        prefs.edit().putString("changes", "[null]").commit()
        assertEquals(ChangeSetStore.LedgerStatus.CORRUPT, ChangeSetStore.readResult(context).status)
        prefs.edit().putString("changes", "[]").commit()

        val item = ChangeSetStore.propose(context, "task-duplicate", "Patch", listOf("app/src/A.kt"),
            ChangeSafetyGuard.validate(listOf("app/src/A.kt")))
        val rows = JSONArray(prefs.getString("changes", "[]"))
        rows.put(rows.getJSONObject(0).let { JSONObject(it.toString()) })
        prefs.edit().putString("changes", rows.toString()).commit()
        assertEquals(ChangeSetStore.LedgerStatus.CORRUPT, ChangeSetStore.readResult(context).status)
        assertEquals(item.id, rows.getJSONObject(0).getString("id"))
    }

    @Test fun hostActiveScopeRejectsCorruptChangeSetLedger() {
        val item = appliedForHostScope()
        assertTrue(HostActiveChangeSetStore.setActive(context, "chat-active", "workspace-sha", item.id))
        context.getSharedPreferences("ai_change_set_ledger_v1", Context.MODE_PRIVATE).edit().putString("changes", "[]x").commit()
        val result = HostActiveChangeSetStore.readActive(context, "chat-active", "workspace-sha")
        assertEquals(HostActiveChangeSetStore.ReadStatus.INVALID, result.status)
        assertTrue("Reason should not be blank: ${result.reason}", result.reason.isNotBlank())
    }

    @Test fun missingLedgerIsAValidEmptyLedger() {
        val read = ChangeSetStore.readResult(context)
        assertEquals(ChangeSetStore.LedgerStatus.VALID, read.status)
        assertTrue(read.items.isEmpty())
    }

    @Test fun legacyAppliedClaimIsDowngradedToUnverified() {
        val item = appliedForHostScope()
        val prefs = context.getSharedPreferences("ai_change_set_ledger_v1", Context.MODE_PRIVATE)
        val rows = JSONArray(prefs.getString("changes", "[]"))
        rows.getJSONObject(0).put("status", "APPLIED").put("testStatus", "PASS").put("testLevel", "PROJECT_TESTS")
        prefs.edit().putString("changes", rows.toString()).commit()
        val loaded = ChangeSetStore.readResult(context)
        assertEquals(ChangeSetStore.LedgerStatus.VALID, loaded.status)
        assertEquals("APPLIED_UNVERIFIED", loaded.items.single().status)
        assertEquals("NOT_RUN", loaded.items.single().testStatus)
        assertEquals("LEGACY_UNVERIFIED", loaded.items.single().testLevel)
        assertEquals(item.id, loaded.items.single().id)
    }

    @Test fun appliedStatusWithoutTrustedTestLevelIsCorrupt() {
        val item = appliedForHostScope()
        val prefs = context.getSharedPreferences("ai_change_set_ledger_v1", Context.MODE_PRIVATE)
        val rows = JSONArray(prefs.getString("changes", "[]"))
        rows.getJSONObject(0).put("status", "APPLIED").put("testStatus", "PASS").put("testLevel", "WORKSPACE_INTEGRITY")
        prefs.edit().putString("changes", rows.toString()).commit()
        assertEquals(ChangeSetStore.LedgerStatus.CORRUPT, ChangeSetStore.readResult(context).status)
        assertEquals(item.id, rows.getJSONObject(0).getString("id"))
    }

    @Test fun hostActiveScopeRejectsStatusChangedAfterPointerWasWritten() {
        val item = appliedForHostScope()
        assertTrue(HostActiveChangeSetStore.setActive(context, "chat-active", "workspace-sha", item.id))
        val prefs = context.getSharedPreferences("ai_change_set_ledger_v1", Context.MODE_PRIVATE)
        val rows = JSONArray(prefs.getString("changes", "[]"))
        rows.getJSONObject(0).put("status", "ROLLED_BACK")
        prefs.edit().putString("changes", rows.toString()).commit()
        assertEquals(HostActiveChangeSetStore.ReadStatus.INVALID,
            HostActiveChangeSetStore.readActive(context, "chat-active", "workspace-sha").status)
    }

    private class FaultingPreferenceStore(
        var value: String? = null,
        var writeResult: Boolean = true,
        var failRead: Boolean = false,
        var failWrite: Boolean = false,
        var mutateThenFailWrite: Boolean = false,
        var failRemove: Boolean = false
    ) : StringPreferenceStore {
        var writeCalls = 0
        override fun read(key: String): String? {
            if (failRead) throw IllegalStateException("simulated preference read failure")
            return value
        }
        override fun write(key: String, value: String): Boolean {
            writeCalls++
            if (failWrite) throw IllegalStateException("simulated preference commit exception")
            if (mutateThenFailWrite) { this.value = value; return false }
            if (!writeResult) return false
            this.value = value
            return true
        }
        override fun remove(key: String): Boolean {
            if (failRemove) return false
            value = null
            return true
        }
    }

    @Test fun preferenceReadExceptionIsReportedAsCorruptAndCannotBeOverwritten() {
        val storage = FaultingPreferenceStore(failRead = true)
        val read = ChangeSetStore.readResult(context, storage)
        assertEquals(ChangeSetStore.LedgerStatus.CORRUPT, read.status)
        assertTrue(read.error.contains("IllegalStateException"))
        assertThrows(IllegalStateException::class.java) {
            ChangeSetStore.propose(context, "task-read-failure", "Must not overwrite", listOf("app/src/A.kt"),
                ChangeSafetyGuard.validate(listOf("app/src/A.kt")), storage = storage)
        }
        assertEquals(0, storage.writeCalls)
    }

    @Test fun failedPreferenceCommitDoesNotReportProposalAsSaved() {
        val storage = FaultingPreferenceStore(writeResult = false)
        assertThrows(IllegalStateException::class.java) {
            ChangeSetStore.propose(context, "task-commit-failure", "Commit must fail", listOf("app/src/A.kt"),
                ChangeSafetyGuard.validate(listOf("app/src/A.kt")), storage = storage)
        }
        assertEquals(1, storage.writeCalls)
        assertNull(storage.value)
    }

    @Test fun malformedInjectedLedgerBlocksWritesWithoutReplacingOriginalBytes() {
        val corrupted = "{ definitely-not-json"
        val storage = FaultingPreferenceStore(value = corrupted)
        assertEquals(ChangeSetStore.LedgerStatus.CORRUPT, ChangeSetStore.readResult(context, storage).status)
        assertThrows(IllegalStateException::class.java) {
            ChangeSetStore.propose(context, "task-corrupt-injected", "Must not overwrite", listOf("app/src/A.kt"),
                ChangeSafetyGuard.validate(listOf("app/src/A.kt")), storage = storage)
        }
        assertEquals(corrupted, storage.value)
        assertEquals(0, storage.writeCalls)
    }

    @Test fun hostActivePointerCommitFailureIsNotReportedAsActive() {
        val item = appliedForHostScope()
        val storage = FaultingPreferenceStore(writeResult = false)
        assertFalse(HostActiveChangeSetStore.setActive(context, "chat-active", "workspace-sha", item.id, storage))
        assertEquals(1, storage.writeCalls)
        assertNull(storage.value)
    }

    @Test fun processLocalActivePointerMutationAfterFailedCommitIsQuarantinedUntilRetry() {
        val item = appliedForHostScope()
        val storage = FaultingPreferenceStore(mutateThenFailWrite = true)
        assertFalse(HostActiveChangeSetStore.setActive(context, "chat-active", "workspace-sha", item.id, storage))
        assertNotNull(storage.value)
        val failed = HostActiveChangeSetStore.readActive(context, "chat-active", "workspace-sha", storage)
        assertEquals(HostActiveChangeSetStore.ReadStatus.INVALID, failed.status)
        assertNull(failed.active)
        assertTrue(failed.reason.contains("niejednoznaczny"))
        storage.mutateThenFailWrite = false
        assertTrue(HostActiveChangeSetStore.setActive(context, "chat-active", "workspace-sha", item.id, storage))
        assertEquals(HostActiveChangeSetStore.ReadStatus.ACTIVE,
            HostActiveChangeSetStore.readActive(context, "chat-active", "workspace-sha", storage).status)
    }

    @Test fun hostActivePointerReadExceptionFailsClosed() {
        val result = HostActiveChangeSetStore.readActive(context, "chat-active", "workspace-sha",
            FaultingPreferenceStore(failRead = true))
        assertEquals(HostActiveChangeSetStore.ReadStatus.INVALID, result.status)
    }

    @Test fun hostActiveSelectionUsesInjectedLedgerAndRejectsCorruption() {
        val item = appliedForHostScope()
        val activeStorage = FaultingPreferenceStore()
        val corruptLedger = FaultingPreferenceStore(value = "{not-json")
        assertFalse(HostActiveChangeSetStore.setActive(context, "chat-active", "workspace-sha", item.id,
            activeStorage, corruptLedger))
        assertEquals(0, activeStorage.writeCalls)
        assertNull(activeStorage.value)
    }

    @Test fun hostActiveReadUsesInjectedLedgerAndFailsClosedOnReadException() {
        val item = appliedForHostScope()
        val activeStorage = FaultingPreferenceStore()
        assertTrue(HostActiveChangeSetStore.setActive(context, "chat-active", "workspace-sha", item.id, activeStorage))
        val unreadableLedger = FaultingPreferenceStore(failRead = true)
        val result = HostActiveChangeSetStore.readActive(context, "chat-active", "workspace-sha",
            activeStorage, unreadableLedger)
        assertEquals(HostActiveChangeSetStore.ReadStatus.INVALID, result.status)
        assertNotNull(activeStorage.value)
    }

    @Test fun hostActivePointerInvalidationCommitFailureIsReported() {
        val item = appliedForHostScope()
        val backing = FaultingPreferenceStore()
        assertTrue(HostActiveChangeSetStore.setActive(context, "chat-active", "workspace-sha", item.id, backing))
        backing.failRemove = true
        assertFalse(HostActiveChangeSetStore.invalidate(context, item.id, backing))
        assertNotNull(backing.value)
    }


    @Test fun recoveryCommitGateRetriesAfterHostActivePointerCommitFailure() {
        val item = appliedForHostScope()
        val activeStorage = FaultingPreferenceStore(writeResult = false)
        var acknowledgeCalls = 0
        fun commit() = PatchReceiptRecoveryCommitGate.commit(
            eligibleForActivation = true,
            expectedWorkspaceIdentity = "workspace-sha",
            chatStillMatches = { true },
            currentWorkspaceIdentity = { "workspace-sha" },
            activate = { HostActiveChangeSetStore.setActive(context, "chat-active", "workspace-sha", item.id, activeStorage) },
            invalidateActiveScope = { assertTrue(HostActiveChangeSetStore.invalidate(context, item.id, activeStorage)) },
            acknowledge = { acknowledgeCalls++; true }
        )
        assertEquals(PatchReceiptRecoveryCommitGate.Result.ACTIVATION_FAILED, commit())
        assertEquals(0, acknowledgeCalls)
        assertNull(activeStorage.value)
        activeStorage.writeResult = true
        assertEquals(PatchReceiptRecoveryCommitGate.Result.ACKNOWLEDGED, commit())
        assertEquals(1, acknowledgeCalls)
        assertEquals(HostActiveChangeSetStore.ReadStatus.ACTIVE,
            HostActiveChangeSetStore.readActive(context, "chat-active", "workspace-sha", activeStorage).status)
    }

    @Test fun recoveryCommitGateRetriesAcknowledgeAfterPointerWasDurablyActivated() {
        val item = appliedForHostScope()
        val activeStorage = FaultingPreferenceStore()
        var acknowledgeSucceeds = false
        var activationCalls = 0
        fun commit() = PatchReceiptRecoveryCommitGate.commit(
            eligibleForActivation = true,
            expectedWorkspaceIdentity = "workspace-sha",
            chatStillMatches = { true },
            currentWorkspaceIdentity = { "workspace-sha" },
            activate = {
                activationCalls++
                HostActiveChangeSetStore.setActive(context, "chat-active", "workspace-sha", item.id, activeStorage)
            },
            invalidateActiveScope = { assertTrue(HostActiveChangeSetStore.invalidate(context, item.id, activeStorage)) },
            acknowledge = { acknowledgeSucceeds }
        )
        assertEquals(PatchReceiptRecoveryCommitGate.Result.ACKNOWLEDGE_FAILED, commit())
        assertEquals(HostActiveChangeSetStore.ReadStatus.ACTIVE,
            HostActiveChangeSetStore.readActive(context, "chat-active", "workspace-sha", activeStorage).status)
        acknowledgeSucceeds = true
        assertEquals(PatchReceiptRecoveryCommitGate.Result.ACKNOWLEDGED, commit())
        assertEquals(2, activationCalls)
        assertEquals(2, activeStorage.writeCalls)
        assertEquals(HostActiveChangeSetStore.ReadStatus.ACTIVE,
            HostActiveChangeSetStore.readActive(context, "chat-active", "workspace-sha", activeStorage).status)
    }

    @Test fun recoveryCommitGateDoesNotAcknowledgeWhenChatSwitchInvalidationFails() {
        val item = appliedForHostScope()
        val activeStorage = FaultingPreferenceStore()
        var chatMatches = true
        var acknowledgeCalls = 0
        val result = PatchReceiptRecoveryCommitGate.commit(
            eligibleForActivation = true,
            expectedWorkspaceIdentity = "workspace-sha",
            chatStillMatches = { chatMatches },
            currentWorkspaceIdentity = { "workspace-sha" },
            activate = {
                HostActiveChangeSetStore.setActive(context, "chat-active", "workspace-sha", item.id, activeStorage)
                    .also { chatMatches = false }
            },
            invalidateActiveScope = {
                activeStorage.failRemove = true
                check(HostActiveChangeSetStore.invalidate(context, item.id, activeStorage))
            },
            acknowledge = { acknowledgeCalls++; true }
        )
        assertEquals(PatchReceiptRecoveryCommitGate.Result.INVALIDATION_FAILED, result)
        assertEquals(0, acknowledgeCalls)
        assertNotNull(activeStorage.value)
        assertEquals(HostActiveChangeSetStore.ReadStatus.INVALID,
            HostActiveChangeSetStore.readActive(context, "another-chat", "workspace-sha", activeStorage).status)
    }

    @Test fun hostActivePointerCommitExceptionFailsClosed() {
        val item = appliedForHostScope()
        val storage = FaultingPreferenceStore(failWrite = true)
        assertFalse(HostActiveChangeSetStore.setActive(context, "chat-active", "workspace-sha", item.id, storage))
        assertEquals(1, storage.writeCalls)
        assertNull(storage.value)
    }

    @Test fun staleActivePointerIsRemovedWhenLedgerConfirmsRollback() {
        val item = appliedForHostScope()
        val activeStorage = FaultingPreferenceStore()
        assertTrue(HostActiveChangeSetStore.setActive(context, "chat-active", "workspace-sha", item.id, activeStorage))
        assertTrue(ChangeSetStore.transition(context, item.id, "ROLLED_BACK"))
        val result = HostActiveChangeSetStore.readActive(context, "chat-active", "workspace-sha", activeStorage)
        assertEquals(HostActiveChangeSetStore.ReadStatus.INVALID, result.status)
        assertNull(activeStorage.value)
        assertTrue(result.reason.contains("unieważniono"))
    }

    @Test fun transientLedgerReadFailureDoesNotDeleteActivePointer() {
        val item = appliedForHostScope()
        val activeStorage = FaultingPreferenceStore()
        assertTrue(HostActiveChangeSetStore.setActive(context, "chat-active", "workspace-sha", item.id, activeStorage))
        val result = HostActiveChangeSetStore.readActive(context, "chat-active", "workspace-sha",
            activeStorage, FaultingPreferenceStore(failRead = true))
        assertEquals(HostActiveChangeSetStore.ReadStatus.INVALID, result.status)
        assertNotNull(activeStorage.value)
    }

    @Test fun staleActivePointerRemovalFailureIsReportedAsInvalid() {
        val item = appliedForHostScope()
        val activeStorage = FaultingPreferenceStore()
        assertTrue(HostActiveChangeSetStore.setActive(context, "chat-active", "workspace-sha", item.id, activeStorage))
        assertTrue(ChangeSetStore.transition(context, item.id, "ROLLED_BACK"))
        activeStorage.failRemove = true
        val result = HostActiveChangeSetStore.readActive(context, "chat-active", "workspace-sha", activeStorage)
        assertEquals(HostActiveChangeSetStore.ReadStatus.INVALID, result.status)
        assertTrue(result.reason.contains("nie udało się trwale"))
        assertNotNull(activeStorage.value)
    }

    @Test fun hostActivePointerRemoveExceptionFailsClosed() {
        val item = appliedForHostScope()
        val storage = FaultingPreferenceStore()
        assertTrue(HostActiveChangeSetStore.setActive(context, "chat-active", "workspace-sha", item.id, storage))
        storage.failRemove = true
        assertFalse(HostActiveChangeSetStore.invalidate(context, item.id, storage))
        assertNotNull(storage.value)
    }

}
