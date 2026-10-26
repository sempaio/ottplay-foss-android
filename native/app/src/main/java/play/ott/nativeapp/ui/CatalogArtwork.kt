package play.ott.nativeapp.ui

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.request.ErrorResult
import play.ott.nativeapp.ArtworkDiagnostics
import play.ott.nativeapp.ArtworkRequestPolicy
import play.ott.nativeapp.BuildConfig
import play.ott.nativeapp.core.MediaEntry

/** Shared loader and size resolver; movie thumbnails may use a smaller TMDB representation. */
@Composable
internal fun CatalogArtwork(entry: MediaEntry, modifier: Modifier = Modifier,
                            contentScale: ContentScale = ContentScale.Fit, surface: String = "grade") {
    val context = LocalContext.current
    val artworkUrl = remember(entry.logo, entry.kind, surface) {
        ArtworkRequestPolicy.url(entry.logo, entry.kind, surface)
    }
    val model = remember(context, artworkUrl, entry.kind, surface) {
        if (!BuildConfig.ARTWORK_DIAGNOSTICS) artworkUrl
        else ImageRequest.Builder(context).data(artworkUrl).listener(object : ImageRequest.Listener {
            private var ticket: ArtworkDiagnostics.Ticket? = null
            override fun onStart(request: ImageRequest) {
                ticket = ArtworkDiagnostics.session.begin(entry.kind.name, surface, artworkUrl)
            }
            private fun finish(result: String, error: String = "") {
                val current = ticket ?: return
                ticket = null
                ArtworkDiagnostics.session.finish(current, result, error)?.let { Log.d("StarlinkArtwork", it) }
            }
            override fun onSuccess(request: ImageRequest, result: SuccessResult) = finish(result.dataSource.name)
            override fun onError(request: ImageRequest, result: ErrorResult) = finish("ERROR", when (result.throwable) {
                is coil.network.HttpException -> "HTTP"
                is java.io.IOException -> "REDE"
                else -> "OUTRO"
            })
            override fun onCancel(request: ImageRequest) = finish("CANCELLED")
        }).build()
    }
    AsyncImage(model, contentDescription = null, modifier = modifier, contentScale = contentScale)
}
