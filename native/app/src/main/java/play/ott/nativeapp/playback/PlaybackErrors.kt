package play.ott.nativeapp.playback

import android.content.Context
import play.ott.nativeapp.i18n.AppLanguages
import play.ott.nativeapp.R
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource

/** User-facing error categories. Never render exception messages containing provider URLs. */
@UnstableApi
object PlaybackErrors {
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
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> text(R.string.message_playback_network_error)
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
