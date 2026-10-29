package play.ott.nativeapp

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import coil.ImageLoader
import coil.decode.DataSource
import coil.disk.DiskCache
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString.Companion.decodeBase64
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import play.ott.nativeapp.playback.PlaybackTestApplication
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = PlaybackTestApplication::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ArtworkCacheOriginTest {
    @Before fun prepareMain() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun restoreMain() { Dispatchers.resetMain() }

    private val png = "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAIAAAD91JpzAAAAFklEQVR4nGNkYPjPwMDAxMDAwMDAAAALHwEDmIWXfgAAAABJRU5ErkJggg==".decodeBase64()!!

    private fun request(context: Context, session: ArtworkOrigins, url: String): ImageRequest {
        val tag = ArtworkTag("SERIES", "grade")
        return ImageRequest.Builder(context).data(url).size(32, 32).tag(tag)
            .listener(onStart = { tag.ticket = session.start(tag.kind, tag.surface, url) },
                onSuccess = { _, result -> session.image(tag.ticket!!, result.dataSource.name) }).build()
    }

    @Test(timeout = 30_000) fun reportsNetworkThenMemoryThenDiskWithoutExtraDownloads() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val cache = DiskCache.Builder().directory(context.cacheDir.resolve("origin-${UUID.randomUUID()}" )).build()
        val session = ArtworkOrigins()
        val loader = ImageLoader.Builder(context).diskCache(cache)
            .okHttpClient(OkHttpClient.Builder().eventListenerFactory(session.httpFactory()).build()).build()
        try { MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "image/png")
                .setHeader("Cache-Control", "public, max-age=86400").setBody(okio.Buffer().write(png)))
            server.start()
            val req = request(context, session, server.url("/private-title?token=secret").toString())
            assertEquals(DataSource.NETWORK, assertIs<SuccessResult>(loader.execute(req)).dataSource)
            assertEquals(DataSource.MEMORY_CACHE, assertIs<SuccessResult>(loader.execute(req)).dataSource)
            loader.memoryCache!!.clear()
            assertEquals(DataSource.DISK, assertIs<SuccessResult>(loader.execute(req)).dataSource)
            assertEquals(1, server.requestCount)
            val report = session.report()
            for (origin in listOf("MEMORY_CACHE", "DISK", "NETWORK")) assertTrue(report.contains(origin))
            assertFalse(report.contains("private-title")); assertFalse(report.contains("token=secret"))
        }} finally { loader.shutdown(); cache.shutdown() }
    }

    @Test(timeout = 30_000) fun network304ReusesCachedImageBytesAndKeepsItsActualStatus() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val cache = DiskCache.Builder().directory(context.cacheDir.resolve("origin-${UUID.randomUUID()}" )).build()
        val session = ArtworkOrigins()
        val loader = ImageLoader.Builder(context).diskCache(cache)
            .okHttpClient(OkHttpClient.Builder().eventListenerFactory(session.httpFactory()).build()).build()
        try { MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "image/png")
                .setHeader("Cache-Control", "public, max-age=0").setHeader("ETag", "fixture")
                .setBody(okio.Buffer().write(png)))
            server.enqueue(MockResponse().setResponseCode(304).setHeader("Cache-Control", "public, max-age=86400"))
            server.start()
            val req = request(context, session, server.url("/poster.png").toString())
            assertIs<SuccessResult>(loader.execute(req))
            loader.memoryCache!!.clear()
            assertIs<SuccessResult>(loader.execute(req))
            assertEquals(2, server.requestCount)
            server.takeRequest()
            assertEquals("fixture", server.takeRequest().getHeader("If-None-Match"))
            assertTrue(session.report().contains("| 304 | 0 bytes |"))
        }} finally { loader.shutdown(); cache.shutdown() }
    }
}

