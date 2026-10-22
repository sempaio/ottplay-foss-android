package play.ott.nativeapp.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class RequestPolicyTest {
    @Test fun `each item has a detached immutable header snapshot`() {
        val mutable = linkedMapOf("Authorization" to "Bearer first")
        val first = RequestPolicy.headers(mutable)
        mutable["Authorization"] = "Bearer second"
        val second = RequestPolicy.headers(mutable)
        assertEquals("Bearer first", first["Authorization"])
        assertEquals("Bearer second", second["Authorization"])
        assertThrows(UnsupportedOperationException::class.java) { (first as MutableMap<String, String>)["Cookie"] = "changed" }
    }

    @Test fun `header injection duplicate names and framing overrides are rejected without values`() {
        listOf(
            mapOf("X-Test" to "secret\r\nAuthorization: injected"),
            mapOf("Bad Header" to "secret"),
            mapOf("authorization" to "secret", "Authorization" to "other"),
            mapOf("Host" to "secret.example"),
        ).forEach { headers ->
            val error = assertThrows(IllegalArgumentException::class.java) { RequestPolicy.headers(headers) }
            assertFalse(error.message.orEmpty().contains("secret"))
        }
    }

    @Test fun `stream validation permits signed URLs and rejects missing host or foreign schemes`() {
        val signed = "https://example.test/live/channel.m3u8?token=private"
        assertEquals(signed, RequestPolicy.requireStreamUrl(signed))
        val bundled = "android.resource://play.ott.nativeapp/2131689472"
        assertEquals(bundled, RequestPolicy.requireStreamUrl(bundled))
        listOf("file:///private", "javascript:secret", "https:///path", "https://user:secret@example.test/x").forEach { url ->
            val error = assertThrows(IllegalArgumentException::class.java) { RequestPolicy.requireStreamUrl(url) }
            assertFalse(error.message.orEmpty().contains("secret"))
        }
    }

    @Test fun `controller access does not trust a package name and keeps notification transport`() {
        assertEquals(ControllerAccess.OWNER, controllerAccess(true, false, false))
        assertEquals(ControllerAccess.TRANSPORT, controllerAccess(false, true, false))
        assertEquals(ControllerAccess.TRANSPORT, controllerAccess(false, false, true))
        assertEquals(ControllerAccess.REJECT, controllerAccess(false, false, false))
    }
}
