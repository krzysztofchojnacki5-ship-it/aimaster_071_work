package pl.aimasterpro.app.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommunicationLogSanitizerTest {
    @Test
    fun redactsCommonProviderAndSourceControlSecrets() {
        val input = """
            Authorization: Bearer abc.def.ghi
            api_key=sk-or-v1-12345678901234567890
            groq=gsk_12345678901234567890
            github ghp_123456789012345678901234567890123456
            json={"access_token":"secret-value"}
        """.trimIndent()
        val output = CommunicationLogSanitizer.sanitize(input)
        listOf("abc.def.ghi", "sk-or-v1-12345678901234567890", "gsk_12345678901234567890",
            "ghp_123456789012345678901234567890123456", "secret-value").forEach {
            assertFalse("Secret leaked: $it", output.contains(it))
        }
        assertTrue(output.contains("[REDACTED]"))
    }

    @Test
    fun preservesOrdinaryTextAndDoesNotTreatPlainTokenDiscussionAsASecret() {
        val input = "Token usage was 1200 tokens; API key should never be printed."
        assertTrue(CommunicationLogSanitizer.sanitize(input).contains("1200 tokens"))
    }
}
