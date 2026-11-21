package pl.aimasterpro.app.core

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LegacyChatHistoryImporterTest {
    @Test fun missingOrBlankLegacyMirrorIsAValidEmptyHistory() {
        assertEquals(emptyList<LegacyChatHistoryImporter.Message>(), LegacyChatHistoryImporter.parse(null))
        assertEquals(emptyList<LegacyChatHistoryImporter.Message>(), LegacyChatHistoryImporter.parse("  "))
        assertEquals(emptyList<LegacyChatHistoryImporter.Message>(), LegacyChatHistoryImporter.parse("[]"))
    }

    @Test fun validLegacyMessagesAreImportedInOriginalOrder() {
        val parsed = LegacyChatHistoryImporter.parse(
            """[{"role":"user","text":"Pytanie"},{"role":"assistant","text":"Odpowiedź"}]"""
        )
        assertEquals(
            listOf(
                LegacyChatHistoryImporter.Message("user", "Pytanie"),
                LegacyChatHistoryImporter.Message("assistant", "Odpowiedź")
            ),
            parsed
        )
    }

    @Test fun malformedJsonDoesNotBecomeAnEmptyHistory() {
        assertNull(LegacyChatHistoryImporter.parse("""[{"role":"user""""))
    }

    @Test fun malformedRowsAndNonStringFieldsFailClosed() {
        assertNull(LegacyChatHistoryImporter.parse("""[42]"""))
        assertNull(LegacyChatHistoryImporter.parse("""[{"role":"user"}]"""))
        assertNull(LegacyChatHistoryImporter.parse("""[{"role":7,"text":"x"}]"""))
        assertNull(LegacyChatHistoryImporter.parse("""[{"role":"user","text":false}]"""))
    }
    @Test fun oversizedLegacyPayloadCollectionsAndFieldsFailClosed() {
        assertNull(LegacyChatHistoryImporter.parse(" ".repeat(1_000_001)))
        val tooMany = org.json.JSONArray()
        repeat(5_001) { tooMany.put(org.json.JSONObject().put("role", "user").put("text", "x")) }
        assertNull(LegacyChatHistoryImporter.parse(tooMany.toString()))
        assertNull(LegacyChatHistoryImporter.parse(org.json.JSONArray().put(org.json.JSONObject().put("role", "r".repeat(33)).put("text", "x")).toString()))
        assertNull(LegacyChatHistoryImporter.parse(org.json.JSONArray().put(org.json.JSONObject().put("role", "user").put("text", "x".repeat(32_001))).toString()))
    }
}
