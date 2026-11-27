package play.ott.nativeapp.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class GoogleCastProtocolTest {
    @Test fun castMessageProtobufRoundTripPreservesAllFields() {
        val original = CastWireMessage(
            sourceId = GoogleCastProtocol.SENDER_ID,
            destinationId = GoogleCastProtocol.RECEIVER_ID,
            namespace = GoogleCastProtocol.NS_RECEIVER,
            payloadUtf8 = GoogleCastProtocol.buildLaunchJson(requestId = 7),
        )
        val framed = GoogleCastProtocol.encodeFramed(original)
        val length = ByteBuffer.wrap(framed, 0, 4).int
        assertEquals(framed.size - 4, length)

        val decoded = GoogleCastProtocol.decodeProtobuf(framed.copyOfRange(4, framed.size))
        assertNotNull(decoded)
        assertEquals(original.sourceId, decoded!!.sourceId)
        assertEquals(original.destinationId, decoded.destinationId)
        assertEquals(original.namespace, decoded.namespace)
        assertEquals(original.payloadUtf8, decoded.payloadUtf8)
    }

    @Test fun parseReceiverStatusExtractsDefaultMediaReceiverTransportId() {
        val payload = """
            {
              "requestId": 1,
              "type": "RECEIVER_STATUS",
              "status": {
                "applications": [
                  {
                    "appId": "CC1AD845",
                    "sessionId": "sess-999",
                    "transportId": "web-42"
                  }
                ],
                "volume": { "level": 0.72, "muted": false }
              }
            }
        """.trimIndent()
        val status = GoogleCastProtocol.parseReceiverStatus(payload)
        assertNotNull(status)
        assertEquals("CC1AD845", status!!.appId)
        assertEquals("sess-999", status.sessionId)
        assertEquals("web-42", status.transportId)
        assertEquals(0.72, status.volumeLevel!!, 0.001)
    }

    @Test fun parseMediaStatusAndLoadMediaJson() {
        val loadJson = GoogleCastProtocol.buildLoadMediaJson(
            requestId = 2,
            mediaUrl = "http://192.168.1.20:51234/cast/abc/live.m3u8",
            title = "Globo News HD",
            contentType = "application/vnd.apple.mpegurl",
            isLive = true,
        )
        assertTrue(loadJson.contains("\"streamType\":\"LIVE\""))
        assertTrue(loadJson.contains("\"contentType\":\"application/vnd.apple.mpegurl\""))
        assertTrue(loadJson.contains("\"hlsSegmentFormat\":\"TS\""))
        assertTrue(loadJson.contains("\"hlsVideoSegmentFormat\":\"MPEG2_TS\""))
        assertTrue(loadJson.contains("\"title\":\"Globo News HD\""))

        val failDetail = GoogleCastProtocol.parseLoadFailureDetail(
            """{"type":"LOAD_FAILED","reason":"MEDIA_SRC_NOT_SUPPORTED","detailedErrorCode":104}""",
        )
        assertEquals("LOAD_FAILED • MEDIA_SRC_NOT_SUPPORTED • code=104", failDetail)

        val mediaStatusPayload = """
            {
              "type": "MEDIA_STATUS",
              "status": [
                {
                  "mediaSessionId": 5,
                  "playerState": "PLAYING",
                  "currentTime": 124.5,
                  "media": { "duration": 5400.0 }
                }
              ]
            }
        """.trimIndent()
        val parsed = GoogleCastProtocol.parseMediaStatus(mediaStatusPayload)
        assertNotNull(parsed)
        assertEquals(5, parsed!!.mediaSessionId)
        assertEquals("PLAYING", parsed.playerState)
        assertEquals(124.5, parsed.currentTimeSec, 0.01)
        assertEquals(5400.0, parsed.durationSec, 0.01)
    }
}
