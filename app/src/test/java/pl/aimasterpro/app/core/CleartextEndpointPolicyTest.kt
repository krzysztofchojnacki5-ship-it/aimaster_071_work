package pl.aimasterpro.app.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CleartextEndpointPolicyTest {
    @Test fun allowsOnlyLoopbackAndEmulatorAlias() {
        assertTrue(CleartextEndpointPolicy.isAllowedHost("localhost"))
        assertTrue(CleartextEndpointPolicy.isAllowedHost("LOCALHOST"))
        assertFalse(CleartextEndpointPolicy.isAllowedHost("LOCALHOST."))
        assertTrue(CleartextEndpointPolicy.isAllowedHost("127.0.0.1"))
        assertFalse(CleartextEndpointPolicy.isAllowedHost("127.0.0.2"))
        assertTrue(CleartextEndpointPolicy.isAllowedHost("10.0.2.2"))
        assertFalse(CleartextEndpointPolicy.isAllowedHost("10.0.2.3"))
        assertFalse(CleartextEndpointPolicy.isAllowedHost("192.168.1.50"))
        assertFalse(CleartextEndpointPolicy.isAllowedHost("172.20.0.5"))
        assertFalse(CleartextEndpointPolicy.isAllowedHost("example.local"))
        assertFalse(CleartextEndpointPolicy.isAllowedHost("127.0.0.999"))
        assertFalse(CleartextEndpointPolicy.isAllowedHost("127.attacker.example"))
    }

    @Test fun doesNotTreatIpv6OrLookalikeNamesAsAllowed() {
        assertFalse(CleartextEndpointPolicy.isAllowedHost("::1"))
        assertFalse(CleartextEndpointPolicy.isAllowedHost("localhost.attacker.example"))
        assertFalse(CleartextEndpointPolicy.isAllowedHost("notlocalhost"))
        assertFalse(CleartextEndpointPolicy.isAllowedHost("10.0.2.2.attacker.example"))
    }
}
