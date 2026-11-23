package pl.aimasterpro.app.core

/**
 * Stable read model for the durable agent-to-agent communication timeline.
 *
 * It combines explicit queue messages with the actual persisted handoff inbox. Provider/model
 * metadata for legacy queue messages is inherited only from progress in the same task/run/sender
 * and at-or-before the message timestamp.
 */
object QueueCommunicationTimeline {
    data class Entry(
        val taskId: String,
        val timestamp: Long,
        val senderLabel: String,
        val recipientLabel: String,
        val status: String,
        val detail: String,
        val providerLabel: String,
        val runId: Int
    )

    fun project(
        events: List<MasterTaskQueueStore.Event>,
        handoffs: List<AgentHandoffStore.Handoff>,
        agentNames: Map<String, String>,
        limit: Int = 120
    ): List<Entry> {
        if (limit <= 0) return emptyList()

        val queueEntries = events
            .filter { MasterTaskQueueStore.isConversationMessage(it) }
            .map { message ->
                val progress = events.asSequence()
                    .filter {
                        it.taskId == message.taskId &&
                            it.runId == message.runId &&
                            it.actor == message.actor &&
                            it.operation == "AGENT_PROGRESS" &&
                            it.timestamp <= message.timestamp &&
                            (it.providerId.isNotBlank() || it.model.isNotBlank())
                    }
                    .maxByOrNull { it.timestamp }

                val providerId = message.providerId.ifBlank { progress?.providerId.orEmpty() }
                val model = message.model.ifBlank { progress?.model.orEmpty() }
                Entry(
                    taskId = message.taskId,
                    timestamp = message.timestamp,
                    senderLabel = displayEndpoint(message.actor, agentNames),
                    recipientLabel = displayEndpoint(message.recipient, agentNames)
                        .ifBlank { "odbiorca nieokreślony" },
                    status = message.toStatus,
                    detail = message.detail,
                    providerLabel = listOf(providerId, model)
                        .filter { it.isNotBlank() }
                        .joinToString(" / ")
                        .ifBlank { "profil modelu nieustalony" },
                    runId = message.runId
                )
            }

        val handoffEntries = handoffs
            .filter { it.payload.isNotBlank() }
            .map { handoff ->
                Entry(
                    taskId = handoff.taskId,
                    timestamp = handoff.updatedAt,
                    senderLabel = displayEndpoint("agent:${handoff.senderAgentId}", agentNames),
                    recipientLabel = displayEndpoint("agent:${handoff.recipientAgentId}", agentNames),
                    status = "HANDOFF_${handoff.state.name}",
                    detail = handoff.payload,
                    providerLabel = "przekazanie agent-agent",
                    runId = handoff.runId
                )
            }

        return (queueEntries + handoffEntries)
            .sortedByDescending { it.timestamp }
            .take(limit)
    }

    private fun displayEndpoint(endpoint: String, agentNames: Map<String, String>): String {
        if (!endpoint.startsWith("agent:")) return endpoint
        val agentId = endpoint.removePrefix("agent:")
        return agentNames[agentId]?.takeIf { it.isNotBlank() } ?: agentId
    }
}
