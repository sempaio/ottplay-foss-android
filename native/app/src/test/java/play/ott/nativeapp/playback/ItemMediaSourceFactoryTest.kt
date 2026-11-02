package play.ott.nativeapp.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.test.core.app.ApplicationProvider
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import play.ott.nativeapp.core.RemoteTransportPolicy
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import androidx.media3.datasource.DataSource
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import play.ott.nativeapp.core.Catalog
import play.ott.nativeapp.core.ProviderRepository
import play.ott.nativeapp.core.SourceConfig
import play.ott.nativeapp.core.SourceKind

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = PlaybackTestApplication::class)
class ItemMediaSourceFactoryTest {
    @Test fun `redirected provider catalog cannot reintroduce source credentials during playback`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val client = OkHttpClient()
        try {
            for (directHls in listOf(true, false)) {
                MockWebServer().use { origin -> MockWebServer().use { cdn ->
                    origin.start(); cdn.start()
                    origin.enqueue(MockResponse().setResponseCode(302).setHeader("Location", cdn.url("/list.m3u8")))
                    val playlist = if (directHls) "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\nsegment.ts"
                        else "#EXTM3U\n#EXTINF:-1,One\nrelative-stream.m3u8"
                    cdn.enqueue(MockResponse().setBody(playlist))
                    cdn.enqueue(MockResponse().setBody("playback"))
                    val source = SourceConfig("source", "Source", SourceKind.M3U, origin.url("/list").toString(),
                        headers = mapOf("Authorization" to "Bearer source", "Cookie" to "source-cookie", "X-Source" to "source-secret", "User-Agent" to "source-agent"))
                    val repository = ProviderRepository(client, RemoteTransportPolicy.HTTP_COMPATIBLE)
                    // Include persistence: provenance must survive encrypted catalog serialization.
                    val catalog = Json.decodeFromString<Catalog>(Json.encodeToString(repository.load(source)))
                    val entry = catalog.entries.single()
                    val stream = repository.resolve(source, entry)
                    val factory = ItemMediaSourceFactory(context, client, RemoteTransportPolicy.HTTP_COMPATIBLE)
                    val item = PlaybackItems.build(entry.id, entry.name, stream.url, stream.headers)
                    read(factory.httpFactoryFor(item), stream.url)
                    assertEquals("Bearer source", requireNotNull(origin.takeRequest(5, TimeUnit.SECONDS)).getHeader("Authorization"))
                    // Both the redirected catalog load and actual subsequent media request are safe.
                    repeat(2) {
                        val request = requireNotNull(cdn.takeRequest(5, TimeUnit.SECONDS))
                        assertNull(request.getHeader("Authorization"))
                        assertNull(request.getHeader("Cookie"))
                        assertNull(request.getHeader("X-Source"))
                        assertEquals("source-agent", request.getHeader("User-Agent"))
                    }
                } }
            }
        } finally {
            client.dispatcher.cancelAll(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
        }
    }

    @Test fun `explicit entry credentials authorize the CDN while source credentials stay on source authority`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val client = OkHttpClient()
        try {
            MockWebServer().use { origin -> MockWebServer().use { cdn ->
                origin.start(); cdn.start()
                origin.enqueue(MockResponse().setBody("#EXTM3U\n#EXTINF:-1,One\n#EXTHTTP:{\"authorization\":\"Bearer entry\",\"X-Entry\":\"entry-secret\"}\n${cdn.url("/stream.m3u8")}"))
                cdn.enqueue(MockResponse().setBody("media"))
                val source = SourceConfig("source", "Source", SourceKind.M3U, origin.url("/list").toString(),
                    headers = mapOf("Authorization" to "Bearer source", "Cookie" to "source-cookie", "X-Source" to "source-secret"))
                val repository = ProviderRepository(client, RemoteTransportPolicy.HTTP_COMPATIBLE)
                val entry = repository.load(source).entries.single()
                val stream = repository.resolve(source, entry)
                val factory = ItemMediaSourceFactory(context, client, RemoteTransportPolicy.HTTP_COMPATIBLE)
                read(factory.httpFactoryFor(PlaybackItems.build(entry.id, entry.name, stream.url, stream.headers)), stream.url)
                assertEquals("Bearer source", requireNotNull(origin.takeRequest(5, TimeUnit.SECONDS)).getHeader("Authorization"))
                val request = requireNotNull(cdn.takeRequest(5, TimeUnit.SECONDS))
                assertEquals("Bearer entry", request.getHeader("Authorization"))
                assertEquals("entry-secret", request.getHeader("X-Entry"))
                assertNull(request.getHeader("Cookie")); assertNull(request.getHeader("X-Source"))
            } }
        } finally {
            client.dispatcher.cancelAll(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
        }
    }

