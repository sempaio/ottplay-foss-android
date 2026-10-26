package play.ott.nativeapp

import android.content.Context
import coil.ImageLoader
import coil.decode.SvgDecoder
import okhttp3.OkHttpClient

/** One shared Coil cache/HTTP policy for the whole app, including SVG channel logos. */
internal fun createArtworkImageLoader(context: Context): ImageLoader = ImageLoader.Builder(context)
    .okHttpClient(AppTransportPolicy.current.secure(OkHttpClient()))
    .components { add(SvgDecoder.Factory()) }
    .build()
