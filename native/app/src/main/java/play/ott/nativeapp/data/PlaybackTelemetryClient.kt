package play.ott.nativeapp.data

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import play.ott.nativeapp.BuildConfig
import play.ott.nativeapp.StarlinkConfig
import play.ott.nativeapp.core.MediaEntry
import play.ott.nativeapp.core.MediaKind
import play.ott.nativeapp.core.SourceConfig
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

internal data class TelemetryErrorClassification(
    val errorCode: String,
    val httpStatus: Int? = null,
)

/**
 * Passive playback failure reporter. Sends minimized, anonymized operational telemetry
 * to the M81 backend so channel outages can be detected without active upstream probing.
 *
 * Never transmits stream URLs, raw exception stacks, or hardware serials.
 */
@UnstableApi
internal class PlaybackTelemetryClient(
    private val endpointBaseUrl: String = StarlinkConfig.XTREAM_BASE_URL,
    private val cooldownMs: Long = 5 * 60 * 1000L,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val sessionDeviceId: String = UUID.randomUUID().toString(),
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .writeTimeout(4, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private val recentSentAt = ConcurrentHashMap<String, Long>()

    fun shouldSend(streamId: String, errorCode: String): Boolean {
        val key = "${streamId.trim()}|$errorCode"
        val now = clockMs()
        val prev = recentSentAt[key]
        if (prev != null && (now - prev) < cooldownMs) return false
        recentSentAt[key] = now
        if (recentSentAt.size > 256) {
            val cutoff = now - cooldownMs
            recentSentAt.entries.removeIf { it.value < cutoff }
        }
        return true
    }

    fun buildPayloadJson(
        source: SourceConfig,
        entry: MediaEntry,
        classification: TelemetryErrorClassification,
        platform: String,
        appVersion: String = BuildConfig.VERSION_NAME,
    ): String {
        val streamId = extractStreamId(entry.id)
        val contentType = when (entry.kind) {
            MediaKind.LIVE -> "live"
            MediaKind.MOVIE -> "vod"
            MediaKind.SERIES, MediaKind.EPISODE -> "series"
        }
        val safeChannel = entry.name.replace(URL_STRIP_REGEX, "").trim().take(120)
        val safeCategory = entry.group.replace(URL_STRIP_REGEX, "").trim().take(90)
        return buildJsonObject {
            put("username", source.username)
            put("password", source.password)
            put("stream_id", streamId)
            put("channel_name", safeChannel)
            put("category_name", safeCategory)
            put("content_type", contentType)
            put("error_code", classification.errorCode)
            if (classification.httpStatus != null) {
                put("http_status", classification.httpStatus)
            }
            put("platform", platform)
            put("app_version", appVersion.take(32))
            put("device_id", sessionDeviceId)
        }.toString()
    }

    suspend fun reportFailure(
        context: Context,
        source: SourceConfig,
        entry: MediaEntry,
        error: PlaybackException,
    ): Boolean = withContext(Dispatchers.IO) {
        if (!StarlinkConfig.isStarlinkAccount(source)) return@withContext false
        if (source.username.isBlank() || source.password.isBlank()) return@withContext false
        val classification = classifyPlaybackException(error)
        val streamId = extractStreamId(entry.id)
        if (!shouldSend(streamId, classification.errorCode)) return@withContext false
        val endpoint = (endpointBaseUrl.trimEnd('/') + "/app/telemetry/playback-error").toHttpUrl()
        if (!endpoint.isHttps) return@withContext false
        val platform = detectPlatform(context)
        val bodyJson = buildPayloadJson(source, entry, classification, platform)
        runCatching {
            val req = Request.Builder()
                .url(endpoint)
                .post(bodyJson.toRequestBody(JSON_MEDIA_TYPE))
                .build()
            http.newCall(req).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val URL_STRIP_REGEX = Regex("https?://\\S+", RegexOption.IGNORE_CASE)

        fun extractStreamId(entryId: String): String {
            val trimmed = entryId.trim()
            val afterColon = trimmed.substringAfterLast(':')
            return afterColon.ifBlank { trimmed }.take(64)
        }

        fun detectPlatform(context: Context): String {
            val uiMode = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
            val isTv = uiMode?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
            return if (isTv) "android_tv" else "android"
        }

        fun classifyPlaybackException(error: PlaybackException): TelemetryErrorClassification {
            val httpStatus = generateSequence(error as Throwable) { it.cause }
                .take(8)
                .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
                .firstOrNull()
                ?.responseCode
            return classifyErrorCode(error.errorCode, httpStatus)
        }

        fun classifyErrorCode(errorCode: Int, httpStatus: Int? = null): TelemetryErrorClassification {
            if (httpStatus != null) {
                val code = when (httpStatus) {
                    401, 403 -> "HTTP_403"
                    404, 410 -> "HTTP_404"
                    in 500..599 -> "HTTP_5XX"
                    else -> "SOURCE_ERROR"
                }
                return TelemetryErrorClassification(code, httpStatus)
            }
            val code = when (errorCode) {
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "TIMEOUT"
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> "NETWORK"
                PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                PlaybackException.ERROR_CODE_DECODING_FAILED,
                PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES -> "DECODER"
                PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
                PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> "MANIFEST"
                PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> "HLS_STALL"
                else -> "UNKNOWN"
            }
            return TelemetryErrorClassification(code, null)
        }
    }
}
