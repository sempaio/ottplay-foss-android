package play.ott.nativeapp.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class LegacySourceImporterTest {
    @Test fun `string encoded storage keys migrate sources while preserving FOSS portal dialect`() {
        val dump = buildJsonObject {
            put("m3um3uArr", JsonPrimitive("""{"M3Us":[{"name":"My channels","www":"https://example.test/list.m3u","rechours":24},{"www":"/old/files/local.m3u"}]}"""))
            put("xtreamxtream_data", JsonPrimitive("""{"server":"https://xtream.test","username":"user","password":"private"}"""))
            put("stalkerstalker_data", JsonPrimitive("""{"portal":"https://portal.test","mac":"00:1a:79:01:02:03","token":"do-not-import"}"""))
        }
        val result = LegacySourceImporter.parse(dump.toString())
        assertEquals(listOf(SourceKind.M3U, SourceKind.XTREAM, SourceKind.STALKER), result.sources.map { it.kind })
        assertEquals("My channels", result.sources[0].name)
        assertEquals("private", result.sources[1].password)
        assertEquals("https://portal.test/stalker_portal/api/", result.sources[2].url)
        assertTrue(result.sources[2].headers.isEmpty())
        assertTrue(result.messages.any { it.key == CoreMessageKey.LEGACY_PLAYLIST_UNSUPPORTED })
        assertEquals(1.0, result.sources[0].catchupDaysFallback)
        assertFalse(result.toString().contains("private"))
        assertFalse(result.toString().contains("do-not-import"))
    }

    @Test fun `ordinary settings backup correctly reports that it does not contain source credentials`() {
        val result = LegacySourceImporter.parse("""{"version":1,"settings":{"localCmdUrl":"https://private.test"},"favoritesArray":[123],"parentalArray":[]}""")
        assertTrue(result.sources.isEmpty())
        assertTrue(result.messages.any { it.key == CoreMessageKey.LEGACY_BACKUP_WITHOUT_SOURCES })
        assertTrue(result.messages.any { it.key == CoreMessageKey.LEGACY_FAVORITES_SKIPPED })
    }

    @Test fun `direct M3U object and wrapped storage objects are accepted without fetching their URLs`() {
        val direct = LegacySourceImporter.parse("""{"M3Us":[{"www":"https://example.test/a.m3u"},{"www":"https://example.test/a.m3u"}]}""")
        assertEquals(1, direct.sources.size)
        val wrapped = LegacySourceImporter.parse("""{"storage":{"xtream_data":{"server":"https://example.test","username":"u","password":"p"}}}""")
        assertEquals(SourceKind.XTREAM, wrapped.sources.single().kind)
    }
}
