package play.ott.nativeapp.ui

import android.content.Context
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.View
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import play.ott.nativeapp.R
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import play.ott.nativeapp.data.TrackPreference
import play.ott.nativeapp.playback.TrackPreferences
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.PlaybackException
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TrackSelectionDialogBuilder
import play.ott.nativeapp.core.MediaEntry

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun NativePlayerPane(
    entry: MediaEntry,
    controller: MediaController?,
    fullscreen: Boolean,
    onFullscreen: () -> Unit,
    onPictureInPicture: () -> Unit,
    onStop: () -> Unit,
    onRetry: () -> Unit,
    onTrackPreference: (Boolean, TrackPreference) -> Unit,
    modifier: Modifier = Modifier,
    inPictureInPicture: Boolean = false,
    showHeaderBar: Boolean = true,
    externalResizeMode: Int? = null,
    onResizeModeChange: ((Int) -> Unit)? = null,
    onPrevious: (() -> Unit)? = null,
    onNext: (() -> Unit)? = null,
    onCast: (() -> Unit)? = null,
    isCasting: Boolean = false,
    onOpenEpg: (() -> Unit)? = null,
    liveNowPlayingTitle: String? = null,
    drawerItems: List<MediaEntry> = emptyList(),
    onSelectDrawerItem: ((MediaEntry) -> Unit)? = null,
) {
    var internalResizeMode by rememberSaveable { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    val resizeMode = externalResizeMode ?: internalResizeMode
    val setResizeMode: (Int) -> Unit = { next ->
        internalResizeMode = next
        onResizeModeChange?.invoke(next)
    }
    var buffering by remember(controller) { mutableStateOf(controller == null || controller.playbackState == Player.STATE_BUFFERING) }
    var playbackError by remember(controller, entry.id) { mutableStateOf(controller?.playerError?.errorCodeName) }
    var optionsOpen by remember { mutableStateOf(false) }
    var trackType by remember { mutableStateOf<Int?>(null) }
    var nativeView by remember { mutableStateOf<RemotePlayerView?>(null) }
    var controlsVisible by remember { mutableStateOf(true) }
    val isTv = LocalConfiguration.current.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION
    val useCinemaHud = fullscreen && !inPictureInPicture && !isTv
    val context = LocalContext.current
    val audioTitle = stringResource(R.string.dialog_audio)
    val subtitleTitle = stringResource(R.string.dialog_subtitles)
    val playerDescription = stringResource(R.string.dialog_player_accessibility)
    BackHandler(enabled = isTv && controller != null && controlsVisible && !optionsOpen && trackType == null && !inPictureInPicture) {
        nativeView?.hideController()
    }
    if (optionsOpen && !inPictureInPicture) PlaybackOptionsDialog(
        controller, resizeMode,
        onResize = setResizeMode,
        onTracks = { optionsOpen = false; trackType = it },
        onPictureInPicture, onStop,
        onDismiss = { optionsOpen = false; if (isTv) nativeView?.focusPlaybackControl() },
    )
    DisposableEffect(trackType, controller, entry.id) {
        val type = trackType
        val dialog = if (type != null && controller != null && controller.isCommandAvailable(Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS)) {
            val parameters = controller.trackSelectionParameters
            val groups = controller.currentTracks.groups.filter { it.type == type }
            TrackSelectionDialogBuilder(context, if (type == C.TRACK_TYPE_AUDIO) audioTitle else subtitleTitle, groups) { disabled, overrides ->
                // An explicit Auto is meaningful even when the preferred language is absent
                // and the player is already using its automatic fallback.
                val next = controller.trackSelectionParameters.buildUpon()
                    .clearOverridesOfType(type).setTrackTypeDisabled(type, disabled).apply {
                        overrides.values.forEach(::addOverride)
                    }.build()
                controller.trackSelectionParameters = next
                val choice = TrackPreferences.capture(next, type)
                onTrackPreference(type == C.TRACK_TYPE_AUDIO, choice)
            }.setIsDisabled(type in parameters.disabledTrackTypes)
                .setOverrides(parameters.overrides.filterValues { it.type == type })
                .setShowDisableOption(type == C.TRACK_TYPE_TEXT).build().apply {
                    setOnDismissListener { trackType = null; if (isTv) nativeView?.focusPlaybackControl() }
                    show()
                }
        } else null
        onDispose { dialog?.dismiss() }
    }
    DisposableEffect(controller) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                buffering = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_READY) playbackError = null
            }
            override fun onPlayerError(error: PlaybackException) {
                buffering = false
                playbackError = error.errorCodeName
            }
        }
        controller?.addListener(listener)
        onDispose { controller?.removeListener(listener) }
    }
    Column(modifier.testTag("player-container").background(Color.Black)) {
        if (!inPictureInPicture && showHeaderBar && !fullscreen) Row(
            modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NeonEmblem(Modifier.size(32.dp))
            Text(
                entry.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(vertical = 12.dp),
            )
            ActionButton(stringResource(R.string.dialog_fullscreen), onFullscreen, compact = true)
            ActionButton(stringResource(R.string.dialog_more), { optionsOpen = true }, Modifier.testTag("player-options"), compact = true)
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            AndroidView(
                factory = { context ->
                    RemotePlayerView(context).apply {
                        nativeView = this
                        openOptions = { optionsOpen = true }
                        tag = "ott-native-video"
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        player = controller
                        useController = !inPictureInPicture && !useCinemaHud
                        controllerAutoShow = !useCinemaHud
                        controllerShowTimeoutMs = 4000
                        if (isTv) setControllerAnimationEnabled(false)
                        setShowSubtitleButton(!isTv)
                        if (isTv) {
                            // Keep all TV settings in the app's remote-aware dialog. Media3's
                            // built-in gear/subtitle menus use a separate IME-bound PopupWindow.
                            findViewById<View>(androidx.media3.ui.R.id.exo_settings)?.setOnClickListener { openOptions() }
                        }
                        setShowFastForwardButton(true)
                        setShowRewindButton(true)
                        setShowPreviousButton(true)
                        setShowNextButton(true)
                        setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                        setKeepContentOnPlayerReset(true)
                        isFocusable = true
                        isFocusableInTouchMode = true
                        contentDescription = playerDescription
                        setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
                            controlsVisible = visibility == View.VISIBLE
                            retainPlaybackFocusAfterControlsHide(
                                visibility,
                                allowFocusRetention = isTv && !optionsOpen && trackType == null && !inPictureInPicture,
                            )
                        })
                        if (isTv) post { focusPlaybackControl() }
                    }
                },
                update = { view ->
                    view.contentDescription = playerDescription
                    if (view.player !== controller) view.player = controller
                    view.resizeMode = resizeMode
                    val wantController = !inPictureInPicture && !useCinemaHud
                    if (view.useController != wantController) {
                        view.useController = wantController
                        if (!wantController) view.hideController()
                    }
                },
                onRelease = { it.player = null; if (nativeView === it) nativeView = null },
                modifier = Modifier.fillMaxSize(),
            )
            if (useCinemaHud) {
                CinemaPlayerOverlay(
                    entry = entry,
                    controller = controller,
                    buffering = buffering,
                    hasError = playbackError != null,
                    resizeMode = resizeMode,
                    onResizeModeChange = setResizeMode,
                    onBack = onFullscreen,
                    onOpenTracks = { type -> trackType = type },
                    onOpenOptions = { optionsOpen = true },
                    onPictureInPicture = onPictureInPicture,
                    onPrevious = onPrevious,
                    onNext = onNext,
                    onCast = onCast,
                    isCasting = isCasting,
                    onOpenEpg = onOpenEpg,
                    liveNowPlayingTitle = liveNowPlayingTitle,
                    drawerItems = drawerItems,
                    onSelectDrawerItem = onSelectDrawerItem,
                    modifier = Modifier.fillMaxSize(),
                )
            } else if (!inPictureInPicture && fullscreen && (controlsVisible || playbackError != null || isTv)) {
                val aspectLabel = when (resizeMode) {
                    AspectRatioFrameLayout.RESIZE_MODE_FIT -> "Ajustar"
                    AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "Zoom"
                    else -> "Preencher"
                }
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Black.copy(alpha = 0.82f), Color.Black.copy(alpha = 0.35f), Color.Transparent)
                            )
                        )
                        .statusBarsPadding()
                        .displayCutoutPadding()
                        .navigationBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FullscreenOverlayPill("← ${stringResource(R.string.dialog_back)}", onFullscreen)
                        Text(
                            entry.name,
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (onPrevious != null) FullscreenOverlayPill("⏮ Ant", onPrevious)
                            if (onNext != null) FullscreenOverlayPill("Próx ⏭", onNext)
                            FullscreenOverlayPill(
                                label = "📺 $aspectLabel",
                                onClick = {
                                    setResizeMode(
                                        when (resizeMode) {
                                            AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                                            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                                        }
                                    )
                                },
                            )
                            if (onCast != null) FullscreenOverlayPill("📡 Cast", onCast)
                            FullscreenOverlayPill(
                                stringResource(R.string.dialog_more),
                                { optionsOpen = true },
                                modifier = Modifier.testTag("player-options"),
                            )
                        }
                    }
                }
            }
            if (!inPictureInPicture && !useCinemaHud && (controller == null || buffering)) CircularProgressIndicator()
            playbackError?.takeUnless { inPictureInPicture }?.let {
                Column(
                    Modifier
                        .background(Color(0xEE141720), RoundedCornerShape(14.dp))
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        stringResource(R.string.dialog_playback_failed),
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                    )
                    Text(
                        stringResource(R.string.dialog_playback_error, it),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFD0D4DC),
                        maxLines = 3,
                    )
                    ActionButton(stringResource(R.string.dialog_retry), onRetry, selected = true)
                }
            }
        }
    }
}

