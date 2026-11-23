package pl.aimasterpro.app.core

/**
 * Stable read model for the durable agent-to-agent communication timeline.
 *
 * Provider/model metadata is taken from the message itself when present. Legacy messages
 * without it may inherit the most recent progress event only from the same task, run,
 * sender and at-or-before the message timestamp.
 */
object QueueCommunicationTimeline {
    data class Entry(
        val event: MasterTaskQueueStore.Event,
        val senderLabel: String,
        val recipientLabel: String,
        val providerLabel: String
    )

    fun project(
        events: List<MasterTaskQueueStore.Event>,
        agentNames: Map<String, String>,
        limit: Int = 120
    ): List<Entry> {
        if (limit <= 0) return emptyList()
        val messages = events
            .filter { MasterTaskQueueStore.isConversationMessage(it) }
            .takeLast(limit)
            .asReversed()

        return messages.map { message ->
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
                event = message,
                senderLabel = displayEndpoint(message.actor, agentNames),
                recipientLabel = displayEndpoint(message.recipient, agentNames)
                    .ifBlank { "odbiorca nieokreślony" },
                providerLabel = listOf(providerId, model)
                    .filter { it.isNotBlank() }
                    .joinToString(" / ")
                    .ifBlank { "profil modelu nieustalony" }
            )
        }
    }

    private fun displayEndpoint(endpoint: String, agentNames: Map<String, String>): String {
        if (!endpoint.startsWith("agent:")) return endpoint
        val agentId = endpoint.removePrefix("agent:")
        return agentNames[agentId]?.takeIf { it.isNotBlank() } ?: agentId
    }
}
