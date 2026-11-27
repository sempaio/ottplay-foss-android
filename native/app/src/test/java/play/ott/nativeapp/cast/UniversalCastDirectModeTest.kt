package play.ott.nativeapp.cast

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UniversalCastDirectModeTest {
    private val chromecastDevice = CastDevice(
        id = "cast:livingroom",
        friendlyName = "Chromecast Sala",
        manufacturer = "Google Cast",
        modelName = "Chromecast",
        host = "192.168.1.50",
        locationUrl = "cast://192.168.1.50:8009",
        avTransportControlUrl = "cast://192.168.1.50:8009/livingroom",
        renderingControlUrl = "cast://192.168.1.50:8009/volume",
        protocol = CastProtocol.CHROMECAST,
        port = 8009,
    )

    private val samsungDlnaDevice = CastDevice(
        id = "dlna:samsung",
        friendlyName = "Samsung QLED",
        manufacturer = "Samsung Electronics",
        modelName = "QN55",
        host = "192.168.1.60",
        locationUrl = "http://192.168.1.60:9197/dmr",
        avTransportControlUrl = "http://192.168.1.60:9197/upnp/control/AVTransport1",
        renderingControlUrl = "http://192.168.1.60:9197/upnp/control/RenderingControl1",
        protocol = CastProtocol.DLNA,
        port = 9197,
    )

    @Test fun detachKeepPlayingOnChromecastDirectModeDoesNotStopReceiver() = runBlocking {
        val stopAppCalls = mutableListOf<Boolean>()
        val controller = UniversalCastController(
            chromecastDisconnectHook = { stopApp -> stopAppCalls.add(stopApp) },
        )
        controller.setSessionStateForTest(
            CastSessionState(
                connectedDevice = chromecastDevice,
                castingTitle = "Pluto TV Filmes",
                castingDirectMode = true,
                castingIsLive = true,
                isPlayingOnTv = true,
                tvTransportState = "PLAYING",
            ),
        )

        val detached = controller.detachKeepPlaying(context = null)

        assertTrue(detached)
        assertEquals(listOf(false), stopAppCalls)
        assertNull(controller.state.value.connectedDevice)
        assertFalse(controller.state.value.castingDirectMode)
        assertTrue(controller.state.value.diagnosticLog.any { it.contains("Celular liberado") })
    }

    @Test fun detachKeepPlayingOnDlnaDirectVodNeverSendsUpnpStop() = runBlocking {
        val dlnaStops = mutableListOf<CastDevice>()
        val controller = UniversalCastController(
            dlnaStopHook = { dev -> dlnaStops.add(dev) },
        )
        controller.setSessionStateForTest(
            CastSessionState(
                connectedDevice = samsungDlnaDevice,
                castingTitle = "Filme Direct CDN",
                castingDirectMode = true,
                castingIsLive = false,
                isPlayingOnTv = true,
            ),
        )

        val detached = controller.detachKeepPlaying(context = null)

        assertTrue(detached)
        assertTrue(dlnaStops.isEmpty())
        assertNull(controller.state.value.connectedDevice)
    }

    @Test fun detachKeepPlayingRefusesRelayModeToAvoidDroppingStream() = runBlocking {
        val stopAppCalls = mutableListOf<Boolean>()
        val controller = UniversalCastController(
            chromecastDisconnectHook = { stopApp -> stopAppCalls.add(stopApp) },
        )
        controller.setSessionStateForTest(
            CastSessionState(
                connectedDevice = chromecastDevice,
                castingTitle = "Canal HTTP Relay",
                castingDirectMode = false,
                castingIsLive = true,
                isPlayingOnTv = true,
            ),
        )

        val detached = controller.detachKeepPlaying(context = null)

        assertFalse(detached)
        assertTrue(stopAppCalls.isEmpty())
        assertEquals(chromecastDevice, controller.state.value.connectedDevice)
    }

    @Test fun disconnectExplicitlyStopsChromecastAndDlna() = runBlocking {
        val stopAppCalls = mutableListOf<Boolean>()
        val dlnaStops = mutableListOf<CastDevice>()
        val controller = UniversalCastController(
            chromecastDisconnectHook = { stopApp -> stopAppCalls.add(stopApp) },
            dlnaStopHook = { dev -> dlnaStops.add(dev) },
        )

        controller.setSessionStateForTest(
            CastSessionState(connectedDevice = chromecastDevice, castingDirectMode = true, isPlayingOnTv = true),
        )
        controller.disconnect(context = null)
        assertEquals(listOf(true), stopAppCalls)
        assertNull(controller.state.value.connectedDevice)

        controller.setSessionStateForTest(
            CastSessionState(connectedDevice = samsungDlnaDevice, castingDirectMode = true, isPlayingOnTv = true),
        )
        controller.disconnect(context = null)
        assertEquals(listOf(samsungDlnaDevice), dlnaStops)
        assertNull(controller.state.value.connectedDevice)
    }

    @Test fun buildDirectChromecastVodCandidatesPrefersResolvedCdnThenHttps307() {
        val rewritten = "https://panel.example.ts.net/movie/user/pass/999.mp4"
        val resolved = "http://cdn.origin.net/files/999.mkv" to "video/x-matroska"

        val candidates = UniversalCastController.buildDirectChromecastVodCandidates(rewritten, resolved)
        assertEquals(2, candidates.size)
        assertEquals("http://cdn.origin.net/files/999.mkv" to "video/x-matroska", candidates[0])
        assertEquals(rewritten to "video/x-matroska", candidates[1])

        val dedup = UniversalCastController.buildDirectChromecastVodCandidates(
            rewritten,
            rewritten to "video/mp4",
        )
        assertEquals(1, dedup.size)

        val fallbackOnly = UniversalCastController.buildDirectChromecastVodCandidates(rewritten, null)
        assertEquals(listOf(rewritten to "video/mp4"), fallbackOnly)
    }

    @Test fun isDirectHttpsHlsManifestRequiresCorsAndHttpsOnlySegments() {
        val httpsManifest = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXTINF:6.0,
            https://cdn.pluto.tv/seg1.ts
            #EXTINF:6.0,
            seg2.ts
        """.trimIndent()
        val mixedManifest = """
            #EXTM3U
            #EXTINF:6.0,
            http://insecure.origin.net/seg1.ts
        """.trimIndent()

        assertTrue(UniversalCastController.isDirectHttpsHlsManifest(httpsManifest, "*"))
        assertTrue(UniversalCastController.isDirectHttpsHlsManifest(httpsManifest, "https://www.gstatic.com"))
        assertFalse(UniversalCastController.isDirectHttpsHlsManifest(httpsManifest, null))
        assertFalse(UniversalCastController.isDirectHttpsHlsManifest(mixedManifest, "*"))
    }
}
