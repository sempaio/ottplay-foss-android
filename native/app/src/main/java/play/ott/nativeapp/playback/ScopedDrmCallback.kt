package play.ott.nativeapp.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.drm.ExoMediaDrm
import androidx.media3.exoplayer.drm.HttpMediaDrmCallback
import androidx.media3.exoplayer.drm.MediaDrmCallback
import java.io.IOException
import java.net.URI
import java.util.UUID
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import play.ott.nativeapp.core.DrmConfig
import play.ott.nativeapp.core.DrmPolicy
import play.ott.nativeapp.AppTransportPolicy
import play.ott.nativeapp.core.RemoteTransportPolicy

/** Native MediaDrm challenge/response, with no wrappers or key extraction. */
@UnstableApi
internal class ScopedDrmCallback(
    config: DrmConfig,
    client: OkHttpClient,
    private val transportPolicy: RemoteTransportPolicy = AppTransportPolicy.current,
) : MediaDrmCallback {
    private val config = DrmPolicy.validated(config).also { transportPolicy.requireHttpUrl(it.licenseUrl) }
    private val transportClient = transportPolicy.secure(client)
    private val licenseHttp = transportClient.newBuilder().followRedirects(false).followSslRedirects(false)
        .addInterceptor { chain ->
            val response = chain.proceed(chain.request())
            val body = response.body
            // OkHttpDataSource eagerly buffers non-2xx responses inside open(). Bound every
            // body here, after OkHttp's transparent decompression and before that buffering.
            val bytes = body.use {
                if (it.contentLength() > MAX_LICENSE_BYTES) {
                    throw IOException("DRM license exceeds the supported size limit")
                }
                val source = it.source()
                if (source.request(MAX_LICENSE_BYTES + 1)) {
                    throw IOException("DRM license exceeds the supported size limit")
                }
                source.readByteArray()
            }
            response.newBuilder().body(bytes.toResponseBody(body.contentType())).build()
        }.build()
    private val license = HttpMediaDrmCallback(this.config.licenseUrl, true,
        restrictedLicenseFactory(licenseHttp, this.config.licenseUrl)).apply {
        this@ScopedDrmCallback.config.licenseHeaders.forEach { (name, value) -> setKeyRequestProperty(name, value) }
    }
    // Android supplies the provisioning URL. It must never receive provider license/stream headers.
    private val provisioning = HttpMediaDrmCallback(null, OkHttpDataSource.Factory(transportClient))

    override fun executeKeyRequest(uuid: UUID, request: ExoMediaDrm.KeyRequest): MediaDrmCallback.Response =
        license.executeKeyRequest(uuid, request)

    override fun executeProvisionRequest(uuid: UUID, request: ExoMediaDrm.ProvisionRequest): MediaDrmCallback.Response =
        provisioning.executeProvisionRequest(uuid, request)

    private fun restrictedLicenseFactory(client: OkHttpClient, licenseUrl: String): DataSource.Factory {
        val allowed = origin(licenseUrl)
        val factory = OkHttpDataSource.Factory(client).setUserAgent("OTT-play-Android/1.0")
        return DataSource.Factory {
            val delegate = factory.createDataSource()
            object : DataSource by delegate {
                private var received = 0L
                private var previousUri = URI(licenseUrl)
                override fun open(dataSpec: DataSpec): Long {
                    // Media3 forwards Location verbatim; resolve relative redirects before checking
                    // the origin, without allowing the HTTP client to forward credentials itself.
                    val requestedUri = try { previousUri.resolve(dataSpec.uri.toString()) } catch (_: Exception) { null }
                    val requested = try { requestedUri?.let { origin(it.toString()) } } catch (_: Exception) { null }
                    if (requested != allowed || requestedUri == null) throw IOException("DRM license redirect to another origin is unsupported")
                    transportPolicy.requireHttpUrl(requestedUri.toString())
                    previousUri = requestedUri
                    received = 0L
                    val length = delegate.open(dataSpec.buildUpon().setUri(requestedUri.toString()).build())
                    if (length > MAX_LICENSE_BYTES) {
                        delegate.close()
                        throw IOException("DRM license exceeds the supported size limit")
                    }
                    return length
                }

                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    val count = delegate.read(buffer, offset, length)
                    if (count > 0) received += count
                    if (received > MAX_LICENSE_BYTES) throw IOException("DRM license exceeds the supported size limit")
                    return count
                }
            }
        }
    }

    private fun origin(value: String): Triple<String, String, Int> {
        val uri = URI(DrmPolicy.licenseUrl(value))
        val scheme = uri.scheme.lowercase(java.util.Locale.ROOT)
        return Triple(scheme, uri.host.lowercase(java.util.Locale.ROOT),
            if (uri.port == -1) { if (scheme == "https") 443 else 80 } else uri.port)
    }

    private companion object { const val MAX_LICENSE_BYTES = 4L * 1024 * 1024 }
}
