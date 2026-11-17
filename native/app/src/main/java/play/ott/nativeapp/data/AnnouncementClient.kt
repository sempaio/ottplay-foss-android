package play.ott.nativeapp.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import play.ott.nativeapp.StarlinkConfig
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/** Global notices are public editorial copy, never client-specific credentials. */
internal data class WelcomeAnnouncement(val id: Long, val title: String, val message: String)

internal class AnnouncementClient {
    private val http = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()

    suspend fun active(): WelcomeAnnouncement? = withContext(Dispatchers.IO) {
        val endpoint = (StarlinkConfig.XTREAM_BASE_URL + "/app/announcements/active").toHttpUrl()
        require(endpoint.isHttps && endpoint.username.isEmpty() && endpoint.password.isEmpty())
        http.newCall(Request.Builder().url(endpoint).get().build()).execute().use { response ->
            require(response.isSuccessful)
            val output = ByteArrayOutputStream()
            response.body.byteStream().use { input ->
                val buffer = ByteArray(4096)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= 32 * 1024)
                    output.write(buffer, 0, count)
                }
            }
            parseWelcomeAnnouncement(output.toString(Charsets.UTF_8.name()))
        }
    }
}

internal fun parseWelcomeAnnouncement(json: String): WelcomeAnnouncement? {
    val root = Json.parseToJsonElement(json).jsonObject
    val value = root["announcement"] ?: return null
    if (value is JsonNull) return null
    val obj = value.jsonObject
    val id = obj["id"]?.jsonPrimitive?.longOrNull ?: error("Invalid announcement id")
    val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: error("Invalid announcement title")
    val message = obj["message"]?.jsonPrimitive?.contentOrNull ?: error("Invalid announcement body")
    require(id > 0 && title.isNotBlank() && title.length <= 150)
    require(message.isNotBlank() && message.length <= 4000)
    return WelcomeAnnouncement(id, title, message)
}

/** Suppress the same notice until the process restarts; a newer notice reappears. */
internal fun shouldShowWelcome(id: Long?, dismissedId: Long?): Boolean =
    id != null && id != dismissedId
