package play.ott.nativeapp

import android.content.pm.ActivityInfo

/**
 * Deterministic orientation policy for phones, tablets, TVs and Picture-in-Picture.
 *
 * - TVs keep their native landscape orientation (returns null -> untouched).
 * - Picture-in-Picture windows are controlled by the OS (returns null -> untouched).
 * - Phones (`smallestScreenWidthDp < 600`) enter sensor landscape only for active fullscreen
 *   local video and explicitly lock back to portrait when browsing, preventing Android from
 *   staying stuck horizontally after closing playback or switching to Cast.
 * - Tablets (`smallestScreenWidthDp >= 600`) enter sensor landscape for fullscreen video and
 *   release to UNSPECIFIED when browsing so users can browse in either orientation.
 */
internal object ScreenOrientationPolicy {
    const val TABLET_MIN_SMALLEST_WIDTH_DP = 600

    fun targetOrientation(
        fullscreenVideo: Boolean,
        isTelevision: Boolean,
        inPictureInPicture: Boolean,
        smallestScreenWidthDp: Int,
    ): Int? {
        if (isTelevision || inPictureInPicture) return null
        return if (fullscreenVideo) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else if (smallestScreenWidthDp < TABLET_MIN_SMALLEST_WIDTH_DP) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    /**
     * Only active local video fullscreen requests landscape on phones.
     * Casting shows a vertical remote control card and should not force landscape.
     */
    fun shouldUseFullscreenVideo(
        fullscreen: Boolean,
        hasPlayingEntry: Boolean,
        isCastingToTv: Boolean,
    ): Boolean = fullscreen && hasPlayingEntry && !isCastingToTv
}
