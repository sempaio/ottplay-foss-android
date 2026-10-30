package play.ott.nativeapp.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import play.ott.nativeapp.core.DrmConfig
import play.ott.nativeapp.core.DrmScheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = PlaybackTestApplication::class)
class PlaybackItemsTest {
    // Exercise the legacy controller IPC format deliberately, including lost local fields.
    @Suppress("DEPRECATION")
    private fun legacyIpcRoundTrip(item: MediaItem) = MediaItem.fromBundle(item.toBundle())

    @Test
    @Config(shadows = [SupportedMediaDrm::class])
    fun `native container checks accept MIME hints on extensionless protected streams`() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        org.robolectric.Shadows.shadowOf(context.packageManager)
            .setSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK, true)
        for ((scheme, mimeType) in listOf(DrmScheme.WIDEVINE to MimeTypes.APPLICATION_MPD,
            DrmScheme.WIDEVINE to MimeTypes.APPLICATION_M3U8,
            DrmScheme.CLEARKEY to MimeTypes.APPLICATION_MPD,
            DrmScheme.PLAYREADY to MimeTypes.APPLICATION_MPD,
            DrmScheme.PLAYREADY to MimeTypes.APPLICATION_SS)) {
            val item = PlaybackItems.build("opaque", "Protected", "https://video.test/play?id=opaque",
                drm = DrmConfig(scheme, "https://license.test/acquire"), mimeType = mimeType)
            PlaybackItems.requireSupported(context, PlaybackItems.resolve(legacyIpcRoundTrip(item)))
        }
    }

    @Test
    @Config(shadows = [SupportedMediaDrm::class])
    fun `legacy television without Leanback retains supported PlayReady playback`() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val packageManager = org.robolectric.Shadows.shadowOf(context.packageManager)
        packageManager.setSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK, false)
        // Deliberately calibrate the older TV capability still supported in production.
        @Suppress("DEPRECATION")
        packageManager.setSystemFeature(android.content.pm.PackageManager.FEATURE_TELEVISION, true)
        val item = PlaybackItems.build("legacy-tv", "Protected", "https://video.test/manifest.mpd",
            drm = DrmConfig(DrmScheme.PLAYREADY, "https://license.test/acquire"))
        PlaybackItems.requireSupported(context, item)
    }

    /** The unit test simulates scheme availability; real MediaDrm is covered by instrumentation. */
    @org.robolectric.annotation.Implements(android.media.MediaDrm::class)
    class SupportedMediaDrm {
        companion object {
            @JvmStatic
            @org.robolectric.annotation.Implementation
            @Suppress("UNUSED_PARAMETER")
            fun isCryptoSchemeSupported(uuid: java.util.UUID): Boolean = true
        }
    }

    @Test fun `DRM and extensionless DASH survive controller serialization without publishing credentials`() {
        val headers = mutableMapOf("Authorization" to "Bearer license-secret")
        val original = PlaybackItems.build("opaque-id", "Protected channel", "https://video.test/opaque?stream-secret",
            headers = mapOf("Authorization" to "Bearer stream-secret"),
            drm = DrmConfig(DrmScheme.WIDEVINE, "https://license.test/acquire?license-secret", headers, multiSession = true),
            mimeType = MimeTypes.APPLICATION_MPD)
        headers["Authorization"] = "mutated"
        val transported = legacyIpcRoundTrip(original)
        assertNull(transported.localConfiguration)
        val rebuilt = PlaybackItems.resolve(transported)
        val native = requireNotNull(rebuilt.localConfiguration?.drmConfiguration)
        assertEquals(C.WIDEVINE_UUID, native.scheme)
        assertEquals("https://license.test/acquire?license-secret", native.licenseUri.toString())
        assertEquals("Bearer license-secret", native.licenseRequestHeaders["Authorization"])
        assertEquals("Bearer stream-secret", PlaybackItems.headers(rebuilt)["Authorization"])
        assertEquals(MimeTypes.APPLICATION_MPD, rebuilt.localConfiguration?.mimeType)
        assertTrue(native.multiSession)
        assertTrue(native.forceDefaultLicenseUri)
        assertFalse(native.playClearContentWithoutKey)
        assertNull(rebuilt.mediaMetadata.extras)
        assertNull(rebuilt.mediaMetadata.artworkUri)
        assertEquals("opaque-id", rebuilt.mediaId)
        assertEquals("Protected channel", rebuilt.mediaMetadata.title.toString())
    }

    @Test fun `unsupported and incomplete DRM cannot downgrade to an unprotected request`() {
        assertThrows(IllegalArgumentException::class.java) {
            PlaybackItems.build("bad", "Bad", "https://video.test/manifest.mpd", unsupportedReason = "Unsupported wrapper")
        }
        val original = PlaybackItems.build("id", "Protected", "https://video.test/manifest.mpd",
            drm = DrmConfig(DrmScheme.CLEARKEY, "https://license.test/acquire"))
        val missing = original.buildUpon().setRequestMetadata(original.requestMetadata.buildUpon()
            .setExtras(Bundle(original.requestMetadata.extras).apply { remove(PlaybackItems.EXTRA_DRM) }).build()).build()
        assertThrows(IllegalArgumentException::class.java) { PlaybackItems.resolve(missing) }
        val malformed = legacyIpcRoundTrip(original)
        malformed.requestMetadata.extras!!.getBundle(PlaybackItems.EXTRA_DRM)!!.putString("unexpected", "secret")
        val failure = assertThrows(IllegalArgumentException::class.java) { PlaybackItems.resolve(malformed) }
        assertFalse(failure.message.orEmpty().contains("secret"))
    }

    @Test fun `unsupported native container and Android TV boundaries fail before obtaining licenses`() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        // Robolectric imports optional manifest features as present; explicitly model a phone.
        val packageManager = org.robolectric.Shadows.shadowOf(context.packageManager)
        packageManager.setSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK, false)
        @Suppress("DEPRECATION")
        packageManager.setSystemFeature(android.content.pm.PackageManager.FEATURE_TELEVISION, false)
        val clearKeyHls = PlaybackItems.build("ck", "ClearKey", "https://video.test/master.m3u8",
            drm = DrmConfig(DrmScheme.CLEARKEY, "https://license.test/acquire"))
        assertThrows(IllegalArgumentException::class.java) { PlaybackItems.requireSupported(context, clearKeyHls) }
        val playReadyPhone = PlaybackItems.build("pr", "PlayReady", "https://video.test/manifest.mpd",
            drm = DrmConfig(DrmScheme.PLAYREADY, "https://license.test/acquire"))
        val failure = assertThrows(IllegalArgumentException::class.java) { PlaybackItems.requireSupported(context, playReadyPhone) }
        assertTrue("Expected the Android TV gate, received: ${failure.message}", failure.message.orEmpty().contains("Android TV"))
    }

    @Test fun `HTTP credentials are source scoped and are absent from public metadata`() {
        val input = mutableMapOf("Authorization" to "Bearer first")
        val first = PlaybackItems.build("first", "Channel one", "https://host.test/one.m3u8?secret=one", input)
        input["Authorization"] = "Bearer second"
        val second = PlaybackItems.build("second", "Channel two", "https://host.test/two.mpd?secret=two", input)
        assertEquals("Bearer first", PlaybackItems.headers(first)["Authorization"])
        assertEquals("Bearer second", PlaybackItems.headers(second)["Authorization"])
        assertNull(first.mediaMetadata.extras)
        assertNull(first.mediaMetadata.artworkUri)
        assertEquals("Channel one", first.mediaMetadata.title.toString())
        assertEquals(MimeTypes.APPLICATION_M3U8, first.localConfiguration?.mimeType)
        assertEquals(MimeTypes.APPLICATION_MPD, second.localConfiguration?.mimeType)
    }

    @Test fun `live playback defaults to live edge while VOD retains requested offset`() {
        val live = PlaybackItems.build("live", "Live", "https://host.test/live.m3u8", isLive = true)
        val vod = PlaybackItems.build("vod", "Movie", "https://host.test/vod.mp4", startPositionMs = 12_345L)
        val negative = PlaybackItems.build("vod", "Movie", "https://host.test/vod.mp4", startPositionMs = -5L)
        assertEquals(C.TIME_UNSET, PlaybackItems.startPositionMs(live))
        assertEquals(12_345L, PlaybackItems.startPositionMs(vod))
        assertEquals(0L, PlaybackItems.startPositionMs(negative))
    }

    @Test fun `requests missing local configuration after IPC are rebuilt with detached headers`() {
        val headers = Bundle().apply { putString("Referer", "https://portal.test/") }
        val extras = Bundle().apply { putBundle(PlaybackItems.EXTRA_HEADERS, headers) }
        val incoming = MediaItem.Builder().setMediaId("channel")
            .setRequestMetadata(MediaItem.RequestMetadata.Builder()
                .setMediaUri(Uri.parse("https://stream.test/live.m3u8"))
                .setExtras(extras).build())
            .build()
        assertNull(incoming.localConfiguration)
        val resolved = PlaybackItems.resolve(incoming)
        headers.putString("Referer", "https://changed.test/")
        assertEquals(Uri.parse("https://stream.test/live.m3u8"), resolved.localConfiguration?.uri)
        assertEquals("https://portal.test/", PlaybackItems.headers(resolved)["Referer"])
    }

    @Test fun `explicit stop cancels play intent and clears media but pause does not`() {
        val calls = mutableListOf<String>()
        val delegate = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args ->
            when (method.name) {
                "setPlayWhenReady" -> calls.add("playWhenReady=${args!![0]}")
                "stop", "clearMediaItems", "pause" -> calls.add(method.name)
            }
            null
        } as Player
        val player = StopClearsPlaylistPlayer(delegate)
        player.pause()
        assertEquals(listOf("pause"), calls)
        calls.clear()
        player.stop()
        assertEquals(listOf("playWhenReady=false", "stop", "clearMediaItems"), calls)
        assertTrue(calls.none { it == "release" })
    }
}
