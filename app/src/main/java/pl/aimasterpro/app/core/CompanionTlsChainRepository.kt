package pl.aimasterpro.app.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.security.PublicKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * Public TLS leaf chain record and its persistence (docs/COMPANION_TLS_CERTIFICATE_LIFECYCLE_PL.md,
 * section 5). The chain is public, so it is not encrypted. Its integrity is checked in two layers:
 *  - a SHA-256 trailer detects corruption (it does NOT prove authenticity: anyone who can modify the
 *    bytes can recompute it);
 *  - every load re-runs CompanionTlsLeafPolicy against the pinned roots, approved names, the
 *    Keystore public key for the alias, and the current time. That re-validation is the authenticity
 *    check.
 */
data class CompanionTlsChainRecord(
    val version: Int,
    val alias: String,
    /** certificates[0] is the leaf; the rest are intermediates in order. */
    val certificates: List<X509Certificate>
) {
    val leaf: X509Certificate get() = certificates[0]
}

class CompanionTlsChainFormatException(val code: String) : IllegalArgumentException(code)

/** Minimal byte-blob storage. The Android implementation lives in data/ and uses SharedPreferences. */
interface CompanionTlsBlobStore {
    fun read(): ByteArray?
    fun write(bytes: ByteArray): Boolean

    /** Removes the stored record; returns true when it is absent afterwards. Used only by explicit recovery. */
    fun clear(): Boolean
}

object CompanionTlsChainCodec {
    private const val MAGIC = "AMCT"
    private const val FORMAT_VERSION: Byte = 1
    private const val MAX_CERT_BYTES = 8 * 1024
    private const val MAX_CERTS = 3
    private const val MAX_ALIAS_BYTES = 64
    private const val DIGEST_BYTES = 32
    private val ALIAS_PATTERN = Regex("ai_master_companion_tls_v([1-9][0-9]{0,8})")

    fun encode(record: CompanionTlsChainRecord): ByteArray {
        requireConsistent(record)
        val body = ByteArrayOutputStream()
        DataOutputStream(body).use { out ->
            out.write(MAGIC.toByteArray(Charsets.US_ASCII))
            out.writeByte(FORMAT_VERSION.toInt())
            out.writeInt(record.version)
            val alias = record.alias.toByteArray(Charsets.UTF_8)
            out.writeShort(alias.size)
            out.write(alias)
            out.writeByte(record.certificates.size)
            record.certificates.forEach { cert ->
                val der = cert.encoded
                out.writeInt(der.size)
                out.write(der)
            }
        }
        val bytes = body.toByteArray()
        return bytes + MessageDigest.getInstance("SHA-256").digest(bytes)
    }

    fun decode(data: ByteArray): CompanionTlsChainRecord {
        if (data.size <= DIGEST_BYTES + MAGIC.length) throw CompanionTlsChainFormatException("TRUNCATED")
        val body = data.copyOfRange(0, data.size - DIGEST_BYTES)
        val digest = data.copyOfRange(data.size - DIGEST_BYTES, data.size)
        val expected = MessageDigest.getInstance("SHA-256").digest(body)
        if (!MessageDigest.isEqual(expected, digest)) throw CompanionTlsChainFormatException("DIGEST_MISMATCH")

        try {
            DataInputStream(ByteArrayInputStream(body)).use { input ->
                val magic = ByteArray(MAGIC.length)
                input.readFully(magic)
                if (String(magic, Charsets.US_ASCII) != MAGIC) throw CompanionTlsChainFormatException("BAD_MAGIC")
                if (input.readByte() != FORMAT_VERSION) throw CompanionTlsChainFormatException("UNSUPPORTED_FORMAT")
                val version = input.readInt()
                val aliasLength = input.readUnsignedShort()
                if (aliasLength !in 1..MAX_ALIAS_BYTES) throw CompanionTlsChainFormatException("BAD_ALIAS_LENGTH")
                val aliasBytes = ByteArray(aliasLength)
                input.readFully(aliasBytes)
                val alias = String(aliasBytes, Charsets.UTF_8)
                val count = input.readUnsignedByte()
                if (count !in 1..MAX_CERTS) throw CompanionTlsChainFormatException("BAD_CERT_COUNT")
                val factory = CertificateFactory.getInstance("X.509")
                val certs = (0 until count).map {
                    val length = input.readInt()
                    if (length !in 1..MAX_CERT_BYTES) throw CompanionTlsChainFormatException("BAD_CERT_LENGTH")
                    val der = ByteArray(length)
                    input.readFully(der)
                    factory.generateCertificate(ByteArrayInputStream(der)) as X509Certificate
                }
                if (input.available() != 0) throw CompanionTlsChainFormatException("TRAILING_DATA")
                val record = CompanionTlsChainRecord(version, alias, certs)
                requireConsistent(record)
                return record
            }
        } catch (e: CompanionTlsChainFormatException) {
            throw e
        } catch (_: Exception) {
            throw CompanionTlsChainFormatException("MALFORMED")
        }
    }

