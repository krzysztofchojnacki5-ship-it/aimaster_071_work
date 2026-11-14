package pl.aimasterpro.app.core

import android.content.Context

internal sealed interface MemoryMutationPermit
private data class AuthorizedMemoryMutationPermit(val request: MemoryGovernance.Request) : MemoryMutationPermit

/**
 * Single execution boundary for durable memory mutations.
 *
 * Authorization and the INTENT record must both succeed before the supplied mutation runs.
 * A terminal audit failure is reported as failure; the unmatched INTENT remains evidence that
 * recovery must inspect the underlying stores before retrying.
 */
object MemoryMutationGate {
    @Synchronized
    internal fun execute(
        context: Context,
        request: MemoryGovernance.Request,
        mutation: (MemoryMutationPermit) -> Boolean
    ): Boolean {
        val decision = MemoryGovernance.authorize(request)
        if (!decision.allowed) {
            MemoryAuditLog.append(
                context,
                auditEvent(request, MemoryAuditLog.Phase.FAILED, "DENIED_${decision.code}")
            )
            return false
        }

        // A previous attempt may have changed a store before the process died.
        // Do not execute it again until an explicit recovery procedure resolves the INTENT.
        if (MemoryAuditLog.hasUnresolvedIntent(
                context, request.actor.name, request.mutation.name, request.objectId
            )) return false

        // A completed USER approval reference is an idempotency key: replaying the same
        // request must not execute a second durable mutation. Recovery remains repeatable.
        if (request.actor == MemoryGovernance.Actor.USER &&
            MemoryAuditLog.hasCompletedRequest(
                context, request.actor.name, request.mutation.name,
                request.objectId, request.approvalRef
            )
        ) {
            MemoryAuditLog.append(
                context,
                auditEvent(request, MemoryAuditLog.Phase.FAILED, "DENIED_DUPLICATE_REQUEST")
            )
            return false
        }

        if (!MemoryAuditLog.append(
                context,
                auditEvent(request, MemoryAuditLog.Phase.INTENT, "AUTHORIZED")
            )
        ) return false

        val permit = AuthorizedMemoryMutationPermit(request)
        val mutationSucceeded = runCatching { mutation(permit) }.getOrDefault(false)
        val terminalPhase = if (mutationSucceeded) MemoryAuditLog.Phase.COMMITTED else MemoryAuditLog.Phase.FAILED
        val terminalOutcome = if (mutationSucceeded) "SUCCESS" else "MUTATION_FAILED"
        val auditSucceeded = MemoryAuditLog.append(
            context,
            auditEvent(request, terminalPhase, terminalOutcome)
        )

        // A mutation without a durable terminal audit record is never reported as successful.
        return mutationSucceeded && auditSucceeded
    }

    internal fun isValid(
        permit: MemoryMutationPermit,
        allowedMutations: Set<MemoryGovernance.Mutation>,
        objectId: String? = null
    ): Boolean {
        val request = (permit as? AuthorizedMemoryMutationPermit)?.request ?: return false
        return request.mutation in allowedMutations &&
            (objectId == null || request.objectId == objectId)
    }

    @Synchronized
    fun execute(context: Context, request: MemoryGovernance.Request, mutation: () -> Boolean): Boolean =
        execute(context, request) { _: MemoryMutationPermit -> mutation() }

    private fun auditEvent(
        request: MemoryGovernance.Request,
        phase: MemoryAuditLog.Phase,
        outcome: String
    ) = MemoryAuditLog.Event(
        actor = request.actor.name,
        mutation = request.mutation.name,
        objectId = request.objectId,
        reason = request.reason,
        approvalRef = request.approvalRef,
        phase = phase,
        outcome = outcome
    )
}
