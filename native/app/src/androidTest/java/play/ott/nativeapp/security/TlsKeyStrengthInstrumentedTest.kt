package play.ott.nativeapp.security

import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.net.InetAddress
import java.security.KeyPairGenerator
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread
import okhttp3.ConnectionPool
import okhttp3.ConnectionSpec
import okhttp3.Protocol
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.TlsVersion
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import play.ott.nativeapp.core.RemoteTransportPolicy

/** Exercise the production boundary on Android's actual TLS provider. */
@RunWith(AndroidJUnit4::class)
class TlsKeyStrengthInstrumentedTest {
    @Test
    fun verifiedChainsEnforceKeyStrengthBeforeHttp() {
        val results = JSONArray()
        val context = SSLContext.getDefault()
        val supported = context.supportedSSLParameters.protocols.toSet()
        val requested = listOf(TlsVersion.TLS_1_2, TlsVersion.TLS_1_3)
        assertTrue("TLS 1.2 must be available", TlsVersion.TLS_1_2.javaName in supported)
        val unsupported = requested.filter { it.javaName !in supported }.map { it.javaName }
        Log.i("OttplayTlsKeyProbe", JSONObject().put("sdk", Build.VERSION.SDK_INT)
            .put("provider", context.provider.name)
            .put("unsupportedRequestedProtocols", JSONArray(unsupported)).toString())
        for (tls in requested.filter { it.javaName in supported }) {
            for (case in listOf("strong", "strong-ec", "weak-leaf", "weak-intermediate", "weak-root", "untrusted", "wrong-host")) {
                val result = probe(case, tls)
                results.put(result)
                Log.i("OttplayTlsKeyProbe", result.toString())
            }
        }
        val report = JSONObject()
            .put("sdk", Build.VERSION.SDK_INT)
            .put("fingerprint", Build.FINGERPRINT)
            .put("provider", context.provider.name)
            .put("unsupportedRequestedProtocols", JSONArray(unsupported))
            .put("cases", results)
        // Keep each individual logcat row small enough to survive Android's message limit.
        println("OTTPLAY_TLS_KEY_PROBE_JSON=$report")
        for (index in 0 until results.length()) {
            val result = results.getJSONObject(index)
            when (result.getString("case")) {
                "strong", "strong-ec" -> {
                    assertTrue(result.toString(), result.getBoolean("accepted"))
                    assertEquals(result.toString(), 1, result.getInt("httpRequests"))
                    assertTrue(result.toString(), result.getBoolean("syntheticAuthReceived"))
                    assertEquals(result.getString("tls"), result.getString("negotiatedTls"))
                }
                "untrusted", "wrong-host" -> {
                    assertFalse(result.toString(), result.getBoolean("accepted"))
                    assertEquals(result.toString(), 0, result.getInt("httpRequests"))
                }
                else -> {
                    assertFalse(result.toString(), result.getBoolean("accepted"))
                    assertEquals(result.toString(), 0, result.getInt("httpRequests"))
                    assertFalse(result.toString(), result.getBoolean("syntheticAuthReceived"))
                    assertTrue(result.toString(), result.getString("error").contains("unsupported or undersized key"))
                }
            }
        }
    }

