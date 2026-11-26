package play.ott.nativeapp.playback

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Response
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
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
    liveClient: OkHttpClient = client,
) : MediaSource.Factory {
    private val client = transportPolicy.secure(client)
    private val liveHttpClient = transportPolicy.secure(liveClient)
    private val context = context.applicationContext
    private var drmProvider: DrmSessionManagerProvider? = null
    private var errorPolicy: LoadErrorHandlingPolicy? = null

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val resolved = PlaybackItems.resolve(mediaItem)
        PlaybackItems.requireSupported(context, resolved)
        val http = httpFactoryFor(resolved)
        val dataSourceFactory = DefaultDataSource.Factory(context, http)
        val isLive = resolved.requestMetadata.extras?.getBoolean(PlaybackItems.EXTRA_IS_LIVE, false) == true
        val isHls = resolved.localConfiguration?.mimeType == MimeTypes.APPLICATION_M3U8 ||
            resolved.localConfiguration?.uri?.path.orEmpty().lowercase(Locale.ROOT).endsWith(".m3u8")
        val factory: MediaSource.Factory = if (isHls) {
            HlsMediaSource.Factory(dataSourceFactory).setAllowChunklessPreparation(true)
        } else {
            DefaultMediaSourceFactory(dataSourceFactory)
        }
        val config = PlaybackItems.drm(resolved)
        val effectiveErrorPolicy = errorPolicy ?: if (isLive && isHls) DefaultLoadErrorHandlingPolicy(4) else null
        if (drmProvider != null) {
            factory.setDrmSessionManagerProvider(requireNotNull(drmProvider))
        } else if (config != null) {
            val builder = DefaultDrmSessionManager.Builder()
                .setUuidAndExoMediaDrmProvider(PlaybackItems.uuid(config.scheme), FrameworkMediaDrm.DEFAULT_PROVIDER)
                .setMultiSession(config.multiSession)
                .setPlayClearSamplesWithoutKeys(false)
            effectiveErrorPolicy?.let(builder::setLoadErrorHandlingPolicy)
            val manager = builder.build(ScopedDrmCallback(config, client, transportPolicy))
            factory.setDrmSessionManagerProvider { manager }
        }
        effectiveErrorPolicy?.let(factory::setLoadErrorHandlingPolicy)
        return factory.createMediaSource(resolved)
    }

    internal fun httpFactoryFor(item: MediaItem): OkHttpDataSource.Factory {
        val headers = PlaybackItems.headers(item)
        val source = (item.localConfiguration?.uri ?: item.requestMetadata.mediaUri)?.toString()?.toHttpUrlOrNull()
        val privateHeaderNames = headers.keys.filter { it.lowercase(Locale.ROOT) !in PUBLIC_HEADERS }
        val isLive = item.requestMetadata.extras?.getBoolean(PlaybackItems.EXTRA_IS_LIVE, false) == true
        val isHls = item.localConfiguration?.mimeType == MimeTypes.APPLICATION_M3U8 ||
            source?.encodedPath.orEmpty().lowercase(Locale.ROOT).endsWith(".m3u8")
        val baseClient = if (isLive) liveHttpClient else client
        val renewableManifestRef = AtomicReference<HttpUrl?>(null)
        val builder = baseClient.newBuilder()
        if (isLive && isHls && source != null) {
            // Some upstream Xtream HLS origins redirect /u/p/id.m3u8 to a short-lived
            // /auth/id.m3u8?token=... URL that returns 404 on the next playlist refresh.
            // Renew directly via the intermediate upstream .m3u8 URL from the device's own IP
            // without touching same-origin Pluto manifests or leaking origin credentials.
            builder.addInterceptor { chain ->
                val request = chain.request()
                val reqUrl = request.url
                val pathLower = reqUrl.encodedPath.lowercase(Locale.ROOT)
                if (!pathLower.endsWith(".m3u8")) {
                    return@addInterceptor chain.proceed(request)
                }
                if (sameOrigin(source, reqUrl)) {
                    val response = chain.proceed(request)
                    captureRenewableUpstream(source, response)?.let { renewableManifestRef.set(it) }
                    return@addInterceptor response
                }
                val renewUrl = renewableManifestRef.get()
                val isShortLivedTokenManifest = renewUrl != null && !sameOrigin(renewUrl, reqUrl) &&
                    (reqUrl.queryParameter("token") != null || pathLower.contains("/auth/"))
                if (isShortLivedTokenManifest) {
                    val renewedRequest = request.newBuilder().url(renewUrl).build()
                    var renewedResponse = chain.proceed(renewedRequest)
                    if (renewedResponse.code in TRANSIENT_UPSTREAM_CODES) {
                        renewedResponse.close()
                        runCatching { Thread.sleep(250L) }
                        renewedResponse = chain.proceed(renewedRequest)
                    }
                    return@addInterceptor renewedResponse
                }
                val response = chain.proceed(request)
                if (response.code in RENEWABLE_FAILURE_CODES && renewUrl != null) {
                    response.close()
                    return@addInterceptor chain.proceed(request.newBuilder().url(renewUrl).build())
                }
                response
            }
        }
        val scopedClient = builder.addNetworkInterceptor { chain ->
            val request = chain.request()
            val target = request.url
            val sameOrigin = sameOrigin(source, target)
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

    private fun sameOrigin(a: HttpUrl?, b: HttpUrl): Boolean =
        a != null && a.scheme == b.scheme && a.host == b.host && a.port == b.port

    private fun captureRenewableUpstream(source: HttpUrl, finalResponse: Response): HttpUrl? {
        val hops = ArrayList<HttpUrl>()
        var cursor: Response? = finalResponse
        while (cursor != null) {
            hops.add(cursor.request.url)
            cursor = cursor.priorResponse
        }
        // Walk from oldest (initial) hop to final hop and pick the first cross-origin .m3u8 hop
        // that does not carry a one-shot token query parameter (e.g. http://acaidopt.org/.../id.m3u8).
        val ordered = hops.asReversed()
        val finalUrl = finalResponse.request.url
        return ordered.firstOrNull { hop ->
            !sameOrigin(source, hop) &&
                !sameOrigin(hop, finalUrl) &&
                hop.encodedPath.lowercase(Locale.ROOT).endsWith(".m3u8") &&
                hop.queryParameter("token") == null
        }
    }

    override fun getSupportedTypes(): IntArray = DefaultMediaSourceFactory(context).supportedTypes

    override fun setDrmSessionManagerProvider(drmSessionManagerProvider: DrmSessionManagerProvider): MediaSource.Factory = apply {
        drmProvider = drmSessionManagerProvider
    }

    override fun setLoadErrorHandlingPolicy(loadErrorHandlingPolicy: LoadErrorHandlingPolicy): MediaSource.Factory = apply {
        errorPolicy = loadErrorHandlingPolicy
    }

    private companion object {
        val PUBLIC_HEADERS = setOf("user-agent", "accept", "accept-language")
        val TRANSIENT_UPSTREAM_CODES = setOf(502, 503, 504)
        val RENEWABLE_FAILURE_CODES = setOf(401, 403, 404, 410, 502, 503)
    }
}
