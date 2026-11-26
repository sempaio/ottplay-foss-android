package play.ott.nativeapp.playback

/** Media3 states mapped to a dependency-free contract so every retry rule is unit-testable. */
internal enum class LiveRecoveryState { IDLE, BUFFERING, READY, ENDED }

/**
 * Conservative IPTV-live watchdog. It never restarts VOD, paused media, or permanent codec errors.
 * The service is solely responsible for executing reconnects and retaining the current MediaItem.
 * All timestamps use elapsedRealtime, never wall-clock time.
 */
internal class LivePlaybackRecoveryPolicy {
    private var mediaId: String? = null
    private var unhealthySinceMs = -1L
    private var lastPositionMs = Long.MIN_VALUE
    private var lastMovementMs = -1L
    private var sustainedSinceMs = -1L
    private var retries = 0
    private var retryAllowedAtMs = 0L

    fun reset() {
        mediaId = null
        unhealthySinceMs = -1L
        lastPositionMs = Long.MIN_VALUE
        lastMovementMs = -1L
        sustainedSinceMs = -1L
        retries = 0
        retryAllowedAtMs = 0L
    }

    fun evaluate(
        nowMs: Long,
        currentMediaId: String?,
        isLive: Boolean,
        playWhenReady: Boolean,
        naturallyEnded: Boolean,
        state: LiveRecoveryState,
        isPlaying: Boolean,
        positionMs: Long,
        retryableError: Boolean,
    ): Boolean {
        if (currentMediaId == null || !isLive || (!playWhenReady && !naturallyEnded)) {
            reset()
            return false
        }
        if (mediaId != currentMediaId) {
            reset()
            mediaId = currentMediaId
        }

        if (state == LiveRecoveryState.READY && isPlaying) {
            if (positionMs != lastPositionMs) {
                lastPositionMs = positionMs
                lastMovementMs = nowMs
                unhealthySinceMs = -1L
                if (sustainedSinceMs < 0L) sustainedSinceMs = nowMs
                if (nowMs - sustainedSinceMs >= 8_000L) {
                    retries = 0
                    retryAllowedAtMs = 0L
                }
                return false
            }
            // Detect a stream stuck on the same timecode while the player reports READY.
            if (lastMovementMs < 0L || nowMs - lastMovementMs < 9_000L) return false
        } else {
            sustainedSinceMs = -1L
            lastPositionMs = positionMs
            lastMovementMs = -1L
        }

        val unhealthy = when (state) {
            LiveRecoveryState.BUFFERING, LiveRecoveryState.ENDED -> true
            LiveRecoveryState.IDLE -> retryableError
            LiveRecoveryState.READY -> isPlaying && lastMovementMs >= 0L && nowMs - lastMovementMs >= 9_000L
        }
        if (!unhealthy) {
            unhealthySinceMs = -1L
            return false
        }
        if (unhealthySinceMs < 0L) unhealthySinceMs = nowMs
        val thresholdMs = when (state) {
            LiveRecoveryState.BUFFERING -> 7_000L
            LiveRecoveryState.READY -> 0L // Already observed >=9s without progress.
            LiveRecoveryState.ENDED, LiveRecoveryState.IDLE -> 1_000L
        }
        if (nowMs - unhealthySinceMs < thresholdMs || nowMs < retryAllowedAtMs) return false

        retries++
        val backoffMs = when (retries) {
            1 -> 1_500L
            2 -> 3_000L
            3 -> 6_000L
            else -> 15_000L
        }
        retryAllowedAtMs = nowMs + backoffMs
        unhealthySinceMs = nowMs
        sustainedSinceMs = -1L
        return true
    }
}
