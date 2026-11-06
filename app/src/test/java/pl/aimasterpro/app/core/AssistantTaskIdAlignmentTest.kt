package pl.aimasterpro.app.core

import org.junit.Assert.assertEquals
import org.junit.Test

class AssistantTaskIdAlignmentTest {
    @Test fun legacyAssistantMessagesReceiveEmptyPlaceholders() {
        assertEquals(listOf("", "", "task-3"), AssistantTaskIdAlignment.align(listOf("task-3"), 3))
    }

    @Test fun staleExtraIdsAreTrimmedWithoutChangingEarlierMappings() {
        assertEquals(listOf("task-1", "task-2"), AssistantTaskIdAlignment.align(listOf("task-1", "task-2", "stale"), 2))
    }

    @Test fun negativeMessageCountProducesEmptyMapping() {
        assertEquals(emptyList<String>(), AssistantTaskIdAlignment.align(listOf("task-1"), -1))
    }
}
