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
        assertTrue(CleartextEndpointPolicy.isAllowedHost("::1"))
        assertFalse(CleartextEndpointPolicy.isAllowedHost("127.0.0.2"))
        assertTrue(CleartextEndpointPolicy.isAllowedHost("10.0.2.2"))
        assertFalse(CleartextEndpointPolicy.isAllowedHost("10.0.2.3"))
        assertFalse(CleartextEndpointPolicy.isAllowedHost("192.168.1.50"))
        assertFalse(CleartextEndpointPolicy.isAllowedHost("172.20.0.5"))
        assertFalse(CleartextEndpointPolicy.isAllowedHost("example.local"))
        assertFalse(CleartextEndpointPolicy.isAllowedHost("127.0.0.999"))
        assertFalse(CleartextEndpointPolicy.isAllowedHost("127.attacker.example"))
    }

    @Test fun privateIpv6RecognitionIsLiteralAndRangeBounded() {
        assertTrue(CleartextEndpointPolicy.isPrivateLanIpv6("fc00::1"))
        assertTrue(CleartextEndpointPolicy.isPrivateLanIpv6("[fd12:3456::1]"))
        assertTrue(CleartextEndpointPolicy.isPrivateLanIpv6("fe80::1"))
        assertTrue(CleartextEndpointPolicy.isPrivateLanIpv6("::1"))
        assertFalse(CleartextEndpointPolicy.isPrivateLanIpv6("2001:4860:4860::8888"))
        assertFalse(CleartextEndpointPolicy.isPrivateLanIpv6("fc-attacker.example"))
        assertFalse(CleartextEndpointPolicy.isPrivateLanIpv6("fe80::1%eth0"))
        assertFalse(CleartextEndpointPolicy.isPrivateLanIpv6("192.168.1.10"))
    }

    @Test fun doesNotTreatIpv6OrLookalikeNamesAsAllowed() {
        assertFalse(CleartextEndpointPolicy.isAllowedHost("::1"))
        assertFalse(CleartextEndpointPolicy.isAllowedHost("localhost.attacker.example"))
        assertFalse(CleartextEndpointPolicy.isAllowedHost("notlocalhost"))
        assertFalse(CleartextEndpointPolicy.isAllowedHost("10.0.2.2.attacker.example"))
    }
}
