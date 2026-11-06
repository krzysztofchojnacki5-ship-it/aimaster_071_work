package pl.aimasterpro.app.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream

class BoundedResponseReaderTest {
    @Test fun acceptsPayloadAtExactLimit() {
        val bytes = "12345678".toByteArray(Charsets.UTF_8)
        assertEquals("12345678", BoundedResponseReader.readUtf8(ByteArrayInputStream(bytes), 8))
    }

    @Test fun rejectsPayloadOneByteOverLimit() {
        val bytes = "123456789".toByteArray(Charsets.UTF_8)
        assertThrows(BoundedResponseReader.ResponseTooLargeException::class.java) {
            BoundedResponseReader.readUtf8(ByteArrayInputStream(bytes), 8)
        }
    }

    @Test fun rejectsMalformedUtf8() {
        assertThrows(BoundedResponseReader.InvalidUtf8Exception::class.java) {
            BoundedResponseReader.readUtf8(ByteArrayInputStream(byteArrayOf(0xC3.toByte(), 0x28)), 8)
        }
    }

    @Test fun acceptsEmptyPayload() {
        assertEquals("", BoundedResponseReader.readUtf8(ByteArrayInputStream(byteArrayOf()), 0))
    }
}
