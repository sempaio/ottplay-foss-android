package play.ott.nativeapp.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*

class StalkerMigrationTest {
    private val json = Json { encodeDefaults = true; prettyPrint = true }
    @Test fun `captured Stalker transport catalogs sessions and playback contracts`(): Unit = runBlocking {
        val cases=json.parseToJsonElement(requireNotNull(javaClass.getResource("/stalker-before-core.json")).readText()).jsonArray
        cases.forEachIndexed { index, case ->
            val input=case.jsonObject.getValue("input").jsonObject
            val calls=mutableListOf<JsonElement>()
            val actual=MockWebServer().use { server ->
                server.dispatcher=object: Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        val response=input.getValue("responses").jsonArray.getOrNull(calls.size)?.jsonObject
                        calls+=buildJsonObject {
                            put("path",request.path);put("method",request.method)
                            put("body",request.body.readUtf8())
                            put("cookie",request.getHeader("Cookie"));put("agent",request.getHeader("X-User-Agent"));put("authorization",request.getHeader("Authorization"))
                        }
                        return MockResponse().setResponseCode(response?.get("status")?.jsonPrimitive?.int ?: if(response==null)599 else 200)
                            .setBody((response?.get("body") ?: JsonNull).toString())
                    }
                }
                server.start()
                val origin=server.url("/").toString().removeSuffix("/")
                val path=input["path"]?.jsonPrimitive?.content ?: if(input["rpc"]?.jsonPrimitive?.booleanOrNull==true) "/stalker_portal/api/" else "/stalker_portal/c/"
                val config=SourceConfig("portal 🎬","Fixture",SourceKind.STALKER,origin+path,mac="00:1a:79:01:02:03")
                val result=buildJsonObject {
                    try {
                        val repository=ProviderRepository()
                        val catalog=repository.load(config)
                        put("catalog",json.encodeToJsonElement(catalog))
                        if(input["twice"]?.jsonPrimitive?.booleanOrNull==true)put("second",json.encodeToJsonElement(repository.load(config)))
                        if(input["resolve"]?.jsonPrimitive?.booleanOrNull==true && catalog.entries.isNotEmpty())put("stream",json.encodeToJsonElement(repository.resolve(config,catalog.entries.first())))
                    }catch(error:Exception){putJsonObject("error"){put("message",error.message);put("status",(error as? ProviderException)?.statusCode);put("type",error.javaClass.simpleName)}}
                    put("calls",JsonArray(calls))
                }
                json.parseToJsonElement(result.toString().replace(origin,"http://fixture.test"))
            }
            assertEquals(case.jsonObject.getValue("expected"),actual,"Fixture $index")
        }
    }
}
