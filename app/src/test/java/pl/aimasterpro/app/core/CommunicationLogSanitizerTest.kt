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
    fun redactsAdditionalProviderAndCookieHeadersAndJsonFields() {
        val input = """
            X-Goog-Api-Key: google-header-secret
            x-auth-token=auth-header-secret
            Cookie: session-cookie-secret; theme=dark
            Set-Cookie: session=set-cookie-secret; HttpOnly
            {"x-goog-api-key":"google-json-secret","cookie":"json-cookie-secret"}
        """.trimIndent()
        val output = CommunicationLogSanitizer.sanitize(input)
        listOf(
            "google-header-secret", "auth-header-secret", "session-cookie-secret",
            "set-cookie-secret", "google-json-secret", "json-cookie-secret"
        ).forEach { assertFalse("Credential leaked: $it", output.contains(it)) }
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

    @Test
    fun redactsStripeSecretAndRestrictedKeys() {
        val secret = "sk_live_1234567890abcdefghijkl"
        val restricted = "rk_test_1234567890abcdefghijkl"
        val output = CommunicationLogSanitizer.sanitize("stripe=$secret restricted=$restricted")
        assertFalse(output.contains(secret))
        assertFalse(output.contains(restricted))
        assertTrue(output.contains("[REDACTED STRIPE KEY]"))
    }

    @Test
    fun redactsJwtCredentialValues() {
        val token = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.signaturepayload123456"
        val output = CommunicationLogSanitizer.sanitize("Authorization payload: $token")
        assertFalse(output.contains(token))
        assertTrue(output.contains("[REDACTED JWT]"))
    }

    @Test
    fun redactsAwsAccessKeyIdentifiers() {
        val access = "AKIAIOSFODNN7EXAMPLE"
        val session = "ASIA1234567890ABCDEF"
        val output = CommunicationLogSanitizer.sanitize("access=$access session=$session")
        assertFalse(output.contains(access))
        assertFalse(output.contains(session))
        assertTrue(output.contains("[REDACTED AWS ACCESS KEY]"))
    }

    @Test
    fun redactsJsonSecretValuesContainingEscapedQuotes() {
        val secret = "escaped-json-secret"
        val input = """{"apiKey":"prefix\"$secret"}"""
        val output = CommunicationLogSanitizer.sanitize(input)
        assertFalse("Secret after escaped quote leaked", output.contains(secret))
        assertTrue(output.contains("[REDACTED]"))
    }

}
