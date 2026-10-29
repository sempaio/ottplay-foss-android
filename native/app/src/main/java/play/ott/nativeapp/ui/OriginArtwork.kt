package play.ott.nativeapp.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.SuccessResult
import play.ott.nativeapp.ArtworkOrigins
import play.ott.nativeapp.ArtworkTag
import play.ott.nativeapp.BuildConfig
import play.ott.nativeapp.core.MediaEntry

/** Same data, size resolver, cache keys and loader as the confirmed layout version. */
@Composable
internal fun OriginArtwork(entry: MediaEntry, modifier: Modifier = Modifier,
                           contentScale: ContentScale = ContentScale.Fit, surface: String = "grade") {
    val context = LocalContext.current
    val model = remember(context, entry.logo, entry.kind, surface) {
        if (!BuildConfig.ARTWORK_ORIGINS) entry.logo else {
            val tag = ArtworkTag(entry.kind.name, surface)
            ImageRequest.Builder(context)
                .data(entry.logo).tag(tag)
                .listener(object : ImageRequest.Listener {
                    private var ticket: ArtworkOrigins.Ticket? = null
                    override fun onStart(request: ImageRequest) {
                        ticket = ArtworkOrigins.session.start(entry.kind.name, surface, entry.logo)
                        tag.ticket = ticket
                    }
                    private fun finish(result: String, width: Int = 0, height: Int = 0) {
                        val current = ticket ?: return
                        ticket = null
                        ArtworkOrigins.session.image(current, result, width, height)
                    }
                    override fun onSuccess(request: ImageRequest, result: SuccessResult) =
                        finish(result.dataSource.name, result.drawable.intrinsicWidth, result.drawable.intrinsicHeight)
                    override fun onError(request: ImageRequest, result: ErrorResult) = finish("ERROR")
                    override fun onCancel(request: ImageRequest) = finish("CANCELLED")
                }).build()
        }
    }
    AsyncImage(model, contentDescription = null, modifier = modifier, contentScale = contentScale)
}
