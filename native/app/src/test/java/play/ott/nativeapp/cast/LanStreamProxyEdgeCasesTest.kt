package play.ott.nativeapp.cast

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class LanStreamProxyEdgeCasesTest {
    @Test fun loopbackRedirectToPrivateIpIsBlockedImmediatelyByAntiSsrfGuard() {
        val running = AtomicBoolean(true)
        val upstream = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val pool = Executors.newCachedThreadPool()
        val upstreamPort = upstream.localPort

        pool.execute {
            while (running.get() && !upstream.isClosed) {
                val sock = runCatching { upstream.accept() }.getOrNull() ?: break
                pool.execute {
                    sock.use { s ->
                        val reqLine = s.getInputStream().bufferedReader().readLine().orEmpty()
                        val path = reqLine.split(' ').getOrNull(1).orEmpty()
                        val out = s.getOutputStream()
                        when (path) {
                            "/redir-lan.mp4" -> {
                                out.write(
                                    ("HTTP/1.1 302 Found\r\n" +
                                        "Location: http://192.168.1.1/router-secret.mp4\r\n" +
                                        "Content-Length: 0\r\n" +
                                        "Connection: close\r\n\r\n").toByteArray(),
                                )
                            }
                            "/redir-meta.m3u8" -> {
                                out.write(
                                    ("HTTP/1.1 307 Temporary Redirect\r\n" +
                                        "Location: http://169.254.169.254/latest/meta-data/\r\n" +
                                        "Content-Length: 0\r\n" +
                                        "Connection: close\r\n\r\n").toByteArray(),
                                )
                            }
                            "/manifest-with-private-uri.m3u8" -> {
                                val body = """
                                    #EXTM3U
                                    #EXT-X-VERSION:3
                                    #EXT-X-TARGETDURATION:4
                                    #EXT-X-KEY:METHOD=AES-128,URI="http://10.0.0.1/key.bin"
                                    #EXTINF:4,
                                    http://192.168.0.1/internal.ts
                                """.trimIndent().toByteArray()
                                out.write(
                                    ("HTTP/1.1 200 OK\r\n" +
                                        "Content-Type: application/vnd.apple.mpegurl\r\n" +
                                        "Content-Length: ${body.size}\r\n" +
                                        "Connection: close\r\n\r\n").toByteArray(),
                                )
                                out.write(body)
                            }
                            else -> out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        }
                        out.flush()
                    }
                }
            }
        }

        val proxy = LanStreamProxyServer(allowLoopbackForTest = true)
        val http = OkHttpClient()
        try {
            // 1. Redirect from loopback -> RFC1918 192.168.1.1 must be blocked fast (< 2s, no TCP connect hang!)
            val t0 = System.currentTimeMillis()
            val vodTarget = proxy.prepareStream(
                upstreamUrl = "http://127.0.0.1:$upstreamPort/redir-lan.mp4",
                isLive = false,
                localLanIp = "127.0.0.1",
            )
            http.newCall(Request.Builder().url(vodTarget.localUrl).get().build()).execute().use { resp ->
                assertTrue("Expected 403 or 502 on SSRF redirect, got ${resp.code}", resp.code == 403 || resp.code == 502)
            }
            val elapsedMs = System.currentTimeMillis() - t0
            assertTrue("SSRF redirect block should fail fast (<2500ms), took ${elapsedMs}ms", elapsedMs < 2500L)
            assertTrue(
                "Expected anti-SSRF diagnostic in lastWriteError, got: ${proxy.currentStats().lastWriteError}",
                proxy.currentStats().lastWriteError.contains("anti-SSRF", ignoreCase = true),
            )

            // 2. Redirect from loopback -> AWS/Cloud metadata IP 169.254.169.254 on HLS manifest blocked fast
            val metaTarget = proxy.prepareStream(
                upstreamUrl = "http://127.0.0.1:$upstreamPort/redir-meta.m3u8",
                isLive = true,
                localLanIp = "127.0.0.1",
            )
            http.newCall(Request.Builder().url(metaTarget.chromecastHlsUrl!!).get().build()).execute().use { resp ->
                assertEquals(502, resp.code)
            }
            assertTrue(proxy.currentStats().lastWriteError.contains("anti-SSRF", ignoreCase = true))

            // 3. HLS manifest containing private RFC1918 key/segment URIs rewrites them to /res/blocked.bin (404)
            val evilManifestTarget = proxy.prepareStream(
                upstreamUrl = "http://127.0.0.1:$upstreamPort/manifest-with-private-uri.m3u8",
                isLive = true,
                localLanIp = "127.0.0.1",
            )
            val rewritten = http.newCall(Request.Builder().url(evilManifestTarget.chromecastHlsUrl!!).get().build()).execute().use { resp ->
                assertEquals(200, resp.code)
                resp.body.string()
            }
            assertFalse(rewritten.contains("10.0.0.1"))
            assertFalse(rewritten.contains("192.168.0.1"))
            assertTrue(rewritten.contains("/res/blocked.bin"))
            val blockedSegUrl = rewritten.lineSequence().first { it.endsWith("/res/blocked.bin") }
            http.newCall(Request.Builder().url(blockedSegUrl).get().build()).execute().use { resp ->
                assertEquals(404, resp.code)
            }
        } finally {
            running.set(false)
            proxy.shutdown()
            runCatching { upstream.close() }
            pool.shutdownNow()
        }
    }

    @Test fun vodRangeEdgeCasesCover200And206And416AndIgnoredRange200Guard() {
        val movieBytes = ByteArray(10_000) { idx -> ((idx * 17 + 3) and 0xFF).toByte() }
        val running = AtomicBoolean(true)
        val upstream = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val pool = Executors.newCachedThreadPool()
        val upstreamPort = upstream.localPort
        val ignoreRangeAfterProbe = AtomicBoolean(false)

        pool.execute {
            while (running.get() && !upstream.isClosed) {
                val sock = runCatching { upstream.accept() }.getOrNull() ?: break
                pool.execute {
                    sock.use { s ->
                        val reader = s.getInputStream().bufferedReader()
                        val reqLine = reader.readLine().orEmpty()
                        val path = reqLine.split(' ').getOrNull(1).orEmpty()
                        var rangeHeader: String? = null
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isBlank()) break
                            if (line.startsWith("Range:", ignoreCase = true)) {
                                rangeHeader = line.substringAfter(':').trim()
                            }
                        }
                        val out = s.getOutputStream()
                        when (path) {
                            "/movie.mp4" -> {
                                val spec = HlsRemuxCodec.parseByteRange(rangeHeader, movieBytes.size.toLong())
                                if (spec == null) {
                                    out.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */${movieBytes.size}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                                } else if (ignoreRangeAfterProbe.get() && spec.start > 0L) {
                                    // Misbehaving CDN ignores Range header on offset > 0 and returns 200 OK from byte 0!
                                    out.write(
                                        ("HTTP/1.1 200 OK\r\n" +
                                            "Content-Type: video/mp4\r\n" +
                                            "Content-Length: ${movieBytes.size}\r\n" +
                                            "Connection: close\r\n\r\n").toByteArray(),
                                    )
                                    out.write(movieBytes)
                                } else {
                                    val slice = movieBytes.copyOfRange(spec.start.toInt(), spec.end.toInt() + 1)
                                    out.write(
                                        ("HTTP/1.1 206 Partial Content\r\n" +
                                            "Content-Type: video/mp4\r\n" +
                                            "Accept-Ranges: bytes\r\n" +
                                            "Content-Range: bytes ${spec.start}-${spec.end}/${movieBytes.size}\r\n" +
                                            "Content-Length: ${slice.size}\r\n" +
                                            "Connection: close\r\n\r\n").toByteArray(),
                                    )
                                    out.write(slice)
                                }
                            }
                            else -> out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        }
                        out.flush()
                    }
                }
            }
        }

        val proxy = LanStreamProxyServer(allowLoopbackForTest = true)
        val http = OkHttpClient()
        try {
            val target = proxy.prepareStream(
                upstreamUrl = "http://127.0.0.1:$upstreamPort/movie.mp4",
                isLive = false,
                localLanIp = "127.0.0.1",
            )
            assertEquals(movieBytes.size.toLong(), target.contentLength)

            // 1. 200 OK full response (no Range header)
            http.newCall(Request.Builder().url(target.localUrl).get().build()).execute().use { resp ->
                assertEquals(200, resp.code)
                assertEquals(movieBytes.size.toString(), resp.header("Content-Length"))
                assertArrayEquals(movieBytes, resp.body.bytes())
            }

            // 2. 206 Partial Content (explicit middle slice)
            http.newCall(
                Request.Builder().url(target.localUrl).header("Range", "bytes=1000-2499").get().build(),
            ).execute().use { resp ->
                assertEquals(206, resp.code)
                assertEquals("bytes 1000-2499/${movieBytes.size}", resp.header("Content-Range"))
                assertEquals("1500", resp.header("Content-Length"))
                assertArrayEquals(movieBytes.copyOfRange(1000, 2500), resp.body.bytes())
            }

            // 3. 206 Partial Content (suffix range)
            http.newCall(
                Request.Builder().url(target.localUrl).header("Range", "bytes=-256").get().build(),
            ).execute().use { resp ->
                assertEquals(206, resp.code)
                assertEquals("bytes 9744-9999/${movieBytes.size}", resp.header("Content-Range"))
                assertArrayEquals(movieBytes.copyOfRange(9744, 10_000), resp.body.bytes())
            }

            // 4. 416 Range Not Satisfiable (out of bounds)
            http.newCall(
                Request.Builder().url(target.localUrl).header("Range", "bytes=50000-60000").get().build(),
            ).execute().use { resp ->
                assertEquals(416, resp.code)
                assertEquals("bytes */${movieBytes.size}", resp.header("Content-Range"))
            }

            // 5. Upstream ignores Range on offset > 0 and returns 200 OK -> proxy MUST NOT stream corrupt byte-0 data!
            ignoreRangeAfterProbe.set(true)
            val bodyOrError = runCatching {
                http.newCall(
                    Request.Builder().url(target.localUrl).header("Range", "bytes=2000-2999").get().build(),
                ).execute().use { resp ->
                    resp.body.bytes()
                }
            }.getOrNull()
            // Either empty/truncated EOF (never the corrupt bytes 0..999!) and lastWriteError explains ignored Range
            if (bodyOrError != null) {
                assertEquals("Proxy must refuse to stream byte-0 data when CDN returns 200 OK on offset > 0", 0, bodyOrError.size)
            }
            assertTrue(
                "Expected ignored Range 200 OK diagnostic, got: ${proxy.currentStats().lastWriteError}",
                proxy.currentStats().lastWriteError.contains("200 OK", ignoreCase = true),
            )
        } finally {
            running.set(false)
            proxy.shutdown()
            runCatching { upstream.close() }
            pool.shutdownNow()
        }
    }

    @Test fun liveHlsTwoConsecutiveExpiredSegmentsTriggerImmediateManifestRenewal() {
        val freshSeg1 = ByteArray(188 * 2) { idx -> if (idx % 188 == 0) 0x47.toByte() else 0x55.toByte() }
        val freshSeg2 = ByteArray(188 * 2) { idx -> if (idx % 188 == 0) 0x47.toByte() else 0x66.toByte() }
        val running = AtomicBoolean(true)
        val upstream = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val pool = Executors.newCachedThreadPool()
        val upstreamPort = upstream.localPort
        val playlistHits = AtomicInteger(0)

        pool.execute {
            while (running.get() && !upstream.isClosed) {
                val sock = runCatching { upstream.accept() }.getOrNull() ?: break
                pool.execute {
                    sock.use { s ->
                        val reqLine = s.getInputStream().bufferedReader().readLine().orEmpty()
                        val fullPath = reqLine.split(' ').getOrNull(1).orEmpty()
                        val out = s.getOutputStream()
                        when {
                            fullPath == "/live-hls/u/p/500.m3u8" -> {
                                val hit = playlistHits.incrementAndGet()
                                val body = if (hit <= 2) {
                                    // Calls 1 (prefetch) & 2 (initial producer fetch) return expired segment tokens!
                                    """
                                        #EXTM3U
                                        #EXT-X-VERSION:3
                                        #EXT-X-TARGETDURATION:4
                                        #EXT-X-MEDIA-SEQUENCE:50
                                        #EXTINF:4,
                                        http://127.0.0.1:$upstreamPort/seg50.ts?token=EXPIRED
                                        #EXTINF:4,
                                        http://127.0.0.1:$upstreamPort/seg51.ts?token=EXPIRED
                                        #EXT-X-ENDLIST
                                    """.trimIndent().toByteArray()
                                } else {
                                    // Call 3 (after 2 consecutive .ts 403 failures trigger renewal) returns fresh tokens!
                                    """
                                        #EXTM3U
                                        #EXT-X-VERSION:3
                                        #EXT-X-TARGETDURATION:4
                                        #EXT-X-MEDIA-SEQUENCE:50
                                        #EXTINF:4,
                                        http://127.0.0.1:$upstreamPort/seg50.ts?token=FRESH
                                        #EXTINF:4,
                                        http://127.0.0.1:$upstreamPort/seg51.ts?token=FRESH
                                        #EXT-X-ENDLIST
                                    """.trimIndent().toByteArray()
                                }
                                out.write("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                                out.write(body)
                            }
                            fullPath.contains("token=EXPIRED") -> {
                                out.write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                            }
                            fullPath == "/seg50.ts?token=FRESH" -> {
                                out.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\nContent-Length: ${freshSeg1.size}\r\nConnection: close\r\n\r\n".toByteArray())
                                out.write(freshSeg1)
                            }
                            fullPath == "/seg51.ts?token=FRESH" -> {
                                out.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\nContent-Length: ${freshSeg2.size}\r\nConnection: close\r\n\r\n".toByteArray())
                                out.write(freshSeg2)
                            }
                            else -> out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        }
                        out.flush()
                    }
                }
            }
        }

        val proxy = LanStreamProxyServer(allowLoopbackForTest = true)
        try {
            val target = proxy.prepareStream(
                upstreamUrl = "http://127.0.0.1:$upstreamPort/live-hls/u/p/500.m3u8",
                isLive = true,
                localLanIp = "127.0.0.1",
            )
            val received = OkHttpClient().newCall(Request.Builder().url(target.localUrl).get().build()).execute().use { resp ->
                assertEquals(200, resp.code)
                resp.body.bytes()
            }
            assertArrayEquals(freshSeg1 + freshSeg2, received)
            assertTrue("Manifest should have been renewed (>= 3 hits), got ${playlistHits.get()}", playlistHits.get() >= 3)
            assertTrue("Reconnect counter should reflect manifest renewal", proxy.currentStats().upstreamReconnects >= 1)
        } finally {
            running.set(false)
            proxy.shutdown()
            runCatching { upstream.close() }
            pool.shutdownNow()
        }
    }

    @Test fun hlsParserAndProxyDetectSampleAesAndExtXMap() {
        val sampleAesPlaylist = """
            #EXTM3U
            #EXT-X-VERSION:5
            #EXT-X-TARGETDURATION:6
            #EXT-X-KEY:METHOD=SAMPLE-AES,URI="https://drm.example/key.bin"
            #EXTINF:6,
            https://cdn.example/drm1.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        val parsedSampleAes = HlsRemuxCodec.parseMediaPlaylist(sampleAesPlaylist, "https://cdn.example/live.m3u8")
        assertTrue(parsedSampleAes.hasUnsupportedEncryption)
        assertFalse(parsedSampleAes.hasInitMap)

        val fmp4Playlist = """
            #EXTM3U
            #EXT-X-VERSION:7
            #EXT-X-TARGETDURATION:4
            #EXT-X-MAP:URI="init.mp4"
            #EXTINF:4,
            chunk_001.m4s
            #EXT-X-ENDLIST
        """.trimIndent()
        val parsedFmp4 = HlsRemuxCodec.parseMediaPlaylist(fmp4Playlist, "https://cdn.example/fmp4/index.m3u8")
        assertFalse(parsedFmp4.hasUnsupportedEncryption)
        assertTrue(parsedFmp4.hasInitMap)
        val rewrittenFmp4 = HlsRemuxCodec.rewritePlaylistForRelay(fmp4Playlist, "https://cdn.example/fmp4/index.m3u8") { remote ->
            "http://192.168.1.5:8888/relay?u=$remote"
        }
        assertTrue(rewrittenFmp4.contains("URI=\"http://192.168.1.5:8888/relay?u=https://cdn.example/fmp4/init.mp4\""))

        // End-to-end check: live TS proxy detects SAMPLE-AES, exits cleanly without stalling, and sets lastWriteError
        val running = AtomicBoolean(true)
        val upstream = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val pool = Executors.newCachedThreadPool()
        val upstreamPort = upstream.localPort
        pool.execute {
            while (running.get() && !upstream.isClosed) {
                val sock = runCatching { upstream.accept() }.getOrNull() ?: break
                pool.execute {
                    sock.use { s ->
                        s.getInputStream().bufferedReader().readLine()
                        val body = sampleAesPlaylist.toByteArray()
                        s.getOutputStream().write(
                            ("HTTP/1.1 200 OK\r\n" +
                                "Content-Type: application/vnd.apple.mpegurl\r\n" +
                                "Content-Length: ${body.size}\r\n" +
                                "Connection: close\r\n\r\n").toByteArray(),
                        )
                        s.getOutputStream().write(body)
                        s.getOutputStream().flush()
                    }
                }
            }
        }

        val proxy = LanStreamProxyServer(allowLoopbackForTest = true)
        try {
            val target = proxy.prepareStream(
                upstreamUrl = "http://127.0.0.1:$upstreamPort/drm.m3u8",
                isLive = true,
                localLanIp = "127.0.0.1",
            )
            assertTrue(
                "Prefetch should already record SAMPLE-AES error: ${proxy.currentStats().lastWriteError}",
                proxy.currentStats().lastWriteError.contains("SAMPLE-AES"),
            )
            val bytes = OkHttpClient().newCall(Request.Builder().url(target.localUrl).get().build()).execute().use { resp ->
                assertEquals(200, resp.code)
                resp.body.bytes()
            }
            assertEquals("SAMPLE-AES live TS stream should exit cleanly with 0 bytes", 0, bytes.size)
            assertNotNull(proxy.currentStats().lastWriteError)
            assertTrue(proxy.currentStats().lastWriteError.contains("SAMPLE-AES"))
        } finally {
            running.set(false)
            proxy.shutdown()
            runCatching { upstream.close() }
            pool.shutdownNow()
        }
    }
}
