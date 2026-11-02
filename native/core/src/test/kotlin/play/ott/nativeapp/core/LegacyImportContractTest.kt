package play.ott.nativeapp.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.*

class LegacyImportContractTest {
    @Test fun `shipping native importer retains all captured sources notes and failures`() {
        val json = Json { encodeDefaults = true }
        val fixture = javaClass.getResource("/native-import-before-core.json")!!.readText()
        json.parseToJsonElement(fixture).jsonObject.getValue("cases").jsonArray.forEach { row ->
            val actual = try {
                val result = LegacySourceImporter.parse(row.jsonObject.getValue("input").toString())
                buildJsonObject { put("result", buildJsonObject {
                    put("sources", JsonArray(result.sources.map { json.encodeToJsonElement(SourceConfig.serializer(), it) }))
                    put("notes", JsonArray(result.notes.map(::JsonPrimitive)))
                    put("messages", JsonArray(result.messages.map { message -> buildJsonObject { put("key", message.key.name); put("args", JsonArray(message.args.map(::JsonPrimitive))) } }))
                }) }
            } catch (failure: Throwable) { buildJsonObject { put("error", buildJsonObject { put("name", failure.javaClass.name); put("message", failure.message) }) } }
            assertEquals(JsonObject(row.jsonObject.filterKeys { it != "input" }), actual, row.jsonObject.getValue("input").toString())
        }
    }
}
