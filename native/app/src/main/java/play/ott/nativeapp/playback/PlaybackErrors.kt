package play.ott.nativeapp.playback

import android.content.Context
import play.ott.nativeapp.i18n.AppLanguages
import play.ott.nativeapp.R
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** User-facing error categories. Never render exception messages containing provider URLs. */
@UnstableApi
object PlaybackErrors {
    /**
     * Safe, bounded diagnosis for a failed media connection.
     * Never expose Throwable.message, request URLs, headers or account details:
     * underlying exception messages may contain Xtream credentials.
     * Diagnostic labels describe the failure class, not which server is at fault.
     */
    internal fun diagnosticCode(error: PlaybackException): String? {
        if (error.errorCode != PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED &&
            error.errorCode != PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT) return null
        val causes = generateSequence(error as Throwable) { it.cause }.take(12).toList()
        return when {
            causes.any { it is UnknownHostException } -> "NET-DNS"
            causes.any { it is NoRouteToHostException } -> "NET-ROUTE"
            causes.any { it is ConnectException } -> "NET-CONNECT"
            causes.any { it is SocketTimeoutException } -> "NET-TIMEOUT"
            causes.any { it is SSLException } -> "NET-TLS"
            else -> "NET-CONNECTION"
        }
    }

    fun message(context: Context, error: PlaybackException): String {
        val localized = AppLanguages.localizedContext(context)
        fun text(id: Int, vararg args: Any) = localized.getString(id, *args)
        val response = generateSequence(error as Throwable) { it.cause }.take(8)
            .filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode
        if (response != null) return when (response) {
            401, 403 -> text(R.string.message_playback_access_denied, response)
            404, 410 -> text(R.string.message_playback_stream_unavailable, response)
            else -> text(R.string.message_playback_http_error, response)
        }
        return when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                text(R.string.message_playback_network_error) + " [" + diagnosticCode(error) + "]"
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> text(R.string.message_playback_file_error)
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES -> text(R.string.message_playback_decoder_error)
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> text(R.string.message_playback_format_error)
            else -> text(R.string.message_playback_unknown_error, error.errorCode)
        }
    }
}
