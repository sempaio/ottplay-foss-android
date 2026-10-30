package play.ott.nativeapp.core

import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate

class RemoteTransportPolicyTest {
    @Test fun `strict providers reject HTTP before sending credentials or EPG requests`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val repository = ProviderRepository(transportPolicy = RemoteTransportPolicy.HTTPS_ONLY)
            val sourceUrl = server.url("/opaque-id").toString()
            for (kind in SourceKind.entries) {
                val source = SourceConfig("source", "Source", kind, sourceUrl,
                    username = "private-user", password = "private-password", mac = "00:1A:79:01:02:03",
                    headers = mapOf("X-Opaque" to "private-header"))
                val error = runCatching { repository.load(source) }.exceptionOrNull()
                assertTrue(error is ProviderException)
                assertTrue(error.message.orEmpty().contains("HTTPS"))
                assertFalse(error.message.orEmpty().contains("private"))
            }
            assertTrue(runCatching { repository.loadEpg(sourceUrl, mapOf("X-Opaque" to "secret")) }.isFailure)
            assertNull(server.takeRequest(150, TimeUnit.MILLISECONDS))
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun `HTTPS playlist cannot resolve an HTTP stream and local playlist follows same network policy`() = runBlocking {
        tlsServer { server, client ->
            server.enqueue(MockResponse().setBody("#EXTM3U\n#EXTINF:-1,One\nhttp://media.example.test/opaque-id"))
            val repository = ProviderRepository(client, RemoteTransportPolicy.HTTPS_ONLY)
            val source = SourceConfig("source", "HTTPS playlist", SourceKind.M3U, server.url("/list").toString())
            val entry = repository.load(source).entries.single()
            assertTrue(runCatching { repository.resolve(source, entry) }.isFailure)
            assertTrue(runCatching { repository.resolve(source.copy(url = "content://documents/list"), entry) }.isFailure)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun `provider HTTPS redirects cannot downgrade to HTTP with opaque credentials`() = runBlocking {
        MockWebServer().use { cleartext ->
            cleartext.start()
            tlsServer { server, client ->
                server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", cleartext.url("/opaque-credential")))
                val source = SourceConfig("source", "Secure", SourceKind.M3U, server.url("/list").toString(),
                    headers = mapOf("X-Provider-Session" to "private"))
                val error = assertFailsWith<ProviderException> {
                    ProviderRepository(client, RemoteTransportPolicy.HTTPS_ONLY).load(source)
                }
                // A connection/TLS error is not evidence that the downgrade policy ran.
                assertEquals(RemoteTransportPolicy.HTTPS_REQUIRED, error.message)
                val request = assertNotNull(server.takeRequest(5, TimeUnit.SECONDS), "HTTPS source request was not received")
                assertEquals("private", request.getHeader("X-Provider-Session"))
                assertNull(cleartext.takeRequest(150, TimeUnit.MILLISECONDS))
                assertEquals(0, cleartext.requestCount)
            }
        }
    }

    @Test fun `HTTP boundary protects generated resource URLs and automatic downgrade redirects`() {
        MockWebServer().use { cleartext ->
            cleartext.start()
            tlsServer { server, client ->
                val strict = RemoteTransportPolicy.HTTPS_ONLY.secure(client)
                assertFailsWith<java.io.IOException> {
                    strict.newCall(Request.Builder().url(cleartext.url("/segment-or-key"))
                        .header("X-Provider-Session", "private").build()).execute().close()
                }
                server.enqueue(MockResponse().setBody("secure-media"))
                strict.newCall(Request.Builder().url(server.url("/segment")).build()).execute().use {
                    assertEquals("secure-media", it.body.string())
                }
                server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", cleartext.url("/secret-segment")))
                assertFailsWith<java.io.IOException> {
                    strict.newCall(Request.Builder().url(server.url("/redirect"))
                        .header("X-Provider-Session", "private").build()).execute().close()
                }
                assertEquals(2, server.requestCount)
                assertNull(cleartext.takeRequest(150, TimeUnit.MILLISECONDS))
                assertEquals(0, cleartext.requestCount)
            }
        }
    }

    @Test fun `explicit compatible distribution keeps HTTP provider support`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("#EXTM3U\n#EXTINF:-1,One\nhttp://media.example.test/one"))
            server.start()
            val source = SourceConfig("source", "Compatible", SourceKind.M3U, server.url("/list").toString())
            val repository = ProviderRepository(transportPolicy = RemoteTransportPolicy.HTTP_COMPATIBLE)
            val entry = repository.load(source).entries.single()
            assertEquals(entry.url, repository.resolve(source, entry).url)
            assertEquals(1, server.requestCount)
        }
    }

    private inline fun <T> tlsServer(block: (MockWebServer, OkHttpClient) -> T): T {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost")
            .addSubjectAlternativeName("127.0.0.1").build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val client = OkHttpClient.Builder().sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager).build()
        try {
            return MockWebServer().use { server ->
                server.useHttps(serverCertificates.sslSocketFactory(), false)
                server.start()
                block(server, client)
            }
        } finally {
            client.dispatcher.cancelAll()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }
}
