package play.ott.nativeapp

import kotlin.test.Test
import kotlin.test.assertEquals
import play.ott.nativeapp.core.ProviderException

class ProviderErrorMessagesTest {
    @Test fun `access denial is distinguished from bad credentials and server outages`() {
        assertEquals(R.string.starlink_access_denied, providerErrorResource(ProviderException("untrusted response", 403), false))
        assertEquals(R.string.starlink_login_denied, providerErrorResource(ProviderException("untrusted response", 401), false))
        assertEquals(R.string.starlink_server_unavailable, providerErrorResource(ProviderException("untrusted response", 503), false))
    }
    @Test fun `unrecognized errors never render raw exception messages`() {
        assertEquals(R.string.message_operation_failed, providerErrorResource(Exception("https://fixture.invalid/?password=synthetic"), false))
        assertEquals(R.string.message_host_not_found, providerErrorResource(java.net.UnknownHostException(), false))
        assertEquals(R.string.message_network_timeout, providerErrorResource(java.net.SocketTimeoutException(), false))
    }
    @Test fun `safe provider categories reach the UI instead of the generic message`() {
        val cases = mapOf(
            play.ott.nativeapp.core.ProviderFailure.DNS to R.string.message_host_not_found,
            play.ott.nativeapp.core.ProviderFailure.TIMEOUT to R.string.message_network_timeout,
            play.ott.nativeapp.core.ProviderFailure.TLS to R.string.starlink_tls_failed,
            play.ott.nativeapp.core.ProviderFailure.CONNECTION to R.string.starlink_connection_failed,
            play.ott.nativeapp.core.ProviderFailure.AUTHENTICATION to R.string.starlink_credentials_rejected,
            play.ott.nativeapp.core.ProviderFailure.ACCOUNT_INACTIVE to R.string.starlink_account_inactive,
            play.ott.nativeapp.core.ProviderFailure.INVALID_RESPONSE to R.string.starlink_invalid_response,
        )
        cases.forEach { (failure, resource) -> assertEquals(resource, providerErrorResource(ProviderException("untrusted", failure = failure), false)) }
    }

}
