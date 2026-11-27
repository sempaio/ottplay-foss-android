package play.ott.nativeapp.ui

import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import kotlin.math.abs
import kotlin.math.roundToInt
import play.ott.nativeapp.core.Programme

internal enum class CinemaGestureZone {
    LEFT_BRIGHTNESS,
    RIGHT_VOLUME,
    HORIZONTAL_SEEK,
}

internal enum class CinemaDrawerMode {
    NONE,
    CHANNELS,
    EPISODES,
    SPEED,
}

internal object CinemaPlayerLogic {
    val SPEEDS: List<Float> = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
    const val DOUBLE_TAP_SEEK_MS: Long = 10_000L
    const val LONG_PRESS_BOOST_SPEED: Float = 2.0f
    const val HUD_AUTO_HIDE_MS: Long = 4_000L
    const val LOCK_HINT_HIDE_MS: Long = 3_000L

    fun formatTimeMs(ms: Long): String {
        val safeSeconds = (ms.coerceAtLeast(0L) / 1000L)
        val hours = safeSeconds / 3600L
        val minutes = (safeSeconds % 3600L) / 60L
        val seconds = safeSeconds % 60L
        return if (hours > 0L) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%02d:%02d".format(minutes, seconds)
        }
    }

    fun formatDeltaMs(deltaMs: Long): String {
        val sign = if (deltaMs >= 0L) "+" else "-"
        return sign + formatTimeMs(abs(deltaMs))
    }

    fun formatSpeed(speed: Float): String {
        val rounded = (speed * 100f).roundToInt() / 100f
        return when {
            rounded == rounded.toInt().toFloat() -> "${rounded.toInt()}.0x"
            (rounded * 10f) == (rounded * 10f).toInt().toFloat() -> "${rounded}x"
            else -> "${rounded}x"
        }
    }

    fun classifyDrag(
        dx: Float,
        dy: Float,
        startX: Float,
        width: Float,
        allowSeek: Boolean,
    ): CinemaGestureZone? {
        if (width <= 0f) return null
        val absX = abs(dx)
        val absY = abs(dy)
        if (absX < 8f && absY < 8f) return null
        if (allowSeek && absX > absY * 1.25f) {
            return CinemaGestureZone.HORIZONTAL_SEEK
        }
        return if (startX < width * 0.5f) {
            CinemaGestureZone.LEFT_BRIGHTNESS
        } else {
            CinemaGestureZone.RIGHT_VOLUME
        }
    }

    fun computeBrightness(startBrightness: Float, totalDeltaY: Float, height: Float): Float {
        if (height <= 0f) return startBrightness.coerceIn(0.05f, 1.0f)
        val normalized = (-totalDeltaY) / (height * 0.65f)
        return (startBrightness + normalized).coerceIn(0.05f, 1.0f)
    }

    fun computeVolume(startVolume: Int, maxVolume: Int, totalDeltaY: Float, height: Float): Int {
        if (maxVolume <= 0 || height <= 0f) return startVolume.coerceAtLeast(0)
        val normalized = (-totalDeltaY) / (height * 0.65f)
        val delta = (normalized * maxVolume).roundToInt()
        return (startVolume + delta).coerceIn(0, maxVolume)
    }

    fun computeSeekTarget(
        startPosMs: Long,
        durationMs: Long,
        totalDeltaX: Float,
        width: Float,
    ): Long {
        if (durationMs <= 0L || width <= 0f) return startPosMs.coerceAtLeast(0L)
        val sweepMs = minOf(120_000L, maxOf(30_000L, durationMs / 6L))
        val deltaMs = ((totalDeltaX / width) * sweepMs).toLong()
        val maxSeek = (durationMs - 1000L).coerceAtLeast(0L)
        return (startPosMs + deltaMs).coerceIn(0L, maxSeek)
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    fun nextResizeMode(current: Int): Int = when (current) {
        AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FILL
        else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    fun resizeModeLabel(mode: Int): String = when (mode) {
        AspectRatioFrameLayout.RESIZE_MODE_FIT -> "Ajustar"
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "Zoom"
        else -> "Preencher"
    }

    fun activeProgrammeTitle(programmes: List<Programme>, nowMs: Long): String? =
        programmes.firstOrNull { it.startMillis <= nowMs && nowMs < it.endMillis }?.title?.takeIf { it.isNotBlank() }
}
