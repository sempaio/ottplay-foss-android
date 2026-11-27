package play.ott.nativeapp.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DlnaProtocolTest {
    @Test fun buildMSearchPacketContainsRequiredSsdpHeaders() {
        val packet = DlnaProtocol.buildMSearchPacket(DlnaProtocol.AV_TRANSPORT_SERVICE, mxSeconds = 2)
        assertTrue(packet.startsWith("M-SEARCH * HTTP/1.1\r\n"))
        assertTrue(packet.contains("HOST: 239.255.255.250:1900\r\n"))
        assertTrue(packet.contains("MAN: \"ssdp:discover\"\r\n"))
        assertTrue(packet.contains("ST: urn:schemas-upnp-org:service:AVTransport:1\r\n"))
        assertTrue(packet.endsWith("\r\n\r\n"))
    }

    @Test fun parseSsdpLocationIsCaseInsensitive() {
        val response = """
            HTTP/1.1 200 OK
            CACHE-CONTROL: max-age=1800
            lOcAtIoN: http://192.168.1.42:9197/dmr
            ST: urn:schemas-upnp-org:service:AVTransport:1
        """.trimIndent()
        assertEquals("http://192.168.1.42:9197/dmr", DlnaProtocol.parseSsdpLocation(response))
    }

    @Test fun lanGuardAllowsPrivateIpsAndBlocksPublicOrLoopback() {
        assertTrue(DlnaProtocol.isPrivateLanHost("192.168.0.15"))
        assertTrue(DlnaProtocol.isPrivateLanHost("10.0.0.8"))
        assertTrue(DlnaProtocol.isPrivateLanHost("172.16.4.2"))
        assertTrue(DlnaProtocol.isPrivateLanHost("172.31.255.1"))
        assertTrue(DlnaProtocol.isPrivateLanHost("169.254.10.20"))

        assertFalse(DlnaProtocol.isPrivateLanHost("127.0.0.1"))
        assertFalse(DlnaProtocol.isPrivateLanHost("8.8.8.8"))
        assertFalse(DlnaProtocol.isPrivateLanHost("172.32.0.1"))
        assertFalse(DlnaProtocol.isPrivateLanHost("example.com"))
        assertFalse(DlnaProtocol.isSafeLanUrl("http://8.8.8.8:8080/dmr"))
        assertTrue(DlnaProtocol.isSafeLanUrl("http://192.168.1.42:9197/dmr"))
    }

    @Test fun parseDeviceDescriptionExtractsSamsungAndLgControlUrls() {
        val xml = """
            <?xml version="1.0"?>
            <root xmlns="urn:schemas-upnp-org:device-1-0">
              <device>
                <friendlyName>Samsung QLED &amp; Sala</friendlyName>
                <manufacturer>Samsung Electronics</manufacturer>
                <modelName>QN55Q60</modelName>
                <UDN>uuid:samsung-tv-01</UDN>
                <serviceList>
                  <service>
                    <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
                    <controlURL>/upnp/control/AVTransport1</controlURL>
                  </service>
                  <service>
                    <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
                    <controlURL>upnp/control/RenderingControl1</controlURL>
                  </service>
                </serviceList>
              </device>
            </root>
        """.trimIndent()

        val device = DlnaProtocol.parseDeviceDescriptionXml(xml, "http://192.168.1.50:9197/dmr")
        assertNotNull(device)
        assertEquals("Samsung QLED & Sala", device!!.friendlyName)
        assertEquals("Samsung Electronics", device.manufacturer)
        assertEquals("QN55Q60", device.modelName)
        assertEquals("http://192.168.1.50:9197/upnp/control/AVTransport1", device.avTransportControlUrl)
        assertEquals("http://192.168.1.50:9197/upnp/control/RenderingControl1", device.renderingControlUrl)
    }

    @Test fun parseDeviceDescriptionRejectsPublicControlUrl() {
        val xml = """
            <root><device>
              <friendlyName>Evil TV</friendlyName>
              <serviceList><service>
                <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
                <controlURL>http://1.2.3.4/attack</controlURL>
              </service></serviceList>
            </device></root>
        """.trimIndent()
        assertNull(DlnaProtocol.parseDeviceDescriptionXml(xml, "http://192.168.1.50:9197/dmr"))
    }

    @Test fun rewriteMediaUrlForCastUpgradesM81LiveToLiveHlsAndLeavesVodIntact() {
        assertEquals(
            "https://panel.taila5c5c2.ts.net/live-hls/demo/pass123/4567.m3u8",
            DlnaProtocol.rewriteMediaUrlForCast("https://panel.taila5c5c2.ts.net/live/demo/pass123/4567.ts", isLive = true),
        )
        assertEquals(
            "https://panel.taila5c5c2.ts.net/live-hls/demo/pass123/4567.m3u8",
            DlnaProtocol.rewriteMediaUrlForCast("https://panel.taila5c5c2.ts.net/live/demo/pass123/4567.m3u8", isLive = true),
        )
        assertEquals(
            "https://panel.taila5c5c2.ts.net/movie/demo/pass123/999.mp4",
            DlnaProtocol.rewriteMediaUrlForCast("https://panel.taila5c5c2.ts.net/movie/demo/pass123/999.mp4", isLive = false),
        )
    }

    @Test fun soapEnvelopesEscapeXmlAndFormatSeekTime() {
        val setUri = DlnaProtocol.buildSetAvTransportUriSoap(
            mediaUrl = "http://cdn.local/movie.mp4?a=1&b=2",
            title = "Ação & Aventura <HD>",
            isLive = false,
            includeMetadata = true,
        )
        assertTrue(setUri.contains("u:SetAVTransportURI"))
        assertTrue(setUri.contains("http://cdn.local/movie.mp4?a=1&amp;b=2"))
        assertTrue(setUri.contains("Ação &amp;amp; Aventura &amp;lt;HD&amp;gt;"))

        val seek = DlnaProtocol.buildSeekSoap(3_723_000L)
        assertTrue(seek.contains("<Target>01:02:03</Target>"))
        assertEquals(3_723_000L, DlnaProtocol.parseUpnpTimeMs("01:02:03"))
        assertEquals(90_000L, DlnaProtocol.parseUpnpTimeMs("0:01:30.500"))
        assertNull(DlnaProtocol.parseUpnpTimeMs("NOT_IMPLEMENTED"))

        val vol = DlnaProtocol.buildSetVolumeSoap(140)
        assertTrue(vol.contains("<DesiredVolume>100</DesiredVolume>"))
    }

    @Test fun detectVendorProfileIdentifiesLgSamsungAndGenericAndRedactsUrls() {
        val lg = CastDevice(
            id = "uuid:lg",
            friendlyName = "[LG] webOS TV UN7310",
            manufacturer = "LG Electronics",
            modelName = "LG TV",
            host = "192.168.1.40",
            locationUrl = "http://192.168.1.40:1770/dmr.xml",
            avTransportControlUrl = "http://192.168.1.40:1770/AVTransport",
            renderingControlUrl = null,
        )
        val samsung = lg.copy(
            id = "uuid:sam",
            friendlyName = "TV Quarto",
            manufacturer = "Samsung Electronics",
            modelName = "UN32T4300A",
        )
        val generic = lg.copy(
            id = "uuid:kodi",
            friendlyName = "Kodi Sala",
            manufacturer = "XBMC",
            modelName = "Kodi",
        )
        assertEquals(DlnaVendorProfile.LG, DlnaProtocol.detectVendorProfile(lg))
        assertEquals(DlnaVendorProfile.SAMSUNG, DlnaProtocol.detectVendorProfile(samsung))
        assertEquals(DlnaVendorProfile.GENERIC, DlnaProtocol.detectVendorProfile(generic))

        val redacted = DlnaProtocol.redactUrlForDiagnostics(
            "https://panel.example/live-hls/user123/secret999/456.m3u8?token=abcdef123",
        )
        assertFalse(redacted.contains("user123"))
        assertFalse(redacted.contains("secret999"))
        assertFalse(redacted.contains("abcdef123"))
    }
}
