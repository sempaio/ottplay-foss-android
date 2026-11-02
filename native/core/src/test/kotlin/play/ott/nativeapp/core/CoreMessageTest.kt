package play.ott.nativeapp.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CoreMessageTest {
    @Test fun typedCatalogNotesSurviveSerializationWithoutRewritingUserContent() {
        val entry = MediaEntry("item", "source", "Пользовательское название", "https://example.test/video")
        val notice = CoreMessage(CoreMessageKey.XTREAM_SECTION_UNAVAILABLE, listOf("MOVIE", "404"))
        val catalog = Catalog(listOf(entry), notes = listOf(notice.english()), messages = listOf(notice))
        val restored = Json.decodeFromString<Catalog>(Json.encodeToString(catalog))
        assertEquals(catalog, restored)
        assertEquals("Пользовательское название", restored.entries.single().name)
        assertNull(restored.entries.single().nameMessage)
        assertEquals(listOf("MOVIE", "404"), restored.messages.single().args)
    }

    @Test fun oldCacheWithoutTypedMessagesStillDecodes() {
        val restored = Json.decodeFromString<Catalog>("""{"entries":[],"notes":["Legacy notice"]}""")
        assertEquals(listOf("Legacy notice"), restored.notes)
        assertEquals(emptyList(), restored.messages)
    }

    @Test fun importerLocalizesOnlyItsOwnFallbackNames() {
        val result = LegacySourceImporter.parse("""{"M3Us":[
            {"www":"https://one.test/list","name":"Мой плейлист"},
            {"www":"https://two.test/list","name":""}
        ]}""", CoreTextResolver { "translated:${it.key}:${it.args.joinToString()}" })
        assertEquals("Мой плейлист", result.sources[0].name)
        assertNull(result.sources[0].nameMessage)
        assertEquals("translated:IMPORTED_PLAYLIST:2", result.sources[1].name)
        assertEquals(CoreMessage(CoreMessageKey.IMPORTED_PLAYLIST, listOf("2")), result.sources[1].nameMessage)
    }
}
