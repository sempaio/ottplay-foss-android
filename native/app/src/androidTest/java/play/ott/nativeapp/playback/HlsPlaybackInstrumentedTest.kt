package play.ott.nativeapp.playback

import android.app.Activity
import android.content.ComponentName
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.util.concurrent.ListenableFuture
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real HLS master/media manifests, TS demuxing, native decoder and two authenticated sources. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class HlsPlaybackInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun hlsPlaylistItemsDecodeWithTheirOwnHeadersAcrossChannelSwitches() {
        val launch = PlaybackTestLifecycle.launchIntent()
        val activity = instrumentation.startActivitySync(launch)
        var controller: MediaController? = null
        HlsFixtureServer().use { server ->
            try {
                val surface = attachSurface(activity)
                val player = connect().also { controller = it }
                onMain {
                    player.setVideoSurfaceHolder(surface.holder)
                    player.repeatMode = Player.REPEAT_MODE_OFF
                    // Both MediaSources are created before A starts. A shared mutable HTTP factory
                    // would now accidentally give A the headers most recently assigned to B.
                    player.setMediaItems(listOf(
                        PlaybackItems.build("hls-test-a", "Synthetic HLS A", server.url("a"), mapOf("Authorization" to TOKEN_A)),
                        PlaybackItems.build("hls-test-b", "Synthetic HLS B", server.url("b"), mapOf("Authorization" to TOKEN_B)),
                    ), 0, 0L)
                    player.prepare()
                    player.play()
                }
                awaitDecoded(player, "hls-test-a")
                onMain { player.seekToNextMediaItem() }
                awaitDecoded(player, "hls-test-b")
                // Returning to A exercises a previously created source after B has been opened.
                onMain { player.seekTo(0, 0L) }
                awaitDecoded(player, "hls-test-a")
                onMain { player.stop() }

                val requests = server.requests.toList()
                for ((route, token) in listOf("a" to TOKEN_A, "b" to TOKEN_B)) {
                    val itemRequests = requests.filter { it.path.startsWith("/$route/") }
                    assertTrue("$route master playlist was not fetched", itemRequests.any { it.path.endsWith("/master.m3u8") })
                    assertTrue("$route media playlist was not fetched", itemRequests.any { it.path.endsWith("/index.m3u8") })
                    assertTrue("$route TS segments were not fetched", itemRequests.any { it.path.endsWith(".ts") })
                    itemRequests.forEach { assertEquals("Wrong headers on ${it.path}", token, it.authorization) }
                }
                assertTrue("Unexpected unauthenticated or invalid HLS requests: ${server.rejections}", server.rejections.isEmpty())
            } finally {
                onMain { controller?.run { stop(); release() }; activity.finish() }
                PlaybackTestLifecycle.finishPreviousPlayback()
            }
        }
    }

    private fun awaitDecoded(player: MediaController, id: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        val transitions = ArrayDeque<String>()
        var previousState = ""
        var lastState = "not observed"
        while (System.nanoTime() < deadline) {
            var decoded = false
            var errorCode: Int? = null
            onMain {
                errorCode = player.playerError?.errorCode
                decoded = player.currentMediaItem?.mediaId == id && player.isPlaying &&
                    player.currentPosition >= 400L && player.videoSize.width == 640 && player.videoSize.height == 360
                // Keep only synthetic ids and native state, never stream URLs or credentials.
                val state = "item=${player.currentMediaItem?.mediaId}, count=${player.mediaItemCount}, " +
                    "state=${player.playbackState}, playWhenReady=${player.playWhenReady}, " +
                    "isPlaying=${player.isPlaying}, suppression=${player.playbackSuppressionReason}, " +
                    "size=${player.videoSize.width}x${player.videoSize.height}, error=$errorCode"
                lastState = "$state, position=${player.currentPosition}, buffered=${player.bufferedPosition}, loading=${player.isLoading}"
                if (state != previousState) {
                    if (transitions.size == 12) transitions.removeFirst()
                    transitions.addLast(lastState)
                    previousState = state
                }
            }
            assertTrue("HLS decoder failed for $id: $lastState; transitions=$transitions", errorCode == null)
            if (decoded) return
            Thread.sleep(50)
        }
        throw AssertionError("HLS video/position did not advance for $id: $lastState; transitions=$transitions")
    }

    private fun connect(): MediaController {
        lateinit var future: ListenableFuture<MediaController>
        onMain {
            future = MediaController.Builder(context,
                SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()
        }
        return future.get(10, TimeUnit.SECONDS)
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
        assertTrue("HLS test surface was not created", ready.await(10, TimeUnit.SECONDS))
        return surface
    }

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private data class Request(val path: String, val authorization: String)

    private inner class HlsFixtureServer : Closeable {
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
                        try { socket.use(::serve) }
                        catch (_: IOException) { /* Media3 cancels old-source requests when switching. */ }
                        finally { sockets.remove(socket) }
                    }
                }
            }
        }

        fun url(route: String): String = "http://127.0.0.1:${server.localPort}/$route/master.m3u8"

        private fun serve(socket: Socket) {
            socket.soTimeout = 5000
            val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
            val requestLine = reader.readLine() ?: return
            val path = requestLine.split(' ').getOrNull(1)?.substringBefore('?').orEmpty()
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) headers[line.substring(0, colon).lowercase()] = line.substring(colon + 1).trim()
            }
            val authorization = headers["authorization"].orEmpty()
            requests.add(Request(path, authorization))
            val route = path.split('/').getOrNull(1)
            val expected = when (route) { "a" -> TOKEN_A; "b" -> TOKEN_B; else -> null }
            if (expected == null || authorization != expected) {
                rejections.add(path)
                writeResponse(socket, "401 Unauthorized", ByteArray(0), "text/plain")
                return
            }
            val file = path.substringAfterLast('/')
            if (file !in FIXTURE_FILES) {
                rejections.add(path)
                writeResponse(socket, "404 Not Found", ByteArray(0), "text/plain")
                return
            }
            val bytes = instrumentation.context.assets.open("playback/hls/$file").use { it.readBytes() }
            val mime = if (file.endsWith(".m3u8")) "application/vnd.apple.mpegurl" else "video/mp2t"
            val range = headers["range"]?.let { Regex("bytes=(\\d+)-(\\d*)").matchEntire(it) }
            if (range == null) writeResponse(socket, "200 OK", bytes, mime)
            else {
                val start = range.groupValues[1].toIntOrNull() ?: bytes.size
                val end = range.groupValues[2].toIntOrNull()?.coerceAtMost(bytes.lastIndex) ?: bytes.lastIndex
                if (start !in bytes.indices || end < start) {
                    writeResponse(socket, "416 Range Not Satisfiable", ByteArray(0), mime)
                } else {
                    writeResponse(socket, "206 Partial Content", bytes.copyOfRange(start, end + 1), mime,
                        "Content-Range: bytes $start-$end/${bytes.size}\r\n")
                }
            }
        }

        private fun writeResponse(socket: Socket, status: String, bytes: ByteArray, mime: String, extra: String = "") {
            socket.getOutputStream().apply {
                write(("HTTP/1.1 $status\r\nContent-Type: $mime\r\nContent-Length: ${bytes.size}\r\n" +
                    "Accept-Ranges: bytes\r\nConnection: close\r\n$extra\r\n").toByteArray(Charsets.US_ASCII))
                write(bytes)
                flush()
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
        const val TOKEN_A = "Bearer synthetic-hls-a"
        const val TOKEN_B = "Bearer synthetic-hls-b"
        val FIXTURE_FILES = setOf("master.m3u8", "index.m3u8", "segment00.ts", "segment01.ts", "segment02.ts", "segment03.ts")
    }
}
