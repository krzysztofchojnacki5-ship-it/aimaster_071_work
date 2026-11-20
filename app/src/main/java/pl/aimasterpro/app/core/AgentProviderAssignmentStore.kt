package pl.aimasterpro.app.core

import android.content.Context

/**
 * Durable, validated persistence for agent -> provider assignments.
 * MASTER is mirrored to selected_provider for compatibility with legacy readers.
 */
object AgentProviderAssignmentStore {
    private const val PREFS = "chat_state"
    private const val MASTER = "MASTER"
    private const val SELECTED_KEY = "selected_provider"
    private const val MAX_ROLE_ID_CHARS = 80
    private const val MAX_PROVIDER_ID_CHARS = 160
    private const val MAX_ASSIGNMENTS = 120
    private const val MAX_PROVIDER_IDS = 500

    fun read(
        context: Context,
        roleDefaults: Map<String, String>
    ): Map<String, String> {
        require(validRoleDefaults(roleDefaults)) { "Invalid provider assignment defaults." }
        return SharedPreferencesWriteHealth.withHealthyAccess(PREFS) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            roleDefaults.mapValues { (role, defaultId) ->
                // Missing/blank is a legitimate first-run state; a type mismatch is corruption.
                // Let it propagate so callers cannot silently route to a different provider.
                val stored = prefs.getString(key(role), null)
                require(stored == null || stored.length <= MAX_PROVIDER_ID_CHARS) { "Stored provider assignment exceeds its supported length." }
                stored?.takeIf { it.isNotBlank() } ?: defaultId
            }
        }
    }

    /** Explicit role changes require a permit issued by the durable memory-governance gate. */
    internal fun save(
        context: Context,
        role: String,
        providerId: String,
        permit: MemoryMutationPermit
    ): Boolean {
        if (!MemoryMutationGate.isValid(
                permit, setOf(MemoryGovernance.Mutation.SET_AGENT_PROVIDER), role
            ) || !validRole(role) || !validProviderId(providerId) ||
            CommunicationLogSanitizer.sanitize(providerId) != providerId ||
            SharedPreferencesWriteHealth.isUncertain(PREFS)) return false
        return runCatching {
            val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(key(role), providerId)
            if (role == MASTER) editor.putString(SELECTED_KEY, providerId)
            SharedPreferencesWriteHealth.commit(PREFS, editor)
        }.getOrDefault(false)
    }

    /**
     * Closes interrupted user assignment intents only after startup has read and durably
     * reconciled the complete role-assignment snapshot. The approval reference carries the
     * intended provider ID as "u:<nonce>:<providerId>"; current durable state is authoritative.
     */
    @Synchronized
    internal fun resolvePendingGovernance(
        context: Context,
        verifiedAssignments: Map<String, String>
    ): Int {
        val pending = MemoryAuditLog.unresolvedOperations(context) ?: return 0
        var resolved = 0
        pending.filter {
            it.actor == MemoryGovernance.Actor.USER.name &&
                it.mutation == MemoryGovernance.Mutation.SET_AGENT_PROVIDER.name
        }.forEach { record ->
            if (!validRole(record.objectId) || !record.approvalRef.startsWith("u:")) return@forEach
            val separator = record.approvalRef.indexOf(':', startIndex = 2)
            if (separator <= 2 || separator == record.approvalRef.lastIndex) return@forEach
            val intendedProviderId = record.approvalRef.substring(separator + 1)
            if (!validProviderId(intendedProviderId) ||
                CommunicationLogSanitizer.sanitize(intendedProviderId) != intendedProviderId) return@forEach
            val actualProviderId = verifiedAssignments[record.objectId] ?: return@forEach
            val recoveryReason = if (actualProviderId == intendedProviderId) {
                "Odzyskiwanie potwierdziło trwałe przypisanie ${record.objectId}=${actualProviderId}."
            } else {
                "Odzyskiwanie potwierdziło zachowanie ${record.objectId}=${actualProviderId}; zmiana na ${intendedProviderId} nie została utrwalona."
            }
            if (MemoryAuditLog.resolveUnresolved(context, record, recoveryReason)) resolved++
        }
        return resolved
    }

    /**
     * Startup recovery: keep every still-valid assignment and repair stale IDs left by
     * process death between provider replacement and assignment reconciliation.
     * The MASTER legacy key and all role assignments are committed atomically.
     */
    fun reconcileExistingAssignments(
        context: Context,
        roleDefaults: Map<String, String>,
        validProviderIds: Set<String>,
        legacyMasterId: String?
    ): Map<String, String>? {
        if (!validRoleDefaults(roleDefaults) || validProviderIds.isEmpty() || validProviderIds.size > MAX_PROVIDER_IDS || validProviderIds.any { !validProviderId(it) } || SharedPreferencesWriteHealth.isUncertain(PREFS)) return null
        return runCatching {
            SharedPreferencesWriteHealth.withHealthyAccess(PREFS) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val oldMaster = prefs.getString(key(MASTER), null)
            val masterId = oldMaster?.takeIf { it in validProviderIds }
                ?: legacyMasterId?.takeIf { it in validProviderIds }
                ?: roleDefaults[MASTER]?.takeIf { it in validProviderIds }
                ?: validProviderIds.sorted().first()
            val resolved = roleDefaults.mapValues { (role, defaultId) ->
                if (role == MASTER) masterId else {
                    val old = prefs.getString(key(role), null)
                    old?.takeIf { it in validProviderIds }
                        ?: defaultId.takeIf { it in validProviderIds }
                        ?: masterId
                }
            }
            val editor = prefs.edit()
            resolved.forEach { (role, id) -> editor.putString(key(role), id) }
            editor.putString(SELECTED_KEY, masterId)
            if (!SharedPreferencesWriteHealth.commit(PREFS, editor)) return@withHealthyAccess null
            resolved
            }
        }.getOrNull()
    }

    /**
     * Reconciles persisted assignments after a full provider import.
     * Valid existing assignments survive. Missing IDs are replaced deterministically.
     * MASTER and selected_provider are written in the same SharedPreferences commit.
     */
    fun reconcileAfterProviderImport(
        context: Context,
        roleDefaults: Map<String, String>,
        validProviderIds: Set<String>,
        preferredMasterId: String
    ): Map<String, String>? {
        if (validProviderIds.isEmpty() || validProviderIds.any { it.isBlank() } || SharedPreferencesWriteHealth.isUncertain(PREFS)) return null
        return runCatching {
            SharedPreferencesWriteHealth.withHealthyAccess(PREFS) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val masterFallback = preferredMasterId.takeIf { it in validProviderIds }
                ?: roleDefaults[MASTER]?.takeIf { it in validProviderIds }
                ?: validProviderIds.sorted().first()
            val resolved = roleDefaults.mapValues { (role, defaultId) ->
                // This path is an explicit full-import recovery operation. Unlike startup,
                // it may repair a wrongly typed assignment by replacing it with a validated fallback.
                val old = runCatching { prefs.getString(key(role), null) }.getOrNull()
                when {
                    role == MASTER -> masterFallback
                    old != null && old in validProviderIds -> old
                    defaultId in validProviderIds -> defaultId
                    else -> masterFallback
                }
            }
            val editor = prefs.edit()
            resolved.forEach { (role, id) -> editor.putString(key(role), id) }
            editor.putString(SELECTED_KEY, resolved[MASTER] ?: masterFallback)
            if (!SharedPreferencesWriteHealth.commit(PREFS, editor)) return@withHealthyAccess null
            resolved
            }
        }.getOrNull()
    }

    private fun validRole(role: String): Boolean = role.isNotBlank() && role.length <= MAX_ROLE_ID_CHARS
    private fun validProviderId(id: String): Boolean = id.isNotBlank() && id.length <= MAX_PROVIDER_ID_CHARS
    private fun validRoleDefaults(values: Map<String, String>): Boolean =
        values.isNotEmpty() && values.size <= MAX_ASSIGNMENTS &&
            values.all { (role, provider) -> validRole(role) && validProviderId(provider) }

    private fun key(role: String): String = "agent_provider_$role"
}
