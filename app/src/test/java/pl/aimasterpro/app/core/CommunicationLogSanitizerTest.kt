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

    @Test
    fun redactsGenericTokenAndAuthorizationAssignments() {
        val input = """
            Authorization: Basic dXNlcjpwYXNz
            ?token=query-secret-value
            "session_token":"session-secret-value"
            auth=auth-secret-value
        """.trimIndent()
        val output = CommunicationLogSanitizer.sanitize(input)
        listOf("dXNlcjpwYXNz", "query-secret-value", "session-secret-value", "auth-secret-value")
            .forEach { assertFalse("Credential leaked: $it", output.contains(it)) }
        assertTrue(output.contains("[REDACTED]"))
    }

    @Test
    fun redactsPasswordsEmbeddedInUrlUserInfo() {
        val input = "Endpoint: https://service-user:url-password-secret@example.invalid/v1"
        val output = CommunicationLogSanitizer.sanitize(input)
        assertFalse(output.contains("url-password-secret"))
        assertTrue(output.contains("https://service-user:[REDACTED]@example.invalid/v1"))
    }

    @Test
    fun redactsPemPrivateKeyBlocksAcrossMultipleLines() {
        val privateKey = """-----BEGIN PRIVATE KEY-----
            MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcw
            private-key-material-secret
            -----END PRIVATE KEY-----""".trimIndent()
        val output = CommunicationLogSanitizer.sanitize("credential=$privateKey")
        assertFalse(output.contains("private-key-material-secret"))
        assertTrue(output.contains("[REDACTED PRIVATE KEY]"))
    }

    @Test
    fun redactsAwsSessionAndPrivateSigningKeyAssignments() {
        val input = """
            aws_secret_access_key=aws-secret-access-value
            aws_session_token=aws-session-token-value
            private_key=local-private-key-value
            signing_key: signing-key-value
        """.trimIndent()
        val output = CommunicationLogSanitizer.sanitize(input)
        listOf(
            "aws-secret-access-value",
            "aws-session-token-value",
            "local-private-key-value",
            "signing-key-value"
        ).forEach { assertFalse("Secret leaked: $it", output.contains(it)) }
        assertTrue(output.contains("[REDACTED]"))
    }
}
