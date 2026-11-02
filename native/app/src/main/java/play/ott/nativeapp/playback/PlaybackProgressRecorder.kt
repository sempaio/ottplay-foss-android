package play.ott.nativeapp.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import play.ott.nativeapp.data.ResumePositionWriter

/** The service records VOD progress even when every Activity/controller has been released. */
@UnstableApi
internal class PlaybackProgressRecorder(
    private val player: Player,
    serviceScope: CoroutineScope,
    private val writer: ResumePositionWriter,
) : Player.Listener {
    private data class Sample(val id: String, val positionMs: Long, val durationMs: Long, val entry: play.ott.nativeapp.core.MediaEntry?)
    private var last: Sample? = null
    private val ticker = serviceScope.launch {
        while (isActive) { delay(5_000); capture()?.let(::submit) }
    }

    init { player.addListener(this) }

    override fun onEvents(player: Player, events: Player.Events) {
        val sample = capture()
        if (sample != null) submit(sample)
        else if (player.currentMediaItem == null || player.playbackState == Player.STATE_IDLE) last?.let(::submit)
    }

    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
        if (oldPosition.mediaItem?.mediaId == newPosition.mediaItem?.mediaId) return
        val previous = last ?: return
        if (oldPosition.mediaItem?.mediaId == previous.id && !isLive(oldPosition.mediaItem)) {
            val precise = previous.copy(positionMs = resumePosition(oldPosition.contentPositionMs, previous.durationMs))
            last = precise
            submit(precise)
        }
    }

    private fun capture(): Sample? {
        val item = player.currentMediaItem ?: return null
        if (isLive(item) || player.isCurrentMediaItemLive || player.duration <= 0 ||
            player.playbackState !in setOf(Player.STATE_READY, Player.STATE_ENDED)) return null
        return Sample(item.mediaId, if (player.playbackState == Player.STATE_ENDED) 0 else
            resumePosition(player.currentPosition, player.duration), player.duration, item.requestMetadata.extras?.getString("play.ott.nativeapp.entry")?.let {
                runCatching { kotlinx.serialization.json.Json.decodeFromString<play.ott.nativeapp.core.MediaEntry>(it) }.getOrNull()
            }).also { last = it }
    }

    private fun submit(sample: Sample) {
        if (sample.entry != null) writer.enqueueRecent(play.ott.nativeapp.data.RecentPlayback(sample.entry, sample.positionMs, sample.durationMs))
        else writer.enqueue(sample.id, sample.positionMs)
    }

    /** Capture before release; the shared writer keeps ordering across service restarts. */
    fun close() {
        ticker.cancel()
        (capture() ?: last)?.let(::submit)
        player.removeListener(this)
    }

    private fun isLive(item: MediaItem?): Boolean =
        item?.requestMetadata?.extras?.getBoolean(PlaybackItems.EXTRA_IS_LIVE, false) == true
}

/** A short clip must not lose its resume point merely because it has fewer than five seconds left. */
internal fun resumePosition(positionMs: Long, durationMs: Long): Long =
    play.ott.core.PlaybackRules.nativeResume(positionMs, durationMs)
