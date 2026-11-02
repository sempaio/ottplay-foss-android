package play.ott.nativeapp.playback

import android.net.Uri
import androidx.core.net.toUri
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import java.util.Locale
import java.util.UUID
import play.ott.nativeapp.core.DrmConfig
import play.ott.nativeapp.core.DrmPolicy
import play.ott.nativeapp.core.DrmScheme

/**
 * UI/service request contract. Credentials belong exclusively in requestMetadata (never title,
 * artist, mediaId, MediaMetadata.extras or log messages). Callers provide an opaque catalogue id.
 *
 * EXTRA_HEADERS: Bundle of String header-name -> String value, scoped to this media item.
 * EXTRA_IS_LIVE: Boolean, catalogue hint; the actual timeline remains authoritative for seeking.
 * EXTRA_START_POSITION_MS: Long, requested VOD offset; 0 means the default position for live.
 * EXTRA_ARTWORK: String, optional private artwork URL, reserved for authenticated image loading.
 * Remote artwork URLs are deliberately not published in MediaMetadata/lock-screen extras.
 */
@UnstableApi
object PlaybackItems {
    const val EXTRA_HEADERS = "play.ott.nativeapp.request_headers"
    const val EXTRA_IS_LIVE = "play.ott.nativeapp.is_live"
    const val EXTRA_START_POSITION_MS = "play.ott.nativeapp.start_position_ms"
    const val EXTRA_ARTWORK = "play.ott.nativeapp.artwork"
    const val EXTRA_DRM = "play.ott.nativeapp.drm"
    const val EXTRA_MIME_TYPE = "play.ott.nativeapp.mime_type"