    @Test fun `item credentials stay on stream origin across direct CDN resources and redirects`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val client = OkHttpClient()
        try {
            MockWebServer().use { origin -> MockWebServer().use { cdn ->
                origin.start()
                cdn.start()
                origin.enqueue(MockResponse().setBody("manifest"))
                origin.enqueue(MockResponse().setResponseCode(302).setHeader("Location", cdn.url("/redirected-segment")))
                origin.enqueue(MockResponse().setBody("later-item"))
                cdn.enqueue(MockResponse().setBody("segment"))
                cdn.enqueue(MockResponse().setBody("redirected-segment"))
                val factory = ItemMediaSourceFactory(context, client, RemoteTransportPolicy.HTTP_COMPATIBLE)
                val headers = mapOf("Authorization" to "Bearer A", "Cookie" to "session=A", "X-Provider-Key" to "private-A",
                    "User-Agent" to "provider-agent", "Accept" to "*/*", "Accept-Language" to "en")
                val first = factory.httpFactoryFor(PlaybackItems.build("a", "A", origin.url("/master.m3u8").toString(), headers))
                val second = factory.httpFactoryFor(PlaybackItems.build("b", "B", origin.url("/other.m3u8").toString(),
                    mapOf("Authorization" to "Bearer B")))
                // Use A after preparing B, including a URL supplied by A's nested manifest.
                read(first, origin.url("/master.m3u8").toString())
                read(first, cdn.url("/segment-key-or-subtitle").toString())
                read(first, origin.url("/redirect").toString())
                read(second, origin.url("/other.m3u8").toString())
                repeat(2) {
                    val request = requireNotNull(origin.takeRequest(5, TimeUnit.SECONDS))
                    assertEquals("Bearer A", request.getHeader("Authorization"))
                    assertEquals("session=A", request.getHeader("Cookie"))
                    assertEquals("private-A", request.getHeader("X-Provider-Key"))
                }
                assertEquals("Bearer B", requireNotNull(origin.takeRequest(5, TimeUnit.SECONDS)).getHeader("Authorization"))
                repeat(2) {
                    val request = requireNotNull(cdn.takeRequest(5, TimeUnit.SECONDS))
                    assertNull(request.getHeader("Authorization"))
                    assertNull(request.getHeader("Cookie"))
                    assertNull(request.getHeader("X-Provider-Key"))
                    assertEquals("provider-agent", request.getHeader("User-Agent"))
                    assertEquals("*/*", request.getHeader("Accept"))
                    assertEquals("en", request.getHeader("Accept-Language"))
                }
            } }
        } finally {
            client.dispatcher.cancelAll()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    private fun read(factory: DataSource.Factory, url: String) {
        val source = factory.createDataSource()
        try {
            source.open(DataSpec(Uri.parse(url)))
            val buffer = ByteArray(64)
            while (source.read(buffer, 0, buffer.size) >= 0) { /* Consume response before closing. */ }
        } finally { source.close() }
    }

    @Test fun `strict item factory rejects a manifest supplied HTTP segment before connecting`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val client = OkHttpClient()
        try {
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                server.soTimeout = 200
                val factory = ItemMediaSourceFactory(context, client, RemoteTransportPolicy.HTTPS_ONLY)
                val item = PlaybackItems.build("item", "HTTPS manifest", "https://media.example.test/master.m3u8",
                    mapOf("X-Provider-Session" to "private"))
                val source = factory.httpFactoryFor(item).createDataSource()
                try {
                    // The same factory opens HLS keys/segments, DASH chunks and subtitle URLs.
                    // Checking only the initial MediaItem would let this request escape.
                    assertThrows(java.io.IOException::class.java) {
                        source.open(DataSpec(Uri.parse("http://127.0.0.1:${server.localPort}/opaque-key")))
                    }
                    assertThrows(java.net.SocketTimeoutException::class.java) { server.accept().close() }
                } finally { source.close() }
            }
        } finally {
            client.dispatcher.cancelAll()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    @Test fun `preparing another item cannot replace headers used by an older source`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val client = OkHttpClient()
        val executor = Executors.newSingleThreadExecutor()
        ServerSocket(0, 2, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 5000
            val received = executor.submit<List<String>> {
                List(2) {
                    server.accept().use { socket ->
                        socket.soTimeout = 5000
                        val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                        var authorization = ""
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            if (line.startsWith("Authorization:", true)) authorization = line.substringAfter(':').trim()
                        }
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Length: 1\r\nConnection: close\r\n\r\nx".toByteArray())
                            flush()
                        }
                        authorization
                    }
                }
            }
            try {
                val url = "http://127.0.0.1:${server.localPort}/stream"
                val factory = ItemMediaSourceFactory(context, client, RemoteTransportPolicy.HTTP_COMPATIBLE)
                val first = factory.httpFactoryFor(PlaybackItems.build("a", "A", url, mapOf("Authorization" to "Bearer A")))
                val second = factory.httpFactoryFor(PlaybackItems.build("b", "B", url, mapOf("Authorization" to "Bearer B")))
                // Open A after constructing B; a global mutable HTTP factory sends B twice.
                listOf(first, second).forEach { sourceFactory ->
                    val source = sourceFactory.createDataSource()
                    try {
                        assertEquals(1L, source.open(DataSpec(Uri.parse(url))))
                        assertEquals(1, source.read(ByteArray(1), 0, 1))
                    } finally {
                        source.close()
                    }
                }
                assertEquals(listOf("Bearer A", "Bearer B"), received.get(5, TimeUnit.SECONDS))
            } finally {
                executor.shutdownNow()
                client.dispatcher.cancelAll()
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
            }
        }
    }
}
