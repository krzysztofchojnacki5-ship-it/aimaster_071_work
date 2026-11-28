package pl.aimasterpro.app.core

/** Conservative restart planner for the ordered MASTER agent pipeline. */
object QueueResumePlanner {
    data class AgentEvidence(
        val agentId: String,
        val reportArchived: Boolean,
        val checkpointStatus: String = "",
        val queueResultStatus: String = "",
        val checkpointDetail: String = "",
        val queueResultDetail: String = ""
    )
    data class Plan(
        val safe: Boolean,
        val completedAgentIds: List<String> = emptyList(),
        val pendingAgentIds: List<String> = emptyList(),
        val reason: String = ""
    )
    private val ambiguousCheckpointStates = setOf(
        "REQUESTING", "USAGE_RECORDED", "USAGE_PENDING", "RESULT_PERSIST_PENDING",
        "INTERRUPTED_USAGE_PENDING", "INTERRUPTED", "TIMED_OUT", "CANCELLED",
        "COMPLETED", "FAILED"
    )

    fun plan(evidence: List<AgentEvidence>): Plan {
        if (evidence.isEmpty()) return Plan(false, reason = "Brak przypisanych agentów.")
        if (evidence.any { it.agentId.isBlank() } ||
            evidence.map { it.agentId }.distinct().size != evidence.size) {
            return Plan(false, reason = "Lista agentów jest nieprawidłowa lub zawiera duplikaty.")
        }
        val completed = mutableListOf<String>()
        val pending = mutableListOf<String>()
        var pendingStarted = false
        evidence.forEach { item ->
            val queueStatus = item.queueResultStatus.uppercase()
            val checkpoint = item.checkpointStatus.uppercase()
            val safeRetry = queueStatus == "BLOCKED" &&
                checkpoint == "BLOCKED" &&
                item.checkpointDetail.trimStart().startsWith("SAFE_RETRY:") &&
                item.queueResultDetail.trimStart().startsWith("BLOCKED: SAFE_RETRY:")
            if (queueStatus in setOf("FAILED", "BLOCKED") && !safeRetry) {
                return Plan(false, reason = "Agent ${item.agentId} zakończył przebieg błędem lub blokadą. Użyj ponowienia od początku.")
            }
            if (safeRetry) {
                // The archive contains a diagnostic placeholder, not a completed agent report.
                // Both durable stores must independently prove the failure happened before POST processing.
                pendingStarted = true
                pending += item.agentId
                return@forEach
            }
            if (item.reportArchived) {
                if (checkpoint in setOf(
                        "REQUESTING", "USAGE_RECORDED", "USAGE_PENDING", "RESULT_PERSIST_PENDING",
                        "INTERRUPTED_USAGE_PENDING", "INTERRUPTED", "TIMED_OUT", "CANCELLED",
                        "FAILED", "BLOCKED"
                    )) {
                    return Plan(false, reason = "Raport agenta ${item.agentId} istnieje, ale jego checkpoint wymaga jeszcze uzgodnienia ($checkpoint).")
                }
                if (pendingStarted) {
                    return Plan(false, reason = "Archiwum nie zachowuje kolejności agentów. Nie można bezpiecznie odtworzyć przekazań.")
                }
                if (queueStatus.isNotBlank() && queueStatus != "COMPLETED") {
                    return Plan(false, reason = "Status wyniku agenta ${item.agentId} jest sprzeczny z archiwum raportu.")
                }
                completed += item.agentId
            } else {
                pendingStarted = true
                if (queueStatus == "COMPLETED" || checkpoint in ambiguousCheckpointStates) {
                    return Plan(false, reason = "Wynik agenta ${item.agentId} jest niejednoznaczny (checkpoint: ${checkpoint.ifBlank { "brak" }}). Nie ponawiam żądania API.")
                }
                if (queueStatus.isNotBlank()) {
                    return Plan(false, reason = "Brakuje raportu agenta ${item.agentId}, mimo zapisanego wyniku kolejki.")
                }
                if (checkpoint.isNotBlank() && checkpoint !in setOf("QUEUED", "RUNNING")) {
                    return Plan(false, reason = "Nieznany stan checkpointu agenta ${item.agentId}: $checkpoint.")
                }
                pending += item.agentId
            }
        }
        return Plan(true, completed, pending)
    }
}
