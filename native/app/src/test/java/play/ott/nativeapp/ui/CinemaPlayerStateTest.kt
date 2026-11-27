package play.ott.nativeapp.ui

import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import play.ott.nativeapp.core.Programme

class CinemaPlayerStateTest {
    @Test fun formatTimeHandlesMinutesAndHours() {
        assertEquals("00:00", CinemaPlayerLogic.formatTimeMs(-5_000L))
        assertEquals("00:45", CinemaPlayerLogic.formatTimeMs(45_000L))
        assertEquals("12:05", CinemaPlayerLogic.formatTimeMs(725_000L))
        assertEquals("1:02:03", CinemaPlayerLogic.formatTimeMs(3_723_000L))
    }

    @Test fun formatDeltaIncludesSign() {
        assertEquals("+00:10", CinemaPlayerLogic.formatDeltaMs(10_000L))
        assertEquals("-01:30", CinemaPlayerLogic.formatDeltaMs(-90_000L))
    }

    @Test fun formatSpeedMatchesCinemaLabels() {
        assertEquals("1.0x", CinemaPlayerLogic.formatSpeed(1.0f))
        assertEquals("1.25x", CinemaPlayerLogic.formatSpeed(1.25f))
        assertEquals("1.5x", CinemaPlayerLogic.formatSpeed(1.5f))
        assertEquals("2.0x", CinemaPlayerLogic.formatSpeed(2.0f))
    }

    @Test fun classifyDragSeparatesBrightnessVolumeAndHorizontalSeek() {
        assertNull(CinemaPlayerLogic.classifyDrag(2f, 3f, 100f, 1000f, allowSeek = true))
        assertEquals(
            CinemaGestureZone.HORIZONTAL_SEEK,
            CinemaPlayerLogic.classifyDrag(45f, 10f, 300f, 1000f, allowSeek = true),
        )
        assertEquals(
            CinemaGestureZone.LEFT_BRIGHTNESS,
            CinemaPlayerLogic.classifyDrag(45f, 10f, 200f, 1000f, allowSeek = false),
        )
        assertEquals(
            CinemaGestureZone.LEFT_BRIGHTNESS,
            CinemaPlayerLogic.classifyDrag(5f, -40f, 250f, 1000f, allowSeek = true),
        )
        assertEquals(
            CinemaGestureZone.RIGHT_VOLUME,
            CinemaPlayerLogic.classifyDrag(5f, -40f, 750f, 1000f, allowSeek = true),
        )
    }

    @Test fun brightnessVolumeAndSeekClampSafely() {
        assertEquals(1.0f, CinemaPlayerLogic.computeBrightness(0.8f, -500f, 400f), 0.001f)
        assertEquals(0.05f, CinemaPlayerLogic.computeBrightness(0.2f, 500f, 400f), 0.001f)
        assertEquals(15, CinemaPlayerLogic.computeVolume(12, 15, -500f, 400f))
        assertEquals(0, CinemaPlayerLogic.computeVolume(3, 15, 500f, 400f))

        val forward = CinemaPlayerLogic.computeSeekTarget(60_000L, 600_000L, 500f, 1000f)
        assertEquals(110_000L, forward)
        val clampedEnd = CinemaPlayerLogic.computeSeekTarget(590_000L, 600_000L, 500f, 1000f)
        assertEquals(599_000L, clampedEnd)
        val clampedStart = CinemaPlayerLogic.computeSeekTarget(10_000L, 600_000L, -500f, 1000f)
        assertEquals(0L, clampedStart)
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    @Test fun resizeModeCyclesCleanly() {
        val fit = AspectRatioFrameLayout.RESIZE_MODE_FIT
        val zoom = CinemaPlayerLogic.nextResizeMode(fit)
        val fill = CinemaPlayerLogic.nextResizeMode(zoom)
        val backToFit = CinemaPlayerLogic.nextResizeMode(fill)

        assertEquals(AspectRatioFrameLayout.RESIZE_MODE_ZOOM, zoom)
        assertEquals(AspectRatioFrameLayout.RESIZE_MODE_FILL, fill)
        assertEquals(fit, backToFit)
        assertEquals("Ajustar", CinemaPlayerLogic.resizeModeLabel(fit))
        assertEquals("Zoom", CinemaPlayerLogic.resizeModeLabel(zoom))
        assertEquals("Preencher", CinemaPlayerLogic.resizeModeLabel(fill))
    }

    @Test fun activeProgrammeTitleFindsCurrentEpgShow() {
        val progs = listOf(
            Programme(channelId = "ch1", title = "Jornal", startMillis = 1_000L, endMillis = 2_000L),
            Programme(channelId = "ch1", title = "Futebol Ao Vivo", startMillis = 2_000L, endMillis = 5_000L),
        )
        assertEquals("Futebol Ao Vivo", CinemaPlayerLogic.activeProgrammeTitle(progs, 3_000L))
        assertNull(CinemaPlayerLogic.activeProgrammeTitle(progs, 6_000L))
    }
}
