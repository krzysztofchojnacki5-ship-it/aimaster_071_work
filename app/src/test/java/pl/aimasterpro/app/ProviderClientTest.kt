package pl.aimasterpro.app

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import pl.aimasterpro.app.core.ProviderConfig
import pl.aimasterpro.app.core.ProviderKind
import pl.aimasterpro.app.network.ProviderClient
import pl.aimasterpro.app.network.ProviderException

@RunWith(RobolectricTestRunner::class)
class ProviderClientTest {
    private val client = ProviderClient()

    @Test(expected = ProviderException::class)
    fun testMissingTokenUnsecuredHttpExternal() {
        val config = ProviderConfig(
            id = "test-external",
            name = "Test External",
            kind = ProviderKind.OPENAI_COMPATIBLE,
            baseUrl = "http://example.com/v1",
            model = "gpt-test",
            apiKey = ""
        )
        client.chat(config, "System prompt", "User prompt")
    }

    @Test(expected = ProviderException::class)
    fun testUnsecuredHttpWithKey() {
        val config = ProviderConfig(
            id = "test-external",
            name = "Test External",
            kind = ProviderKind.OPENAI_COMPATIBLE,
            baseUrl = "http://example.com/v1",
            model = "gpt-test",
            apiKey = "sk-somekey"
        )
        client.chat(config, "System prompt", "User prompt")
    }
}
