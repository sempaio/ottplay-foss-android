package play.ott.nativeapp

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class OrientationTestApplication : OttplayApplication() {
    override fun scheduleEpgRefresh() = Unit
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = OrientationTestApplication::class)
class ScreenOrientationPolicyTest {
    @Test fun phonePolicySwitchesBetweenLandscapeVideoAndPortraitBrowsing() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
            ScreenOrientationPolicy.targetOrientation(
                fullscreenVideo = true,
                isTelevision = false,
                inPictureInPicture = false,
                smallestScreenWidthDp = 360,
            ),
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            ScreenOrientationPolicy.targetOrientation(
                fullscreenVideo = false,
                isTelevision = false,
                inPictureInPicture = false,
                smallestScreenWidthDp = 360,
            ),
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            ScreenOrientationPolicy.targetOrientation(
                fullscreenVideo = false,
                isTelevision = false,
                inPictureInPicture = false,
                smallestScreenWidthDp = 0,
            ),
        )
    }

    @Test fun tabletReleasesToUnspecifiedWhileTvAndPipRemainUntouched() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
            ScreenOrientationPolicy.targetOrientation(
                fullscreenVideo = false,
                isTelevision = false,
                inPictureInPicture = false,
                smallestScreenWidthDp = 600,
            ),
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
            ScreenOrientationPolicy.targetOrientation(
                fullscreenVideo = true,
                isTelevision = false,
                inPictureInPicture = false,
                smallestScreenWidthDp = 720,
            ),
        )
        assertNull(
            ScreenOrientationPolicy.targetOrientation(
                fullscreenVideo = true,
                isTelevision = true,
                inPictureInPicture = false,
                smallestScreenWidthDp = 960,
            ),
        )
        assertNull(
            ScreenOrientationPolicy.targetOrientation(
                fullscreenVideo = false,
                isTelevision = true,
                inPictureInPicture = false,
                smallestScreenWidthDp = 360,
            ),
        )
        assertNull(
            ScreenOrientationPolicy.targetOrientation(
                fullscreenVideo = true,
                isTelevision = false,
                inPictureInPicture = true,
                smallestScreenWidthDp = 360,
            ),
        )
    }

    @Test fun castingOverlayAndIdleStateDoNotForceLandscapeVideo() {
        assertTrue(
            ScreenOrientationPolicy.shouldUseFullscreenVideo(
                fullscreen = true,
                hasPlayingEntry = true,
                isCastingToTv = false,
            ),
        )
        assertFalse(
            ScreenOrientationPolicy.shouldUseFullscreenVideo(
                fullscreen = true,
                hasPlayingEntry = true,
                isCastingToTv = true,
            ),
        )
        assertFalse(
            ScreenOrientationPolicy.shouldUseFullscreenVideo(
                fullscreen = true,
                hasPlayingEntry = false,
                isCastingToTv = false,
            ),
        )
        assertFalse(
            ScreenOrientationPolicy.shouldUseFullscreenVideo(
                fullscreen = false,
                hasPlayingEntry = true,
                isCastingToTv = false,
            ),
        )
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-port")
    fun mainActivityPhoneReturnsToPortraitAfterFullscreenAndOnResume() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        val activity = controller.get()
        try {
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, activity.requestedOrientation)

            activity.setFullscreen(true)
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, activity.requestedOrientation)

            activity.setFullscreen(false)
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, activity.requestedOrientation)

            // Simulate orientation drift while an external Activity (e.g. Cast/System UI) ran.
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            activity.resources.configuration.orientation = Configuration.ORIENTATION_LANDSCAPE
            controller.pause().resume()
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, activity.requestedOrientation)

            // Unexpected landscape configuration event while browsing must also restore portrait.
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            val strayLandscape = Configuration(activity.resources.configuration).apply {
                orientation = Configuration.ORIENTATION_LANDSCAPE
                smallestScreenWidthDp = 360
            }
            activity.onConfigurationChanged(strayLandscape)
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, activity.requestedOrientation)

            // Returning window focus after a system overlay (e.g. Android Cast/Mirroring prompt)
            // must also restore portrait if orientation drifted.
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            activity.resources.configuration.orientation = Configuration.ORIENTATION_LANDSCAPE
            activity.onWindowFocusChanged(true)
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, activity.requestedOrientation)

            // OEM stuck-landscape recovery: even when requestedOrientation is already PORTRAIT
            // while configuration reports LANDSCAPE, setFullscreen(false) forces WMS re-evaluation
            // and ends locked in PORTRAIT.
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            activity.resources.configuration.orientation = Configuration.ORIENTATION_LANDSCAPE
            activity.setFullscreen(false)
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, activity.requestedOrientation)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    @Config(qualifiers = "w960dp-h540dp-land-television")
    fun mainActivityTvNeverForcesPortrait() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        val activity = controller.get()
        try {
            val initial = activity.requestedOrientation
            activity.setFullscreen(true)
            assertEquals(initial, activity.requestedOrientation)
            activity.setFullscreen(false)
            assertEquals(initial, activity.requestedOrientation)
            assertNotEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, activity.requestedOrientation)
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
