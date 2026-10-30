package play.ott.nativeapp.core

import java.net.SocketTimeoutException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlin.test.*

class XtreamTimeoutRecoveryTest {
    private fun exercise(failingAction: String, failures: Int, expectedSuccess: Boolean, httpStatus: Int? = null): List<String> = runBlocking {
        val calls = mutableListOf<String>()
        var attempts = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val action = chain.request().url.queryParameter("action").orEmpty()
            calls += action
            if (action == failingAction && ++attempts <= failures) {
                if (httpStatus != null) return@addInterceptor Response.Builder().request(chain.request())
                    .protocol(Protocol.HTTP_1_1).code(httpStatus).message("Denied")
                    .body("{}".toResponseBody()).build()
                throw SocketTimeoutException("private-value")
            }
            val body = when (action) {
                "" -> """{"user_info":{"auth":1,"status":"Active"}}"""
                "get_live_streams" -> """[{"stream_id":42,"name":"Fixture"}]"""
                else -> "[]"
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(body.toResponseBody()).build()
        }.build()
        try {
            val source = SourceConfig("fixture", "Fixture", SourceKind.XTREAM,
                "https://fixture.invalid", "test-user", "test-password")
            if (expectedSuccess) {
                val catalog = ProviderRepository(client).load(source)
                assertEquals("42", catalog.entries.single().providerId)
            } else {
                val error = assertFailsWith<ProviderException> { ProviderRepository(client).load(source) }
                if (httpStatus == null) assertEquals(ProviderFailure.TIMEOUT, error.failure)
                else assertEquals(httpStatus, error.statusCode)
                assertFalse(error.message.orEmpty().contains("private-value"))
            }
            calls.toList()
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    @Test fun `alternate route rotates only target host and preserves all DNS addresses`() {
        val addresses = listOf("192.0.2.1", "192.0.2.2", "2001:db8::1").map(java.net.InetAddress::getByName)
        val dns = AlternateRouteDns(object : okhttp3.Dns {
            override fun lookup(hostname: String) = addresses
        }, "fixture.invalid")
        assertEquals(listOf(addresses[1], addresses[2], addresses[0]), dns.lookup("fixture.invalid"))
        assertEquals(addresses, dns.lookup("other.invalid"))
        val single = AlternateRouteDns(object : okhttp3.Dns {
            override fun lookup(hostname: String) = addresses.take(1)
        }, "fixture.invalid")
        assertEquals(addresses.take(1), single.lookup("fixture.invalid"))
    }

    @Test fun `one transient catalog timeout recovers without repeating completed stages`() {
        val calls = exercise("get_vod_streams", 1, true)
        assertEquals(2, calls.count { it == "get_vod_streams" })
        assertEquals(1, calls.count { it == "" })
        assertEquals(1, calls.count { it == "get_live_streams" })
        assertEquals(1, calls.count { it == "get_series" })
    }

    @Test fun `persistent timeout is reported after exactly two attempts`() {
        val calls = exercise("get_live_streams", 100, false)
        assertEquals(2, calls.count { it == "get_live_streams" })
    }

    @Test fun `account HTTP denial is not retried`() {
        for (status in listOf(401, 403, 429, 500)) {
            assertEquals(listOf(""), exercise("", 100, false, status))
        }
    }

    @Test fun `transient account GET timeout recovers`() {
        assertEquals(2, exercise("", 1, true).count { it == "" })
    }

    @Test fun `persistent account GET timeout stops after two attempts`() {
        assertEquals(listOf("", ""), exercise("", 100, false))
    }
}
