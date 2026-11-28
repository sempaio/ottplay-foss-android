package play.ott.nativeapp.cast

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.X509TrustManager

class CastReviewRegressionTest {
    private val chromecastDevice = CastDevice(
        id = "cast:review",
        friendlyName = "Chromecast Review",
        manufacturer = "Google Cast",
        modelName = "Chromecast",
        host = "192.168.1.50",
        locationUrl = "cast://192.168.1.50:8009",
        avTransportControlUrl = "cast://192.168.1.50:8009/review",
        renderingControlUrl = "cast://192.168.1.50:8009/volume",
        protocol = CastProtocol.CHROMECAST,
        port = 8009,
    )

    @Test fun ssrfBlocksTrailingDotIpv4CompatNat64And6to4() {
        val bypassUrls = listOf(
            "http://localhost./admin",
            "http://sub.localhost./admin",
            "http://127.0.0.1./secret",
            "http://192.168.1.1./router",
            "http://169.254.169.254./latest/meta-data/",
            "http://tv.local./dmr",
            "http://metadata.google.internal./computeMetadata/v1/",
            "http://[::127.0.0.1]/loop",
            "http://[::192.168.1.1]/lan",
            "http://[::169.254.169.254]/meta",
            "http://[::ffff:0:192.168.1.1]/translated",
            "http://[64:ff9b::192.168.1.1]/nat64-lan",
            "http://[64:ff9b::169.254.169.254]/nat64-meta",
            "http://[64:ff9b:1::8.8.8.8]/nat64-local",
            "http://[2002:c0a8:0101::]/6to4-lan",
            "http://[2002:7f00:0001::]/6to4-loop",
        )
        val bypassed = bypassUrls.filter { UpstreamUrlSecurity.isAllowedUpstreamUrl(it) }
        assertTrue("SSRF bypasses found: $bypassed", bypassed.isEmpty())

        // Even with allowLoopbackForTest = true, wrapped IPv6 loopback and private LAN remain blocked!
        assertFalse(UpstreamUrlSecurity.isAllowedUpstreamUrl("http://[::127.0.0.1]/loop", allowLoopbackForTest = true))
        assertFalse(UpstreamUrlSecurity.isAllowedUpstreamUrl("http://[::ffff:127.0.0.1]/loop", allowLoopbackForTest = true))
        assertFalse(UpstreamUrlSecurity.isAllowedUpstreamUrl("http://192.168.1.1./lan", allowLoopbackForTest = true))
        // resolveDns = true must fail closed on unresolvable hostnames
        assertFalse(UpstreamUrlSecurity.isAllowedUpstreamUrl("https://nonexistent.invalid/stream.m3u8", resolveDns = true))
    }

    @Test fun safeSocketFactoryBlocksUnresolvedLoopbackAddress() {
        val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        try {
            val sf = UpstreamUrlSecurity.createSafeSocketFactory(allowLoopbackForTest = false)
            val sock = sf.createSocket()
            try {
                sock.connect(InetSocketAddress.createUnresolved("localhost.", server.localPort), 1000)
                fail("Expected unresolved localhost. connect to be blocked before TCP connect")
            } catch (io: IOException) {
                assertTrue("Expected anti-SSRF block message, got: ${io.message}", io.message?.contains("Blocked unsafe") == true)
            } finally {
                runCatching { sock.close() }
            }
        } finally {
            runCatching { server.close() }
        }
    }