@Composable
private fun FullscreenOverlayPill(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .background(Color(0xCC1B1F2A), RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** Channel keys remain in the native media-session path, including lazy provider resolution. */
@androidx.annotation.OptIn(UnstableApi::class)
private class RemotePlayerView(context: Context) : PlayerView(context) {
    var openOptions: () -> Unit = {}
    private val remoteInput = TvRemoteInput()

    override fun dispatchKeyEventPreIme(event: KeyEvent): Boolean =
        remoteInput.dispatch(this, event, ::dispatchRemoteKey) || super.dispatchKeyEventPreIme(event)

    private fun dispatchRemoteKey(event: KeyEvent): Boolean {
        if (dispatchKeyEvent(event)) return true
        if (event.action != KeyEvent.ACTION_DOWN) return false
        val direction = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> View.FOCUS_LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> View.FOCUS_RIGHT
            KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP
            KeyEvent.KEYCODE_DPAD_DOWN -> View.FOCUS_DOWN
            else -> return false
        }
        // ViewRoot normally performs this after an unhandled native arrow. When the
        // hidden IME intercepts it, complete that same native focus search here.
        val focused = findFocus() ?: return false
        val next = focused.focusSearch(direction) ?: return false
        return next !== focused && next.requestFocus(direction)
    }

    override fun onDetachedFromWindow() {
        remoteInput.clear()
        super.onDetachedFromWindow()
    }
    private var playbackFocusPending = false
    private var controllerHidWithFocus = false

    override fun clearChildFocus(child: View) {
        // GONE clears child focus before Media3 reports the controller's visibility.
        // Capture ownership now; the framework may assign another focus meanwhile.
        controllerHidWithFocus = child.id == androidx.media3.ui.R.id.exo_controller &&
            child.visibility == View.GONE && focusedChild === child && hasWindowFocus() &&
            resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION
        super.clearChildFocus(child)
    }

    fun retainPlaybackFocusAfterControlsHide(visibility: Int, allowFocusRetention: Boolean) {
        val restoreFocus = controllerHidWithFocus
        controllerHidWithFocus = false
        if (visibility == View.GONE && restoreFocus && allowFocusRetention &&
            isAttachedToWindow && isShown && hasWindowFocus() && useController && player != null) {
            // This runs after setVisibility(GONE) finishes clearing focus. Keep the
            // video ready for remote input without reopening controls or dialogs.
            requestFocus()
        }
    }

    fun focusPlaybackControl() {
        playbackFocusPending = true
        restorePlaybackFocusWhenReady()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        if (!hasWindowFocus) remoteInput.clear()
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) restorePlaybackFocusWhenReady()
    }

    private fun restorePlaybackFocusWhenReady() {
        if (!playbackFocusPending || !hasWindowFocus()) return
        post {
            // Dismissing a dialog schedules removal of its separate window. Restore
            // focus only after that handoff, without stealing unrelated future focus.
            if (playbackFocusPending && isAttachedToWindow && hasWindowFocus()) {
                showController()
                if (findViewById<View>(androidx.media3.ui.R.id.exo_play_pause).requestFocus()) {
                    playbackFocusPending = false
                }
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_MENU) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) openOptions()
            return true
        }
        val currentPlayer = player ?: return super.dispatchKeyEvent(event)
        val forward = event.keyCode == KeyEvent.KEYCODE_CHANNEL_UP
        val backward = event.keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN
        if (!forward && !backward) return super.dispatchKeyEvent(event)
        val itemCommand = if (forward) Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM else Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM
        val generalCommand = if (forward) Player.COMMAND_SEEK_TO_NEXT else Player.COMMAND_SEEK_TO_PREVIOUS
        if (!currentPlayer.isCommandAvailable(itemCommand) && !currentPlayer.isCommandAvailable(generalCommand)) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            if (currentPlayer.isCommandAvailable(itemCommand)) {
                if (forward) currentPlayer.seekToNextMediaItem() else currentPlayer.seekToPreviousMediaItem()
            } else {
                if (forward) currentPlayer.seekToNext() else currentPlayer.seekToPrevious()
            }
        }
        return true
    }
}
