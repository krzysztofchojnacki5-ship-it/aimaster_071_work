package pl.aimasterpro.app.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatScreenPolicyTest {
    @Test fun assistantActionsStayInAssistantChat() {
        assertEquals("Asystent", ChatScreenPolicy.destination("Asystent"))
        assertEquals("Asystent", ChatScreenPolicy.destination("asystent"))
    }

    @Test fun masterChatRemainsDefaultForOtherScreens() {
        assertEquals("Czat", ChatScreenPolicy.destination("Czat"))
        assertEquals("Czat", ChatScreenPolicy.destination("Historia"))
        assertEquals("Czat", ChatScreenPolicy.destination(""))
    }

    @Test fun restoredNavigationSnapshotSkipsRedundantAgentActivityRead() {
        assertEquals(false, ChatScreenPolicy.shouldRefreshAgentActivity(snapshotAlreadyRestored = true))
    }

    @Test fun directChatRenderStillRefreshesAgentActivity() {
        assertEquals(true, ChatScreenPolicy.shouldRefreshAgentActivity(snapshotAlreadyRestored = false))
    }
}
