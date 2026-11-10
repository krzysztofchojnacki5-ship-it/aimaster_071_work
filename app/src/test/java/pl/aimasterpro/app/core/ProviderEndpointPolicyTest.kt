package pl.aimasterpro.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderEndpointPolicyTest {
    @Test fun acceptsHttpsAndExactLocalCleartextWithoutCredentials() {
        assertTrue(ProviderEndpointPolicy.validate("https://api.example.test/v1", "key").allowed)
        assertTrue(ProviderEndpointPolicy.validate("http://localhost:11434/v1", "").allowed)
        assertTrue(ProviderEndpointPolicy.validate("http://LOCALHOST:11434/v1", "").allowed)
        assertTrue(ProviderEndpointPolicy.validate("http://127.0.0.1:11434/v1", "").allowed)
        assertTrue(ProviderEndpointPolicy.validate("http://10.0.2.2:11434/v1", "").allowed)
    }

    @Test fun rejectsUnsupportedSchemesMalformedAuthorityAndInvalidPorts() {
        assertEquals("UNSUPPORTED_ENDPOINT_SCHEME", ProviderEndpointPolicy.validate("ftp://example.test/model", "").category)
        assertEquals("UNSUPPORTED_ENDPOINT_SCHEME", ProviderEndpointPolicy.validate("file:///etc/passwd", "").category)
        assertEquals("INVALID_ENDPOINT", ProviderEndpointPolicy.validate("not a url", "").category)
        assertEquals("INVALID_ENDPOINT", ProviderEndpointPolicy.validate("https:///missing-host", "").category)
        assertEquals("INVALID_ENDPOINT", ProviderEndpointPolicy.validate("https://example.test:99999/v1", "").category)
        assertEquals("INVALID_ENDPOINT", ProviderEndpointPolicy.validate("https://example.test:0/v1", "").category)
    }

    @Test fun privateLanOllamaRequiresExplicitOptInAndNeverAllowsCredentials() {
        val endpoint = "http://192.168.1.50:11434/v1"
        assertEquals("SECURITY_HTTP", ProviderEndpointPolicy.validate(endpoint, "").category)
        assertTrue(ProviderEndpointPolicy.validate(endpoint, "", allowInsecureLanOllama = true).allowed)
        assertTrue(ProviderEndpointPolicy.validate("http://192.168.1.50:11434/v1/models", "", true).allowed)
        assertTrue(ProviderEndpointPolicy.validate("http://192.168.1.50:11434/v1/chat/completions", "", true).allowed)
        assertEquals("SECURITY_HTTP", ProviderEndpointPolicy.validate("http://192.168.1.50:11434/v1/admin", "", true).category)
        assertEquals("SECURITY_HTTP", ProviderEndpointPolicy.validate(endpoint, "secret", allowInsecureLanOllama = true).category)
        assertEquals("SECURITY_HTTP", ProviderEndpointPolicy.validate("http://192.168.1.50:11435/v1", "", true).category)
        assertEquals("SECURITY_HTTP", ProviderEndpointPolicy.validate("http://8.8.8.8:11434/v1", "", true).category)
        assertEquals("SECURITY_HTTP", ProviderEndpointPolicy.validate("http://172.15.0.2:11434/v1", "", true).category)
        assertTrue(ProviderEndpointPolicy.validate("http://172.16.0.2:11434/v1", "", true).allowed)
        assertTrue(ProviderEndpointPolicy.validate("http://10.1.2.3:11434/v1", "", true).allowed)
    }

    @Test fun rejectsUrlCredentialsAndAllCredentialBearingCleartextEndpoints() {
        assertEquals("URL_CREDENTIALS_NOT_ALLOWED", ProviderEndpointPolicy.validate("https://user:pass@example.test/v1", "").category)
        assertEquals("URL_CREDENTIALS_NOT_ALLOWED", ProviderEndpointPolicy.validate("http://user:pass@localhost/v1", "").category)
        assertEquals("SECURITY_HTTP", ProviderEndpointPolicy.validate("http://192.168.1.20:11434/v1", "").category)
        assertEquals("SECURITY_HTTP", ProviderEndpointPolicy.validate("http://127.0.0.1:11434/v1", "secret").category)
        assertEquals("SECURITY_HTTP", ProviderEndpointPolicy.validate("http://127.0.0.1:11434/v1?access_token=secret", "").category)
        assertEquals("SECURITY_HTTP", ProviderEndpointPolicy.validate("http://127.0.0.1:11434/v1?%61pi_key=secret", "").category)
        assertEquals("SECURITY_HTTP", ProviderEndpointPolicy.validate("http://127.0.0.1:11434/v1?secret=secret", "").category)
        assertFalse(ProviderEndpointPolicy.validate("http://localhost.attacker.test/v1", "").allowed)
    }
}
