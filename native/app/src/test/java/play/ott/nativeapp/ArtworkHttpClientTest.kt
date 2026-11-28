package play.ott.nativeapp

import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import play.ott.nativeapp.playback.PlaybackTestApplication

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = PlaybackTestApplication::class)
class ArtworkHttpClientTest {
    @Test fun artworkHttpClientUsesHigherPerHostConcurrencyAndBrowserHeaders() {
        val client = OttplayApplication.buildArtworkHttpClient()
        try {
            assertTrue(client.dispatcher.maxRequestsPerHost >= 12)
            assertTrue(client.followRedirects)
            assertTrue(client.followSslRedirects)
            assertTrue(client.retryOnConnectionFailure)

            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
                server.start()
                val response = client.newCall(Request.Builder().url(server.url("/poster.jpg")).build()).execute()
                response.use { assertEquals(200, it.code) }
                val recorded = server.takeRequest()
                assertTrue(recorded.getHeader("User-Agent").orEmpty().contains("Mozilla/5.0"))
                assertTrue(recorded.getHeader("Accept").orEmpty().contains("image/"))
            }
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }
}
