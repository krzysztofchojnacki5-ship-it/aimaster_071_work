package pl.aimasterpro.app.core

/**
 * Cleartext is deliberately restricted to exact local endpoints.
 * Keep this allowlist synchronized with res/xml/network_security_config.xml.
 */
object CleartextEndpointPolicy {
    private val allowedHosts = setOf("localhost", "127.0.0.1", "10.0.2.2")

    fun isAllowedHost(host: String): Boolean = allowedHosts.any { it.equals(host, ignoreCase = true) }

    /** RFC 1918 IPv4 only; no DNS resolution, public IPs, link-local, or IPv6 scope guessing. */
    fun isPrivateLanIpv4(host: String): Boolean {
        val parts = host.split(".")
        if (parts.size != 4) return false
        val octets = parts.map { part ->
            if (part.isEmpty() || (part.length > 1 && part.startsWith("0")) || !part.all(Char::isDigit)) return false
            part.toIntOrNull()?.takeIf { it in 0..255 } ?: return false
        }
        return octets[0] == 10 ||
            (octets[0] == 172 && octets[1] in 16..31) ||
            (octets[0] == 192 && octets[1] == 168)
    }
}
