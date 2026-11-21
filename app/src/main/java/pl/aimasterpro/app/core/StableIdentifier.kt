package pl.aimasterpro.app.core

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Builds deterministic, collision-resistant identifiers from complete identity parts.
 * Length-prefixing keeps distinct part boundaries unambiguous; no source ID is truncated.
 */
object StableIdentifier {
    fun create(prefix: String, vararg parts: String): String {
        require(prefix.isNotBlank() && prefix.matches(Regex("[a-zA-Z0-9_-]{1,16}")))
        val digest = MessageDigest.getInstance("SHA-256")
        parts.forEach { part ->
            val bytes = part.toByteArray(StandardCharsets.UTF_8)
            digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
            digest.update(bytes)
        }
        val hex = digest.digest().joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
        return "$prefix-$hex"
    }
}
