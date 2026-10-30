package play.ott.nativeapp.core

import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaylistEpgTest {
    private val config = SourceConfig("list", "My list", SourceKind.M3U, "https://example.test/list/channels.m3u",
        headers = mapOf("User-Agent" to "Default agent", "Authorization" to "Bearer private"))

    @Test fun `M3U quoted commas folded metadata options and relative URLs stay with the entry`() {
        val text = """
            #EXTM3U x-tvg-url="../epg.xml.gz,https://guide.test/guide.xml" catchup="append" catchup-days="2" catchup-source="?utc=${'$'}{start}&duration=${'$'}{duration}"
            #EXTINF:-1 tvg-id="news" tvg-logo="../logos/news.png" group-title="News, World",World, News
            #EXTVLCOPT:http-user-agent=Entry agent
            #EXTVLCOPT:http-referrer=https://portal.test/
            # An ordinary comment must not become a URI
            ../live/news.m3u8|User-Agent=Final%20agent&Origin=https%3A%2F%2Fportal.test
            #EXTINF:3600 tvg-id="movie"
            tvg-name="Movie" group-title="Films",A film
            ../movie/film.mp4
        """.trimIndent()
        val catalog = M3uParser.parse(text, config)
        assertEquals(2, catalog.entries.size)
        val first = catalog.entries.first()
        assertEquals("World, News", first.name)
        assertEquals("News, World", first.group)
        assertEquals("https://example.test/live/news.m3u8", first.url)
        assertEquals("https://example.test/logos/news.png", first.logo)
        assertEquals("Final agent", first.headers["User-Agent"])
        assertEquals("Bearer private", first.headers["Authorization"])
        assertEquals("https://portal.test/", first.headers["Referer"])
        assertEquals(2.0, first.catchup?.days)
        assertEquals(MediaKind.MOVIE, catalog.entries[1].kind)
        assertEquals("Default agent", catalog.entries[1].headers["User-Agent"])
        assertNull(catalog.entries[1].headers["Referer"])
        assertEquals(listOf("https://example.test/epg.xml.gz", "https://guide.test/guide.xml"), catalog.epgUrls)
    }

    @Test fun `M3U skips malformed metadata and unsupported schemes without dropping the next channel`() {
        val catalog = M3uParser.parse("""
            #EXTM3U
            #EXTINF:-1 tvg-id="unterminated,broken
            https://example.test/broken.m3u8
            #EXTINF:-1,Unsafe
            javascript:alert(1)
            #EXTINF:-1 tvg-id="good",Good
            https://example.test/good.m3u8
            #EXTINF:-1 tvg-id="good",Good
            https://example.test/good.m3u8
        """.trimIndent(), config)
        assertEquals(listOf("Good"), catalog.entries.map { it.name })
        assertFailsWith<ProviderException> { M3uParser.parse("Access denied", config) }
        assertFailsWith<ProviderException> { M3uParser.parse("<html>Login required</html>", config) }
    }

    @Test fun `same name on different TV channels is not a collision`() {
        val playlist = "#EXTM3U\n#EXTINF:-1,News\nhttps://example.test/one\n#EXTINF:-1,News\nhttps://example.test/two"
        val channels = M3uParser.parse(playlist, config).entries
        assertEquals(2, channels.size)
        assertNotEquals(channels[0].id, channels[1].id)
        assertEquals(channels.map { it.id }, M3uParser.parse(playlist, config).entries.map { it.id })
    }

    @Test fun `HLS manifest is a single playable stream rather than a list of video segments`() {
        val result = M3uParser.parse("#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\nsegment1.ts\n#EXTINF:6,\nsegment2.ts", config)
        assertEquals(1, result.entries.size)
        assertEquals(config.url, result.entries.single().url)
        assertEquals(MediaKind.LIVE, result.entries.single().kind)
    }

    @Test fun `KODI and EXHTTP stream headers parse and reject newline injection`() {
        val catalog = M3uParser.parse("""
            #EXTM3U
            #EXTINF:-1,One
            #EXTHTTP:{"Cookie":"session=abc"}
            #KODIPROP:inputstream.adaptive.stream_headers=User-Agent=Kodi+agent
            https://example.test/one
        """.trimIndent(), config)
        assertEquals("session=abc", catalog.entries.single().headers["Cookie"])
        assertEquals("Kodi agent", catalog.entries.single().headers["User-Agent"])
        assertFailsWith<IllegalArgumentException> {
            M3uParser.parse("#EXTM3U\n#EXTINF:-1,One\nhttps://example.test/one|Origin=good%0D%0AX-Leak%3Ayes", config)
        }
    }

    @Test fun `unknown DRM metadata is reported without importing keys or pretending catchup is available`() {
        val catalog = M3uParser.parse("""
            #EXTM3U
            #EXTINF:-1 tvg-id="one" catchup="unsupported-mode" catchup-days="3",One
            #KODIPROP:inputstream.adaptive.license_type=com.unknown.drm
            #KODIPROP:inputstream.adaptive.license_key=https://license.example.test/?private=secret-key
            https://media.example.test/encrypted.mpd
        """.trimIndent(), config)
        assertEquals(1, catalog.entries.size)
        assertEquals(1, catalog.notes.size)
        assertTrue(catalog.notes.single().contains("DRM"))
        assertTrue(catalog.notes.none { it.contains("secret-key") || it.contains("license.example.test") })
        assertTrue(catalog.entries.single().headers.keys.none { it.contains("license", true) })
        val now = Instant.parse("2026-09-13T12:00:00Z").toEpochMilli()
        assertNull(CatchupResolver.resolve(catalog.entries.single(), Programme("one", "Show", now - 7200000, now - 3600000), now))
    }

    @Test fun `XMLTV gzip handles offsets entities duplicate programmes and invalid dates`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tv>
              <channel id="news"><display-name>News</display-name></channel>
              <programme channel="news" start="20260913003000 +0230" stop="20260913013000 +0230"><title>News &amp; weather</title><desc><![CDATA[<Weather> forecast]]></desc></programme>
              <programme channel="news" start="20260912220000 Z" stop="20260912230000 Z"><title>Duplicate</title></programme>
              <programme channel="news" start="20260230000000 +0000" stop="20260913020000 +0000"><title>Invalid</title></programme>
              <programme channel="news" start="20260914010000 +0000" stop="20260914000000 +0000"><title>Backwards</title></programme>
            </tv>
        """.trimIndent()
        val bytes = ByteArrayOutputStream().also { output -> GZIPOutputStream(output).use { it.write(xml.toByteArray()) } }.toByteArray()
        val programmes = XmltvParser.parse(bytes)
        assertEquals(1, programmes.size)
        assertEquals(Instant.parse("2026-09-12T22:00:00Z").toEpochMilli(), programmes.single().startMillis)
        assertEquals("News & weather", programmes.single().title)
        assertEquals("<Weather> forecast", programmes.single().description)
        assertEquals(Instant.parse("2026-09-13T00:00:00Z").toEpochMilli(), XmltvParser.parseTimestamp("20260913"))
        assertNull(XmltvParser.parseTimestamp("not a date"))
    }

    @Test fun `XMLTV rejects external and internal entity declarations`() {
        listOf(
            "<!DOCTYPE tv [<!ENTITY external SYSTEM 'file:///etc/passwd'>]><tv><programme channel='a' start='20260913000000' stop='20260913010000'><title>&external;</title></programme></tv>",
            "<!DOCTYPE tv [<!ENTITY internal 'data'>]><tv/>",
        ).forEach { xml -> assertFailsWith<ProviderException> { XmltvParser.parse(xml.toByteArray()) } }
    }

    @Test fun `archive templates honor duration clipping retention and channel identity`() {
        val now = Instant.parse("2026-09-13T12:00:00Z").toEpochMilli()
        val programme = Programme("news", "News", now - 3_600_000, now + 1_800_000)
        val entry = MediaEntry("id", "list", "News", "https://example.test/news.m3u8", epgId = "news",
            headers = mapOf("User-Agent" to "Client"), catchup = Catchup("append", "?utc=${'$'}{start}&end={end}&duration={duration}&offset=${'$'}{offset}", 2.0))
        val archive = CatchupResolver.resolve(entry, programme, now)!!
        assertTrue(archive.url.endsWith("?utc=${programme.startMillis / 1000}&end=${now / 1000}&duration=3600&offset=3600"))
        assertEquals(entry.headers, archive.headers)
        assertNull(CatchupResolver.resolve(entry, programme.copy(channelId = "other"), now))
        assertNull(CatchupResolver.resolve(entry, programme.copy(startMillis = now - 3 * 86_400_000L), now))
        assertNull(CatchupResolver.resolve(entry.copy(catchup = Catchup("default", "https://example.test/{unknown}", 2.0)), programme, now))
    }

    @Test fun `flussonic preserves stream token and Xtream archive respects server timezone`() {
        val now = Instant.parse("2026-09-13T12:00:00Z").toEpochMilli()
        val programme = Programme("a", "Show", now - 3_600_000, now - 1_800_000)
        val flussonic = MediaEntry("a", "list", "One", "https://example.test/channel/index.m3u8?token=private", catchup = Catchup("flussonic", days = 3.0))
        assertEquals("https://example.test/channel/archive-${programme.startMillis / 1000}-1800.m3u8?token=private", CatchupResolver.resolve(flussonic, programme, now)?.url)
        val xtream = flussonic.copy(catchup = Catchup("xtream", "https://example.test/timeshift/u/p/{durationMinutes}/{startDate}/9.ts", 3.0, "Europe/Berlin"))
        assertEquals("https://example.test/timeshift/u/p/30/2026-09-13:13-00/9.ts", CatchupResolver.resolve(xtream, programme, now)?.url)
    }
}
