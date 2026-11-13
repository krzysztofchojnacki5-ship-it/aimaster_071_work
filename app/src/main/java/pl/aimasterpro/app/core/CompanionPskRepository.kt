package pl.aimasterpro.app.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.SecureRandom

/**
 * Encrypted at-rest storage for the Companion pairing secret (PSK), per
 * docs/PSK_MIGRATION_DESIGN_PL.md sections 3–5. Transitional model: the PSK is generated on the
 * device and kept encrypted with a Keystore-backed key; the pairing protocol still receives the raw
 * bytes in memory. Moving to a non-exportable HMAC key is a later step (design 2b).
 *
 * Scope limits (explicit):
 *  - No legacy migration path: there is no PSK producer or storage in the current production code
 *    (design section 1), so scenarios S1–S3 have nothing to act on and are not implemented.
 *  - One record per blob; the peerId is bound through AAD and checked on load.
 *  - Monotonic version: a provisioned record is version 1; re-provisioning is refused, so there is
 *    no silent replacement. Rotation is a future explicit procedure.
 */
class CompanionPskRepository(
    private val blob: CompanionTlsBlobStore,
    private val cipher: CompanionPskCipher,
    private val random: SecureRandom = SecureRandom()
) {
    /** Encryption boundary. Implementations must authenticate AAD (AES-GCM). */
    interface CompanionPskCipher {
        fun encrypt(plain: ByteArray, aad: ByteArray): Pair<ByteArray, ByteArray>   // (iv, ciphertext)
        fun decrypt(iv: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray
    }

    sealed interface ProvisionResult {
        object Provisioned : ProvisionResult
        data class Rejected(val code: String) : ProvisionResult
    }

    sealed interface LoadResult {
        object Absent : LoadResult
        /** The caller owns [secret] and must zero it after use. */
        class Active(val version: Int, val secret: ByteArray) : LoadResult
        data class Invalid(val code: String) : LoadResult
    }

    fun provision(peerId: String): ProvisionResult {
        validatePeerId(peerId)
        val existing = blob.read()
        if (existing != null) {
            return try {
                decode(existing)
                ProvisionResult.Rejected("ALREADY_PROVISIONED")
            } catch (_: CompanionPskFormatException) {
                ProvisionResult.Rejected("STORE_CORRUPT")
            }
        }
        val secret = ByteArray(PSK_BYTES).also { random.nextBytes(it) }
        try {
            val (iv, ciphertext) = cipher.encrypt(secret, aad(peerId, FIRST_VERSION))
            val record = Record(FIRST_VERSION, peerId, iv, ciphertext)
            return if (blob.write(encode(record))) ProvisionResult.Provisioned
            else ProvisionResult.Rejected("WRITE_FAILED")
        } catch (_: Exception) {
            return ProvisionResult.Rejected("ENCRYPT_FAILED")
        } finally {
            secret.fill(0)
        }
    }

    fun load(peerId: String): LoadResult {
        validatePeerId(peerId)
        val stored = blob.read() ?: return LoadResult.Absent
        val record = try {
            decode(stored)
        } catch (_: CompanionPskFormatException) {
            return LoadResult.Invalid("STORE_CORRUPT")
        }
        if (record.peerId != peerId) return LoadResult.Invalid("PEER_MISMATCH")
        return try {
            LoadResult.Active(record.version, cipher.decrypt(record.iv, record.ciphertext, aad(record.peerId, record.version)))
        } catch (_: Exception) {
            LoadResult.Invalid("DECRYPT_FAILED")
        }
    }

    /** Structural check without decryption: null when absent, true when the record parses, false when corrupt. */
    fun wellFormed(): Boolean? {
        val stored = blob.read() ?: return null
        return try {
            decode(stored)
            true
        } catch (_: CompanionPskFormatException) {
            false
        }
    }

    private data class Record(val version: Int, val peerId: String, val iv: ByteArray, val ciphertext: ByteArray)

    private class CompanionPskFormatException : IllegalArgumentException("PSK_FORMAT")

    private fun encode(record: Record): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { data ->
            data.write(MAGIC.toByteArray(Charsets.US_ASCII))
            data.writeByte(FORMAT)
            data.writeInt(record.version)
            val peer = record.peerId.toByteArray(Charsets.US_ASCII)
            data.writeByte(peer.size)
            data.write(peer)
            data.writeByte(record.iv.size)
            data.write(record.iv)
            data.writeShort(record.ciphertext.size)
            data.write(record.ciphertext)
        }
        return out.toByteArray()
    }

    private fun decode(bytes: ByteArray): Record {
        try {
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                val magic = ByteArray(MAGIC.length)
                input.readFully(magic)
                if (String(magic, Charsets.US_ASCII) != MAGIC) throw CompanionPskFormatException()
                if (input.readByte().toInt() != FORMAT) throw CompanionPskFormatException()
                val version = input.readInt()
                if (version < FIRST_VERSION) throw CompanionPskFormatException()
                val peer = ByteArray(input.readUnsignedByte()).also { input.readFully(it) }
                val peerId = String(peer, Charsets.US_ASCII)
                validatePeerId(peerId)
                val iv = ByteArray(input.readUnsignedByte()).also { input.readFully(it) }
                if (iv.size != GCM_IV_BYTES) throw CompanionPskFormatException()
                val ciphertext = ByteArray(input.readUnsignedShort()).also { input.readFully(it) }
                if (ciphertext.isEmpty() || input.available() != 0) throw CompanionPskFormatException()
                return Record(version, peerId, iv, ciphertext)
            }
        } catch (e: CompanionPskFormatException) {
            throw e
        } catch (_: Exception) {
            throw CompanionPskFormatException()
        }
    }

    private fun aad(peerId: String, version: Int): ByteArray =
        "$AAD_PREFIX|$peerId|$version".toByteArray(Charsets.UTF_8)

    private fun validatePeerId(peerId: String) {
        require(PEER_ID.matches(peerId)) { "Nieprawidłowy identyfikator peer." }
    }

    companion object {
        const val PSK_BYTES = 32
        private const val FIRST_VERSION = 1
        private const val MAGIC = "AMPS"
        private const val FORMAT = 1
        private const val GCM_IV_BYTES = 12
        private const val AAD_PREFIX = "AI_MASTER_COMPANION_PSK_V1"
        private val PEER_ID = Regex("[A-Za-z0-9._-]{1,80}")
    }
}
