package play.ott.nativeapp.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*

class XtreamMigrationTest {
    private val json = Json { encodeDefaults = true; prettyPrint = true; allowSpecialFloatingPointValues = true }
    private fun finite(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.mapValues { finite(it.value) })
        is JsonArray -> JsonArray(value.map(::finite))
        is JsonPrimitive -> if (!value.isString && value.content in listOf("Infinity", "-Infinity", "NaN")) JsonPrimitive("#" + value.content) else value
    }
    @Test fun `captured Xtream catalogs errors requests and episodes preserve Android contracts`() = runBlocking {
        val cases = json.parseToJsonElement(requireNotNull(javaClass.getResource("/xtream-before-core.json")).readText()).jsonArray
        cases.forEachIndexed { index, case ->
            val input = case.jsonObject.getValue("input").jsonObject
            val calls = mutableListOf<String>()
            val actual = MockWebServer().use { server ->
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        calls += request.path.orEmpty()
                        val action = request.requestUrl?.queryParameter("action")
                        val status = input["statuses"]?.jsonObject?.get(action)?.jsonPrimitive?.int
                        if (status != null) return MockResponse().setResponseCode(status)
                        val data = if (action == null) input["account"] else if (action == "get_series_info") input["series"] else input["actions"]?.jsonObject?.get(action)
                        return MockResponse().setBody((data ?: JsonArray(emptyList())).toString())
                    }
                }
                server.start()
                val origin = server.url("/").toString().removeSuffix("/")
                val config = SourceConfig("xc 🎬", "Fixture", SourceKind.XTREAM, "$origin/folder/player_api.php?old=1", "a/b", "x?&")
                val result = buildJsonObject {
                    try {
                        val repository = ProviderRepository()
                        val catalog = repository.load(config)
                        put("catalog", json.encodeToJsonElement(catalog))
                        if (input.containsKey("series") && catalog.entries.any { it.kind == MediaKind.SERIES }) {
                            put("episodes", json.encodeToJsonElement(repository.loadEpisodes(config, catalog.entries.first { it.kind == MediaKind.SERIES })))
                        }
                    } catch (error: ProviderException) {
                        putJsonObject("error") { put("message", error.message); put("status", error.statusCode) }
                    }
                    put("calls", json.encodeToJsonElement(calls))
                }
                json.parseToJsonElement(finite(result).toString().replace(origin, "http://fixture.test"))
            }
            assertEquals(case.jsonObject.getValue("expected"), actual, "Fixture $index")
        }
    }
}
