package play.ott.nativeapp.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

class DlnaCapabilityResolverTest {
    @Test fun parseDeviceDescriptionExtractsConnectionManagerAndBuildsProtocolInfoSoap() {
        val xml = """
            <?xml version="1.0"?>
            <root xmlns="urn:schemas-upnp-org:device-1-0">
              <device>
                <friendlyName>[LG] webOS TV OLED55</friendlyName>
                <manufacturer>LG Electronics</manufacturer>
                <modelName>OLED55C1</modelName>
                <UDN>uuid:lg-oled-55</UDN>
                <serviceList>
                  <service>
                    <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
                    <controlURL>/AVTransport/control</controlURL>
                  </service>
                  <service>
                    <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
                    <controlURL>/RenderingControl/control</controlURL>
                  </service>
                  <service>
                    <serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType>
                    <controlURL>/ConnectionManager/control</controlURL>
                  </service>
                </serviceList>
              </device>
            </root>
        """.trimIndent()

        val device = DlnaProtocol.parseDeviceDescriptionXml(xml, "http://192.168.1.77:1770/dmr.xml")
        assertNotNull(device)
        assertEquals("http://192.168.1.77:1770/ConnectionManager/control", device!!.connectionManagerControlUrl)

        val soap = DlnaProtocol.buildGetProtocolInfoSoap()
        assertTrue(soap.contains("urn:schemas-upnp-org:service:ConnectionManager:1"))
        assertTrue(soap.contains("<u:GetProtocolInfo"))
    }

    @Test fun parseDeviceDescriptionIgnoresPublicConnectionManagerControlUrl() {
        val xml = """
            <root><device>
              <friendlyName>Smart TV</friendlyName>
              <serviceList>
                <service>
                  <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
                  <controlURL>/avt</controlURL>
                </service>
                <service>
                  <serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType>
                  <controlURL>http://8.8.8.8/evil-cm</controlURL>
                </service>
              </serviceList>
            </device></root>
        """.trimIndent()

        val device = DlnaProtocol.parseDeviceDescriptionXml(xml, "http://192.168.1.77:1770/dmr.xml")
        assertNotNull(device)
        assertNull(device!!.connectionManagerControlUrl)
    }

    @Test fun parseSinkProtocolInfoDetectsHlsMpegTsMp4MkvAndWebm() {
        val lgSinkXml = """
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
              <s:Body>
                <u:GetProtocolInfoResponse xmlns:u="urn:schemas-upnp-org:service:ConnectionManager:1">
                  <Source></Source>
                  <Sink>http-get:*:application/vnd.apple.mpegurl:*,http-get:*:video/mp4:DLNA.ORG_PN=AVC_MP4_HP_HD_AAC,http-get:*:video/x-matroska:*,http-get:*:video/webm:*</Sink>
                </u:GetProtocolInfoResponse>
              </s:Body>
            </s:Envelope>
        """.trimIndent()

        val rawSink = DlnaProtocol.extractTagText(lgSinkXml, "Sink").orEmpty()
        val caps = DlnaCapabilityResolver.parseSinkProtocolInfo(rawSink)

        assertEquals(4, caps.rawProtocols.size)
        assertTrue(caps.supportsHls)
        assertFalse(caps.supportsMpegTs)
        assertTrue(caps.supportsMp4)
        assertTrue(caps.supportsMkv)
        assertTrue(caps.supportsWebm)
        assertTrue(caps.summaryTag().contains("HLS=sim"))
        assertTrue(caps.summaryTag().contains("TS=não"))

        val samsungSink = "http-get:*:video/mp2t:DLNA.ORG_PN=MPEG_TS_SD_NA_ISO,http-get:*:video/mp4:*,http-get:*:video/x-mkv:*"
        val samCaps = DlnaCapabilityResolver.parseSinkProtocolInfo(samsungSink)
        assertFalse(samCaps.supportsHls)
        assertTrue(samCaps.supportsMpegTs)
        assertTrue(samCaps.supportsMp4)
        assertTrue(samCaps.supportsMkv)
        assertFalse(samCaps.supportsWebm)

        val emptyCaps = DlnaCapabilityResolver.parseSinkProtocolInfo("")
        assertTrue(emptyCaps.rawProtocols.isEmpty())
        assertFalse(emptyCaps.supportsHls)
        assertFalse(emptyCaps.supportsMpegTs)
    }

