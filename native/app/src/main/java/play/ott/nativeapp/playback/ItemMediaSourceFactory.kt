package play.ott.nativeapp.playback

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Locale
import play.ott.nativeapp.AppTransportPolicy
import play.ott.nativeapp.core.RemoteTransportPolicy

/**
 * A new HTTP factory for each source. HLS manifests, segments and keys (and DASH resources)
 * keep the same immutable header snapshot while a later playlist item is being prepared.
 * Sharing one mutable setDefaultRequestProperties across the playlist would leak credentials.
 */
@UnstableApi
internal class ItemMediaSourceFactory(
    context: Context,
    client: OkHttpClient,
    private val transportPolicy: RemoteTransportPolicy = AppTransportPolicy.current,
) : MediaSource.Factory {
    private val client = transportPolicy.secure(client)
    private val context = context.applicationContext
    private var drmProvider: DrmSessionManagerProvider? = null
    private var errorPolicy: LoadErrorHandlingPolicy? = null

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val resolved = PlaybackItems.resolve(mediaItem)
        PlaybackItems.requireSupported(context, resolved)
        val http = httpFactoryFor(resolved)
        val factory = DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http))
        val config = PlaybackItems.drm(resolved)
        if (drmProvider != null) {
            factory.setDrmSessionManagerProvider(requireNotNull(drmProvider))
        } else if (config != null) {
            val builder = DefaultDrmSessionManager.Builder()
                .setUuidAndExoMediaDrmProvider(PlaybackItems.uuid(config.scheme), FrameworkMediaDrm.DEFAULT_PROVIDER)
                .setMultiSession(config.multiSession)
                .setPlayClearSamplesWithoutKeys(false)
            errorPolicy?.let(builder::setLoadErrorHandlingPolicy)
            val manager = builder.build(ScopedDrmCallback(config, client, transportPolicy))
            factory.setDrmSessionManagerProvider { manager }
        }
        errorPolicy?.let(factory::setLoadErrorHandlingPolicy)
        return factory.createMediaSource(resolved)
    }

    internal fun httpFactoryFor(item: MediaItem): OkHttpDataSource.Factory {
        val headers = PlaybackItems.headers(item)
        val source = (item.localConfiguration?.uri ?: item.requestMetadata.mediaUri)?.toString()?.toHttpUrlOrNull()
        val privateHeaderNames = headers.keys.filter { it.lowercase(Locale.ROOT) !in PUBLIC_HEADERS }
        val scopedClient = client.newBuilder().addNetworkInterceptor { chain ->
            val request = chain.request()
            val target = request.url
            val sameOrigin = source != null && source.scheme == target.scheme &&
                source.host == target.host && source.port == target.port
            // Manifests can name CDN resources directly, and OkHttp may follow redirects.
            // A network interceptor checks both against the original item's origin, without
            // putting credentials back if a redirect already stripped them on an earlier hop.
            val scopedRequest = if (sameOrigin) request else request.newBuilder().apply {
                privateHeaderNames.forEach(::removeHeader)
            }.build()
            chain.proceed(scopedRequest)
        }.build()
        return OkHttpDataSource.Factory(scopedClient)
            .setUserAgent("OTT-play-Android/1.0")
            .setDefaultRequestProperties(headers)
    }

    override fun getSupportedTypes(): IntArray = DefaultMediaSourceFactory(context).supportedTypes

    override fun setDrmSessionManagerProvider(drmSessionManagerProvider: DrmSessionManagerProvider): MediaSource.Factory = apply {
        drmProvider = drmSessionManagerProvider
    }

    override fun setLoadErrorHandlingPolicy(loadErrorHandlingPolicy: LoadErrorHandlingPolicy): MediaSource.Factory = apply {
        errorPolicy = loadErrorHandlingPolicy
    }

    private companion object { val PUBLIC_HEADERS = setOf("user-agent", "accept", "accept-language") }
}