    fun build(
        id: String,
        title: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        artwork: String? = null,
        isLive: Boolean = false,
        startPositionMs: Long = 0L,
        drm: DrmConfig? = null,
        mimeType: String? = null,
        unsupportedReason: String? = null,
    ): MediaItem {
        require(unsupportedReason == null) { "This entry uses an unsupported DRM or media configuration" }
        require(id.isNotBlank()) { "A catalogue item id is required" }
        val uri = RequestPolicy.requireStreamUrl(url).toUri()
        val validatedDrm = drm?.let(DrmPolicy::validated)
        val contentMimeType = validatedMimeType(mimeType) ?: mimeType(uri)
        val extras = Bundle().apply {
            putBundle(EXTRA_HEADERS, headerBundle(RequestPolicy.headers(headers)))
            putBoolean(EXTRA_IS_LIVE, isLive)
            putLong(EXTRA_START_POSITION_MS, startPositionMs.coerceAtLeast(0L))
            if (!artwork.isNullOrBlank()) putString(EXTRA_ARTWORK, artwork)
            validatedDrm?.let { putBundle(EXTRA_DRM, drmBundle(it)) }
            contentMimeType?.let { putString(EXTRA_MIME_TYPE, it) }
        }
        return MediaItem.Builder()
            .setMediaId(id)
            .setUri(uri)
            .setMimeType(contentMimeType)
            .setDrmConfiguration(validatedDrm?.let(::nativeDrm))
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).setExtras(extras).build())
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title.ifBlank { "OTT-play" }).build())
            .build()
    }

    /** Call before replacing a playing item. Device provisioning/license approval still happen later. */
    fun requireSupported(context: Context, item: MediaItem) {
        val config = drm(item) ?: return
        val uri = item.localConfiguration?.uri ?: item.requestMetadata.mediaUri
        requireNotNull(uri) { "A stream URL is required" }
        val type = Util.inferContentTypeForUriAndMimeType(uri, requestMimeType(item) ?: mimeType(uri))
        require(type == C.CONTENT_TYPE_DASH ||
            (config.scheme != DrmScheme.CLEARKEY && type == C.CONTENT_TYPE_HLS) ||
            (config.scheme == DrmScheme.PLAYREADY && type == C.CONTENT_TYPE_SS)) {
            "This container is unsupported for the selected DRM system"
        }
        require(config.scheme != DrmScheme.PLAYREADY ||
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            hasLegacyTelevisionFeature(context)) {
            "PlayReady is supported only on compatible Android TV devices"
        }
        val supported = try { FrameworkMediaDrm.isCryptoSchemeSupported(uuid(config.scheme)) } catch (_: Exception) { false }
        require(supported) { "The device does not support the selected DRM system" }
    }

    // Older Android TV implementations can expose this feature without leanback.
    // Retain their existing PlayReady capability path until those devices are retired.
    @Suppress("DEPRECATION")
    private fun hasLegacyTelevisionFeature(context: Context) =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEVISION)

    internal fun drm(item: MediaItem): DrmConfig? {
        val extras = item.requestMetadata.extras
        if (extras?.containsKey(EXTRA_DRM) != true) {
            require(item.localConfiguration?.drmConfiguration == null) { "Complete DRM request configuration is missing" }
            return null
        }
        val bundle = requireNotNull(extras.getBundle(EXTRA_DRM)) { "Invalid DRM request configuration" }
        require(bundle.keySet() == setOf("scheme", "license_url", "headers", "multi_session")) {
            "Invalid DRM request configuration"
        }
        val scheme = DrmScheme.entries.firstOrNull { it.name == bundle.getString("scheme") }
        requireNotNull(scheme) { "The selected DRM system is unsupported" }
        val headers = requireNotNull(bundle.getBundle("headers")) { "Invalid DRM license headers" }
        return DrmPolicy.validated(DrmConfig(scheme,
            requireNotNull(bundle.getString("license_url")) { "DRM license address is missing" },
            headers.keySet().associateWith { requireNotNull(headers.getString(it)) { "Invalid DRM license header" } },
            bundle.getBoolean("multi_session"),
        ))
    }

    fun startPositionMs(item: MediaItem): Long {
        val extras = item.requestMetadata.extras
        val position = extras?.getLong(EXTRA_START_POSITION_MS, 0L)?.coerceAtLeast(0L) ?: 0L
        return if (position == 0L && extras?.getBoolean(EXTRA_IS_LIVE, false) == true) C.TIME_UNSET else position
    }

    internal fun headers(item: MediaItem): Map<String, String> {
        val bundle = item.requestMetadata.extras?.getBundle(EXTRA_HEADERS) ?: return emptyMap()
        val values = bundle.keySet().associateWith { key ->
            requireNotNull(bundle.getString(key)) { "Invalid request header value" }
        }
        return RequestPolicy.headers(values)
    }

    /** Rebuild after controller IPC; do not depend on LocalConfiguration surviving serialization. */
    internal fun resolve(item: MediaItem): MediaItem {
        val uri = item.localConfiguration?.uri ?: item.requestMetadata.mediaUri
        requireNotNull(uri) { "A stream URL is required" }
        RequestPolicy.requireStreamUrl(uri.toString())
        val config = drm(item)
        val type = requestMimeType(item) ?: mimeType(uri)
        val extras = Bundle(item.requestMetadata.extras ?: Bundle()).apply {
            putBundle(EXTRA_HEADERS, headerBundle(headers(item)))
            config?.let { putBundle(EXTRA_DRM, drmBundle(it)) }
            type?.let { putString(EXTRA_MIME_TYPE, it) }
        }
        return item.buildUpon()
            .setUri(uri)
            .setMimeType(type)
            .setDrmConfiguration(config?.let(::nativeDrm))
            .setRequestMetadata(item.requestMetadata.buildUpon().setMediaUri(uri).setExtras(extras).build())
            .build()
    }

    private fun drmBundle(config: DrmConfig): Bundle = Bundle().apply {
        putString("scheme", config.scheme.name)
        putString("license_url", config.licenseUrl)
        putBundle("headers", headerBundle(config.licenseHeaders))
        putBoolean("multi_session", config.multiSession)
    }

    internal fun uuid(scheme: DrmScheme): UUID = when (scheme) {
        DrmScheme.WIDEVINE -> C.WIDEVINE_UUID
        DrmScheme.PLAYREADY -> C.PLAYREADY_UUID
        DrmScheme.CLEARKEY -> C.CLEARKEY_UUID
    }

    private fun nativeDrm(config: DrmConfig): MediaItem.DrmConfiguration =
        MediaItem.DrmConfiguration.Builder(uuid(config.scheme))
            .setLicenseUri(config.licenseUrl)
            .setLicenseRequestHeaders(config.licenseHeaders)
            .setForceDefaultLicenseUri(true)
            .setMultiSession(config.multiSession)
            .setPlayClearContentWithoutKey(false)
            .build()

    private fun requestMimeType(item: MediaItem): String? = validatedMimeType(
        item.requestMetadata.extras?.getString(EXTRA_MIME_TYPE) ?: item.localConfiguration?.mimeType,
    )

    private fun validatedMimeType(value: String?): String? {
        require(value == null || (value.length <= 128 && value.matches(Regex("[A-Za-z0-9.+_-]+/[A-Za-z0-9.+_-]+")))) {
            "Invalid media MIME type"
        }
        return value
    }

    private fun headerBundle(headers: Map<String, String>): Bundle = Bundle().apply {
        headers.forEach { (key, value) -> putString(key, value) }
    }

    private fun mimeType(uri: Uri): String? = when {
        uri.path.orEmpty().lowercase(Locale.ROOT).endsWith(".m3u8") -> MimeTypes.APPLICATION_M3U8
        uri.path.orEmpty().lowercase(Locale.ROOT).endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
        else -> null
    }
}
