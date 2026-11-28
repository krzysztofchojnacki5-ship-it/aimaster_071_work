package pl.aimasterpro.app.agents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.aimasterpro.app.core.MasterTaskQueueStore

class MasterQueueAutoStartPolicyTest {
    private fun task(
        id: String,
        status: MasterTaskQueueStore.Status,
        priority: Int = 2,
        createdAt: Long = 10L,
        dependencies: List<String> = emptyList()
    ) = MasterTaskQueueStore.Task(
        id = id,
        title = id,
        objective = "Objective for $id",
        assignedAgentIds = emptyList(),
        dependencies = dependencies,
        priority = priority,
        status = status,
        currentStep = "",
        createdAt = createdAt,
        updatedAt = createdAt
    )

    @Test fun ordersEligibleTasksByPriorityThenAgeThenStableId() {
        val snapshot = MasterTaskQueueStore.Snapshot(
            MasterTaskQueueStore.ReadStatus.AVAILABLE,
            tasks = listOf(
                task("later", MasterTaskQueueStore.Status.QUEUED, priority = 3, createdAt = 20),
                task("older", MasterTaskQueueStore.Status.QUEUED, priority = 3, createdAt = 10),
                task("alpha", MasterTaskQueueStore.Status.QUEUED, priority = 3, createdAt = 10),
                task("normal", MasterTaskQueueStore.Status.QUEUED, priority = 1, createdAt = 1)
            )
        )

        assertEquals(
            listOf("alpha", "older", "later", "normal"),
            MasterQueueAutoStartPolicy.orderedRunnableTasks(snapshot).map { it.id }
        )
    }

    @Test fun excludesTasksWithIncompleteDependenciesAndNonQueuedStates() {
        val snapshot = MasterTaskQueueStore.Snapshot(
            MasterTaskQueueStore.ReadStatus.AVAILABLE,
            tasks = listOf(
                task("done", MasterTaskQueueStore.Status.COMPLETED),
                task("ready", MasterTaskQueueStore.Status.QUEUED, dependencies = listOf("done")),
                task("waiting", MasterTaskQueueStore.Status.QUEUED, dependencies = listOf("still-running")),
                task("paused", MasterTaskQueueStore.Status.PAUSED),
                task("blocked", MasterTaskQueueStore.Status.BLOCKED),
                task("assigned", MasterTaskQueueStore.Status.ASSIGNED)
            )
        )

        assertEquals(listOf("ready"), MasterQueueAutoStartPolicy.orderedRunnableTasks(snapshot).map { it.id })
    }

    @Test fun unavailableQueueFailsClosed() {
        val snapshot = MasterTaskQueueStore.Snapshot(
            MasterTaskQueueStore.ReadStatus.UNAVAILABLE,
            tasks = listOf(task("queued", MasterTaskQueueStore.Status.QUEUED))
        )
        assertTrue(MasterQueueAutoStartPolicy.orderedRunnableTasks(snapshot).isEmpty())
    }
}
