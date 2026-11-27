package play.ott.nativeapp.cast

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

internal data class CastWireMessage(
    val sourceId: String,
    val destinationId: String,
    val namespace: String,
    val payloadUtf8: String,
)

internal data class CastReceiverAppStatus(
    val appId: String,
    val sessionId: String,
    val transportId: String,
    val volumeLevel: Double?,
)

internal data class CastMediaStatus(
    val mediaSessionId: Int,
    val playerState: String,
    val currentTimeSec: Double,
    val durationSec: Double,
)

/**
 * Pure, dependency-free Google Cast v2 (`CastMessage` protobuf + JSON namespace) codec.
 * Supports Default Media Receiver (`CC1AD845`) on Chromecast, Google TV, Android TV, and Nest Hub.
 */
internal object GoogleCastProtocol {
    const val DEFAULT_MEDIA_RECEIVER_APP_ID = "CC1AD845"
    const val SENDER_ID = "sender-starlink-1"
    const val RECEIVER_ID = "receiver-0"

    const val NS_CONNECTION = "urn:x-cast:com.google.cast.tp.connection"
    const val NS_HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat"
    const val NS_RECEIVER = "urn:x-cast:com.google.cast.receiver"
    const val NS_MEDIA = "urn:x-cast:com.google.cast.media"

    private val json = Json { ignoreUnknownKeys = true }

    /** Encodes a Cast v2 `CastMessage` protobuf with a 4-byte big-endian length prefix. */
    fun encodeFramed(msg: CastWireMessage): ByteArray {
        val body = encodeProtobuf(msg)
        return ByteBuffer.allocate(4 + body.size)
            .putInt(body.size)
            .put(body)
            .array()
    }

    fun encodeProtobuf(msg: CastWireMessage): ByteArray {
        val out = ByteArrayOutputStream(msg.payloadUtf8.length + 96)
        // field 1: protocol_version = 0 (CASTV2_1_0)
        writeVarintField(out, 1, 0L)
        // field 2: source_id
        writeStringField(out, 2, msg.sourceId)
        // field 3: destination_id
        writeStringField(out, 3, msg.destinationId)
        // field 4: namespace
        writeStringField(out, 4, msg.namespace)
        // field 5: payload_type = 0 (STRING)
        writeVarintField(out, 5, 0L)
        // field 6: payload_utf8
        writeStringField(out, 6, msg.payloadUtf8)
        return out.toByteArray()
    }

    fun decodeProtobuf(bytes: ByteArray): CastWireMessage? {
        var idx = 0
        var sourceId = ""
        var destinationId = ""
        var namespace = ""
        var payloadUtf8 = ""

        while (idx < bytes.size) {
            val (tag, afterTag) = readVarint(bytes, idx) ?: return null
            idx = afterTag
            val fieldNum = (tag ushr 3).toInt()
            val wireType = (tag and 0x07L).toInt()
            when (wireType) {
                0 -> {
                    val (_, nextIdx) = readVarint(bytes, idx) ?: return null
                    idx = nextIdx
                }
                2 -> {
                    val (lenLong, start) = readVarint(bytes, idx) ?: return null
                    val len = lenLong.toInt()
                    if (len < 0 || start + len > bytes.size) return null
                    val str = String(bytes, start, len, Charsets.UTF_8)
                    when (fieldNum) {
                        2 -> sourceId = str
                        3 -> destinationId = str
                        4 -> namespace = str
                        6 -> payloadUtf8 = str
                    }
                    idx = start + len
                }
                1 -> idx += 8
                5 -> idx += 4
                else -> return null
            }
        }
        if (namespace.isBlank()) return null
        return CastWireMessage(sourceId, destinationId, namespace, payloadUtf8)
    }

    fun buildConnectJson(): String = """{"type":"CONNECT"}"""
    fun buildCloseJson(): String = """{"type":"CLOSE"}"""
    fun buildPingJson(): String = """{"type":"PING"}"""
    fun buildPongJson(): String = """{"type":"PONG"}"""

    fun buildLaunchJson(requestId: Int, appId: String = DEFAULT_MEDIA_RECEIVER_APP_ID): String =
        buildJsonObject {
            put("type", "LAUNCH")
            put("appId", appId)
            put("requestId", requestId)
        }.toString()

    fun buildStopSessionJson(requestId: Int, sessionId: String): String =
        buildJsonObject {
            put("type", "STOP")
            put("sessionId", sessionId)
            put("requestId", requestId)
        }.toString()

    fun buildSetVolumeJson(requestId: Int, level01: Double): String =
        buildJsonObject {
            put("type", "SET_VOLUME")
            put("requestId", requestId)
            put("volume", buildJsonObject {
                put("level", level01.coerceIn(0.0, 1.0))
                put("muted", false)
            })
        }.toString()

