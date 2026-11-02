package play.ott.nativeapp.data

import kotlin.test.Test
import kotlin.test.assertEquals
import play.ott.nativeapp.core.SourceConfig
import play.ott.nativeapp.core.SourceKind

class EpgHeadersTest {
    private val source = SourceConfig("id", "IPTV", SourceKind.M3U, "https://provider.invalid/list.m3u",
        headers = mapOf("Authorization" to "Bearer synthetic", "X-Api-Key" to "test-key", "Cookie" to "test-session", "User-Agent" to "OTTPlayNative"))
    @Test fun sameOriginGuideRetainsSourceHeaders() {
        assertEquals(source.headers, epgHeaders(source, "https://provider.invalid/epg.xml"))
    }
    @Test fun advertisedExternalGuideReceivesNoCredentials() {
        listOf("https://cdn.invalid/epg.xml", "http://provider.invalid/epg.xml", "https://provider.invalid:8443/epg.xml").forEach {
            assertEquals(mapOf("User-Agent" to "OTTPlayNative"), epgHeaders(source, it))
        }
    }
}
