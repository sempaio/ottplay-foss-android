package play.ott.nativeapp.core

import kotlin.test.*

class PlaylistMigrationTest {
    private val source = SourceConfig("list", "", SourceKind.M3U, "https://example.test/list.m3u",
        headers = mapOf("User-Agent" to "Default"))
    @Test fun `invalid directive is validated even without a playable URI`() {
        val error = assertFailsWith<ProviderException> {
            M3uParser.parse("#EXTM3U\n#EXTINF:-1,Bad\n#EXTHTTP:{broken\njavascript:bad", source)
        }
        assertEquals("Playlist contains invalid HTTP headers", error.message)
    }
    @Test fun `duplicate row validates DRM without replacing first stream or headers`() {
        val result = M3uParser.parse("""
            #EXTM3U
            #EXTINF:-1 tvg-id=one,One
            #EXTVLCOPT:http-user-agent=First
            https://example.test/one
            #EXTINF:-1 tvg-id=one,One
            #KODIPROP:inputstream.adaptive.license_type=com.unknown.drm
            https://example.test/one
            #EXTINF:-1,Two
            https://example.test/two
        """.trimIndent(), source)
        assertEquals(2, result.entries.size)
        assertEquals("First", result.entries[0].headers["User-Agent"])
        assertEquals("Default", result.entries[1].headers["User-Agent"])
        assertNull(result.entries[0].playbackUnsupportedReason)
        assertEquals(1, result.notes.size)
    }
    @Test fun `generated name remains localizable and HLS uses configured feed literally`() {
        val stream = M3uParser.parse("https://example.test/", source).entries.single()
        assertEquals("Stream 1", stream.name)
        assertEquals(CoreMessage(CoreMessageKey.STREAM, listOf("1")), stream.nameMessage)
        val hls = M3uParser.parse("#EXTM3U\n#EXT-X-TARGETDURATION:6", source.copy(epgUrl = "../guide.xml"))
        assertEquals(listOf("../guide.xml"), hls.epgUrls)
        assertEquals(CoreMessage(CoreMessageKey.HLS_STREAM), hls.entries.single().nameMessage)
    }
}
