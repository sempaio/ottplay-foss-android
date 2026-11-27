package play.ott.nativeapp.data

import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import play.ott.nativeapp.StarlinkConfig
import play.ott.nativeapp.core.MediaEntry
import play.ott.nativeapp.core.MediaKind
import play.ott.nativeapp.core.SourceConfig
import play.ott.nativeapp.core.SourceKind

@UnstableApi
class PlaybackTelemetryClientTest {
    @Test
    fun cooldownPreventsFloodingSameChannelAndError() {
        var now = 1_000L
        val client = PlaybackTelemetryClient(cooldownMs = 300_000L, clockMs = { now })

        assertTrue(client.shouldSend("101", "TIMEOUT"))
        now += 60_000L
        assertFalse(client.shouldSend("101", "TIMEOUT"))
        // Different error or different channel is still allowed
        assertTrue(client.shouldSend("101", "HTTP_404"))
        assertTrue(client.shouldSend("202", "TIMEOUT"))
        // After cooldown expires, same pair is allowed again
        now += 250_000L
        assertTrue(client.shouldSend("101", "TIMEOUT"))
    }

    @Test
    fun payloadStripsUrlsAndNormalizesStreamId() {
        val client = PlaybackTelemetryClient(sessionDeviceId = "anon-session-uuid")
        val source = SourceConfig(
            id = "src-1",
            name = "Starlink",
            kind = SourceKind.XTREAM,
            url = StarlinkConfig.XTREAM_BASE_URL,
            username = "5521999990000",
            password = "000",
        )
        val entry = MediaEntry(
            id = "xtream:live:4567",
            sourceId = "src-1",
            name = "Combate HD http://user:pass@cdn.example/live.ts",
            kind = MediaKind.LIVE,
            group = "Esportes https://secret.example/cat",
        )
        val rawJson = client.buildPayloadJson(
            source = source,
            entry = entry,
            classification = TelemetryErrorClassification("TIMEOUT", 504),
            platform = "android_tv",
            appVersion = "0.3.17",
        )
        val obj = Json.parseToJsonElement(rawJson).jsonObject
        assertEquals("4567", obj["stream_id"]?.jsonPrimitive?.content)
        assertEquals("Combate HD", obj["channel_name"]?.jsonPrimitive?.content)
        assertEquals("Esportes", obj["category_name"]?.jsonPrimitive?.content)
        assertEquals("live", obj["content_type"]?.jsonPrimitive?.content)
        assertEquals("TIMEOUT", obj["error_code"]?.jsonPrimitive?.content)
        assertEquals("504", obj["http_status"]?.jsonPrimitive?.content)
        assertEquals("android_tv", obj["platform"]?.jsonPrimitive?.content)
        assertFalse(rawJson.contains("cdn.example"))
        assertFalse(rawJson.contains("secret.example"))
    }

    @Test
    fun classifiesPlaybackExceptionCodesAccurately() {
        assertEquals(
            "TIMEOUT",
            PlaybackTelemetryClient.classifyErrorCode(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT).errorCode,
        )
        assertEquals(
            "HLS_STALL",
            PlaybackTelemetryClient.classifyErrorCode(PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW).errorCode,
        )
        assertEquals(
            "DECODER",
            PlaybackTelemetryClient.classifyErrorCode(PlaybackException.ERROR_CODE_DECODING_FAILED).errorCode,
        )
        val http403 = PlaybackTelemetryClient.classifyErrorCode(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, 403)
        assertEquals("HTTP_403", http403.errorCode)
        assertEquals(403, http403.httpStatus)
        val http502 = PlaybackTelemetryClient.classifyErrorCode(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, 502)
        assertEquals("HTTP_5XX", http502.errorCode)
        assertEquals(502, http502.httpStatus)
    }
}
