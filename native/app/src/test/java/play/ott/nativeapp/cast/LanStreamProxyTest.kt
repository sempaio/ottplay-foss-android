package play.ott.nativeapp.cast

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class LanStreamProxyTest {
    @Test fun hlsRemuxPicksBalancedPlutoVariant() {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=640930
            /iptv/pluto/play/abc/resource/low.m3u8
            #EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=2294080
            /iptv/pluto/play/abc/resource/hd.m3u8
            #EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=3321280
            /iptv/pluto/play/abc/resource/fhd.m3u8
            #EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=6500000
            /iptv/pluto/play/abc/resource/uhd.m3u8
        """.trimIndent()
        val picked = HlsRemuxCodec.pickBestVariantUrl(master, "https://panel.taila5c5c2.ts.net/iptv/pluto/play/abc/master.m3u8")
        assertEquals("https://panel.taila5c5c2.ts.net/iptv/pluto/play/abc/resource/fhd.m3u8", picked)
    }

    @Test fun hlsRemuxParsesPlutoDynamicAes128AndNoneKeys() {
        val playlist = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:5
            #EXT-X-MEDIA-SEQUENCE:120
            #EXT-X-KEY:METHOD=NONE
            #EXTINF:5,
            https://cdn.example/bumper0.ts
            #EXT-X-KEY:METHOD=AES-128,URI="https://cdn.example/key1.bin"
            #EXTINF:5,
            https://cdn.example/show1.ts
            #EXT-X-KEY:METHOD=AES-128,URI="/key2.bin",IV=0x0000000000000000000000000000002a
            #EXTINF:5,
            seg2.ts
        """.trimIndent()

        val parsed = HlsRemuxCodec.parseMediaPlaylist(playlist, "https://cdn.example/live/index.m3u8")
        assertEquals(5, parsed.targetDurationSec)
        assertEquals(120L, parsed.mediaSequence)
        assertEquals(3, parsed.segments.size)

        assertEquals("NONE", parsed.segments[0].keyMethod)
        assertEquals(120L, parsed.segments[0].sequence)

        assertEquals("AES-128", parsed.segments[1].keyMethod)
        assertEquals("https://cdn.example/key1.bin", parsed.segments[1].keyUri)
        assertEquals(121L, parsed.segments[1].sequence)

        assertEquals("AES-128", parsed.segments[2].keyMethod)
        assertEquals("https://cdn.example/key2.bin", parsed.segments[2].keyUri)
        assertEquals("0000000000000000000000000000002a", parsed.segments[2].ivHex)
        assertEquals("https://cdn.example/live/seg2.ts", parsed.segments[2].uri)
    }

    @Test fun aes128CbcDecryptsMpegTsSegmentUsingSequenceIv() {
        val key = ByteArray(16) { (it + 1).toByte() }
        val seq = 122L
        val iv = HlsRemuxCodec.buildIvBytes(null, seq)
        val plainTs = ByteArray(188) { idx -> if (idx == 0) 0x47.toByte() else (idx % 251).toByte() }

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val encrypted = cipher.doFinal(plainTs)

        val decrypted = HlsRemuxCodec.decryptAes128Cbc(encrypted, key, iv)
        assertArrayEquals(plainTs, decrypted)
        assertEquals(0x47.toByte(), decrypted[0])
    }

    @Test fun lanProxyServesSamsungDlnaHeadersAndDecryptsLiveHlsToTs() {
        val key = ByteArray(16) { (0x10 + it).toByte() }
        val plainTs = ByteArray(188) { idx -> if (idx == 0) 0x47.toByte() else (0x20 + (idx % 90)).toByte() }
        val iv = HlsRemuxCodec.buildIvBytes(null, 5L)
        val encCipher = Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        }
        val encryptedTs = encCipher.doFinal(plainTs)

        val running = AtomicBoolean(true)
        val upstream = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val pool = Executors.newCachedThreadPool()
        val upstreamPort = upstream.localPort

        pool.execute {
            while (running.get() && !upstream.isClosed) {
                val sock = runCatching { upstream.accept() }.getOrNull() ?: break
                pool.execute {
                    sock.use { s ->
                        val firstLine = s.getInputStream().bufferedReader().readLine().orEmpty()
                        val path = firstLine.split(' ').getOrNull(1).orEmpty()
                        val out = s.getOutputStream()
                        when (path) {
                            "/live.m3u8" -> {
                                val body = """
                                    #EXTM3U
                                    #EXT-X-VERSION:3
                                    #EXT-X-TARGETDURATION:4
                                    #EXT-X-MEDIA-SEQUENCE:5
                                    #EXT-X-KEY:METHOD=AES-128,URI="http://127.0.0.1:$upstreamPort/key.bin"
                                    #EXTINF:4,
                                    http://127.0.0.1:$upstreamPort/seg5.ts
                                    #EXT-X-ENDLIST
                                """.trimIndent().toByteArray(Charsets.UTF_8)
                                out.write("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                                out.write(body)
                            }
                            "/key.bin" -> {
                                out.write("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: ${key.size}\r\nConnection: close\r\n\r\n".toByteArray())
                                out.write(key)
                            }
                            "/seg5.ts" -> {
                                out.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\nContent-Length: ${encryptedTs.size}\r\nConnection: close\r\n\r\n".toByteArray())
                                out.write(encryptedTs)
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
                upstreamUrl = "http://127.0.0.1:$upstreamPort/live.m3u8",
                isLive = true,
                localLanIp = "127.0.0.1",
            )
            assertEquals("video/mp2t", target.mimeType)
            assertTrue(target.localUrl.startsWith("http://127.0.0.1:"))
            assertTrue(target.localUrl.endsWith("/live.ts"))

            val client = OkHttpClient()
            // 1. Samsung TV sends HEAD first
            client.newCall(Request.Builder().url(target.localUrl).head().build()).execute().use { head ->
                assertEquals(200, head.code)
                assertEquals("video/mp2t", head.header("Content-Type"))
                assertEquals("Streaming", head.header("transferMode.dlna.org"))
                assertNotNull(head.header("contentFeatures.dlna.org"))
            }

            // 2. Samsung TV sends GET and receives clean decrypted MPEG-TS bytes
            client.newCall(Request.Builder().url(target.localUrl).get().build()).execute().use { get ->
                assertEquals(200, get.code)
                assertEquals("video/mp2t", get.header("Content-Type"))
                val received = get.body.bytes()
                assertArrayEquals(plainTs, received)
            }
        } finally {
            running.set(false)
            proxy.shutdown()
            runCatching { upstream.close() }
            pool.shutdownNow()
        }
    }

    @Test fun soapFaultSummaryExtractsSamsungUpnpCode() {
        val fault = """
            <s:Envelope><s:Body><s:Fault><detail>
              <UPnPError><errorCode>714</errorCode><errorDescription>Illegal MIME-type</errorDescription></UPnPError>
            </detail></s:Fault></s:Body></s:Envelope>
        """.trimIndent()
        assertEquals("UPnP 714 (Illegal MIME-type)", DlnaProtocol.extractSoapFaultSummary(fault))
    }

    @Test fun mpegTsNullBurstAndSegmentDedupHandleCdnRotation() {
        val burst = HlsRemuxCodec.createMpegTsNullBurst(packetCount = 4)
        assertEquals(188 * 4, burst.size)
        for (i in 0 until 4) {
            val base = i * 188
            assertEquals(0x47.toByte(), burst[base])
            assertEquals(0x1F.toByte(), burst[base + 1])
            assertEquals(0xFF.toByte(), burst[base + 2])
            assertEquals(0x10.toByte(), burst[base + 3])
        }

        val k1 = HlsRemuxCodec.segmentDedupKey("http://cdn1.net/81598_760.ts?token=AAA", queryDifferentiated = false)
        val k2 = HlsRemuxCodec.segmentDedupKey("http://cdn2.net/81598_760.ts?token=BBB", queryDifferentiated = false)
        assertEquals(k1, k2)
    }

    @Test fun rewritePlaylistForRelayRewritesSegmentsAndKeys() {
        val manifest = """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="https://cdn.example/key.bin"
            #EXTINF:5,
            seg_100.ts
        """.trimIndent()
        val rewritten = HlsRemuxCodec.rewritePlaylistForRelay(manifest, "https://cdn.example/live/index.m3u8") { remote ->
            "http://192.168.1.5:9000/proxy?url=$remote"
        }
        assertTrue(rewritten.contains("URI=\"http://192.168.1.5:9000/proxy?url=https://cdn.example/key.bin\""))
        assertTrue(rewritten.contains("http://192.168.1.5:9000/proxy?url=https://cdn.example/live/seg_100.ts"))
    }

    @Test fun alignMpegTsStripsLeadingGarbageAndTrailingPartialPacket() {
        val junk = ByteArray(11) { 0x11 }
        val validTs = ByteArray(188 * 2) { idx -> if (idx % 188 == 0) 0x47.toByte() else (idx % 127).toByte() }
        val tail = ByteArray(45) { 0x22 }
        val combined = junk + validTs + tail
        val aligned = HlsRemuxCodec.alignMpegTsPackets(combined)
        assertArrayEquals(validTs, aligned)
    }

    @Test fun parseByteRangeHandlesStandardAndSuffixRanges() {
        val full = HlsRemuxCodec.parseByteRange(null, 1000L)
        assertEquals(ByteRangeSpec(0L, 999L, false), full)
        val openEnd = HlsRemuxCodec.parseByteRange("bytes=250-", 1000L)
        assertEquals(ByteRangeSpec(250L, 999L, true), openEnd)
        val exact = HlsRemuxCodec.parseByteRange("bytes=10-29", 1000L)
        assertEquals(ByteRangeSpec(10L, 29L, true), exact)
        val suffix = HlsRemuxCodec.parseByteRange("bytes=-100", 1000L)
        assertEquals(ByteRangeSpec(900L, 999L, true), suffix)
        assertEquals(null, HlsRemuxCodec.parseByteRange("bytes=2000-", 1000L))
    }

    @Test fun vodProxyStitchesRangeChunksAndRecoversFromUpstreamDrop() {
        val fullMovie = ByteArray(120_000) { idx -> ((idx * 31) and 0xFF).toByte() }
        val running = AtomicBoolean(true)
        val upstream = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val pool = Executors.newCachedThreadPool()
        val upstreamPort = upstream.localPort
        val hitCount = java.util.concurrent.atomic.AtomicInteger(0)

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
                        if (path == "/movie/test.mp4") {
                            // Simulate M81 307 redirect to Cloudflare Worker URL
                            out.write("HTTP/1.1 307 Temporary Redirect\r\nLocation: http://127.0.0.1:$upstreamPort/worker/movie.mp4\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        } else if (path == "/worker/movie.mp4") {
                            val n = hitCount.incrementAndGet()
                            val spec = HlsRemuxCodec.parseByteRange(rangeHeader, fullMovie.size.toLong())!!
                            // Simulate Cloudflare Worker dropping mid-stream on request #2 (returns only first 40KB then closes!)
                            val slice = if (n == 2 && spec.start == 0L && spec.end > 40_000L) {
                                fullMovie.copyOfRange(0, 40_000)
                            } else {
                                fullMovie.copyOfRange(spec.start.toInt(), spec.end.toInt() + 1)
                            }
                            out.write(
                                ("HTTP/1.1 206 Partial Content\r\n" +
                                    "Content-Type: video/mp4\r\n" +
                                    "Accept-Ranges: bytes\r\n" +
                                    "Content-Range: bytes ${spec.start}-${spec.end}/${fullMovie.size}\r\n" +
                                    "Content-Length: ${(spec.end - spec.start) + 1}\r\n" +
                                    "Connection: close\r\n\r\n").toByteArray(),
                            )
                            out.write(slice)
                        }
                        out.flush()
                    }
                }
            }
        }

        val proxy = LanStreamProxyServer(allowLoopbackForTest = true)
        try {
            val target = proxy.prepareStream(
                upstreamUrl = "http://127.0.0.1:$upstreamPort/movie/test.mp4",
                isLive = false,
                localLanIp = "127.0.0.1",
            )
            assertEquals(fullMovie.size.toLong(), target.contentLength)
            val client = OkHttpClient()
            // TV sends ONE unbroken GET for the whole movie; proxy stitches across the upstream mid-stream drop!
            client.newCall(Request.Builder().url(target.localUrl).get().build()).execute().use { resp ->
                assertEquals(200, resp.code)
                assertEquals("video/mp4", resp.header("Content-Type"))
                val received = resp.body.bytes()
                assertArrayEquals(fullMovie, received)
            }
        } finally {
            running.set(false)
            proxy.shutdown()
            runCatching { upstream.close() }
            pool.shutdownNow()
        }
    }
}
