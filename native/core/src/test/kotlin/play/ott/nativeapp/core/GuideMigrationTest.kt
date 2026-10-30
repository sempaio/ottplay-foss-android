package play.ott.nativeapp.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.*

class GuideMigrationTest {
    @Test fun `EPG source precedence retains raw URLs and exact duplicates`() {
        val provider = ProviderRepository()
        val source = SourceConfig("fixture", "fixture", SourceKind.M3U, "https://playlist.invalid/", epgUrl = " https://a.invalid/ ")
        assertEquals(listOf(" https://a.invalid/ ", "https://b.invalid/", "https://a.invalid/"),
            provider.epgSources(source, Catalog(emptyList(), listOf("", " ", "https://b.invalid/", "https://a.invalid/", "https://b.invalid/"))))
        assertEquals(emptyList(), provider.epgSources(source.copy(epgUrl = ""), Catalog(emptyList())))
    }
    private val json=Json { encodeDefaults=true;prettyPrint=true }
    private fun result(xml:String):JsonElement = try {
        json.encodeToJsonElement(XmltvParser.parse(xml.toByteArray()))
    } catch(error:Exception) { buildJsonObject { put("error",error.message) } }
    @Test fun `captured Android XMLTV programme contracts`() {
        val fixtures=json.parseToJsonElement(requireNotNull(javaClass.getResource("/guide-before-core.json")).readText()).jsonArray
        fixtures.forEachIndexed { index,row->assertEquals(row.jsonObject.getValue("expected"),result(row.jsonObject.getValue("xml").jsonPrimitive.content),"Fixture $index") }
    }
}
