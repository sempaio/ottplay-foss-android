package play.ott.nativeapp

import kotlin.test.Test
import kotlin.test.assertEquals
import play.ott.nativeapp.core.ProviderException

class ProviderErrorMessagesTest {
    @Test fun `access denial is distinguished from bad credentials and server outages`() {
        assertEquals(R.string.starlink_access_denied, providerErrorResource(ProviderException("untrusted response", 403)))
        assertEquals(R.string.starlink_login_denied, providerErrorResource(ProviderException("untrusted response", 401)))
        assertEquals(R.string.starlink_server_unavailable, providerErrorResource(ProviderException("untrusted response", 503)))
    }
    @Test fun `unrecognized errors never render raw exception messages`() {
        assertEquals(R.string.message_operation_failed, providerErrorResource(Exception("https://fixture.invalid/?password=synthetic")))
        assertEquals(R.string.message_host_not_found, providerErrorResource(java.net.UnknownHostException()))
        assertEquals(R.string.message_network_timeout, providerErrorResource(java.net.SocketTimeoutException()))
    }
}
