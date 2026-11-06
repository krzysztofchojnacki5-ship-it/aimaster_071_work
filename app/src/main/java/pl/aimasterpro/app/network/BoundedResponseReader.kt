package pl.aimasterpro.app.network

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** Reads provider responses without allowing an untrusted endpoint to exhaust app memory. */
internal object BoundedResponseReader {
    const val DEFAULT_MAX_BYTES: Int = 4 * 1024 * 1024
    private const val BUFFER_SIZE = 8 * 1024

    class ResponseTooLargeException(val limitBytes: Int) : Exception(
        "Odpowiedź dostawcy przekracza limit ${limitBytes} bajtów."
    )

    class InvalidUtf8Exception(cause: Throwable) : Exception("Odpowiedź dostawcy nie jest poprawnym UTF-8.", cause)

    fun readUtf8(input: InputStream, maxBytes: Int = DEFAULT_MAX_BYTES): String {
        require(maxBytes in 0 until Int.MAX_VALUE) { "maxBytes must be between 0 and Int.MAX_VALUE - 1" }
        val output = ByteArrayOutputStream(minOf(maxBytes, BUFFER_SIZE))
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0
        while (true) {
            val remainingPlusOne = maxBytes - total + 1
            val read = input.read(buffer, 0, minOf(buffer.size, remainingPlusOne))
            if (read < 0) break
            if (read == 0) {
                val single = input.read()
                if (single < 0) break
                if (total == maxBytes) throw ResponseTooLargeException(maxBytes)
                output.write(single)
                total++
                continue
            }
            if (read > maxBytes - total) throw ResponseTooLargeException(maxBytes)
            output.write(buffer, 0, read)
            total += read
        }
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(java.nio.ByteBuffer.wrap(output.toByteArray())).toString()
        } catch (e: Exception) {
            throw InvalidUtf8Exception(e)
        }
    }
}