    private fun probe(case: String, tls: TlsVersion): JSONObject {
        val root = certificate("synthetic-root", if (case == "weak-root") 1024 else 2048, 1)
        val intermediate = certificate("synthetic-intermediate", if (case == "weak-intermediate") 1024 else 2048, 0, root)
        val leaf = certificate("synthetic-leaf", if (case == "weak-leaf") 1024 else 2048, null, intermediate,
            if (case == "wrong-host") "wrong.example.test" else "127.0.0.1", ec = case == "strong-ec")
        // Confirm the generated signatures independently of the TLS acceptance result.
        root.certificate.verify(root.keyPair.public)
        intermediate.certificate.verify(root.keyPair.public)
        leaf.certificate.verify(intermediate.keyPair.public)
        listOf(root, intermediate, leaf).forEach { it.certificate.checkValidity() }
        val serverCertificates = HandshakeCertificates.Builder()
            .heldCertificate(leaf, intermediate.certificate).build()
        val trust = if (case == "untrusted") certificate("unrelated-root", 2048, 1) else root
        val clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(trust.certificate).build()
        val base = OkHttpClient.Builder()
            .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
            .connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).callTimeout(10, TimeUnit.SECONDS)
            .build()
        val client = RemoteTransportPolicy.HTTPS_ONLY.secure(base).newBuilder()
            .connectionSpecs(listOf(ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS).tlsVersions(tls).build()))
            .build()
        try {
            return LocalTlsServer(serverCertificates, tls).use { server ->
                var accepted = false
                var error = ""
                var negotiatedTls = ""
                try {
                    client.newCall(Request.Builder().url("https://127.0.0.1:${server.port}/probe")
                        .header("Authorization", "Bearer synthetic-key-probe-only").build()).execute().use { response ->
                        accepted = response.isSuccessful && response.body.string() == "synthetic-response"
                        negotiatedTls = response.handshake?.tlsVersion?.javaName.orEmpty()
                    }
                } catch (failure: Exception) {
                    error = "${failure.javaClass.simpleName}: ${failure.message}"
                }
                server.awaitCompletion()
                JSONObject().put("case", case).put("tls", tls.javaName)
                    .put("negotiatedTls", negotiatedTls)
                    .put("accepted", accepted).put("httpRequests", server.requests.get())
                    .put("syntheticAuthReceived", server.syntheticAuth.get())
                    .put("serverError", server.failure.get())
                    .put("error", error.take(1000))
            }
        } finally {
            client.dispatcher.cancelAll()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
            client.cache?.close()
        }
    }

    @Test
    fun reusedConnectionsAndResumedSessionsStillCheckBeforeOriginData() {
        for (weak in listOf(false, true)) {
            val root = certificate("reuse-root", 2048, 1)
            val leaf = certificate("reuse-leaf", if (weak) 1024 else 2048, null, root, "127.0.0.1")
            val serverCertificates = HandshakeCertificates.Builder().heldCertificate(leaf).build()
            val clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(root.certificate).build()
            for (resumed in listOf(false, true)) {
                val base = tlsClient(clientCertificates)
                val safe = RemoteTransportPolicy.HTTPS_ONLY.secure(base).newBuilder()
                    .connectionSpecs(base.connectionSpecs).build()
                // Warm the real provider's session cache with the same normally verified SSL context.
                // Only this synthetic pre-fix calibration deliberately omits the new HTTP interceptor.
                val warm = if (weak) base else safe
                val connections = if (resumed) 3 else 1
                val perConnection = if (resumed) 1 else 2
                try {
                    LocalTlsServer(serverCertificates, TlsVersion.TLS_1_2, connections, perConnection).use { server ->
                        assertEquals("synthetic-response", get(warm, server))
                        if (resumed) assertEquals("synthetic-response", get(warm, server))
                        if (weak) {
                            assertKeyRejected(safe, "https://127.0.0.1:${server.port}/probe")
                        } else {
                            assertEquals("synthetic-response", get(safe, server))
                        }
                        server.awaitCompletion()
                        assertEquals("connection count: ${server.failure.get()}", connections, server.connections.get())
                        val expectedRequests = if (resumed) { if (weak) 2 else 3 } else { if (weak) 1 else 2 }
                        assertEquals(expectedRequests, server.requests.get())
                        val sessions = server.sessions.toList()
                        assertEquals(connections, sessions.size)
                        if (resumed) {
                            assertTrue("TLS 1.2 session ID must be available", sessions[0].isNotEmpty())
                            assertEquals("baseline must actually resume", sessions[0], sessions[1])
                            assertEquals("guarded connection must actually resume", sessions[0], sessions[2])
                        }
                        Log.i("OttplayTlsKeyProbe", JSONObject().put("case", "reuse")
                            .put("weak", weak).put("resumed", resumed).put("connections", server.connections.get())
                            .put("httpRequests", server.requests.get()).put("sessionIdsEqual", sessions.distinct().size == 1).toString())
                    }
                } finally { closeClient(base) }
            }
        }
    }

    @Test
    fun redirectsAndCompatibleDistributionCannotSendDataToWeakHttpsPeer() {
        val root = certificate("redirect-root", 2048, 1)
        val strong = certificate("redirect-strong", 2048, null, root, "127.0.0.1")
        val weak = certificate("redirect-weak", 1024, null, root, "127.0.0.1")
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(root.certificate).build()
        for (policy in listOf(RemoteTransportPolicy.HTTPS_ONLY, RemoteTransportPolicy.HTTP_COMPATIBLE)) {
            val base = tlsClient(trust)
            val client = policy.secure(base).newBuilder().connectionSpecs(base.connectionSpecs).build()
            try {
                LocalTlsServer(HandshakeCertificates.Builder().heldCertificate(weak).build(), TlsVersion.TLS_1_2).use { target ->
                    val targetUrl = "https://127.0.0.1:${target.port}/probe"
                    val response = "HTTP/1.1 307 Temporary Redirect\r\nLocation: $targetUrl\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                    LocalTlsServer(HandshakeCertificates.Builder().heldCertificate(strong).build(), TlsVersion.TLS_1_2,
                        response = response).use { origin ->
                        assertKeyRejected(client, "https://127.0.0.1:${origin.port}/probe", post = true)
                        origin.awaitCompletion()
                        target.awaitCompletion()
                        assertEquals(1, origin.requests.get())
                        assertTrue(origin.syntheticAuth.get())
                        assertEquals(0, target.requests.get())
                        assertFalse(target.syntheticAuth.get())
                        assertEquals(0, target.bodyBytes.get())
                    }
                }
            } finally { closeClient(base) }
        }
    }

    private fun tlsClient(certificates: HandshakeCertificates) = OkHttpClient.Builder()
        .sslSocketFactory(certificates.sslSocketFactory(), certificates.trustManager)
        .protocols(listOf(Protocol.HTTP_1_1))
        .connectionSpecs(listOf(ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS).tlsVersions(TlsVersion.TLS_1_2).build()))
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).callTimeout(10, TimeUnit.SECONDS)
        .connectionPool(ConnectionPool()).build()

    private fun get(client: OkHttpClient, server: LocalTlsServer): String? = client.newCall(Request.Builder()
        .url("https://127.0.0.1:${server.port}/probe").header("Authorization", "Bearer synthetic-key-probe-only")
        .build()).execute().use { it.body.string() }

    private fun assertKeyRejected(client: OkHttpClient, url: String, post: Boolean = false) {
        val request = Request.Builder().url(url).header("Authorization", "Bearer synthetic-key-probe-only")
        if (post) request.post("synthetic-body-only".toRequestBody())
        try {
            client.newCall(request.build()).execute().use { fail("Weak peer accepted HTTP: ${it.code}") }
        } catch (failure: javax.net.ssl.SSLPeerUnverifiedException) {
            assertTrue(failure.toString(), failure.message.orEmpty().contains("unsupported or undersized key"))
        }
    }

    private fun closeClient(client: OkHttpClient) {
        client.dispatcher.cancelAll()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        client.cache?.close()
    }

    private class LocalTlsServer(certificates: HandshakeCertificates, tls: TlsVersion,
                                 expectedConnections: Int = 1, requestsPerConnection: Int = 1,
                                 response: String? = null) : java.io.Closeable {
        private val listener = certificates.sslContext().serverSocketFactory
            .createServerSocket(0, 1, InetAddress.getByName("127.0.0.1")) as SSLServerSocket
        val port = listener.localPort
        val requests = AtomicInteger()
        val connections = AtomicInteger()
        val bodyBytes = AtomicInteger()
        val sessions: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val syntheticAuth = AtomicBoolean()
        val failure = AtomicReference("")
        private val peer = AtomicReference<SSLSocket?>()
        private val worker: Thread

        init {
            listener.enabledProtocols = arrayOf(tls.javaName)
            listener.soTimeout = 5000
            worker = thread(name = "synthetic-tls-probe", isDaemon = true) {
                try {
                    repeat(expectedConnections) {
                        (listener.accept() as SSLSocket).use { socket ->
                            peer.set(socket)
                            connections.incrementAndGet()
                            socket.soTimeout = 5000
                            socket.startHandshake()
                            sessions.add(socket.session.id.joinToString("") { "%02x".format(it) })
                            val input = socket.inputStream.bufferedReader(Charsets.US_ASCII)
                            for (number in 1..requestsPerConnection) {
                                val request = input.readLine() ?: break
                                check(request == "GET /probe HTTP/1.1" || request == "POST /probe HTTP/1.1") { "Unexpected synthetic request" }
                                requests.incrementAndGet()
                                var ended = false
                                var length = 0
                                for (index in 0 until 64) {
                                    val line = input.readLine() ?: error("Incomplete request headers")
                                    check(line.length <= 8192)
                                    if (line.isEmpty()) { ended = true; break }
                                    if (line.startsWith("Content-Length:", ignoreCase = true)) {
                                        length = line.substringAfter(':').trim().toInt()
                                        check(length in 0..1024)
                                    }
                                    if (line.equals("Authorization: Bearer synthetic-key-probe-only", ignoreCase = true)) syntheticAuth.set(true)
                                }
                                check(ended) { "Request headers exceeded bound" }
                                repeat(length) { check(input.read() >= 0); bodyBytes.incrementAndGet() }
                                val connection = if (number < requestsPerConnection) "keep-alive" else "close"
                                val reply = response ?: "HTTP/1.1 200 OK\r\nContent-Length: 18\r\nConnection: $connection\r\n\r\nsynthetic-response"
                                socket.outputStream.write(reply.toByteArray(Charsets.US_ASCII))
                                socket.outputStream.flush()
                            }
                        }
                    }
                } catch (error: Exception) {
                    failure.set("${error.javaClass.simpleName}: ${error.message}".take(1000))
                }
            }
        }

        fun awaitCompletion() { worker.join(6000) }

        override fun close() {
            listener.close()
            peer.get()?.close()
            worker.join(2000)
            check(!worker.isAlive) { "Synthetic TLS server did not stop" }
        }
    }

    private fun certificate(name: String, bits: Int, caDepth: Int?, signer: HeldCertificate? = null,
                            host: String? = null, ec: Boolean = false): HeldCertificate {
        val generator = KeyPairGenerator.getInstance(if (ec) "EC" else "RSA")
        generator.initialize(if (ec) 256 else bits)
        val builder = HeldCertificate.Builder().commonName(name).keyPair(generator.generateKeyPair())
            .duration(1, TimeUnit.HOURS)
        if (caDepth != null) builder.certificateAuthority(caDepth)
        if (signer != null) builder.signedBy(signer)
        if (host != null) builder.addSubjectAlternativeName(host)
        return builder.build()
    }
}
