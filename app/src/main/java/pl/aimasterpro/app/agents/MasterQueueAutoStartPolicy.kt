package pl.aimasterpro.app.agents

import pl.aimasterpro.app.core.MasterTaskQueueStore

/**
 * Pure queue-drain policy. It never changes task state or retries interrupted work.
 * Only QUEUED tasks with completed dependencies are eligible for automatic start.
 */
object MasterQueueAutoStartPolicy {
    fun orderedRunnableTasks(
        snapshot: MasterTaskQueueStore.Snapshot
    ): List<MasterTaskQueueStore.Task> {
        if (snapshot.status != MasterTaskQueueStore.ReadStatus.AVAILABLE) return emptyList()
        val completedIds = snapshot.tasks.asSequence()
            .filter { it.status == MasterTaskQueueStore.Status.COMPLETED }
            .map { it.id }
            .toSet()
        return snapshot.tasks.asSequence()
            .filter { it.status == MasterTaskQueueStore.Status.QUEUED }
            .filter { task -> task.dependencies.all { it in completedIds } }
            .sortedWith(
                compareByDescending<MasterTaskQueueStore.Task> { it.priority }
                    .thenBy { it.createdAt }
                    .thenBy { it.id }
            )
            .toList()
    }
}
