package play.ott.nativeapp.playback

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.drm.ExoMediaDrm
import java.io.BufferedInputStream
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import play.ott.nativeapp.core.DrmConfig
import play.ott.nativeapp.core.DrmScheme
import play.ott.nativeapp.core.RemoteTransportPolicy

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = PlaybackTestApplication::class)
class ScopedDrmCallbackTest {
    @Test fun `strict DRM rejects cleartext licenses and device supplied provisioning URLs before connecting`() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 200
            withClient { client ->
                val url = "http://127.0.0.1:${server.localPort}/opaque-id"
                assertThrows(IllegalArgumentException::class.java) {
                    ScopedDrmCallback(DrmConfig(DrmScheme.WIDEVINE, url,
                        mapOf("X-License" to "private")), client, RemoteTransportPolicy.HTTPS_ONLY)
                }
                val callback = ScopedDrmCallback(DrmConfig(DrmScheme.WIDEVINE,
                    "https://license.example.test/opaque-id"), client, RemoteTransportPolicy.HTTPS_ONLY)
                assertThrows(Exception::class.java) {
                    callback.executeProvisionRequest(C.WIDEVINE_UUID,
                        ExoMediaDrm.ProvisionRequest("private-device-challenge".toByteArray(), url))
                }
                assertThrows(java.net.SocketTimeoutException::class.java) { server.accept().close() }
            }
        }
    }

    @Test fun `license POST uses raw challenge and item scoped headers while provisioning receives none`() {
        HttpFixture(List(3) { Reply() }).use { server ->
            withClient { client ->
                val first = ScopedDrmCallback(DrmConfig(DrmScheme.WIDEVINE, server.url("license-a"),
                    mapOf("Authorization" to "Bearer A", "X-License" to "private")), client)
                val second = ScopedDrmCallback(DrmConfig(DrmScheme.CLEARKEY, server.url("license-b"),
                    mapOf("Authorization" to "Bearer B")), client)
                val challenge = byteArrayOf(0, 1, 0x7f, -1)
                assertArrayEquals(byteArrayOf(1, 2, 3), first.executeKeyRequest(C.WIDEVINE_UUID,
                    ExoMediaDrm.KeyRequest(challenge, "https://ignored.test/manifest-license")).data)
                second.executeKeyRequest(C.CLEARKEY_UUID, ExoMediaDrm.KeyRequest("{\"kids\":[]}".toByteArray(), ""))
                first.executeProvisionRequest(C.WIDEVINE_UUID,
                    ExoMediaDrm.ProvisionRequest("synthetic-provision".toByteArray(), server.url("provision")))
                val requests = server.requests()
                assertEquals("POST /license-a HTTP/1.1", requests[0].line)
                assertArrayEquals(challenge, requests[0].body)
                assertEquals("Bearer A", requests[0].headers["authorization"])
                assertEquals("application/octet-stream", requests[0].headers["content-type"])
                assertEquals("private", requests[0].headers["x-license"])
                assertEquals("Bearer B", requests[1].headers["authorization"])
                assertEquals("application/json", requests[1].headers["content-type"])
                assertFalse(requests[1].headers.containsKey("x-license"))
                assertTrue(requests[2].line.startsWith("POST /provision "))
                assertFalse(requests[2].headers.containsKey("authorization"))
                assertFalse(requests[2].headers.containsKey("x-license"))
                assertTrue(requests[2].body.toString(Charsets.UTF_8).contains("synthetic-provision"))
            }
        }
    }

    @Test fun `same origin 307 preserves challenge but another origin never receives credentials`() {
        HttpFixture(listOf(Reply(307, "/final"), Reply())).use { server ->
            withClient { client ->
                val callback = ScopedDrmCallback(DrmConfig(DrmScheme.WIDEVINE, server.url("redirect"),
                    mapOf("Authorization" to "Bearer private")), client)
                val challenge = "native-challenge".toByteArray()
                callback.executeKeyRequest(C.WIDEVINE_UUID, ExoMediaDrm.KeyRequest(challenge, ""))
                val requests = server.requests()
                assertEquals(2, requests.size)
                assertEquals("POST /final HTTP/1.1", requests[1].line)
                assertArrayEquals(challenge, requests[1].body)
                assertEquals("Bearer private", requests[1].headers["authorization"])
            }
        }
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { otherOrigin ->
            otherOrigin.soTimeout = 250
            HttpFixture(listOf(Reply(307, "http://127.0.0.1:${otherOrigin.localPort}/stolen"))).use { server ->
                withClient { client ->
                    val callback = ScopedDrmCallback(DrmConfig(DrmScheme.WIDEVINE, server.url("redirect"),
                        mapOf("Authorization" to "Bearer private")), client)
                    assertThrows(Exception::class.java) {
                        callback.executeKeyRequest(C.WIDEVINE_UUID, ExoMediaDrm.KeyRequest(byteArrayOf(1), ""))
                    }
                    assertEquals(1, server.requests().size)
                    assertThrows(java.net.SocketTimeoutException::class.java) { otherOrigin.accept().close() }
                }
            }
        }
    }

    @Test fun `PlayReady uses native SOAP defaults and explicit content type overrides without duplicate casing`() {
        HttpFixture(List(2) { Reply() }).use { server ->
            withClient { client ->
                ScopedDrmCallback(DrmConfig(DrmScheme.PLAYREADY, server.url("playready")), client)
                    .executeKeyRequest(C.PLAYREADY_UUID, ExoMediaDrm.KeyRequest("<soap/>".toByteArray(), ""))
                ScopedDrmCallback(DrmConfig(DrmScheme.WIDEVINE, server.url("widevine"),
                    mapOf("content-type" to "application/custom")), client)
                    .executeKeyRequest(C.WIDEVINE_UUID, ExoMediaDrm.KeyRequest(byteArrayOf(1), ""))
                val requests = server.requests()
                assertEquals("text/xml", requests[0].headers["content-type"])
                assertTrue(requests[0].headers["soapaction"].orEmpty().contains("AcquireLicense"))
                assertEquals("application/custom", requests[1].headers["content-type"])
            }
        }
    }

    @Test fun `error and redirect response bodies are bounded before the HTTP data source buffers them`() {
        for (reply in listOf(Reply(403, bodySize = 4 * 1024 * 1024 + 1),
            Reply(403, bodySize = 4 * 1024 * 1024 + 1, chunked = true),
            Reply(500, bodySize = 4 * 1024 * 1024 + 1, chunked = true, gzip = true),
            Reply(307, "/unused", bodySize = 4 * 1024 * 1024 + 1, chunked = true))) {
            HttpFixture(listOf(reply)).use { server ->
                withClient { client ->
                    val callback = ScopedDrmCallback(DrmConfig(DrmScheme.WIDEVINE, server.url("license")), client)
                    val error = assertThrows(Exception::class.java) {
                        callback.executeKeyRequest(C.WIDEVINE_UUID, ExoMediaDrm.KeyRequest(byteArrayOf(1), ""))
                    }
                    val messages = generateSequence(error as Throwable) { it.cause }.map { it.message.orEmpty() }.toList()
                    assertTrue("Oversized error was not bounded before buffering", messages.any { "size limit" in it })
                }
            }
        }
    }

    private inline fun withClient(block: (OkHttpClient) -> Unit) {
        val client = OkHttpClient()
        try { block(client) } finally {
            client.dispatcher.cancelAll()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    private data class Reply(val status: Int = 200, val location: String? = null,
        val bodySize: Int = 3, val chunked: Boolean = false, val gzip: Boolean = false)
    private data class Request(val line: String, val headers: Map<String, String>, val body: ByteArray)

    private class HttpFixture(replies: List<Reply>) : Closeable {
        private val socket = ServerSocket(0, 3, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 5000 }
        private val executor = Executors.newSingleThreadExecutor()
        private val result = executor.submit<List<Request>> {
            replies.map { reply ->
                socket.accept().use { connection ->
                    connection.soTimeout = 5000
                    val input = BufferedInputStream(connection.getInputStream())
                    val line = input.line()
                    val headers = linkedMapOf<String, String>()
                    while (true) {
                        val header = input.line()
                        if (header.isEmpty()) break
                        headers[header.substringBefore(':').lowercase()] = header.substringAfter(':').trim()
                    }
                    val body = ByteArray(headers["content-length"]?.toInt() ?: 0)
                    var read = 0
                    while (read < body.size) {
                        val count = input.read(body, read, body.size - read)
                        check(count > 0)
                        read += count
                    }
                    val location = reply.location?.let { "Location: $it\r\n" }.orEmpty()
                    val responseBody = if (reply.bodySize == 3) byteArrayOf(1, 2, 3) else ByteArray(reply.bodySize)
                    val wireBody = if (reply.gzip) java.io.ByteArrayOutputStream().let { output ->
                        java.util.zip.GZIPOutputStream(output).use { it.write(responseBody) }
                        output.toByteArray()
                    } else responseBody
                    connection.getOutputStream().apply {
                        val encoding = if (reply.gzip) "Content-Encoding: gzip\r\n" else ""
                        val framing = if (reply.chunked) "Transfer-Encoding: chunked" else "Content-Length: ${wireBody.size}"
                        write("HTTP/1.1 ${reply.status} Test\r\n$location$encoding$framing\r\nConnection: close\r\n\r\n".toByteArray())
                        if (reply.chunked) write("${wireBody.size.toString(16)}\r\n".toByteArray())
                        write(wireBody)
                        if (reply.chunked) write("\r\n0\r\n\r\n".toByteArray())
                        flush()
                    }
                    Request(line, headers, body)
                }
            }
        }
        fun url(path: String): String = "http://127.0.0.1:${socket.localPort}/$path"
        fun requests(): List<Request> = result.get(5, TimeUnit.SECONDS)
        override fun close() { socket.close(); executor.shutdownNow() }

        private fun BufferedInputStream.line(): String {
            val value = StringBuilder()
            while (true) {
                val next = read()
                check(next >= 0)
                if (next == 10) return value.toString().removeSuffix("\r")
                value.append(next.toChar())
                check(value.length <= 16384)
            }
        }
    }
}
