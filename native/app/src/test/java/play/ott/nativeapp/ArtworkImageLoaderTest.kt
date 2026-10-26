package play.ott.nativeapp

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import coil.decode.DataSource
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import play.ott.nativeapp.playback.PlaybackTestApplication
import kotlin.test.assertEquals
import kotlin.test.assertIs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = PlaybackTestApplication::class)
class ArtworkImageLoaderTest {
    @Test fun svgLogoAtPngPathDecodesAndReusesTheSharedMemoryCache() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "image/svg+xml")
                .setHeader("Cache-Control", "public, max-age=86400")
                .setBody("""<svg xmlns="http://www.w3.org/2000/svg" width="192" height="108"><rect width="192" height="108" fill="#17334b"/></svg>"""))
            server.start()
            val loader = createArtworkImageLoader(context).newBuilder().diskCache(null).build()
            try {
                val request = ImageRequest.Builder(context)
                    .data(server.url("/channel-logo/01234567890123456789.png").toString())
                    .size(192, 108).build()
                assertIs<SuccessResult>(loader.execute(request))
                val warm = assertIs<SuccessResult>(loader.execute(request))
                assertEquals(DataSource.MEMORY_CACHE, warm.dataSource)
                assertEquals(1, server.requestCount)
            } finally { loader.shutdown() }
        }
    }
}
