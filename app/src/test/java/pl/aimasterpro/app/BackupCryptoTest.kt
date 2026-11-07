package pl.aimasterpro.app

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import pl.aimasterpro.app.data.BackupCrypto

@RunWith(RobolectricTestRunner::class)
class BackupCryptoTest {
    @Test
    fun testEncryptDecrypt() {
        val plaintext = "{\"app\":\"AI_MASTER_PRO_ANDROID\",\"providers\":[]}"
        val password = "securepassword123".toCharArray()
        val encrypted = BackupCrypto.encrypt(plaintext, password)
        assertNotNull(encrypted)
        assertFalse(encrypted.contains(plaintext))

        val decrypted = BackupCrypto.decrypt(encrypted, password)
        assertEquals(plaintext, decrypted)
    }

    @Test(expected = Exception::class)
    fun testWrongPassword() {
        val plaintext = "secret data"
        val encrypted = BackupCrypto.encrypt(plaintext, "password123".toCharArray())
        BackupCrypto.decrypt(encrypted, "wrongpassword".toCharArray())
    }

    @Test(expected = Exception::class)
    fun testCorruptedJson() {
        BackupCrypto.decrypt("{invalid_json_envelope}", "password123".toCharArray())
    }

    @Test(expected = Exception::class)
    fun testInvalidEnvelopeFormat() {
        BackupCrypto.decrypt("{\"format\":\"UNKNOWN\",\"version\":1}", "password123".toCharArray())
    }

    @Test(expected = Exception::class)
    fun testMissingCryptoFields() {
        BackupCrypto.decrypt("{\"format\":\"AIMASTER_BACKUP\",\"version\":1}", "password123".toCharArray())
    }

    @Test(expected = Exception::class)
    fun testTamperedCiphertext() {
        val plaintext = "important config data"
        val password = "securepassword123".toCharArray()
        val encrypted = BackupCrypto.encrypt(plaintext, password)
        val tampered = encrypted.replace(Regex("\"data\":\\s*\"[^\"]+\""), "\"data\":\"dGFtcGVyZWRkYXRh\"")
        BackupCrypto.decrypt(tampered, password)
    }

    @Test
    fun testLegacyV1BackupWithoutIterationFieldUsesFixedWorkFactor() {
        val encrypted = BackupCrypto.encrypt("legacy-compatible", "securepassword123".toCharArray())
        val legacy = encrypted.replace(Regex(""",\s*"iterations":\s*210000"""), "")
        assertNotEquals(encrypted, legacy)
        assertEquals("legacy-compatible", BackupCrypto.decrypt(legacy, "securepassword123".toCharArray()))
    }

    @Test
    fun testUnsupportedKdfIterationCountIsRejectedBeforeKeyDerivation() {
        val encrypted = BackupCrypto.encrypt("secret", "securepassword123".toCharArray())
        val weakened = encrypted.replace("\"iterations\": 210000", "\"iterations\": 1")
        val excessive = encrypted.replace("\"iterations\": 210000", "\"iterations\": 2147483647")
        assertNotEquals(encrypted, weakened)
        assertNotEquals(encrypted, excessive)
        try {
            BackupCrypto.decrypt(weakened, "securepassword123".toCharArray())
            fail("A downgraded KDF work factor must be rejected.")
        } catch (_: IllegalArgumentException) { }
        try {
            BackupCrypto.decrypt(excessive, "securepassword123".toCharArray())
            fail("An excessive KDF work factor must be rejected before expensive derivation.")
        } catch (_: IllegalArgumentException) { }
    }


    @Test
    fun testMalformedIvAfterSaltDecodeIsRejected() {
        val encrypted = BackupCrypto.encrypt("secret", "securepassword123".toCharArray())
        val malformed = encrypted.replace(
            Regex("\\"iv\\":\\s*\\"[^\\"]+\\""),
            "\\"iv\\":\\"!\\""
        )
        assertNotEquals(encrypted, malformed)
        try {
            BackupCrypto.decrypt(malformed, "securepassword123".toCharArray())
            fail("Malformed IV Base64 must be rejected.")
        } catch (_: IllegalArgumentException) { }
    }

    @Test
    fun testMalformedDataAfterSaltAndIvDecodeIsRejected() {
        val encrypted = BackupCrypto.encrypt("secret", "securepassword123".toCharArray())
        val malformed = encrypted.replace(
            Regex("\\"data\\":\\s*\\"[^\\"]+\\""),
            "\\"data\\":\\"!\\""
        )
        assertNotEquals(encrypted, malformed)
        try {
            BackupCrypto.decrypt(malformed, "securepassword123".toCharArray())
            fail("Malformed ciphertext Base64 must be rejected.")
        } catch (_: IllegalArgumentException) { }
    }

    @Test
    fun testLargePayload() {
        val largeText = "x".repeat(50_000)
        val plaintext = "{\"app\":\"AI_MASTER_PRO_ANDROID\",\"data\":\"$largeText\"}"
        val password = "securepassword123".toCharArray()
        val encrypted = BackupCrypto.encrypt(plaintext, password)
        val decrypted = BackupCrypto.decrypt(encrypted, password)
        assertEquals(plaintext, decrypted)
    }
    @Test
    fun testPayloadOverLimitIsRejectedBeforeEncryption() {
        val oversized = "x".repeat(4 * 1024 * 1024 + 1)
        try {
            BackupCrypto.encrypt(oversized, "securepassword123".toCharArray())
            fail("Payload above 4 MiB must be rejected.")
        } catch (_: IllegalArgumentException) { }
    }

    @Test
    fun testEnvelopeOverLimitIsRejectedBeforeParsing() {
        val oversized = " ".repeat(6 * 1024 * 1024 + 1)
        try {
            BackupCrypto.decrypt(oversized, "securepassword123".toCharArray())
            fail("Envelope above 6 MiB must be rejected.")
        } catch (_: IllegalArgumentException) { }
    }

    @Test
    fun testTooShortPasswordIsRejectedOnDecrypt() {
        val encrypted = BackupCrypto.encrypt("secret", "securepassword123".toCharArray())
        try {
            BackupCrypto.decrypt(encrypted, "short".toCharArray())
            fail("Password shorter than eight characters must be rejected.")
        } catch (_: IllegalArgumentException) { }
    }

}
