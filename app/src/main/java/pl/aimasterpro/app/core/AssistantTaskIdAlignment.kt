package pl.aimasterpro.app.core

/** Keeps persisted request IDs aligned with assistant-message order during legacy migration. */
object AssistantTaskIdAlignment {
    fun align(existingIds: List<String>, assistantMessageCount: Int): List<String> {
        val targetSize = assistantMessageCount.coerceAtLeast(0)
        return when {
            existingIds.size == targetSize -> existingIds.toList()
            existingIds.size < targetSize -> List(targetSize - existingIds.size) { "" } + existingIds
            else -> existingIds.take(targetSize)
        }
    }
}