    fun buildLoadMediaJson(
        requestId: Int,
        mediaUrl: String,
        title: String,
        contentType: String,
        isLive: Boolean,
        startPositionSec: Double = 0.0,
    ): String = buildJsonObject {
        put("type", "LOAD")
        put("requestId", requestId)
        put("autoplay", true)
        if (!isLive && startPositionSec > 1.0) {
            put("currentTime", startPositionSec)
        }
        put("media", buildJsonObject {
            put("contentId", mediaUrl)
            put("streamType", if (isLive) "LIVE" else "BUFFERED")
            put("contentType", contentType)
            put("metadata", buildJsonObject {
                put("metadataType", 0)
                put("title", title.ifBlank { "STARLINK TV" })
            })
        })
    }.toString()

    fun buildMediaCommandJson(
        type: String,
        requestId: Int,
        mediaSessionId: Int,
        seekTimeSec: Double? = null,
    ): String = buildJsonObject {
        put("type", type)
        put("requestId", requestId)
        put("mediaSessionId", mediaSessionId)
        if (seekTimeSec != null) {
            put("currentTime", seekTimeSec.coerceAtLeast(0.0))
        }
    }.toString()

    fun buildGetStatusJson(requestId: Int): String =
        buildJsonObject {
            put("type", "GET_STATUS")
            put("requestId", requestId)
        }.toString()

    fun parseJsonObject(payload: String): JsonObject? = runCatching {
        json.parseToJsonElement(payload).jsonObject
    }.getOrNull()

    fun parseReceiverStatus(payload: String, targetAppId: String = DEFAULT_MEDIA_RECEIVER_APP_ID): CastReceiverAppStatus? {
        val root = parseJsonObject(payload) ?: return null
        if (root["type"]?.jsonPrimitive?.content != "RECEIVER_STATUS") return null
        val status = root["status"]?.jsonObject ?: return null
        val volumeLevel = status["volume"]?.jsonObject?.get("level")?.jsonPrimitive?.doubleOrNull
        val apps = status["applications"]?.jsonArray.orEmpty()
        val matched = apps.firstOrNull { elem ->
            elem.jsonObject["appId"]?.jsonPrimitive?.content == targetAppId
        }?.jsonObject ?: return null
        val sessionId = matched["sessionId"]?.jsonPrimitive?.content ?: return null
        val transportId = matched["transportId"]?.jsonPrimitive?.content ?: return null
        return CastReceiverAppStatus(
            appId = targetAppId,
            sessionId = sessionId,
            transportId = transportId,
            volumeLevel = volumeLevel,
        )
    }

    fun parseMediaStatus(payload: String): CastMediaStatus? {
        val root = parseJsonObject(payload) ?: return null
        if (root["type"]?.jsonPrimitive?.content != "MEDIA_STATUS") return null
        val first = root["status"]?.jsonArray?.firstOrNull()?.jsonObject ?: return null
        val sessionId = first["mediaSessionId"]?.jsonPrimitive?.intOrNull ?: return null
        val playerState = first["playerState"]?.jsonPrimitive?.content ?: "UNKNOWN"
        val currentTime = first["currentTime"]?.jsonPrimitive?.doubleOrNull ?: 0.0
        val duration = first["media"]?.jsonObject?.get("duration")?.jsonPrimitive?.doubleOrNull ?: 0.0
        return CastMediaStatus(
            mediaSessionId = sessionId,
            playerState = playerState,
            currentTimeSec = currentTime,
            durationSec = duration,
        )
    }

    fun parseMessageType(payload: String): String? =
        parseJsonObject(payload)?.get("type")?.let { (it as? JsonPrimitive)?.content }

    private fun writeVarintField(out: ByteArrayOutputStream, fieldNum: Int, value: Long) {
        writeVarint(out, ((fieldNum shl 3) or 0).toLong())
        writeVarint(out, value)
    }

    private fun writeStringField(out: ByteArrayOutputStream, fieldNum: Int, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeVarint(out, ((fieldNum shl 3) or 2).toLong())
        writeVarint(out, bytes.size.toLong())
        out.write(bytes)
    }

    private fun writeVarint(out: ByteArrayOutputStream, initialValue: Long) {
        var v = initialValue
        while (true) {
            if ((v and 0x7FL.inv()) == 0L) {
                out.write(v.toInt())
                return
            }
            out.write(((v and 0x7FL) or 0x80L).toInt())
            v = v ushr 7
        }
    }

    private fun readVarint(data: ByteArray, startIdx: Int): Pair<Long, Int>? {
        var result = 0L
        var shift = 0
        var idx = startIdx
        while (idx < data.size && shift < 64) {
            val b = data[idx++].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            if ((b and 0x80) == 0) return result to idx
            shift += 7
        }
        return null
    }
}
