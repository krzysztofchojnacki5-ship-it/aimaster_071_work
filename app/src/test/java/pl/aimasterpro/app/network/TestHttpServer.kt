package pl.aimasterpro.app.network

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
                val lineBuf = StringBuilder()
                var c: Int
                while (input.read().also { c = it } != -1) {
                    if (c == '\n'.code) {
                        val line = lineBuf.toString().trimEnd('\r')
                        lineBuf.setLength(0)
                        if (line.isEmpty()) break
                        headers.add(line)
                    } else {
                        lineBuf.append(c.toChar())
                    }
                }
                if (headers.isEmpty()) return

                var contentLength = 0
                for (h in headers) {
                    if (h.startsWith("Content-Length:", ignoreCase = true)) {
                        contentLength = h.substringAfter(":").trim().toIntOrNull() ?: 0
                    }
                }

                val bodyBytes = ByteArray(contentLength)
                var readTotal = 0
                while (readTotal < contentLength) {
                    val r = input.read(bodyBytes, readTotal, contentLength - readTotal)
                    if (r == -1) break
                    readTotal += r
                }
                val body = String(bodyBytes, 0, readTotal, Charsets.UTF_8)

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
        } catch (_: Exception) {
        }
    }

    fun stop(delayMs: Int = 0) {
        isRunning = false
        try { serverSocket.close() } catch (_: Exception) {}
        executor.shutdownNow()
    }
}
