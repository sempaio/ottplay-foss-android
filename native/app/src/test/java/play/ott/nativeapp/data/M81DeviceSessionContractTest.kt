package play.ott.nativeapp.data

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.*

/** Contract tests aligned with M81 panel's /app/auth/{login,validate,logout} 3-device limit. */
class M81DeviceSessionContractTest {
    @Test fun loginRegistersDeviceAndReturnsSessionToken() = runTest {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"authorized":true,"session_token":"tok_12345678901234567890","client_name":"Cliente Teste","status":"ativo"}"""
                )
            )
            server.start()
            val client = M81DeviceSessionClient(server.url("/").toString(), requireHttps = false)
            val result = client.login("user1", "pass1", "android-dev-1")
            assertEquals(
                M81SessionResult.Authorized("tok_12345678901234567890", "Cliente Teste", "ativo"),
                result,
            )
            val request = server.takeRequest()
            assertEquals("/app/auth/login", request.path)
            val body = request.body.readUtf8()
            assertTrue(body.contains("\"login\":\"user1\""))
            assertTrue(body.contains("\"device_id\":\"android-dev-1\""))
        }
    }

    @Test fun validateDetectsRevokedSessionWhenDeviceLimitExceeded() = runTest {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(401).setBody(
                    """{"detail":{"code":"invalid_credentials","message":"Sessão revogada"}}"""
                )
            )
            server.start()
            val client = M81DeviceSessionClient(server.url("/").toString(), requireHttps = false)
            val result = client.validate("tok_12345678901234567890", "android-dev-1")
            assertEquals(M81SessionResult.RevokedOrExpired, result)
            assertEquals("/app/auth/validate", server.takeRequest().path)
        }
    }

    @Test fun validateAcceptsActiveSessionAndPreservesToken() = runTest {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"authorized":true,"client_name":"Maria","status":"ativo"}"""
                )
            )
            server.start()
            val client = M81DeviceSessionClient(server.url("/").toString(), requireHttps = false)
            val result = client.validate("tok_active_123456789012", "android-dev-2")
            assertEquals(
                M81SessionResult.Authorized("tok_active_123456789012", "Maria", "ativo"),
                result,
            )
        }
    }

    @Test fun deniedAndRateLimitedResponsesAreClassified() {
        assertEquals(
            M81SessionResult.Denied("client_blocked"),
            parseSessionResponse(403, """{"detail":{"code":"client_blocked","message":"Bloqueado"}}""", null),
        )
        assertEquals(
            M81SessionResult.Denied("subscription_expired"),
            parseSessionResponse(403, """{"detail":{"code":"subscription_expired","message":"Vencido"}}""", null),
        )
        assertEquals(
            M81SessionResult.Denied("invalid_credentials"),
            parseSessionResponse(401, """{"detail":{"code":"invalid_credentials"}}""", null),
        )
        assertEquals(
            M81SessionResult.RateLimited,
            parseSessionResponse(429, """{"detail":"Muitas tentativas"}""", null),
        )
        assertEquals(
            M81SessionResult.Unavailable,
            parseSessionResponse(503, "Service Unavailable", null),
        )
    }

    @Test fun logoutSendsSessionAndDeviceToServer() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))
            server.start()
            val client = M81DeviceSessionClient(server.url("/").toString(), requireHttps = false)
            client.logout("tok_logout_123456789012", "android-dev-3")
            val request = server.takeRequest()
            assertEquals("/app/auth/logout", request.path)
            val body = request.body.readUtf8()
            assertTrue(body.contains("\"session_token\":\"tok_logout_123456789012\""))
            assertTrue(body.contains("\"device_id\":\"android-dev-3\""))
        }
    }
}
