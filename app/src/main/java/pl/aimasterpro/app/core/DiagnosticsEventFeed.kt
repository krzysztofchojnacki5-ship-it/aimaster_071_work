package pl.aimasterpro.app.core

/**
 * Read-only projection of the durable queue journal and handoff inbox for Diagnostics.
 *
 * Queue events describe transitions; handoff rows describe the latest durable state.
 * Payloads are deliberately excluded so the diagnostics feed never duplicates report bodies.
 */
object DiagnosticsEventFeed {
    data class Filter(
        val taskId: String = "",
        val agentId: String = "",
        val providerId: String = "",
        val operation: String = ""
    )

    data class Entry(
        val id: String,
        val taskId: String,
        val timestamp: Long,
        val operation: String,
        val actor: String,
        val recipient: String,
        val providerId: String,
        val model: String,
        val detail: String,
        val source: String
    )

    data class Snapshot(
        val queueStatus: MasterTaskQueueStore.ReadStatus,
        val handoffStatus: AgentHandoffStore.ReadStatus,
        val entries: List<Entry>
    ) {
        val isComplete: Boolean
            get() = queueStatus == MasterTaskQueueStore.ReadStatus.AVAILABLE &&
                handoffStatus == AgentHandoffStore.ReadStatus.AVAILABLE
    }

    fun build(
        queue: MasterTaskQueueStore.Snapshot,
        handoffs: AgentHandoffStore.Snapshot,
        filter: Filter = Filter(),
        limit: Int = 12
    ): Snapshot {
        val entries = ArrayList<Entry>()
        if (queue.status == MasterTaskQueueStore.ReadStatus.AVAILABLE) {
            queue.events.forEach { event ->
                entries += Entry(
                    id = event.id,
                    taskId = event.taskId,
                    timestamp = event.timestamp,
                    operation = event.operation,
                    actor = event.actor,
                    recipient = event.recipient,
                    providerId = event.providerId,
                    model = event.model,
                    // Queue event details may contain user or agent authored content.
                    // Render only structured operational metadata, never free-form details.
                    detail = when (event.operation) {
                        "CREATE" -> "Utworzono zadanie w kolejce."
                        "ASSIGN" -> "Zapisano przydział agentów."
                        "TRANSITION" -> "Zmiana stanu: ${event.fromStatus} → ${event.toStatus}."
                        "AGENT_RESULT" -> "Wynik agenta zapisany (${event.toStatus})."
                        "AGENT_MESSAGE" -> "Wiadomość agenta zarejestrowana (${event.toStatus})."
                        "AGENT_PROGRESS" -> "Postęp agenta: ${event.toStatus}."
                        "AGENT_TRACE" -> "Ślad techniczny: ${event.toStatus}."
                        "RECOVER_INTERRUPTED" -> "Odzyskiwanie przerwanego zadania: ${event.fromStatus} → ${event.toStatus}."
                        else -> "Zarejestrowano zdarzenie operacyjne."
                    },
                    source = "QUEUE"
                )
            }
        }
        if (handoffs.status == AgentHandoffStore.ReadStatus.AVAILABLE) {
            handoffs.messages.forEach { message ->
                val detail = when (message.state) {
                    AgentHandoffStore.State.PENDING -> "Przekazanie oczekuje na odbiorcę."
                    AgentHandoffStore.State.CLAIMED -> "Przekazanie przejęte; wynik żądania odbiorcy może być nieznany."
                    AgentHandoffStore.State.ACKNOWLEDGED -> "Odbiorca potwierdził trwałe przyjęcie przekazania."
                    AgentHandoffStore.State.REJECTED -> "Odbiorca odrzucił przekazanie."
                    AgentHandoffStore.State.MANUALLY_RESOLVED ->
                        "Ręcznie zamknięto jako wynik nieznany (${message.resolutionActor})."
                }
                entries += Entry(
                    id = message.id,
                    taskId = message.taskId,
                    timestamp = message.updatedAt,
                    operation = "HANDOFF_${message.state.name}",
                    actor = "agent:${message.senderAgentId}",
                    recipient = "agent:${message.recipientAgentId}",
                    providerId = "",
                    model = "",
                    detail = detail,
                    source = "HANDOFF"
                )
            }
        }

        val taskFilter = filter.taskId.trim()
        val agentFilter = filter.agentId.trim().removePrefix("agent:")
        val providerFilter = filter.providerId.trim()
        val operationFilter = filter.operation.trim()

        fun matchesAgent(endpoint: String): Boolean {
            val endpointId = endpoint.removePrefix("agent:")
            return endpoint.equals(agentFilter, ignoreCase = true) ||
                endpointId.equals(agentFilter, ignoreCase = true)
        }

        val filtered = entries.asSequence()
            .filter { taskFilter.isEmpty() || it.taskId == taskFilter }
            .filter {
                agentFilter.isEmpty() || matchesAgent(it.actor) || matchesAgent(it.recipient)
            }
            .filter {
                providerFilter.isEmpty() ||
                    it.providerId.contains(providerFilter, ignoreCase = true) ||
                    it.model.contains(providerFilter, ignoreCase = true)
            }
            .filter { operationFilter.isEmpty() || it.operation.equals(operationFilter, ignoreCase = true) }
            .sortedWith(
                compareByDescending<Entry> { it.timestamp }
                    .thenBy { it.id }
                    .thenBy { it.source }
            )
            .take(limit.coerceIn(1, 100))
            .toList()

        return Snapshot(queue.status, handoffs.status, filtered)
    }
}
