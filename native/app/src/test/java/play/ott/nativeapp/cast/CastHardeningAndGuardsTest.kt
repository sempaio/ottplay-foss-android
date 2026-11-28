package play.ott.nativeapp.cast

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class CastHardeningAndGuardsTest {
    private val chromecastDevice = CastDevice(
        id = "cast:sala",
        friendlyName = "Chromecast Sala",
        manufacturer = "Google Cast",
        modelName = "Chromecast",
        host = "192.168.1.50",
        locationUrl = "cast://192.168.1.50:8009",
        avTransportControlUrl = "cast://192.168.1.50:8009/sala",
        renderingControlUrl = "cast://192.168.1.50:8009/volume",
        protocol = CastProtocol.CHROMECAST,
        port = 8009,
    )

    private val samsungDevice = CastDevice(
        id = "uuid:samsung-sala",
        friendlyName = "Samsung QLED",
        manufacturer = "Samsung Electronics",
        modelName = "QN55Q60",
        host = "192.168.1.60",
        locationUrl = "http://192.168.1.60:9197/dmr",
        avTransportControlUrl = "http://192.168.1.60:9197/upnp/control/AVTransport1",
        renderingControlUrl = "http://192.168.1.60:9197/upnp/control/RenderingControl1",
        protocol = CastProtocol.DLNA,
        port = 9197,
    )

    @Test fun sessionGenerationIsStrictlyMonotonicAndRejectsZeroOrStale() {
        val gen = CastSessionGeneration()
        assertEquals(0L, gen.current())
        assertFalse(gen.isCurrent(0L))
        assertFalse(gen.isCurrent(-1L))

        val g1 = gen.next()
        assertEquals(1L, g1)
        assertTrue(gen.isCurrent(g1))

        val g2 = gen.advance()
        assertEquals(2L, g2)
        assertFalse(gen.isCurrent(g1))
        assertTrue(gen.isCurrent(g2))
    }

    @Test fun twentyRapidChannelZapsEnsureStaleAttemptsNeverOverwriteFinalChannel() = runBlocking {
        val totalZaps = 20
        val gates = List(totalZaps) { CompletableDeferred<Unit>() }
        val started = List(totalZaps) { CompletableDeferred<Unit>() }

        val controller = UniversalCastController(
            castAttemptExecutor = { _, rawUrl, title, _, _ ->
                val idx = title.substringAfter('#').toInt() - 1
                started[idx].complete(Unit)
                gates[idx].await()
                CastAttemptResult(
                    success = true,
                    effectiveUrl = "http://192.168.1.10:5555/ch/${idx + 1}",
                    initialVolume = 40 + (idx % 30),
                    isDirectMode = (idx % 2 == 0),
                )
            },
        )

        coroutineScope {
            val jobs = (0 until totalZaps).map { idx ->
                val deferred = async {
                    controller.castMedia(
                        context = null,
                        device = samsungDevice,
                        rawUrl = "https://panel.example/live/u/p/${1000 + idx}.ts",
                        title = "Canal #${idx + 1}",
                        isLive = true,
                    )
                }
                // Wait until each zap registers its generation in order before firing the next zap
                started[idx].await()
                deferred
            }

            // Finish the LAST zap (#20) FIRST, then unleash stale zaps #19 down to #1!
            gates[totalZaps - 1].complete(Unit)
            val lastResult = jobs[totalZaps - 1].await()
            assertTrue("Final zap #20 must succeed", lastResult)
            assertEquals("Canal #$totalZaps", controller.state.value.castingTitle)

            // Now release all 19 older in-flight zaps in reverse order (worst-case race!)
            for (i in (totalZaps - 2) downTo 0) {
                gates[i].complete(Unit)
            }
            val results = jobs.awaitAll()

            // Every stale zap must return false and NEVER overwrite Canal #20
            for (i in 0 until totalZaps - 1) {
                assertFalse("Stale zap #${i + 1} must be discarded", results[i])
            }
            val finalState = controller.state.value
            assertEquals(samsungDevice, finalState.connectedDevice)
            assertEquals("Canal #$totalZaps", finalState.castingTitle)
            assertEquals("http://192.168.1.10:5555/ch/$totalZaps", finalState.castingUrl)
            assertEquals("https://panel.example/live/u/p/${1000 + totalZaps - 1}.ts", finalState.rawSourceUrl)
            assertNull(finalState.busyAction)
            assertTrue(finalState.isPlayingOnTv)
        }
    }

    @Test fun concurrentDisconnectDuringInFlightCastMediaPreventsZombieSession() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val stopCalls = mutableListOf<Boolean>()

        val controller = UniversalCastController(
            chromecastDisconnectHook = { stopApp -> stopCalls.add(stopApp) },
            castAttemptExecutor = { _, _, _, _, _ ->
                started.complete(Unit)
                gate.await()
                CastAttemptResult(
                    success = true,
                    effectiveUrl = "https://cdn.example/late.m3u8",
                    isDirectMode = true,
                )
            },
        )

        coroutineScope {
            val castJob = async {
                controller.castMedia(
                    context = null,
                    device = chromecastDevice,
                    rawUrl = "https://panel.example/live/u/p/77.m3u8",
                    title = "Canal Lento",
                    isLive = true,
                )
            }
            started.await()

            // User cancels/disconnects while castMedia is still connecting
            controller.disconnect(context = null)
            assertNull(controller.state.value.connectedDevice)
            assertNull(controller.state.value.busyAction)

            // Slow castMedia finally resolves AFTER disconnect
            gate.complete(Unit)
            val result = castJob.await()

            assertFalse("Stale castMedia after disconnect must return false", result)
            assertNull("Disconnected state must NEVER be overwritten", controller.state.value.connectedDevice)
            assertEquals("", controller.state.value.castingTitle)
            assertFalse(controller.state.value.isPlayingOnTv)
            assertNull(controller.state.value.busyAction)
        }
    }

    @Test fun concurrentDetachKeepPlayingDuringInFlightZapInvalidatesStaleZapAndKeepsTvPlaying() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val stopAppCalls = mutableListOf<Boolean>()

        val controller = UniversalCastController(
            chromecastDisconnectHook = { stopApp -> stopAppCalls.add(stopApp) },
            castAttemptExecutor = { _, _, _, _, _ ->
                started.complete(Unit)
                gate.await()
                CastAttemptResult(
                    success = true,
                    effectiveUrl = "https://cdn.example/next_channel.m3u8",
                    isDirectMode = false,
                )
            },
        )

        // Start from an active Direct Cloud session on Chromecast
        controller.setSessionStateForTest(
            CastSessionState(
                connectedDevice = chromecastDevice,
                castingTitle = "Pluto TV Direto",
                castingDirectMode = true,
                castingIsLive = true,
                isPlayingOnTv = true,
                tvTransportState = "PLAYING",
            ),
        )

        coroutineScope {
            val slowZap = async {
                controller.castMedia(
                    context = null,
                    device = chromecastDevice,
                    rawUrl = "https://panel.example/live/u/p/88.m3u8",
                    title = "Novo Canal Lento",
                    isLive = true,
                )
            }
            started.await()

            // User taps "Soltar celular" while the zap is still in-flight
            val detached = controller.detachKeepPlaying(context = null)
            assertTrue(detached)
            assertEquals(listOf(false), stopAppCalls)
            assertNull(controller.state.value.connectedDevice)

            // Now let the stale zap complete
            gate.complete(Unit)
            assertFalse("Stale zap after detachKeepPlaying must be discarded", slowZap.await())
            assertNull(controller.state.value.connectedDevice)
            assertFalse(controller.state.value.isPlayingOnTv)
            assertFalse(controller.state.value.castingDirectMode)
        }
    }

    @Test fun hundredConsecutiveSessionStartStopCyclesAreIdempotentWithZeroLeakedSockets() = runBlocking {
        val stopCalls = AtomicInteger(0)
        val controller = UniversalCastController(
            dlnaStopHook = { stopCalls.incrementAndGet() },
            castAttemptExecutor = { _, rawUrl, _, _, _ ->
                CastAttemptResult(
                    success = true,
                    effectiveUrl = rawUrl,
                    initialVolume = 50,
                    isDirectMode = false,
                )
            },
        )

        for (i in 1..100) {
            val ok = controller.castMedia(
                context = null,
                device = samsungDevice,
                rawUrl = "https://cdn.example/live/$i.m3u8",
                title = "Ciclo #$i",
                isLive = true,
            )
            assertTrue("Cycle $i castMedia failed", ok)
            assertEquals("Ciclo #$i", controller.state.value.castingTitle)

            // Call disconnect TWICE per cycle to prove strict idempotency
            controller.disconnect(context = null)
            controller.disconnect(context = null)
            assertNull(controller.state.value.connectedDevice)
            assertFalse(controller.state.value.isPlayingOnTv)
            assertNull(controller.state.value.busyAction)
        }
        // Only the first disconnect per cycle had an active connectedDevice -> exactly 100 DLNA stops
        assertEquals(100, stopCalls.get())
        assertEquals(300L, controller.currentGenerationForTest())

        // Also stress-test LanStreamProxyServer across 100 prepare/clear (+ periodic shutdown/restart) cycles
        val running = AtomicBoolean(true)
        val upstream = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val pool = Executors.newCachedThreadPool()
        val upstreamPort = upstream.localPort
        pool.execute {
            while (running.get() && !upstream.isClosed) {
                val sock = runCatching { upstream.accept() }.getOrNull() ?: break
                pool.execute {
                    sock.use { s ->
                        s.getInputStream().bufferedReader().readLine()
                        val body = "0123456789".toByteArray()
                        s.getOutputStream().write(
                            ("HTTP/1.1 206 Partial Content\r\n" +
                                "Content-Type: video/mp4\r\n" +
                                "Accept-Ranges: bytes\r\n" +
                                "Content-Range: bytes 0-0/10\r\n" +
                                "Content-Length: 1\r\n" +
                                "Connection: close\r\n\r\n0").toByteArray(),
                        )
                        s.getOutputStream().flush()
                    }
                }
            }
        }

        val proxy = LanStreamProxyServer(allowLoopbackForTest = true)
        try {
            for (cycle in 1..100) {
                val target = proxy.prepareStream(
                    upstreamUrl = "http://127.0.0.1:$upstreamPort/vod/$cycle.mp4",
                    isLive = false,
                    localLanIp = "127.0.0.1",
                )
                assertTrue(target.localUrl.startsWith("http://127.0.0.1:"))
                proxy.clearStream()
                assertEquals("Zero active clients expected after clearStream on cycle $cycle", 0, proxy.currentStats().activeClients)
                if (cycle % 25 == 0) {
                    proxy.shutdown()
                    proxy.shutdown() // idempotent shutdown
                }
            }
        } finally {
            running.set(false)
            proxy.shutdown()
            runCatching { upstream.close() }
            pool.shutdownNow()
        }
    }

    @Test fun chromecastBridgeWatchdogTriggersAtMostOneRecoveryAndRespectsPause() = runBlocking {
        val nowMs = AtomicLong(100_000L)
        var statusToReturn: CastMediaStatus? = CastMediaStatus(
            mediaSessionId = 7,
            playerState = "IDLE",
            currentTimeSec = 60.0,
            durationSec = 0.0,
            idleReason = "ERROR",
        )
        var proxyStats = ProxyRuntimeStats(
            bytesSent = 4_500_000L,
            activeClients = 0,
            lastActivityMs = 60_000L,
        )
        val reconnectCalls = AtomicInteger(0)

        val controller = UniversalCastController(
            castAttemptExecutor = { _, rawUrl, _, _, _ ->
                reconnectCalls.incrementAndGet()
                CastAttemptResult(success = true, effectiveUrl = rawUrl, isDirectMode = false)
            },
            chromecastStatusHook = { statusToReturn },
            proxyStatsProvider = { proxyStats },
            clockMs = { nowMs.get() },
        )

        // 1. User-paused Chromecast session must NEVER auto-reconnect
        controller.setSessionStateForTest(
            CastSessionState(
                connectedDevice = chromecastDevice,
                castingTitle = "Globo HD",
                rawSourceUrl = "https://panel.example/live/u/p/10.m3u8",
                castingDirectMode = false,
                castingIsLive = true,
                isPlayingOnTv = false,
                tvTransportState = "PAUSED",
                proxyStats = proxyStats,
            ),
            startedAtMs = 10_000L,
        )
        controller.watchdogTick(context = null)
        controller.watchdogTick(context = null)
        assertEquals(0, reconnectCalls.get())

        // 2. Active stable Chromecast Bridge session (>45s, >=2MB) that drops to IDLE (ERROR)
        //    must recover after 2 consecutive error ticks, and NEVER loop a second time!
        controller.setSessionStateForTest(
            CastSessionState(
                connectedDevice = chromecastDevice,
                castingTitle = "Globo HD",
                rawSourceUrl = "https://panel.example/live/u/p/10.m3u8",
                castingDirectMode = false,
                castingIsLive = true,
                isPlayingOnTv = true,
                tvTransportState = "PLAYING",
                proxyStats = proxyStats,
            ),
            startedAtMs = 10_000L, // 90s elapsed
        )
        // Tick 1: detects IDLE (ERROR) (1/2)
        controller.watchdogTick(context = null)
        assertEquals(0, reconnectCalls.get())
        assertEquals("IDLE (ERROR)", controller.state.value.tvTransportState)

        // Tick 2: persistent IDLE (ERROR) crosses 2-tick threshold -> triggers 1 quiet reconnect
        nowMs.addAndGet(12_000L)
        controller.watchdogTick(context = null)
        assertEquals("Watchdog must trigger exactly 1 quiet reconnect on persistent IDLE (ERROR)", 1, reconnectCalls.get())
        assertEquals(1, controller.state.value.autoReconnectCount)

        // Subsequent drops must NOT auto-reconnect again (autoReconnectCount cap = 1)
        nowMs.addAndGet(60_000L)
        controller.watchdogTick(context = null)
        nowMs.addAndGet(12_000L)
        controller.watchdogTick(context = null)
        assertEquals("Watchdog must cap auto-reconnect at 1 attempt", 1, reconnectCalls.get())
    }

    @Test fun upstreamUrlSecurityBlocksLoopbackPrivateLinkLocalUlaObfuscatedAndNonHttpSchemes() {
        // Blocked by default (allowLoopbackForTest = false)
        val blockedUrls = listOf(
            "http://127.0.0.1/live.m3u8",
            "http://127.255.255.1:8080/a.ts",
            "http://localhost/admin",
            "http://sub.localhost/test",
            "http://[::1]/live.m3u8",
            "http://[0:0:0:0:0:0:0:1]/live.m3u8",
            "http://[0::1]/live.m3u8",
            "http://[::ffff:127.0.0.1]/live.m3u8",
            "http://[::ffff:172.16.0.5]/secret",
            "http://[::ffff:10.0.0.1]/secret",
            "http://[::ffff:192.168.1.1]/router",
            "http://[::ffff:7f00:1]/loop",
            "http://10.0.0.1/router",
            "http://172.16.0.1/private",
            "http://172.31.255.254/private",
            "http://192.168.0.1/admin",
            "http://169.254.169.254/latest/meta-data/",
            "http://0.0.0.0/zero",
            "http://224.0.0.1/multicast",
            "http://[fc00::1]/ula",
            "http://[fd12:3456:789a::1]/ula",
            "http://[fe80::1]/linklocal",
            "http://[ff02::1]/multicast6",
            "http://tv.local/dmr",
            "http://metadata.google.internal/computeMetadata/v1/",
            // Obfuscated integer / short / octal / hex IP forms
            "http://2130706433/int-loopback",
            "http://127.1/short-loopback",
            "http://0177.0.0.1/octal-loopback",
            "http://0x7f000001/hex-loopback",
            // Non-HTTP schemes
            "file:///etc/passwd",
            "ftp://cdn.example.com/movie.mp4",
            "javascript:alert(1)",
            "gopher://127.0.0.1:6379/_INFO",
            "",
            "not-a-url",
        )
        for (url in blockedUrls) {
            assertFalse("Expected URL to be blocked: $url", UpstreamUrlSecurity.isAllowedUpstreamUrl(url))
        }

        // Loopback allowed ONLY when allowLoopbackForTest = true (private RFC1918 still blocked!)
        assertTrue(UpstreamUrlSecurity.isAllowedUpstreamUrl("http://127.0.0.1:8080/live.m3u8", allowLoopbackForTest = true))
        assertTrue(UpstreamUrlSecurity.isAllowedUpstreamUrl("http://[::1]:8080/live.m3u8", allowLoopbackForTest = true))
        assertFalse(UpstreamUrlSecurity.isAllowedUpstreamUrl("http://192.168.1.1/admin", allowLoopbackForTest = true))
        assertFalse(UpstreamUrlSecurity.isAllowedUpstreamUrl("http://169.254.169.254/meta", allowLoopbackForTest = true))

        // Public CDN & Tailscale CGNAT (100.64.0.0/10) remain allowed
        assertTrue(UpstreamUrlSecurity.isAllowedUpstreamUrl("https://panel.taila5c5c2.ts.net/live-hls/u/p/1.m3u8"))
        assertTrue(UpstreamUrlSecurity.isAllowedUpstreamUrl("http://172.32.0.1/public-cdn/seg.ts"))
        assertTrue(UpstreamUrlSecurity.isAllowedUpstreamUrl("http://8.8.8.8/stream.mp4"))
        assertTrue(UpstreamUrlSecurity.isAllowedUpstreamUrl("http://100.64.10.20:8080/live.m3u8"))
    }

    @Test fun readBoundedBytesEnforcesExactLimitAndRejectsOverflow() {
        val exactPayload = ByteArray(1024) { (it and 0xFF).toByte() }
        val readExact = UpstreamUrlSecurity.readBoundedBytes(ByteArrayInputStream(exactPayload), maxBytes = 1024)
        assertArrayEquals(exactPayload, readExact)

        val emptyRead = UpstreamUrlSecurity.readBoundedBytes(ByteArrayInputStream(ByteArray(0)), maxBytes = 64)
        assertEquals(0, emptyRead.size)

        val overflowPayload = ByteArray(1025) { 0x42 }
        try {
            UpstreamUrlSecurity.readBoundedBytes(ByteArrayInputStream(overflowPayload), maxBytes = 1024)
            fail("Expected IOException when payload exceeds maxBytes")
        } catch (io: IOException) {
            assertNotNull(io.message)
            assertTrue("Message should report readable byte limit: ${io.message}", io.message!!.contains("1024 bytes"))
        }

        try {
            UpstreamUrlSecurity.readBoundedBytes(ByteArrayInputStream(ByteArray(1)), maxBytes = 0)
            fail("Expected IllegalArgumentException for non-positive maxBytes")
        } catch (_: IllegalArgumentException) {
            // Expected
        }
    }

    @Test fun castFailureClassifierCoversAllCategoriesAndAvoidsSubstringFalsePositives() {
        val upnp701 = CastFailureClassifier.classify("LG-TS: Play UPnP 701 (Transition not available)")
        assertEquals(CastFailureCategory.UPNP_TRANSITION_701, upnp701.category)
        assertEquals("UPNP_701", upnp701.tag)

        val token403 = CastFailureClassifier.classify("Upstream returned HTTP 403 Forbidden")
        assertEquals(CastFailureCategory.ORIGIN_OR_TOKEN, token403.category)
        assertEquals("ORIGEM_TOKEN", token403.tag)

        val token404 = CastFailureClassifier.classify("HTTP 404 Not Found")
        assertEquals(CastFailureCategory.ORIGIN_OR_TOKEN, token404.category)

        val tokenByCode = CastFailureClassifier.classify("Falha genérica", httpCode = 410)
        assertEquals(CastFailureCategory.ORIGIN_OR_TOKEN, tokenByCode.category)

        val cors = CastFailureClassifier.classify("Origem HTTP sem HTTPS ou CORS bloqueado")
        assertEquals(CastFailureCategory.CORS_OR_MIXED_CONTENT, cors.category)
        assertEquals("CORS_HTTP", cors.tag)

        // "4030ms" must NOT trigger 403 ORIGIN_OR_TOKEN false positive!
        val timeout = CastFailureClassifier.classify("Socket timed out after 4030ms on port 7010")
        assertEquals(CastFailureCategory.NETWORK_OR_TIMEOUT, timeout.category)
        assertEquals("REDE_TIMEOUT", timeout.tag)

        val unsupportedCodec = CastFailureClassifier.classify("LOAD_FAILED • MEDIA_SRC_NOT_SUPPORTED • code=104")
        assertEquals(CastFailureCategory.RECEIVER_UNSUPPORTED, unsupportedCodec.category)
        assertEquals("CODEC_PERFIL", unsupportedCodec.tag)

        val sampleAes = CastFailureClassifier.classify("HLS usa criptografia não suportada (SAMPLE-AES)")
        assertEquals(CastFailureCategory.RECEIVER_UNSUPPORTED, sampleAes.category)

        val upnp714 = CastFailureClassifier.classify("UPnP 714 (Illegal MIME-type)")
        assertEquals(CastFailureCategory.RECEIVER_UNSUPPORTED, upnp714.category)

        val unknown = CastFailureClassifier.classify("Erro completamente inédito")
        assertEquals(CastFailureCategory.UNKNOWN, unknown.category)
        assertEquals("ERRO_GERAL", unknown.tag)
    }
}