    private fun requireConsistent(record: CompanionTlsChainRecord) {
        val match = ALIAS_PATTERN.matchEntire(record.alias)
            ?: throw CompanionTlsChainFormatException("BAD_ALIAS")
        if (match.groupValues[1].toIntOrNull() != record.version) {
            throw CompanionTlsChainFormatException("ALIAS_VERSION_MISMATCH")
        }
        if (record.version < 1) throw CompanionTlsChainFormatException("BAD_VERSION")
        if (record.certificates.isEmpty() || record.certificates.size > MAX_CERTS) {
            throw CompanionTlsChainFormatException("BAD_CERT_COUNT")
        }
    }
}

/**
 * Persistence and loading of the current chain. Fail-closed rules:
 *  - a corrupt stored record is never overwritten silently (STORE_CORRUPT requires explicit recovery);
 *  - an application-level write must have a strictly higher version (VERSION_NOT_MONOTONIC).
 *    This does not protect against an attacker who can roll back the whole store; that needs
 *    hardware-anchored counters and is an open item.
 */
class CompanionTlsChainRepository(private val blob: CompanionTlsBlobStore) {

    sealed interface SaveResult {
        object Saved : SaveResult
        data class Rejected(val code: String) : SaveResult
    }

    sealed interface LoadResult {
        object Absent : LoadResult
        data class Valid(val record: CompanionTlsChainRecord) : LoadResult
        data class Invalid(val code: String) : LoadResult
    }

    fun save(record: CompanionTlsChainRecord): SaveResult {
        val encoded = try {
            CompanionTlsChainCodec.encode(record)
        } catch (e: CompanionTlsChainFormatException) {
            return SaveResult.Rejected(e.code)
        }
        val existing = blob.read()
        if (existing != null) {
            val current = try {
                CompanionTlsChainCodec.decode(existing)
            } catch (_: CompanionTlsChainFormatException) {
                return SaveResult.Rejected("STORE_CORRUPT")
            }
            if (record.version <= current.version) return SaveResult.Rejected("VERSION_NOT_MONOTONIC")
        }
        return if (blob.write(encoded)) SaveResult.Saved else SaveResult.Rejected("WRITE_FAILED")
    }

    /**
     * Loads and re-validates the stored chain. [expectedPublicKey] must come from the Keystore key
     * for the record's alias; if it is unavailable the caller passes null and the result is Invalid.
     */
    fun load(
        trustedRoots: Set<X509Certificate>,
        dnsNames: Set<String>,
        expectedPublicKeyFor: (String) -> PublicKey?,
        nowMillis: Long
    ): LoadResult {
        val stored = blob.read() ?: return LoadResult.Absent
        val record = try {
            CompanionTlsChainCodec.decode(stored)
        } catch (_: CompanionTlsChainFormatException) {
            return LoadResult.Invalid("STORE_CORRUPT")
        }
        val key = expectedPublicKeyFor(record.alias) ?: return LoadResult.Invalid("KEY_UNAVAILABLE")
        val verdict = CompanionTlsLeafPolicy.validate(
            leaf = record.leaf,
            intermediates = record.certificates.drop(1),
            trustedRoots = trustedRoots,
            expectedPublicKey = key,
            expectedDnsNames = dnsNames,
            nowMillis = nowMillis
        )
        return if (verdict.accepted) LoadResult.Valid(record) else LoadResult.Invalid(verdict.code)
    }

}
