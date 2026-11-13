package pl.aimasterpro.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryGovernanceTest {
    private fun request(
        actor: MemoryGovernance.Actor,
        mutation: MemoryGovernance.Mutation,
        approvalRef: String = "ui-confirmation:proposal-1",
        objectId: String = "proposal-1",
        reason: String = "Jawna decyzja użytkownika"
    ) = MemoryGovernance.Request(actor, mutation, objectId, reason, approvalRef)

    @Test fun agentsCannotMutateAnyPersistedLearningState() {
        MemoryGovernance.Mutation.values().forEach { mutation ->
            val decision = MemoryGovernance.authorize(
                request(MemoryGovernance.Actor.AGENT, mutation, approvalRef = "agent-request")
            )
            assertFalse("Agent mutation must be denied: $mutation", decision.allowed)
            assertEquals("AGENT_READ_ONLY", decision.code)
        }
    }

    @Test fun masterCanProposeLearningButCannotApproveOrMutateMemory() {
        MemoryGovernance.Mutation.values().forEach { mutation ->
            val decision = MemoryGovernance.authorize(
                request(MemoryGovernance.Actor.MASTER, mutation, approvalRef = "master-proposal")
            )
            assertFalse("MASTER mutation must be denied: $mutation", decision.allowed)
            assertEquals("MASTER_PROPOSAL_ONLY", decision.code)
        }
    }

    @Test fun userMutationsRequireExplicitApprovalReferenceAndReason() {
        val userMutations = listOf(
            MemoryGovernance.Mutation.ADD_SHARED_RULE,
            MemoryGovernance.Mutation.REMOVE_SHARED_RULE,
            MemoryGovernance.Mutation.ADD_AGENT_LESSON,
            MemoryGovernance.Mutation.REMOVE_AGENT_LESSON,
            MemoryGovernance.Mutation.APPROVE_PROPOSAL,
            MemoryGovernance.Mutation.REJECT_PROPOSAL
        )
        userMutations.forEach { mutation ->
            assertTrue(MemoryGovernance.authorize(
                request(MemoryGovernance.Actor.USER, mutation)
            ).allowed)
            val missingApproval = MemoryGovernance.authorize(
                request(MemoryGovernance.Actor.USER, mutation, approvalRef = "")
            )
            assertFalse(missingApproval.allowed)
            assertEquals("USER_APPROVAL_REQUIRED", missingApproval.code)
        }
    }

    @Test fun userCannotInvokeInternalRecoveryMutations() {
        listOf(
            MemoryGovernance.Mutation.RECONCILE_APPROVED_PROPOSAL,
            MemoryGovernance.Mutation.RECONCILE_REVOKED_PROPOSAL
        ).forEach { mutation ->
            val decision = MemoryGovernance.authorize(
                request(MemoryGovernance.Actor.USER, mutation)
            )
            assertFalse(decision.allowed)
            assertEquals("USER_MUTATION_SCOPE", decision.code)
        }
    }

    @Test fun recoveryIsLimitedToExactProposalReferences() {
        listOf(
            MemoryGovernance.Mutation.RECONCILE_APPROVED_PROPOSAL,
            MemoryGovernance.Mutation.RECONCILE_REVOKED_PROPOSAL
        ).forEach { mutation ->
            assertTrue(MemoryGovernance.authorize(
                request(
                    MemoryGovernance.Actor.SYSTEM_RECOVERY,
                    mutation,
                    approvalRef = "proposal:proposal-1"
                )
            ).allowed)
            val wrongReference = MemoryGovernance.authorize(
                request(
                    MemoryGovernance.Actor.SYSTEM_RECOVERY,
                    mutation,
                    approvalRef = "proposal:other"
                )
            )
            assertFalse(wrongReference.allowed)
            assertEquals("RECOVERY_REFERENCE_REQUIRED", wrongReference.code)
        }
    }

    @Test fun malformedMutationMetadataIsRejected() {
        val missingObject = MemoryGovernance.authorize(
            request(MemoryGovernance.Actor.USER, MemoryGovernance.Mutation.ADD_SHARED_RULE, objectId = "")
        )
        assertFalse(missingObject.allowed)
        assertEquals("INVALID_OBJECT", missingObject.code)

        val missingReason = MemoryGovernance.authorize(
            request(MemoryGovernance.Actor.USER, MemoryGovernance.Mutation.ADD_SHARED_RULE, reason = " ")
        )
        assertFalse(missingReason.allowed)
        assertEquals("INVALID_REASON", missingReason.code)
    }
}
