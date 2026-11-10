package pl.aimasterpro.app.network

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class TestHttpServer(
    private val handler: (body: String, attempt: Int) -> Pair<Int, String>
) {
    private val serverSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = serverSocket.localPort
    val address: InetSocketAddress get() = InetSocketAddress(serverSocket.inetAddress, serverSocket.localPort)
    val requestCount = AtomicInteger(0)
    private val executor = Executors.newCachedThreadPool()
    @Volatile private var isRunning = true
    @Volatile var lastRequestBody: String? = null
        private set
    @Volatile var lastFailure: String? = null
        private set

    init {
        executor.execute {
            while (isRunning) {
                try {
                    val socket = serverSocket.accept()
                    val attempt = requestCount.incrementAndGet()
                    executor.execute { handleSocket(socket, attempt) }
                } catch (_: Exception) {
                    break
                }
            }
        }
    }

    private fun handleSocket(socket: Socket, attempt: Int) {
        try {
            socket.use { s ->
                val input = s.getInputStream()
                val headers = mutableListOf<String>()
                while (true) {
                    val line = input.readAsciiLine() ?: break
                    if (line.isEmpty()) break
                    headers.add(line)
                }
                if (headers.isEmpty()) return

                val contentLength = headers.firstNotNullOfOrNull { header ->
                    if (header.startsWith("Content-Length:", ignoreCase = true)) {
                        header.substringAfter(":").trim().toIntOrNull()
                    } else null
                } ?: 0
                val isChunked = headers.any {
                    it.startsWith("Transfer-Encoding:", ignoreCase = true) &&
                        it.substringAfter(":").contains("chunked", ignoreCase = true)
                }

                val bodyBytes = when {
                    isChunked -> input.readChunkedBody()
                    contentLength > 0 -> ByteArray(contentLength).also { bytes ->
                        var offset = 0
                        while (offset < bytes.size) {
                            val count = input.read(bytes, offset, bytes.size - offset)
                            if (count < 0) break
                            offset += count
                        }
                    }.let { bytes ->
                        // A short fixed-length body is still returned as received.
                        bytes
                    }
                    else -> ByteArray(0)
                }
                val body = String(bodyBytes, Charsets.UTF_8)
                lastRequestBody = body

                val (statusCode, responseText) = handler(body, attempt)
                val respBytes = responseText.toByteArray(Charsets.UTF_8)
                val statusMsg = when (statusCode) {
                    200 -> "OK"
                    400 -> "Bad Request"
                    401 -> "Unauthorized"
                    403 -> "Forbidden"
                    404 -> "Not Found"
                    408 -> "Request Timeout"
                    429 -> "Too Many Requests"
                    500 -> "Internal Server Error"
                    503 -> "Service Unavailable"
                    else -> "Custom"
                }
                val output = s.getOutputStream()
                val headerText = "HTTP/1.1 $statusCode $statusMsg\r\n" +
                        "Content-Length: ${respBytes.size}\r\n" +
                        "Content-Type: application/json; charset=utf-8\r\n" +
                        "Connection: close\r\n" +
                        "\r\n"
                output.write(headerText.toByteArray(Charsets.UTF_8))
                output.write(respBytes)
                output.flush()
            }
        } catch (error: Exception) {
            lastFailure = "${error::class.java.simpleName}: ${error.message.orEmpty()}"
        }
    }

    fun stop(delayMs: Int = 0) {
        isRunning = false
        try { serverSocket.close() } catch (_: Exception) {}
        executor.shutdownNow()
    }
}

private fun InputStream.readAsciiLine(): String? {
    val bytes = ByteArrayOutputStream()
    while (true) {
        val value = read()
        if (value < 0) return if (bytes.size() == 0) null else bytes.toString(Charsets.US_ASCII.name()).trimEnd('\r')
        if (value == '\n'.code) return bytes.toString(Charsets.US_ASCII.name()).trimEnd('\r')
        bytes.write(value)
    }
}

private fun InputStream.readChunkedBody(): ByteArray {
    val body = ByteArrayOutputStream()
    while (true) {
        val sizeLine = readAsciiLine() ?: throw java.io.IOException("Unexpected end of chunked request")
        val sizeToken = sizeLine.substringBefore(';').trim()
        val size = sizeToken.toLongOrNull(16)
            ?: throw java.io.IOException("Invalid chunk size")
        if (size == 0L) {
            // Consume optional trailer headers and the final empty line.
            while (true) {
                val trailer = readAsciiLine() ?: break
                if (trailer.isEmpty()) break
            }
            return body.toByteArray()
        }
        require(size <= Int.MAX_VALUE) { "Chunk is too large" }
        var remaining = size.toInt()
        val buffer = ByteArray(minOf(8192, remaining))
        while (remaining > 0) {
            val count = read(buffer, 0, minOf(buffer.size, remaining))
            if (count < 0) throw java.io.IOException("Truncated chunk")
            body.write(buffer, 0, count)
            remaining -= count
        }
        val cr = read()
        val lf = read()
        if (cr != '\r'.code || lf != '\n'.code) throw java.io.IOException("Invalid chunk terminator")
    }
}
