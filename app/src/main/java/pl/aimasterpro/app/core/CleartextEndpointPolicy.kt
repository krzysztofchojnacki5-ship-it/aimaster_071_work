package pl.aimasterpro.app.core

/**
 * App-level cleartext allowlist. Android permits the socket layer because a private-LAN
 * Ollama host is user-configured dynamically; every HTTP request must still pass
 * ProviderEndpointPolicy before a connection is opened.
 */
object CleartextEndpointPolicy {
    private val allowedHosts = setOf("localhost", "127.0.0.1", "10.0.2.2")

    fun isAllowedHost(host: String): Boolean = allowedHosts.any { it.equals(host, ignoreCase = true) }

    /** RFC 1918 IPv4 only; no DNS resolution, public IPs, link-local, or IPv6 scope guessing. */
    fun isPrivateLanIpv4(host: String): Boolean {
        val parts = host.split(".")
        if (parts.size != 4) return false
        val octets = IntArray(4)
        for (index in parts.indices) {
            val part = parts[index]
            if (part.isEmpty() || (part.length > 1 && part.startsWith("0")) || part.any { it !in '0'..'9' }) return false
            val value = part.toIntOrNull() ?: return false
            if (value !in 0..255) return false
            octets[index] = value
        }
        return octets[0] == 10 ||
            (octets[0] == 172 && octets[1] in 16..31) ||
            (octets[0] == 192 && octets[1] == 168)
    }
}
