package play.ott.nativeapp.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import java.time.Instant

class ArchiveMigrationTest {
    @Test fun `shared archive uses the host zone database across a daylight saving fold`() {
        val entry = MediaEntry("entry", "source", "Display", "https://example.invalid/live.m3u8",
            catchup = Catchup("xtream", "https://example.invalid/{durationMinutes}/{startDate}", 2.0, "Europe/Berlin"))
        for (clock in listOf("2026-10-25T00:30:00Z", "2026-10-25T01:30:00Z")) {
            val start = Instant.parse(clock).toEpochMilli()
            val programme = Programme("Display", "Show", start, start + 180_000)
            assertEquals("https://example.invalid/2/2026-10-25:02-30", CatchupResolver.resolve(entry, programme, start + 90_000)?.url)
        }
    }

    @Test fun `flussonic preserves encoded resource parsing and signed query while boundary remains strict`() {
        val entry = MediaEntry("entry", "source", "Display", "https://example.invalid/index%2Em3u8?token=a%2Fb&x=1&x=2#player",
            catchup = Catchup("flussonic", days = 2.0))
        val programme = Programme("Display", "Show", 1_800_000_000_000, 1_800_000_060_000)
        assertEquals("https://example.invalid/archive-1800000000-60.m3u8?token=a%2Fb&x=1&x=2#player",
            CatchupResolver.resolve(entry, programme, programme.startMillis + 600_000)?.url)
        assertEquals("https://example.invalid/timeshift_abs-1800000000.m3u8?token=a%2Fb&x=1&x=2#player",
            CatchupResolver.resolve(entry, programme, programme.startMillis + 599_000)?.url)
    }

    @Test fun `legacy archive hours are fallback only and explicit playlist disable wins`() {
        val source = LegacySourceImporter.parse("""{"M3Us":[{"www":"https://example.invalid/list.m3u","rechours":48}]}""").sources.single()
        val entries = M3uParser.parse("""
            #EXTM3U
            #EXTINF:-1,Inherited
            https://example.invalid/one.m3u8
            #EXTINF:-1 catchup-days="7",Explicit
            https://example.invalid/two.m3u8
            #EXTINF:-1 catchup="none",Disabled
            https://example.invalid/three.m3u8
        """.trimIndent(), source).entries
        assertEquals(2.0, entries[0].catchup?.days)
        assertEquals(7.0, entries[1].catchup?.days)
        assertNull(entries[2].catchup)
    }

    @Test fun `archive accepts guide identity normalization and display-name fallback used by database`() {
        val entry = MediaEntry("entry", "source", "Display Name", "https://example.invalid/live.m3u8",
            epgId = "exact-id", catchup = Catchup(days = 2.0))
        val now = 1_800_000_000_000L
        val programme = Programme("EXACT-ID", "Show", now - 3_600_000, now - 1_800_000)
        assertNotNull(CatchupResolver.resolve(entry, programme, now))
        assertNotNull(CatchupResolver.resolve(entry, programme.copy(channelId = "display name"), now))
        assertNull(CatchupResolver.resolve(entry, programme.copy(channelId = "unrelated"), now))
    }

    @Test fun `archive retains source credentials only on their original authority`() {
        val source = SourceConfig("source", "Source", SourceKind.M3U, "https://origin.invalid/list",
            headers = mapOf("Authorization" to "Bearer source", "User-Agent" to "player"))
        val entry = M3uParser.parse("""
            #EXTM3U
            #EXTINF:-1 catchup-days="2",Channel
            https://origin.invalid/live.m3u8
        """.trimIndent(), source).entries.single()
        val now = 1_800_000_000_000L
        val programme = Programme("Channel", "Show", now - 3_600_000, now - 1_800_000)
        assertEquals(source.headers, assertNotNull(CatchupResolver.resolve(entry, programme, now)).headers)
        val crossOrigin = entry.copy(catchup = Catchup(source = "https://cdn.invalid/archive?utc={utc}", days = 2.0))
        assertEquals(mapOf("User-Agent" to "player"), assertNotNull(CatchupResolver.resolve(crossOrigin, programme, now)).headers)
    }
}
