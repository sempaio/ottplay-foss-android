package play.ott.nativeapp.playback

import android.app.Activity
import android.content.ComponentName
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.util.concurrent.ListenableFuture
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import play.ott.nativeapp.core.M3uParser
import play.ott.nativeapp.core.SourceConfig
import play.ott.nativeapp.core.SourceKind
import play.ott.nativeapp.OttplayApplication

/** Self-owned CENC video, real Android ClearKey session, native license POST and MediaController IPC. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class ClearKeyPlaybackInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun encryptedDashDecodesThroughNativeClearKeyWithIsolatedLicenseHeaders() {
        assertTrue("ClearKey is required on the Android phone/TV CI images", FrameworkMediaDrm.isCryptoSchemeSupported(C.CLEARKEY_UUID))
        PlaybackTestLifecycle.finishPreviousPlayback()
        val preferences = (context.applicationContext as OttplayApplication).repository.preferences
        val previousPreferences = runBlocking { preferences.data.first() }
        val activity = instrumentation.startActivitySync(PlaybackTestLifecycle.launchIntent())
        var controller: MediaController? = null
        ClearKeyServer().use { server ->
            try {
                val source = SourceConfig("clearkey-test", "Synthetic ClearKey", SourceKind.M3U, server.url("playlist.m3u"))
                val entry = M3uParser.parse("""
                    #EXTM3U
                    #EXTINF:-1,Synthetic encrypted DASH
                    #KODIPROP:inputstream.adaptive.drm_legacy=org.w3.clearkey|${server.url("license")}|Authorization=Bearer+synthetic-license
                    #KODIPROP:inputstream.adaptive.stream_headers=Authorization=Bearer+synthetic-stream
                    #KODIPROP:mimetype=application/dash+xml
                    ${server.url("manifest.mpd")}
                """.trimIndent(), source).entries.single()
                assertNotNull("M3U DRM configuration was lost", entry.drm)
                val surface = attachSurface(activity)
                lateinit var future: ListenableFuture<MediaController>
                onMain {
                    future = MediaController.Builder(context,
                        SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()
                }
                val player = future.get(10, TimeUnit.SECONDS).also { controller = it }
                onMain {
                    player.setVideoSurfaceHolder(surface.holder)
                    player.repeatMode = Player.REPEAT_MODE_OFF
                    player.setMediaItem(PlaybackItems.build(entry.id, entry.name, entry.url, entry.headers,
                        drm = entry.drm, mimeType = entry.mimeType, unsupportedReason = entry.playbackUnsupportedReason))
                    player.prepare()
                    player.play()
                }
                awaitDecoded(player)
                val requests = server.requests.toList()
                val licenses = requests.filter { it.path == "/license" }
                assertTrue("Android did not request a ClearKey license", licenses.isNotEmpty())
                licenses.forEach { request ->
                    assertEquals("POST", request.method)
                    assertEquals(LICENSE_AUTHORIZATION, request.authorization)
                    val kids = Json.parseToJsonElement(request.body.toString(Charsets.UTF_8)).jsonObject["kids"]!!.jsonArray
                        .map { it.jsonPrimitive.content }
                    assertEquals(listOf(KEY_ID), kids)
                }
                val streamRequests = requests.filter { it.path != "/license" }
                assertTrue("DASH initialization/segments were not fetched", streamRequests.any { it.path == "/encrypted.mp4" })
                streamRequests.forEach { assertEquals(STREAM_AUTHORIZATION, it.authorization) }
                assertTrue("Rejected test routes or credentials: ${server.rejections}", server.rejections.isEmpty())
            } finally {
                onMain { controller?.run { stop(); release() }; activity.finish() }
                PlaybackTestLifecycle.finishPreviousPlayback()
                runBlocking { preferences.update { previousPreferences } }
            }
        }
    }

    private fun awaitDecoded(player: MediaController) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (System.nanoTime() < deadline) {
            var decoded = false
            var error: Int? = null
            onMain {
                error = player.playerError?.errorCode
                decoded = player.isPlaying && player.currentPosition >= 500L &&
                    player.videoSize.width == 640 && player.videoSize.height == 360
            }
            assertTrue("Native ClearKey playback failed with Media3 code $error", error == null)
            if (decoded) return
            Thread.sleep(50)
        }
        throw AssertionError("Encrypted ClearKey video did not advance")
    }

    private fun attachSurface(activity: Activity): SurfaceView {
        val ready = CountDownLatch(1)
        lateinit var surface: SurfaceView
        onMain {
            surface = SurfaceView(activity).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) { ready.countDown() }
                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
                    override fun surfaceDestroyed(holder: SurfaceHolder) {}
                })
            }
            activity.addContentView(surface, FrameLayout.LayoutParams(320, 180))
        }
        assertTrue("ClearKey test surface was not created", ready.await(10, TimeUnit.SECONDS))
        return surface
    }

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private data class Request(val method: String, val path: String, val authorization: String, val body: ByteArray)

    private inner class ClearKeyServer : Closeable {
        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val executor = Executors.newCachedThreadPool()
        private val sockets = ConcurrentHashMap.newKeySet<Socket>()
        val requests = CopyOnWriteArrayList<Request>()
        val rejections = CopyOnWriteArrayList<String>()

        init {
            executor.execute {
                while (!server.isClosed) {
                    val socket = try { server.accept() } catch (_: IOException) { break }
                    sockets.add(socket)
                    executor.execute {
                        try { socket.use(::serve) } catch (_: IOException) { /* Player cancellation. */ }
                        finally { sockets.remove(socket) }
                    }
                }
            }
        }

        fun url(path: String): String = "http://127.0.0.1:${server.localPort}/$path"

        private fun serve(socket: Socket) {
            socket.soTimeout = 5000
            val input = BufferedInputStream(socket.getInputStream())
            val requestLine = input.line().split(' ')
            val method = requestLine.getOrNull(0).orEmpty()
            val path = requestLine.getOrNull(1).orEmpty()
            val headers = linkedMapOf<String, String>()
            while (true) {
                val line = input.line()
                if (line.isEmpty()) break
                headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
            }
            val length = headers["content-length"]?.toIntOrNull() ?: 0
            require(length in 0..16384)
            val body = ByteArray(length)
            var count = 0
            while (count < length) {
                val next = input.read(body, count, length - count)
                if (next < 0) throw IOException("Incomplete fixture request")
                count += next
            }
            val authorization = headers["authorization"].orEmpty()
            requests.add(Request(method, path, authorization, body))
            val license = path == "/license"
            if (authorization != if (license) LICENSE_AUTHORIZATION else STREAM_AUTHORIZATION) {
                rejections.add(path)
                respond(socket, "401 Unauthorized", ByteArray(0), "text/plain")
                return
            }
            if (license) {
                respond(socket, "200 OK", """{"keys":[{"kty":"oct","kid":"$KEY_ID","k":"$KEY"}],"type":"temporary"}""".toByteArray(), "application/json")
                return
            }
            if (method != "GET" || path !in setOf("/manifest.mpd", "/encrypted.mp4")) {
                rejections.add(path)
                respond(socket, "404 Not Found", ByteArray(0), "text/plain")
                return
            }
            val bytes = instrumentation.context.assets.open("playback/clearkey${path}").use { it.readBytes() }
            val mime = if (path.endsWith(".mpd")) "application/dash+xml" else "video/mp4"
            val range = headers["range"]?.let { Regex("bytes=(\\d+)-(\\d*)").matchEntire(it) }
            if (range == null) respond(socket, "200 OK", bytes, mime)
            else {
                val start = range.groupValues[1].toIntOrNull() ?: bytes.size
                val end = range.groupValues[2].toIntOrNull()?.coerceAtMost(bytes.lastIndex) ?: bytes.lastIndex
                if (start !in bytes.indices || end < start) respond(socket, "416 Range Not Satisfiable", ByteArray(0), mime)
                else respond(socket, "206 Partial Content", bytes.copyOfRange(start, end + 1), mime,
                    "Content-Range: bytes $start-$end/${bytes.size}\r\n")
            }
        }

        private fun BufferedInputStream.line(): String {
            val result = StringBuilder()
            while (true) {
                val next = read()
                if (next < 0) throw IOException("Incomplete fixture headers")
                if (next == 10) return result.toString().removeSuffix("\r")
                result.append(next.toChar())
                if (result.length > 16384) throw IOException("Oversized fixture headers")
            }
        }

        private fun respond(socket: Socket, status: String, bytes: ByteArray, mime: String, extra: String = "") {
            socket.getOutputStream().apply {
                write(("HTTP/1.1 $status\r\nContent-Type: $mime\r\nContent-Length: ${bytes.size}\r\n" +
                    "Accept-Ranges: bytes\r\nConnection: close\r\n$extra\r\n").toByteArray(Charsets.US_ASCII))
                write(bytes); flush()
            }
        }

        override fun close() {
            server.close()
            sockets.forEach { try { it.close() } catch (_: IOException) { } }
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    private companion object {
        const val STREAM_AUTHORIZATION = "Bearer synthetic-stream"
        const val LICENSE_AUTHORIZATION = "Bearer synthetic-license"
        const val KEY_ID = "ESIzRFVmd4iZAKq7zN3u_w"
        const val KEY = "ABEiM0RVZneImaq7zN3u_w"
    }
}
