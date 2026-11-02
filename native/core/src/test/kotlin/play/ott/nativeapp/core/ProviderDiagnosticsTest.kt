package play.ott.nativeapp.core

import java.net.*
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import kotlin.test.*

class ProviderDiagnosticsTest {
    @Test fun `transport errors retain safe categories through asynchronous HTTP callbacks`() = runBlocking {
        val failures = listOf(
            UnknownHostException("https://fixture.invalid/?password=synthetic-secret") to ProviderFailure.DNS,
            SocketTimeoutException("synthetic-secret") to ProviderFailure.TIMEOUT,
            SSLHandshakeException("synthetic-secret") to ProviderFailure.TLS,
            ConnectException("synthetic-secret") to ProviderFailure.CONNECTION,
        )
        for ((failure, expected) in failures) {
            val client = OkHttpClient.Builder().addInterceptor { throw failure }.build()
            try {
                val repo = ProviderRepository(client)
                val error = assertFailsWith<ProviderException> { repo.authenticate(SourceConfig("test", "Fixture", SourceKind.XTREAM, "https://fixture.invalid", "fixture-user", "fixture-password")) }
                assertEquals(expected, error.failure)
                assertFalse(error.message.orEmpty().contains("synthetic-secret"))
                assertNull(error.cause)
            } finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
        }
    }
    @Test fun `HTTP 200 auth denial inactive account and invalid payload are distinct`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val source = SourceConfig("test", "Fixture", SourceKind.XTREAM, server.url("/").toString(), "fixture-user", "fixture-password")
            val repo = ProviderRepository()
            for ((body, reason) in listOf(
                """{"user_info":{"auth":0,"status":"Disabled"}}""" to ProviderFailure.AUTHENTICATION,
                """{"user_info":{"auth":1,"status":"Expired"}}""" to ProviderFailure.ACCOUNT_INACTIVE,
                """{"result":"not an account"}""" to ProviderFailure.INVALID_RESPONSE,
                "<html>unavailable</html>" to ProviderFailure.INVALID_RESPONSE,
            )) {
                server.enqueue(MockResponse().setBody(body))
                val error = assertFailsWith<ProviderException> { repo.authenticate(source) }
                assertEquals(reason, error.failure)
                assertNull(error.statusCode, "Never invent HTTP status for a 200 response")
            }
        }
    }
}
