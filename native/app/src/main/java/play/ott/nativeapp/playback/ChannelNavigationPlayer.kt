package play.ott.nativeapp.playback

import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import play.ott.nativeapp.core.MediaEntry
import play.ott.nativeapp.core.PlaybackStream

/** Adds source-local IPTV channel navigation without inventing resolved URLs or a fake timeline. */
@UnstableApi
internal class ChannelNavigationPlayer(
    delegate: Player,
    scope: CoroutineScope,
    loadChannels: suspend (String) -> List<MediaEntry>,
    resolve: suspend (MediaEntry) -> PlaybackStream,
    onFailure: () -> Unit,
    validate: (MediaItem) -> Unit = {},
) : ForwardingSimpleBasePlayer(delegate) {
    private val navigator = ChannelNavigator(
        scope = scope,
        currentId = { player.currentMediaItem?.mediaId },
        loadChannels = loadChannels,
        resolve = resolve,
        commit = { entry, stream ->
            val item = PlaybackItems.build(entry.id, entry.name, stream.url, stream.headers,
                artwork = entry.logo.takeIf { it.isNotBlank() }, isLive = true,
                drm = entry.drm, mimeType = entry.mimeType ?: stream.mimeType,
                unsupportedReason = entry.playbackUnsupportedReason)
            validate(item)
            player.setMediaItem(item, PlaybackItems.startPositionMs(item))
            player.prepare()
            player.play()
        },
        changed = { invalidateState() },
        failed = onFailure,
    )
    private val transitionListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            navigator.currentItemChanged(mediaItem?.mediaId)
        }
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady) navigator.cancelSwitch()
        }
    }

    init {
        delegate.addListener(transitionListener)
        navigator.currentItemChanged(delegate.currentMediaItem?.mediaId)
    }

    override fun getState(): State {
        val state = super.getState()
        // The base class may query getState during construction, before this field is initialized.
        val navigation = navigatorOrNull()
        if (navigation?.available != true) return state
        return state.buildUpon().setAvailableCommands(state.availableCommands.buildUpon().addAll(
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
        ).build()).build()
    }

    // Kotlin's non-null field has a JVM-null value while the super constructor is executing.
    private fun navigatorOrNull(): ChannelNavigator? = navigator

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val direction = when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> 1
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> -1
            else -> 0
        }
        if (direction != 0 && navigator.step(direction)) return Futures.immediateVoidFuture()
        return super.handleSeek(mediaItemIndex, positionMs, seekCommand)
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (!playWhenReady) navigator.cancelSwitch()
        return super.handleSetPlayWhenReady(playWhenReady)
    }

    override fun handleStop(): ListenableFuture<*> {
        navigator.cancelSwitch()
        return super.handleStop()
    }

    override fun handleSetMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
        navigator.cancelSwitch()
        return super.handleSetMediaItems(mediaItems, startIndex, startPositionMs)
    }

    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> {
        navigator.cancelSwitch()
        return super.handleRemoveMediaItems(fromIndex, toIndex)
    }

    override fun handleRelease(): ListenableFuture<*> {
        navigator.close()
        player.removeListener(transitionListener)
        return super.handleRelease()
    }
}
