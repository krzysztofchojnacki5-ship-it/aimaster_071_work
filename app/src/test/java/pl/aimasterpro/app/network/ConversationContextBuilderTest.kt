package pl.aimasterpro.app.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationContextBuilderTest {
    @Test fun preservesVisibleTurnOrderAndRoles() {
        val result = ConversationContextBuilder.build(
            listOf(
                ConversationTurn("user", "Pierwsze pytanie"),
                ConversationTurn("assistant", "Pierwsza odpowiedź"),
                ConversationTurn("system", "sekretna instrukcja"),
                ConversationTurn("tool", "wewnętrzny wynik"),
                ConversationTurn("user", "Drugie pytanie")
            ),
            currentUserText = "Aktualne pytanie"
        )
        assertEquals(
            listOf("user", "assistant", "user"),
            result.map { it.role }
        )
        assertEquals(
            listOf("Pierwsze pytanie", "Pierwsza odpowiedź", "Drugie pytanie"),
            result.map { it.content }
        )
    }

    @Test fun removesOnlyThePendingDuplicateCurrentUserTurn() {
        val result = ConversationContextBuilder.build(
            listOf(
                ConversationTurn("user", "Powtórz"),
                ConversationTurn("assistant", "Wynik"),
                ConversationTurn("user", "Aktualne")
            ),
            currentUserText = "Aktualne"
        )
        assertEquals(2, result.size)
        assertEquals("Powtórz", result.first().content)
    }

    @Test fun keepsNewestContextWhenHistoryExceedsBudget() {
        val result = ConversationContextBuilder.build(
            listOf(
                ConversationTurn("user", "stare"),
                ConversationTurn("assistant", "123456"),
                ConversationTurn("user", "najnowsze")
            ),
            currentUserText = "teraz",
            maxHistoryChars = 10
        )
        assertEquals(listOf("assistant", "user"), result.map { it.role })
        assertEquals(listOf("6", "najnowsze"), result.map { it.content })
        assertEquals(10, result.sumOf { it.content.length })
    }

    @Test fun truncatesAnOversizedNewestTurnFromItsBeginning() {
        val result = ConversationContextBuilder.build(
            listOf(ConversationTurn("assistant", "0123456789")),
            currentUserText = "nowe",
            maxHistoryChars = 4
        )
        assertEquals(listOf("6789"), result.map { it.content })
    }

    @Test fun emptyHistoryProducesNoContext() {
        assertTrue(ConversationContextBuilder.build(emptyList(), "hello").isEmpty())
    }

    @Test fun ignoresBlankTurnsAndNormalizesRoleAndWhitespace() {
        val result = ConversationContextBuilder.build(
            listOf(
                ConversationTurn(" USER ", "  cześć  "),
                ConversationTurn("assistant", "  "),
                ConversationTurn("SYSTEM", "nie wysyłaj")
            ),
            currentUserText = "dalej"
        )
        assertEquals(listOf(ConversationTurn("user", "cześć")), result)
    }
}