    @Test fun orderCandidatesDeduplicatesAppliesSinkAndPromotesCachedWinnerWithTtl() {
        val nowMs = AtomicLong(1_000L)
        val resolver = DlnaCapabilityResolver(clockMs = { nowMs.get() }, ttlMs = 60_000L)

        val cTsPn = DlnaCandidateProfile("LG-TS+PN", "http://192.168.1.5:9000/live.ts", "video/mp2t", "PN=TS", true)
        val cTsPnDup = DlnaCandidateProfile("LG-TS+PN-dup", "http://192.168.1.5:9000/live.ts", "VIDEO/MP2T", "PN=TS", true)
        val cHls = DlnaCandidateProfile("LG-HLS-m3u8", "http://192.168.1.5:9000/live.m3u8", "application/vnd.apple.mpegurl", "*", true)
        val cWild = DlnaCandidateProfile("LG-TS-wild", "http://192.168.1.5:9000/live.ts", "video/mp2t", "*", true)

        val hlsOnlySink = DlnaCapabilityResolver.parseSinkProtocolInfo("http-get:*:application/vnd.apple.mpegurl:*,http-get:*:video/mp4:*")
        resolver.cacheSink("uuid:lg-1", hlsOnlySink)

        // 1. Deduplication + Sink-guided HLS preference on LG live
        val orderedLg = resolver.orderCandidates(
            deviceId = "uuid:lg-1",
            vendor = DlnaVendorProfile.LG,
            isLive = true,
            mimeType = "video/mp2t",
            candidates = listOf(cTsPn, cTsPnDup, cHls, cWild),
        )
        assertEquals(listOf("LG-HLS-m3u8", "LG-TS+PN", "LG-TS-wild"), orderedLg.map { it.label })

        // 2. Samsung vendor safety guard keeps MPEG-TS ahead of HLS when no winning profile cached
        val orderedSamsung = resolver.orderCandidates(
            deviceId = "uuid:sam-1",
            vendor = DlnaVendorProfile.SAMSUNG,
            isLive = true,
            mimeType = "video/mp2t",
            candidates = listOf(cHls, cTsPn),
            sinkCaps = hlsOnlySink,
        )
        assertEquals("LG-TS+PN", orderedSamsung.first().label)

        // 3. Record winning profile and verify immediate promotion on subsequent zaps
        resolver.recordWorkingProfile("uuid:lg-1", isLive = true, mimeType = "video/mp2t", label = "LG-TS-wild")
        val withWinner = resolver.orderCandidates(
            deviceId = "uuid:lg-1",
            vendor = DlnaVendorProfile.LG,
            isLive = true,
            mimeType = "video/mp2t",
            candidates = listOf(cTsPn, cHls, cWild),
        )
        assertEquals("LG-TS-wild", withWinner.first().label)

        // 4. VOD keeps Direct HTTP candidate at index 0 even when a proxy profile won earlier
        val vodDirect = DlnaCandidateProfile("LG-VOD-direto", "http://cdn.net/movie.mp4", "video/mp4", "*", true, isDirect = true)
        val vodDlna = DlnaCandidateProfile("LG-VOD-DLNA", "http://192.168.1.5:9000/v.mp4", "video/mp4", "OP=01", true)
        val vodWild = DlnaCandidateProfile("LG-VOD-wild", "http://192.168.1.5:9000/v.mp4", "video/mp4", "*", true)
        resolver.recordWorkingProfile("uuid:lg-1", isLive = false, mimeType = "video/mp4", label = "LG-VOD-wild")
        val orderedVod = resolver.orderCandidates(
            deviceId = "uuid:lg-1",
            vendor = DlnaVendorProfile.LG,
            isLive = false,
            mimeType = "video/mp4",
            candidates = listOf(vodDirect, vodDlna, vodWild),
        )
        assertEquals(listOf("LG-VOD-direto", "LG-VOD-wild", "LG-VOD-DLNA"), orderedVod.map { it.label })

        // 5. TTL expiration evicts both Sink and working-profile caches cleanly
        nowMs.set(61_000L) // exactly at expiresAtMs (1_000 + 60_000)
        assertNull(resolver.getCachedSink("uuid:lg-1"))
        assertNull(resolver.getWorkingProfile("uuid:lg-1", isLive = true, mimeType = "video/mp2t"))
        val afterExpiry = resolver.orderCandidates(
            deviceId = "uuid:lg-1",
            vendor = DlnaVendorProfile.LG,
            isLive = true,
            mimeType = "video/mp2t",
            candidates = listOf(cTsPn, cHls, cWild),
        )
        assertEquals("LG-TS+PN", afterExpiry.first().label)
    }

    @Test fun upnp701FaultDetectionMatchesTransitionErrorsWithoutFalsePositives() {
        assertTrue(DlnaCapabilityResolver.isUpnp701Fault("UPnP 701 (Transition not available)"))
        assertTrue(DlnaCapabilityResolver.isUpnp701Fault("UPnP 701"))
        assertTrue(DlnaCapabilityResolver.isUpnp701Fault("Transition not available"))
        assertTrue(DlnaCapabilityResolver.isUpnp701Fault("701"))

        assertFalse(DlnaCapabilityResolver.isUpnp701Fault(null))
        assertFalse(DlnaCapabilityResolver.isUpnp701Fault(""))
        assertFalse(DlnaCapabilityResolver.isUpnp701Fault("UPnP 714 (Illegal MIME-type)"))
        assertFalse(DlnaCapabilityResolver.isUpnp701Fault("HTTP 500 on port 7010"))
    }
}
