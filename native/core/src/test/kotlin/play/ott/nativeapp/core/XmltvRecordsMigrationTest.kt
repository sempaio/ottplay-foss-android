package play.ott.nativeapp.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.json.*

class XmltvRecordsMigrationTest {
    private val json = Json { encodeDefaults = true }

    private fun capturedResult(xml: String): JsonElement = try {
        buildJsonObject {
            put("result", buildJsonObject {
                put("programmes", json.encodeToJsonElement(XmltvParser.parse(xml.toByteArray())))
            })
        }
    } catch (error: Exception) {
        buildJsonObject {
            put("error", buildJsonObject {
                put("type", error.javaClass.name)
                put("message", error.message.orEmpty())
            })
        }
    }

    @Test fun `shipping parser matches captured XMLTV record results`() {
        val fixture = json.parseToJsonElement(
            requireNotNull(javaClass.getResource("/xmltv-records-before-core.json")).readText(),
        ).jsonObject
        val cases = fixture.getValue("cases").jsonArray
        assertEquals(20, cases.size)
        for (case in cases) {
            val row = case.jsonObject
            assertEquals(row.getValue("expected"), capturedResult(row.getValue("xml").jsonPrimitive.content),
                row.getValue("name").jsonPrimitive.content)
        }
    }

    private fun programme(body: String) =
        "<programme channel='a' start='20260914110000 +0000' stop='20260914120000 +0000'>$body</programme>"

    private fun parse(body: String) = XmltvParser.parse("<tv>$body</tv>".toByteArray())

    @Test fun `SAX field limit counts every current title and description`() {
        for (field in listOf("title", "desc")) {
            val value = "X".repeat(65_536)
            val row = parse(programme("<$field>$value</$field>")).single()
            assertEquals(value, if (field == "title") row.title else row.description)
            assertFailsWith<ProviderException>(field) {
                parse(programme("<$field>${value}X</$field>"))
            }
            // A prior selected field must not bypass the decoder's limit for later fields.
            assertFailsWith<ProviderException>("repeated $field") {
                parse(programme("<$field>First</$field><$field>${value}X</$field>"))
            }
        }
        assertEquals("Untitled programme", parse(programme("<unknown>${"X".repeat(65_537)}</unknown>")).single().title)
    }

    @Test fun `SAX depth and nested programme barriers remain active`() {
        assertEquals(emptyList(), parse("<x>".repeat(63) + "</x>".repeat(63)))
        assertFailsWith<ProviderException> { parse("<x>".repeat(64) + "</x>".repeat(64)) }
        assertFailsWith<ProviderException> { parse(programme(programme("<title>Nested</title>"))) }
    }
}