    @Test fun safeSocketFactoryWorksWithRealHttpsTlsAndBlocksHttpsRedirectsToPrivateIps() {
        val tempDir = Files.createTempDirectory("cast-tls-test")
        val keystoreFile = tempDir.resolve("test-server.p12").toFile()
        val password = "changeit".toCharArray()
        val keytool = ProcessBuilder(
            "keytool",
            "-genkeypair",
            "-alias", "test",
            "-keyalg", "RSA",
            "-keysize", "2048",
            "-validity", "1",
            "-storetype", "PKCS12",
            "-keystore", keystoreFile.absolutePath,
            "-storepass", String(password),
            "-dname", "CN=127.0.0.1",
            "-ext", "SAN=IP:127.0.0.1",
        ).redirectErrorStream(true).start()
        val keytoolOut = keytool.inputStream.bufferedReader().readText()
        assertEquals("keytool failed: $keytoolOut", 0, keytool.waitFor())

        val ks = KeyStore.getInstance("PKCS12").apply {
            keystoreFile.inputStream().use { load(it, password) }
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(ks, password)
        }
        val serverSslCtx = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        val serverSocket = serverSslCtx.serverSocketFactory.createServerSocket(
            0,
            8,
            InetAddress.getByName("127.0.0.1"),
        ) as SSLServerSocket
        val tlsPort = serverSocket.localPort
        val running = AtomicBoolean(true)
        val pool = Executors.newCachedThreadPool()

        pool.execute {
            while (running.get() && !serverSocket.isClosed) {
                val sock = runCatching { serverSocket.accept() }.getOrNull() ?: break
                pool.execute {
                    sock.use { s ->
                        val reqLine = s.getInputStream().bufferedReader().readLine().orEmpty()
                        val path = reqLine.split(' ').getOrNull(1).orEmpty()
                        val out = s.getOutputStream()
                        when (path) {
                            "/ok.m3u8" -> {
                                val body = "#EXTM3U\n#EXTINF:4,\nhttps://cdn.example/seg.ts\n".toByteArray()
                                out.write("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                                out.write(body)
                            }
                            "/redir-https-lan" -> {
                                out.write("HTTP/1.1 307 Temporary Redirect\r\nLocation: https://192.168.1.1/secret.m3u8\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                            }
                            "/redir-meta" -> {
                                out.write("HTTP/1.1 302 Found\r\nLocation: http://169.254.169.254/latest/meta-data/\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                            }
                            "/redir-v6compat" -> {
                                out.write("HTTP/1.1 302 Found\r\nLocation: http://[::192.168.1.1]/lan\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                            }
                            else -> out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        }
                        out.flush()
                    }
                }
            }
        }

        val trustTestCert = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val clientSslCtx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustTestCert), null) }
        val blockedReasons = mutableListOf<String>()
        val client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .dns(UpstreamUrlSecurity.createSafeUpstreamDns(allowLoopbackForTest = true) { blockedReasons.add(it) })
            .socketFactory(UpstreamUrlSecurity.createSafeSocketFactory(allowLoopbackForTest = true) { blockedReasons.add(it) })
            .sslSocketFactory(clientSslCtx.socketFactory, trustTestCert)
            .hostnameVerifier { host, _ -> host == "127.0.0.1" }
            .addInterceptor(UpstreamUrlSecurity.createSafeUpstreamInterceptor(allowLoopbackForTest = true) { blockedReasons.add(it) })
            .addNetworkInterceptor(UpstreamUrlSecurity.createSafeUpstreamInterceptor(allowLoopbackForTest = true) { blockedReasons.add(it) })
            .build()

        try {
            // 1. Real HTTPS/TLS handshake + response succeeds through createSafeSocketFactory!
            client.newCall(Request.Builder().url("https://127.0.0.1:$tlsPort/ok.m3u8").get().build()).execute().use { resp ->
                assertEquals(200, resp.code)
                assertTrue(resp.body.string().startsWith("#EXTM3U"))
            }

            // 2. HTTPS 307/302 redirects to private IPv4, metadata IP, and IPv4-compatible IPv6 block fast (< 2s)
            for (path in listOf("/redir-https-lan", "/redir-meta", "/redir-v6compat")) {
                val t0 = System.currentTimeMillis()
                try {
                    client.newCall(Request.Builder().url("https://127.0.0.1:$tlsPort$path").get().build()).execute().use {
                        fail("Expected anti-SSRF block for HTTPS redirect $path")
                    }
                } catch (io: IOException) {
                    val elapsed = System.currentTimeMillis() - t0
                    assertTrue("HTTPS SSRF redirect $path should block fast (<2000ms), took ${elapsed}ms", elapsed < 2000L)
                    assertTrue("Expected Blocked unsafe message for $path, got: ${io.message}", io.message?.contains("Blocked unsafe") == true)
                }
            }
            assertTrue(blockedReasons.size >= 3)
        } finally {
            running.set(false)
            runCatching { serverSocket.close() }
            pool.shutdownNow()
            runCatching { keystoreFile.delete() }
            runCatching { Files.deleteIfExists(tempDir) }
        }
    }

    @Test fun dlnaSinkParserDoesNotConfuseVideoMpeg4WithMpegTs() {
        val caps = DlnaCapabilityResolver.parseSinkProtocolInfo(
            "http-get:*:video/mpeg4:*,http-get:*:application/vnd.apple.mpegurl:*",
        )
        assertTrue(caps.supportsHls)
        assertTrue(caps.supportsMp4)
        assertFalse("video/mpeg4 must NOT set supportsMpegTs=true", caps.supportsMpegTs)
    }

    @Test fun watchdogRecoversIdleWithStaleActivityAndIgnoresFinishedOrCancelled() = runBlocking {
        val nowMs = AtomicLong(100_000L)
        var status: CastMediaStatus? = CastMediaStatus(
            mediaSessionId = 1,
            playerState = "IDLE",
            currentTimeSec = 50.0,
            durationSec = 0.0,
            idleReason = null,
        )
        val staleStats = ProxyRuntimeStats(
            bytesSent = 5_000_000L,
            activeClients = 0,
            lastActivityMs = 50_000L, // 50s stale!
        )
        val reconnects = AtomicInteger(0)
        val controller = UniversalCastController(
            castAttemptExecutor = { _, rawUrl, _, _, _ ->
                reconnects.incrementAndGet()
                CastAttemptResult(success = true, effectiveUrl = rawUrl, isDirectMode = false)
            },
            chromecastStatusHook = { status },
            proxyStatsProvider = { staleStats },
            clockMs = { nowMs.get() },
        )

        // 1. IDLE (no reason) + >25s stale proxy activity MUST trigger 1 recovery on tick 2
        controller.setSessionStateForTest(
            CastSessionState(
                connectedDevice = chromecastDevice,
                castingTitle = "Canal Ao Vivo",
                rawSourceUrl = "https://panel.example/live/u/p/1.m3u8",
                castingDirectMode = false,
                castingIsLive = true,
                isPlayingOnTv = true,
                tvTransportState = "PLAYING",
                proxyStats = staleStats,
            ),
            startedAtMs = 10_000L,
        )
        controller.watchdogTick(null)
        nowMs.addAndGet(12_000L)
        controller.watchdogTick(null)
        assertEquals("IDLE + stale lastActivityMs must recover on tick 2", 1, reconnects.get())

        // 2. IDLE (FINISHED) with unknown duration + stale activity must NEVER auto-reconnect
        reconnects.set(0)
        status = CastMediaStatus(1, "IDLE", 5400.0, 0.0, "FINISHED")
        controller.setSessionStateForTest(
            CastSessionState(
                connectedDevice = chromecastDevice,
                castingTitle = "Filme",
                rawSourceUrl = "https://panel.example/movie/u/p/2.mp4",
                castingDirectMode = false,
                castingIsLive = false,
                isPlayingOnTv = true,
                tvTransportState = "PLAYING",
                proxyStats = staleStats,
            ),
            startedAtMs = 10_000L,
        )
        controller.watchdogTick(null)
        nowMs.addAndGet(12_000L)
        controller.watchdogTick(null)
        assertEquals("IDLE (FINISHED) must NEVER auto-reconnect", 0, reconnects.get())

        // 3. IDLE (CANCELLED) + stale activity must NEVER auto-reconnect
        status = CastMediaStatus(1, "IDLE", 120.0, 0.0, "CANCELLED")
        controller.setSessionStateForTest(
            CastSessionState(
                connectedDevice = chromecastDevice,
                castingTitle = "Canal",
                rawSourceUrl = "https://panel.example/live/u/p/3.m3u8",
                castingDirectMode = false,
                castingIsLive = true,
                isPlayingOnTv = true,
                tvTransportState = "PLAYING",
                proxyStats = staleStats,
            ),
            startedAtMs = 10_000L,
        )
        controller.watchdogTick(null)
        nowMs.addAndGet(12_000L)
        controller.watchdogTick(null)
        assertEquals("IDLE (CANCELLED) must NEVER auto-reconnect", 0, reconnects.get())
    }

    @Test fun manualZapDuringAutoReconnectResetsReconnectBudgetAndStaleReconnectNeverResurrectsDisconnect() = runBlocking {
        val reconnectStarted = CompletableDeferred<Unit>()
        val releaseReconnect = CompletableDeferred<Unit>()
        val controller = UniversalCastController(
            castAttemptExecutor = { _, rawUrl, title, _, _ ->
                if (title == "Canal Antigo") {
                    reconnectStarted.complete(Unit)
                    releaseReconnect.await()
                }
                CastAttemptResult(success = true, effectiveUrl = rawUrl, isDirectMode = false)
            },
        )

        controller.setSessionStateForTest(
            CastSessionState(
                connectedDevice = chromecastDevice,
                castingTitle = "Canal Antigo",
                rawSourceUrl = "https://panel.example/live/u/p/10.m3u8",
                castingDirectMode = false,
                castingIsLive = true,
                isPlayingOnTv = true,
                tvTransportState = "PLAYING",
            ),
        )
        val genBeforeDisconnect = controller.currentGenerationForTest()

        coroutineScope {
            // Start an auto-reconnect that stalls in flight
            val autoJob = async { controller.reconnectCurrent(context = null, isAuto = true, expectedGen = genBeforeDisconnect) }
            reconnectStarted.await()

            // While auto-reconnect is still running, user manually zaps to a brand-new channel
            val manualOk = controller.castMedia(
                context = null,
                device = chromecastDevice,
                rawUrl = "https://panel.example/live/u/p/99.m3u8",
                title = "Canal Novo Manual",
                isLive = true,
            )
            assertTrue(manualOk)
            assertEquals("Canal Novo Manual", controller.state.value.castingTitle)
            assertEquals("Manual zap must reset autoReconnectCount to 0 even during in-flight auto-reconnect", 0, controller.state.value.autoReconnectCount)

            // Let stale auto-reconnect finish -> must be discarded
            releaseReconnect.complete(Unit)
            assertFalse(autoJob.await())
            assertEquals("Canal Novo Manual", controller.state.value.castingTitle)
            assertEquals(0, controller.state.value.autoReconnectCount)
        }

        // Now test that stale reconnectCurrent after disconnect NEVER resurrects a closed session
        val staleGen = controller.currentGenerationForTest()
        controller.disconnect(context = null)
        assertNull(controller.state.value.connectedDevice)
        val resurrected = controller.reconnectCurrent(context = null, isAuto = true, expectedGen = staleGen)
        assertFalse("Stale reconnectCurrent after disconnect must return false", resurrected)
        assertNull("Disconnected session must remain null", controller.state.value.connectedDevice)
    }

    @Test fun lanProxyGenerationFencePreventsStalePrepareStreamFromOverwritingNewerOrDisconnectedStream() {
        val running = AtomicBoolean(true)
        val upstream = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val pool = Executors.newCachedThreadPool()
        val upstreamPort = upstream.localPort
        val slowProbeEntered = CountDownLatch(1)
        val releaseSlowProbe = CountDownLatch(1)

        pool.execute {
            while (running.get() && !upstream.isClosed) {
                val sock = runCatching { upstream.accept() }.getOrNull() ?: break
                pool.execute {
                    sock.use { s ->
                        val reqLine = s.getInputStream().bufferedReader().readLine().orEmpty()
                        val path = reqLine.split(' ').getOrNull(1).orEmpty()
                        if (path == "/slow.mp4") {
                            slowProbeEntered.countDown()
                            releaseSlowProbe.await(5, TimeUnit.SECONDS)
                        }
                        val out = s.getOutputStream()
                        out.write(
                            ("HTTP/1.1 206 Partial Content\r\n" +
                                "Content-Type: video/mp4\r\n" +
                                "Accept-Ranges: bytes\r\n" +
                                "Content-Range: bytes 0-0/10\r\n" +
                                "Content-Length: 1\r\n" +
                                "Connection: close\r\n\r\n0").toByteArray(),
                        )
                        out.flush()
                    }
                }
            }
        }

        val proxy = LanStreamProxyServer(allowLoopbackForTest = true)
        try {
            // Gen 1 enters slow probe
            val gen1Future = pool.submit<ProxiedCastTarget> {
                proxy.prepareStream(
                    upstreamUrl = "http://127.0.0.1:$upstreamPort/slow.mp4",
                    isLive = false,
                    localLanIp = "127.0.0.1",
                    sessionGeneration = 1L,
                )
            }
            assertTrue("Slow gen1 probe did not start in time", slowProbeEntered.await(3, TimeUnit.SECONDS))

            // Gen 2 zaps ahead and installs cleanly
            proxy.advanceMinimumGeneration(2L)
            val gen2Target = proxy.prepareStream(
                upstreamUrl = "http://127.0.0.1:$upstreamPort/fast.mp4",
                isLive = false,
                localLanIp = "127.0.0.1",
                sessionGeneration = 2L,
            )
            assertFalse(gen2Target.wasSuperseded)
            val gen2Token = proxy.activeStreamTokenForTest()
            assertNotNull(gen2Token)
            assertEquals(2L, proxy.activeStreamGenerationForTest())

            // Release slow gen 1 probe -> MUST be discarded as superseded and MUST NOT overwrite gen 2!
            releaseSlowProbe.countDown()
            val gen1Target = gen1Future.get(5, TimeUnit.SECONDS)
            assertTrue("Stale gen1 prepareStream must report wasSuperseded=true", gen1Target.wasSuperseded)
            assertEquals("Active stream token must still belong to gen2", gen2Token, proxy.activeStreamTokenForTest())
            assertEquals(2L, proxy.activeStreamGenerationForTest())

            // Stale gen 1 clearStream MUST NOT wipe gen 2!
            proxy.clearStream(expectedGeneration = 1L)
            assertEquals(gen2Token, proxy.activeStreamTokenForTest())

            // Disconnect (gen 3) clears gen 2 cleanly and blocks stale gen 2 re-prepare
            proxy.invalidateAndClearUpTo(3L)
            assertNull(proxy.activeStreamTokenForTest())
            val staleAfterDisconnect = proxy.prepareStream(
                upstreamUrl = "http://127.0.0.1:$upstreamPort/fast.mp4",
                isLive = false,
                localLanIp = "127.0.0.1",
                sessionGeneration = 2L,
            )
            assertTrue(staleAfterDisconnect.wasSuperseded)
            assertNull("Stale prepareStream after disconnect must never leak an ActiveStream", proxy.activeStreamTokenForTest())
        } finally {
            releaseSlowProbe.countDown()
            running.set(false)
            proxy.shutdown()
            runCatching { upstream.close() }
            pool.shutdownNow()
        }
    }

    @Test fun steadyStateOneSegmentPerPollTwoConsecutiveFailuresTriggersManifestRenewal() {
        val ts0 = ByteArray(188 * 2) { idx -> if (idx % 188 == 0) 0x47.toByte() else 0x10.toByte() }
        val tsFresh = ByteArray(188 * 2) { idx -> if (idx % 188 == 0) 0x47.toByte() else 0x77.toByte() }
        val running = AtomicBoolean(true)
        val upstream = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val pool = Executors.newCachedThreadPool()
        val upstreamPort = upstream.localPort
        val masterHits = AtomicInteger(0)
        val variantOldHits = AtomicInteger(0)

        pool.execute {
            while (running.get() && !upstream.isClosed) {
                val sock = runCatching { upstream.accept() }.getOrNull() ?: break
                pool.execute {
                    sock.use { s ->
                        val reqLine = s.getInputStream().bufferedReader().readLine().orEmpty()
                        val fullPath = reqLine.split(' ').getOrNull(1).orEmpty()
                        val out = s.getOutputStream()
                        when (fullPath) {
                            "/live-hls/u/p/777.m3u8" -> {
                                val hit = masterHits.incrementAndGet()
                                val variantPath = if (hit == 1) "/variant-old.m3u8" else "/variant-fresh.m3u8"
                                val body = """
                                    #EXTM3U
                                    #EXT-X-STREAM-INF:BANDWIDTH=2000000
                                    http://127.0.0.1:$upstreamPort$variantPath
                                """.trimIndent().toByteArray()
                                out.write("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                                out.write(body)
                            }
                            "/variant-old.m3u8" -> {
                                val h = variantOldHits.incrementAndGet()
                                val body = when (h) {
                                    // Poll 1 (prefetch): 1 healthy segment
                                    1 -> """
                                        #EXTM3U
                                        #EXT-X-TARGETDURATION:1
                                        #EXT-X-MEDIA-SEQUENCE:100
                                        #EXTINF:1,
                                        http://127.0.0.1:$upstreamPort/seg100.ts
                                    """.trimIndent()
                                    // Poll 2 (producer poll 1): 1 NEW expired segment (seg101)
                                    2 -> """
                                        #EXTM3U
                                        #EXT-X-TARGETDURATION:1
                                        #EXT-X-MEDIA-SEQUENCE:100
                                        #EXTINF:1,
                                        http://127.0.0.1:$upstreamPort/seg100.ts
                                        #EXTINF:1,
                                        http://127.0.0.1:$upstreamPort/seg101.ts?token=EXPIRED
                                    """.trimIndent()
                                    // Poll 3+ (producer poll 2): 1 MORE expired segment (seg102) -> MUST renew master!
                                    else -> """
                                        #EXTM3U
                                        #EXT-X-TARGETDURATION:1
                                        #EXT-X-MEDIA-SEQUENCE:101
                                        #EXTINF:1,
                                        http://127.0.0.1:$upstreamPort/seg101.ts?token=EXPIRED
                                        #EXTINF:1,
                                        http://127.0.0.1:$upstreamPort/seg102.ts?token=EXPIRED
                                    """.trimIndent()
                                }.toByteArray()
                                out.write("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                                out.write(body)
                            }
                            "/variant-fresh.m3u8" -> {
                                val body = """
                                    #EXTM3U
                                    #EXT-X-TARGETDURATION:1
                                    #EXT-X-MEDIA-SEQUENCE:101
                                    #EXTINF:1,
                                    http://127.0.0.1:$upstreamPort/seg101.ts?token=FRESH
                                    #EXT-X-ENDLIST
                                """.trimIndent().toByteArray()
                                out.write("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                                out.write(body)
                            }
                            "/seg100.ts" -> {
                                out.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\nContent-Length: ${ts0.size}\r\nConnection: close\r\n\r\n".toByteArray())
                                out.write(ts0)
                            }
                            "/seg101.ts?token=FRESH" -> {
                                out.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\nContent-Length: ${tsFresh.size}\r\nConnection: close\r\n\r\n".toByteArray())
                                out.write(tsFresh)
                            }
                            else -> {
                                out.write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                            }
                        }
                        out.flush()
                    }
                }
            }
        }

        val proxy = LanStreamProxyServer(allowLoopbackForTest = true)
        val http = OkHttpClient.Builder()
            .callTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
        try {
            val target = proxy.prepareStream(
                upstreamUrl = "http://127.0.0.1:$upstreamPort/live-hls/u/p/777.m3u8",
                isLive = true,
                localLanIp = "127.0.0.1",
            )
            val bytes = http.newCall(Request.Builder().url(target.localUrl).get().build()).execute().use { resp ->
                assertEquals(200, resp.code)
                resp.body.bytes()
            }
            assertTrue("Master playlist should have been renewed after 2 consecutive 1-seg-per-poll failures (hits=${masterHits.get()})", masterHits.get() >= 2)
            assertArrayEquals(ts0 + tsFresh, bytes)
        } finally {
            running.set(false)
            proxy.shutdown()
            runCatching { upstream.close() }
            pool.shutdownNow()
        }
    }

    @Test fun vodRejectsCaseInsensitiveMisaligned206AndFmp4TsStreamExitsCleanly() {
        val running = AtomicBoolean(true)
        val upstream = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val pool = Executors.newCachedThreadPool()
        val upstreamPort = upstream.localPort
        val movieBytes = ByteArray(4096) { (it and 0xFF).toByte() }
        val fmp4Segment = byteArrayOf(0x00, 0x00, 0x00, 0x18, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte()) + ByteArray(200)

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
                            "/misaligned.mp4" -> {
                                if (rangeHeader == "bytes=0-0") {
                                    out.write("HTTP/1.1 206 Partial Content\r\nContent-Type: video/mp4\r\nAccept-Ranges: bytes\r\nContent-Range: bytes 0-0/${movieBytes.size}\r\nContent-Length: 1\r\nConnection: close\r\n\r\n0".toByteArray())
                                } else {
                                    // Returns uppercase BYTES with wrong start offset 500 instead of 2000!
                                    val badSlice = movieBytes.copyOfRange(500, 1000)
                                    out.write("HTTP/1.1 206 Partial Content\r\nContent-Type: video/mp4\r\nContent-Range: BYTES 500-999/${movieBytes.size}\r\nContent-Length: ${badSlice.size}\r\nConnection: close\r\n\r\n".toByteArray())
                                    out.write(badSlice)
                                }
                            }
                            "/fmp4.m3u8" -> {
                                val body = """
                                    #EXTM3U
                                    #EXT-X-VERSION:7
                                    #EXT-X-TARGETDURATION:4
                                    #EXT-X-MAP:URI="http://127.0.0.1:$upstreamPort/init.mp4"
                                    #EXTINF:4,
                                    http://127.0.0.1:$upstreamPort/chunk1.m4s
                                """.trimIndent().toByteArray()
                                out.write("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                                out.write(body)
                            }
                            "/chunk1.m4s" -> {
                                out.write("HTTP/1.1 200 OK\r\nContent-Type: video/iso.segment\r\nContent-Length: ${fmp4Segment.size}\r\nConnection: close\r\n\r\n".toByteArray())
                                out.write(fmp4Segment)
                            }
                            else -> out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        }
                        out.flush()
                    }
                }
            }
        }

        val proxy = LanStreamProxyServer(allowLoopbackForTest = true)
        val http = OkHttpClient.Builder().callTimeout(6, TimeUnit.SECONDS).build()
        try {
            // 1. Misaligned uppercase BYTES 206 must be rejected (never emit corrupt offset-500 bytes!)
            val vodTarget = proxy.prepareStream("http://127.0.0.1:$upstreamPort/misaligned.mp4", isLive = false, localLanIp = "127.0.0.1")
            val vodResult = runCatching {
                http.newCall(Request.Builder().url(vodTarget.localUrl).header("Range", "bytes=2000-2499").get().build()).execute().use { it.body.bytes() }
            }.getOrNull()
            if (vodResult != null) {
                assertEquals(0, vodResult.size)
            }
            assertTrue(
                "Expected Content-Range mismatch diagnostic, got: ${proxy.currentStats().lastWriteError}",
                proxy.currentStats().lastWriteError.contains("desalinhado", ignoreCase = true),
            )

            // 2. fMP4 (#EXT-X-MAP) live TS stream exits cleanly without hanging indefinitely
            val fmp4Target = proxy.prepareStream("http://127.0.0.1:$upstreamPort/fmp4.m3u8", isLive = true, localLanIp = "127.0.0.1")
            val t0 = System.currentTimeMillis()
            val bytes = http.newCall(Request.Builder().url(fmp4Target.localUrl).get().build()).execute().use { resp ->
                assertEquals(200, resp.code)
                resp.body.bytes()
            }
            val elapsed = System.currentTimeMillis() - t0
            assertEquals(0, bytes.size)
            assertTrue("fMP4 live TS should exit promptly (<3000ms), took ${elapsed}ms", elapsed < 3000L)
            assertTrue(proxy.currentStats().lastWriteError.contains("#EXT-X-MAP"))
        } finally {
            running.set(false)
            proxy.shutdown()
            runCatching { upstream.close() }
            pool.shutdownNow()
        }
    }
}
