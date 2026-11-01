package play.ott.nativeapp.playback

import androidx.media3.common.PlaybackException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackErrorsDiagnosticsTest {
    private fun failed(cause: Throwable, code: Int = PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED) =
        PlaybackException("Sensitive URL must never be shown", cause, code)

    @Test fun dnsFailureUsesStableSafeCodeWithoutExceptionText() {
        val error = failed(IOException("transport", UnknownHostException("https://provider.example/movie/alice/SECRET/42")))
        val code = PlaybackErrors.diagnosticCode(error)
        assertEquals("NET-DNS", code)
        assertFalse(requireNotNull(code).contains("alice"))
        assertFalse(code.contains("SECRET"))
        assertFalse(code.contains("provider.example"))
    }

    @Test fun connectFailureHasDedicatedCategory() {
        assertEquals("NET-CONNECT", PlaybackErrors.diagnosticCode(failed(ConnectException("connection refused"))))
    }

    @Test fun noRouteUsesMoreSpecificCategoryThanGenericConnect() {
        assertEquals("NET-ROUTE", PlaybackErrors.diagnosticCode(failed(NoRouteToHostException("no route"))))
    }

    @Test fun timeoutAndTlsRemainDistinct() {
        assertEquals("NET-TIMEOUT", PlaybackErrors.diagnosticCode(
            failed(SocketTimeoutException("read timed out"), PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)))
        assertEquals("NET-TLS", PlaybackErrors.diagnosticCode(
            failed(SSLHandshakeException("TLS handshake"))))
    }

    @Test fun genericNetworkFailureDoesNotGuessCause() {
        assertEquals("NET-CONNECTION", PlaybackErrors.diagnosticCode(failed(IOException("opaque"))))
    }

    @Test fun nonNetworkMedia3ErrorsDoNotReceiveNetworkDiagnosis() {
        assertNull(PlaybackErrors.diagnosticCode(failed(
            IOException("HTTP 403"), PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)))
    }
}
