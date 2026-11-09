package pl.aimasterpro.app.core

import java.io.IOException
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test
import pl.aimasterpro.app.network.TestHttpServer

class OllamaModelManagerTest {
    @Test fun nativeBaseUrlStripsOnlyOpenAiV1Suffix() {
        assertEquals("http://10.0.2.2:11434", OllamaModelManager.nativeBaseUrl("http://10.0.2.2:11434/v1"))
        assertEquals("http://127.0.0.1:11434", OllamaModelManager.nativeBaseUrl("http://127.0.0.1:11434/"))
    }

    @Test fun rejectsUnexpectedBasePathAndCredentials() {
        assertThrows(IllegalArgumentException::class.java) {
            OllamaModelManager.nativeBaseUrl("http://127.0.0.1:11434/custom/v1")
        }
        assertThrows(IllegalArgumentException::class.java) {
            OllamaModelManager.nativeBaseUrl("http://user:secret@127.0.0.1:11434/v1")
        }
    }

    @Test fun identifiesOllamaByProfileOrNativePort() {
        val byId = ProviderConfig("ollama", "Local", ProviderKind.OPENAI_COMPATIBLE, "http://10.0.2.2:11434/v1", "", "")
        val byPort = ProviderConfig("custom", "Local endpoint", ProviderKind.OPENAI_COMPATIBLE, "http://127.0.0.1:11434/v1", "", "")
        val cloud = ProviderConfig("groq", "Groq", ProviderKind.OPENAI_COMPATIBLE, "https://api.groq.com/openai/v1", "", "")
        assertTrue(OllamaModelManager.isOllamaProvider(byId))
        assertTrue(OllamaModelManager.isOllamaProvider(byPort))
        assertFalse(OllamaModelManager.isOllamaProvider(cloud))
    }

    @Test fun pullStreamsProgressAndRequiresSuccessRecord() {
        val response = listOf(
            """{"status":"pulling manifest"}""",
            """{"status":"downloading","digest":"sha256:abc","total":100,"completed":40}""",
            """{"status":"downloading","digest":"sha256:abc","total":100,"completed":100}""",
            """{"status":"success"}"""
        ).joinToString("\n", postfix = "\n")
        val server = TestHttpServer { body, _ ->
            assertTrue(body.contains("\"name\":\"qwen2.5-coder:7b\""))
            assertTrue(body.contains("\"stream\":true"))
            200 to response
        }
        try {
            val progress = mutableListOf<OllamaModelManager.Progress>()
            val result = OllamaModelManager.pull(
                "http://127.0.0.1:${server.port}/v1",
                "qwen2.5-coder:7b",
                onProgress = progress::add
            )
            assertEquals("qwen2.5-coder:7b", result.model)
            assertEquals("success", result.finalStatus)
            assertEquals(4, result.progressEvents)
            assertEquals(100, progress.last().percent)
            assertEquals(100L, result.totalBytes)
            assertEquals(100L, result.completedBytes)
        } finally {
            server.stop()
        }
    }

    @Test fun progressAggregatesMultipleModelLayersInsteadOfShowingOnlyLargestLayer() {
        val response = listOf(
            """{"status":"downloading","digest":"sha256:layer-a","total":100,"completed":100}""",
            """{"status":"downloading","digest":"sha256:layer-b","total":100,"completed":50}""",
            """{"status":"success"}"""
        ).joinToString("\\n", postfix = "\\n")
        val server = TestHttpServer { _, _ -> 200 to response }
        try {
            val progress = mutableListOf<OllamaModelManager.Progress>()
            val result = OllamaModelManager.pull(
                "http://127.0.0.1:${server.port}/v1",
                "llama3:8b",
                onProgress = progress::add
            )
            assertEquals(200L, result.totalBytes)
            assertEquals(150L, result.completedBytes)
            assertEquals(75, progress[1].percent)
        } finally {
            server.stop()
        }
    }

    @Test fun serverErrorIsReportedWithoutTreatingItAsSuccess() {
        val server = TestHttpServer { _, _ -> 200 to """{"error":"model not found"}""" }
        try {
            val error = assertThrows(IOException::class.java) {
                OllamaModelManager.pull("http://127.0.0.1:${server.port}/v1", "missing:model")
            }
            assertTrue(error.message.orEmpty().contains("OLLAMA_PULL_ERROR"))
        } finally {
            server.stop()
        }
    }

    @Test fun cancellationBeforeStartDoesNotSendRequest() {
        val server = TestHttpServer { _, _ -> 200 to """{"status":"success"}""" }
        val cancellation = OllamaModelManager.Cancellation()
        cancellation.cancel()
        try {
            assertThrows(CancellationException::class.java) {
                OllamaModelManager.pull("http://127.0.0.1:${server.port}/v1", "llama3:8b", cancellation)
            }
            assertEquals(0, server.requestCount.get())
        } finally {
            server.stop()
        }
    }

    @Test fun invalidModelNameIsRejectedBeforeNetwork() {
        assertThrows(IllegalArgumentException::class.java) {
            OllamaModelManager.pull("http://127.0.0.1:11434/v1", "llama 3:8b")
        }
    }
}
