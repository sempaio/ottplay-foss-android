package play.ott.nativeapp.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.AudioManager
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import kotlinx.coroutines.delay
import play.ott.nativeapp.core.MediaEntry
import play.ott.nativeapp.core.MediaKind

private val CinemaCyan = Color(0xFF00E5FF)
private val CinemaLiveRed = Color(0xFFFF3B5C)
private val CinemaGlass = Color(0xB80E121B)
private val CinemaGlassStrong = Color(0xE6101520)
private val CinemaGlassBorder = Color(0x38FFFFFF)

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * DeepCine-inspired full-screen touch Cinema Player HUD.
 *
 * Keeps all Media3 playback/recovery/HLS logic untouched in PlaybackService; provides
 * gesture brightness/volume/seek, double-tap +/-10s, long-press 2x speed, screen lock,
 * live channel / series episode side drawer, speed selector, tracks, Cast, and PiP.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun CinemaPlayerOverlay(
    entry: MediaEntry,
    controller: MediaController?,
    buffering: Boolean,
    hasError: Boolean,
    resizeMode: Int,
    onResizeModeChange: (Int) -> Unit,
    onBack: () -> Unit,
    onOpenTracks: (Int) -> Unit,
    onOpenOptions: () -> Unit,
    onPictureInPicture: () -> Unit,
    onPrevious: (() -> Unit)? = null,
    onNext: (() -> Unit)? = null,
    onCast: (() -> Unit)? = null,
    isCasting: Boolean = false,
    onOpenEpg: (() -> Unit)? = null,
    liveNowPlayingTitle: String? = null,
    drawerItems: List<MediaEntry> = emptyList(),
    onSelectDrawerItem: ((MediaEntry) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val audioManager = remember(context) { context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager }
    val supportsPip = remember(context) {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }

    val isLive = entry.kind == MediaKind.LIVE
    var controlsVisible by remember(entry.id) { mutableStateOf(true) }
    var interactionNonce by remember { mutableIntStateOf(0) }
    var locked by remember { mutableStateOf(false) }
    var lockHintVisible by remember { mutableStateOf(false) }
    var drawerMode by remember(entry.id) { mutableStateOf(CinemaDrawerMode.NONE) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    // Live player state snapshots synced from MediaController
    var isPlaying by remember(controller) { mutableStateOf(controller?.isPlaying == true) }
    var currentPosMs by remember(controller, entry.id) { mutableLongStateOf(controller?.currentPosition?.coerceAtLeast(0L) ?: 0L) }
    var bufferedPosMs by remember(controller, entry.id) { mutableLongStateOf(controller?.bufferedPosition?.coerceAtLeast(0L) ?: 0L) }
    var durationMs by remember(controller, entry.id) {
        val d = controller?.duration ?: C.TIME_UNSET
        mutableLongStateOf(if (d > 0L && d != C.TIME_UNSET) d else 0L)
    }
    var playbackSpeed by remember(controller) { mutableFloatStateOf(controller?.playbackParameters?.speed ?: 1f) }
    var audioAvailable by remember(controller) { mutableStateOf(controller?.currentTracks?.isTypeSupported(C.TRACK_TYPE_AUDIO) == true) }
    var subtitlesAvailable by remember(controller) { mutableStateOf(controller?.currentTracks?.isTypeSupported(C.TRACK_TYPE_TEXT) == true) }
    var scrubbing by remember { mutableStateOf(false) }
    var scrubTargetMs by remember { mutableLongStateOf(0L) }

    // Gesture feedback HUD states
    var gestureHudLabel by remember { mutableStateOf<String?>(null) }
    var gestureHudIcon by remember { mutableStateOf("") }
    var gestureHudFraction by remember { mutableFloatStateOf(-1f) }
    var gestureHudSub by remember { mutableStateOf<String?>(null) }
    var doubleTapFeedback by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var longPressBoostActive by remember { mutableStateOf(false) }
    var speedBeforeBoost by remember { mutableFloatStateOf(1f) }
    var currentBrightness by remember {
        val current = activity?.window?.attributes?.screenBrightness ?: -1f
        mutableFloatStateOf(if (current in 0.01f..1f) current else 0.5f)
    }

    // Restore system brightness override when exiting the Cinema player
    DisposableEffect(activity) {
        onDispose {
            activity?.window?.let { win ->
                val lp = win.attributes
                if (lp.screenBrightness != WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE) {
                    lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                    win.attributes = lp
                }
            }
        }
    }

    // Track controller events
    DisposableEffect(controller) {
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                isPlaying = player.isPlaying
                playbackSpeed = player.playbackParameters.speed
                audioAvailable = player.currentTracks.isTypeSupported(C.TRACK_TYPE_AUDIO)
                subtitlesAvailable = player.currentTracks.isTypeSupported(C.TRACK_TYPE_TEXT)
                val d = player.duration
                if (d > 0L && d != C.TIME_UNSET) durationMs = d
            }
        }
        controller?.addListener(listener)
        onDispose { controller?.removeListener(listener) }
    }

    // Poll playback position smoothly every 500ms while HUD or VOD is active
    LaunchedEffect(controller, isLive, controlsVisible, scrubbing) {
        while (controller != null) {
            if (!scrubbing) {
                currentPosMs = controller.currentPosition.coerceAtLeast(0L)
                bufferedPosMs = controller.bufferedPosition.coerceAtLeast(0L)
                val d = controller.duration
                if (d > 0L && d != C.TIME_UNSET) durationMs = d
            }
            isPlaying = controller.isPlaying
            audioAvailable = controller.currentTracks.isTypeSupported(C.TRACK_TYPE_AUDIO)
            subtitlesAvailable = controller.currentTracks.isTypeSupported(C.TRACK_TYPE_TEXT)
            delay(if (controlsVisible && !isLive) 400L else 1000L)
        }
    }

    // Auto-hide controls after 4 seconds of inactivity while playing
    LaunchedEffect(controlsVisible, isPlaying, interactionNonce, drawerMode, scrubbing, locked, hasError) {
        if (controlsVisible && isPlaying && drawerMode == CinemaDrawerMode.NONE && !scrubbing && !locked && !hasError) {
            delay(CinemaPlayerLogic.HUD_AUTO_HIDE_MS)
            controlsVisible = false
        }
    }

    // Auto-hide lock unlock-hint after 3 seconds
    LaunchedEffect(lockHintVisible, interactionNonce) {
        if (lockHintVisible) {
            delay(CinemaPlayerLogic.LOCK_HINT_HIDE_MS)
            lockHintVisible = false
        }
    }

    // Auto-clear double-tap ripple feedback
    LaunchedEffect(doubleTapFeedback) {
        if (doubleTapFeedback != null) {
            delay(750L)
            doubleTapFeedback = null
        }
    }

    // Back button behavior: close drawer first; block exit while locked (show unlock pill)
    BackHandler(enabled = drawerMode != CinemaDrawerMode.NONE || locked) {
        if (drawerMode != CinemaDrawerMode.NONE) {
            drawerMode = CinemaDrawerMode.NONE
        } else if (locked) {
            lockHintVisible = true
            interactionNonce++
        }
    }

    val bumpHud = {
        interactionNonce++
        if (!locked) controlsVisible = true
    }

    val canSeek = !isLive && durationMs > 0L && controller?.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) == true
    val canChangeSpeed = !isLive && controller?.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH) == true
    val canChangeTracks = controller?.isCommandAvailable(Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS) == true

    Box(
        modifier
            .fillMaxSize()
            .testTag("cinema-player-overlay")
            .onSizeChanged { containerSize = it },
    ) {
        // 1. Full-screen DeepCine Gesture Layer (taps, double-taps, long-press 2x, brightness/volume/seek drags)
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(locked, canSeek, canChangeSpeed, containerSize, controller) {
                    detectTapGestures(
                        onTap = {
                            if (drawerMode != CinemaDrawerMode.NONE) {
                                drawerMode = CinemaDrawerMode.NONE
                                interactionNonce++
                            } else if (locked) {
                                lockHintVisible = !lockHintVisible
                                interactionNonce++
                            } else {
                                controlsVisible = !controlsVisible
                                interactionNonce++
                            }
                        },
                        onDoubleTap = { offset ->
                            if (locked || drawerMode != CinemaDrawerMode.NONE || controller == null) return@detectTapGestures
                            val w = containerSize.width.toFloat().coerceAtLeast(1f)
                            if (canSeek && offset.x < w * 0.35f) {
                                val target = (controller.currentPosition - CinemaPlayerLogic.DOUBLE_TAP_SEEK_MS).coerceAtLeast(0L)
                                controller.seekTo(target)
                                currentPosMs = target
                                doubleTapFeedback = false to "↺ -10s"
                            } else if (canSeek && offset.x > w * 0.65f) {
                                val maxSeek = (durationMs - 1000L).coerceAtLeast(0L)
                                val target = (controller.currentPosition + CinemaPlayerLogic.DOUBLE_TAP_SEEK_MS).coerceAtMost(maxSeek)
                                controller.seekTo(target)
                                currentPosMs = target
                                doubleTapFeedback = true to "+10s ↻"
                            } else {
                                if (controller.isPlaying) controller.pause() else controller.play()
                                bumpHud()
                            }
                        },
                        onLongPress = {
                            if (locked || !canChangeSpeed || controller == null || !controller.isPlaying) return@detectTapGestures
                            speedBeforeBoost = controller.playbackParameters.speed
                            controller.setPlaybackSpeed(CinemaPlayerLogic.LONG_PRESS_BOOST_SPEED)
                            longPressBoostActive = true
                        },
                        onPress = {
                            try {
                                tryAwaitRelease()
                            } finally {
                                if (longPressBoostActive) {
                                    longPressBoostActive = false
                                    if (controller != null && controller.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)) {
                                        controller.setPlaybackSpeed(speedBeforeBoost)
                                    }
                                }
                            }
                        },
                    )
                }
                .pointerInput(locked, canSeek, containerSize, controller) {
                    var activeZone: CinemaGestureZone? = null
                    var totalDx = 0f
                    var totalDy = 0f
                    var dragStartX = 0f
                    var startBrightness = 0.5f
                    var startVolume = 0
                    var maxVolume = 15
                    var startPos = 0L
                    var pendingSeek = -1L

                    detectDragGestures(
                        onDragStart = { startOffset ->
                            if (locked || drawerMode != CinemaDrawerMode.NONE) {
                                activeZone = null
                                return@detectDragGestures
                            }
                            activeZone = null
                            totalDx = 0f
                            totalDy = 0f
                            dragStartX = startOffset.x
                            startBrightness = currentBrightness
                            maxVolume = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC)?.coerceAtLeast(1) ?: 15
                            startVolume = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
                            startPos = controller?.currentPosition?.coerceAtLeast(0L) ?: currentPosMs
                            pendingSeek = -1L
                        },
                        onDrag = { change, dragAmount ->
                            if (locked || drawerMode != CinemaDrawerMode.NONE) return@detectDragGestures
                            totalDx += dragAmount.x
                            totalDy += dragAmount.y
                            val w = containerSize.width.toFloat()
                            val h = containerSize.height.toFloat()
                            if (activeZone == null) {
                                activeZone = CinemaPlayerLogic.classifyDrag(totalDx, totalDy, dragStartX, w, canSeek)
                            }
                            when (activeZone) {
                                CinemaGestureZone.LEFT_BRIGHTNESS -> {
                                    change.consume()
                                    val next = CinemaPlayerLogic.computeBrightness(startBrightness, totalDy, h)
                                    currentBrightness = next
                                    activity?.window?.let { win ->
                                        val lp = win.attributes
                                        lp.screenBrightness = next
                                        win.attributes = lp
                                    }
                                    gestureHudIcon = "☀"
                                    gestureHudLabel = "Brilho ${(next * 100).toInt()}%"
                                    gestureHudFraction = next
                                    gestureHudSub = null
                                }
                                CinemaGestureZone.RIGHT_VOLUME -> {
                                    change.consume()
                                    val nextVol = CinemaPlayerLogic.computeVolume(startVolume, maxVolume, totalDy, h)
                                    audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, nextVol, 0)
                                    val pct = (nextVol.toFloat() / maxVolume.toFloat()).coerceIn(0f, 1f)
                                    gestureHudIcon = if (nextVol == 0) "🔇" else "🔊"
                                    gestureHudLabel = "Volume ${(pct * 100).toInt()}%"
                                    gestureHudFraction = pct
                                    gestureHudSub = null
                                }
                                CinemaGestureZone.HORIZONTAL_SEEK -> {
                                    change.consume()
                                    val target = CinemaPlayerLogic.computeSeekTarget(startPos, durationMs, totalDx, w)
                                    pendingSeek = target
                                    scrubbing = true
                                    scrubTargetMs = target
                                    val delta = target - startPos
                                    gestureHudIcon = if (delta >= 0L) "⏩" else "⏪"
                                    gestureHudLabel = CinemaPlayerLogic.formatDeltaMs(delta)
                                    gestureHudSub = "${CinemaPlayerLogic.formatTimeMs(target)} / ${CinemaPlayerLogic.formatTimeMs(durationMs)}"
                                    gestureHudFraction = if (durationMs > 0L) (target.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else -1f
                                }
                                null -> Unit
                            }
                        },
                        onDragEnd = {
                            if (activeZone == CinemaGestureZone.HORIZONTAL_SEEK && pendingSeek >= 0L) {
                                controller?.seekTo(pendingSeek)
                                currentPosMs = pendingSeek
                            }
                            scrubbing = false
                            activeZone = null
                            gestureHudLabel = null
                            gestureHudSub = null
                            gestureHudFraction = -1f
                        },
                        onDragCancel = {
                            scrubbing = false
                            activeZone = null
                            gestureHudLabel = null
                            gestureHudSub = null
                            gestureHudFraction = -1f
                        },
                    )
                },
        )

        // 1b. Subtle centered spinner when buffering while HUD is hidden or screen is locked
        if ((buffering || controller == null) && !hasError && (!controlsVisible || locked) && drawerMode == CinemaDrawerMode.NONE) {
            CircularProgressIndicator(
                color = CinemaCyan,
                strokeWidth = 3.dp,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(40.dp),
            )
        }

        // 2. Main Cinema Controls HUD (Top + Center + Bottom)
        AnimatedVisibility(
            visible = !locked && (controlsVisible || hasError) && drawerMode == CinemaDrawerMode.NONE,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(Modifier.fillMaxSize()) {
                // TOP GRADIENT BAR
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Black.copy(alpha = 0.86f), Color.Black.copy(alpha = 0.42f), Color.Transparent),
                            ),
                        )
                        .statusBarsPadding()
                        .displayCutoutPadding()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CinemaCircleIconButton(
                            size = 40.dp,
                            onClick = onBack,
                            modifier = Modifier.testTag("cinema-back"),
                        ) {
                            Text("←", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                        }
                        Column(Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                if (isLive) {
                                    Box(
                                        Modifier
                                            .background(CinemaLiveRed, RoundedCornerShape(6.dp))
                                            .padding(horizontal = 7.dp, vertical = 2.dp),
                                    ) {
                                        Text("● AO VIVO", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
                                    }
                                }
                                Text(
                                    entry.name,
                                    color = Color.White,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            val subtitle = when {
                                isLive && !liveNowPlayingTitle.isNullOrBlank() -> "Agora: $liveNowPlayingTitle"
                                entry.group.isNotBlank() -> entry.group
                                else -> null
                            }
                            if (subtitle != null) {
                                Text(
                                    subtitle,
                                    color = Color(0xFFD0D8E4),
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        if (onCast != null) {
                            CinemaTopPill(
                                label = if (isCasting) "📡 Na TV" else "📡 Transmitir",
                                highlighted = isCasting,
                                modifier = Modifier.testTag("cinema-cast"),
                            ) {
                                bumpHud()
                                onCast()
                            }
                        }
                        if (supportsPip) {
                            CinemaTopPill("⧉ PiP") {
                                onPictureInPicture()
                            }
                        }
                        CinemaCircleIconButton(
                            size = 38.dp,
                            onClick = { bumpHud(); onOpenOptions() },
                            modifier = Modifier.testTag("player-options"),
                        ) {
                            Text("⚙", color = Color.White, fontSize = 16.sp)
                        }
                    }
                }

                // LEFT-EDGE SCREEN LOCK BUTTON (DeepCine LockControlView signature position)
                CinemaCircleIconButton(
                    size = 44.dp,
                    onClick = {
                        locked = true
                        controlsVisible = false
                        lockHintVisible = true
                        interactionNonce++
                    },
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .displayCutoutPadding()
                        .padding(start = 18.dp)
                        .testTag("cinema-lock"),
                ) {
                    Text("🔒", fontSize = 17.sp)
                }

                // CENTER CINEMA TRANSPORT BUTTONS (DeepCine / Streaming Style)
                if (!hasError) {
                    Row(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalArrangement = Arrangement.spacedBy(28.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (isLive) {
                            if (onPrevious != null) {
                                CinemaCircleIconButton(size = 54.dp, onClick = { bumpHud(); onPrevious() }) {
                                    Text("⏮", color = Color.White, fontSize = 22.sp)
                                }
                            }
                        } else if (canSeek) {
                            CinemaCircleIconButton(size = 54.dp, onClick = {
                                bumpHud()
                                val target = (currentPosMs - CinemaPlayerLogic.DOUBLE_TAP_SEEK_MS).coerceAtLeast(0L)
                                controller?.seekTo(target)
                                currentPosMs = target
                            }) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("↺", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                    Text("10s", color = Color(0xFFD6E4F0), fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }

                        // Primary Play/Pause Circle with Starlink Cyan ring
                        Box(
                            modifier = Modifier
                                .size(70.dp)
                                .clip(CircleShape)
                                .background(CinemaGlassStrong)
                                .border(2.dp, CinemaCyan.copy(alpha = 0.9f), CircleShape)
                                .clickable {
                                    bumpHud()
                                    if (controller != null) {
                                        if (controller.isPlaying) controller.pause() else controller.play()
                                    }
                                }
                                .testTag("cinema-play-pause"),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (buffering || controller == null) {
                                CircularProgressIndicator(
                                    color = CinemaCyan,
                                    strokeWidth = 3.dp,
                                    modifier = Modifier.size(30.dp),
                                )
                            } else {
                                CinemaPlayPauseGlyph(isPlaying = isPlaying, modifier = Modifier.size(28.dp))
                            }
                        }

                        if (isLive) {
                            if (onNext != null) {
                                CinemaCircleIconButton(size = 54.dp, onClick = { bumpHud(); onNext() }) {
                                    Text("⏭", color = Color.White, fontSize = 22.sp)
                                }
                            }
                        } else if (canSeek) {
                            CinemaCircleIconButton(size = 54.dp, onClick = {
                                bumpHud()
                                val maxSeek = (durationMs - 1000L).coerceAtLeast(0L)
                                val target = (currentPosMs + CinemaPlayerLogic.DOUBLE_TAP_SEEK_MS).coerceAtMost(maxSeek)
                                controller?.seekTo(target)
                                currentPosMs = target
                            }) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("↻", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                    Text("10s", color = Color(0xFFD6E4F0), fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }

                // BOTTOM GRADIENT BAR (Seekbar + DeepCine Quick Action Strip)
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f), Color.Black.copy(alpha = 0.90f)),
                            ),
                        )
                        .navigationBarsPadding()
                        .displayCutoutPadding()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (!isLive && durationMs > 0L) {
                            val shownPos = if (scrubbing) scrubTargetMs else currentPosMs
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Text(
                                    CinemaPlayerLogic.formatTimeMs(shownPos),
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Slider(
                                    value = shownPos.toFloat().coerceIn(0f, durationMs.toFloat()),
                                    onValueChange = { value ->
                                        scrubbing = true
                                        scrubTargetMs = value.toLong()
                                        interactionNonce++
                                    },
                                    onValueChangeFinished = {
                                        controller?.seekTo(scrubTargetMs)
                                        currentPosMs = scrubTargetMs
                                        scrubbing = false
                                        interactionNonce++
                                    },
                                    valueRange = 0f..durationMs.toFloat().coerceAtLeast(1f),
                                    colors = SliderDefaults.colors(
                                        thumbColor = CinemaCyan,
                                        activeTrackColor = CinemaCyan,
                                        inactiveTrackColor = Color.White.copy(alpha = 0.28f),
                                    ),
                                    modifier = Modifier.weight(1f).height(26.dp),
                                )
                                Text(
                                    CinemaPlayerLogic.formatTimeMs(durationMs),
                                    color = Color(0xFFC5CFDC),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }

                        // Quick Action Bar
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (isLive && drawerItems.isNotEmpty() && onSelectDrawerItem != null) {
                                CinemaActionChip(
                                    label = "📺 Canais (${drawerItems.size})",
                                    highlighted = true,
                                    onClick = { drawerMode = CinemaDrawerMode.CHANNELS },
                                )
                            }
                            if (!isLive && drawerItems.isNotEmpty() && onSelectDrawerItem != null) {
                                CinemaActionChip(
                                    label = "🎬 Episódios (${drawerItems.size})",
                                    highlighted = true,
                                    onClick = { drawerMode = CinemaDrawerMode.EPISODES },
                                )
                            }
                            if (!isLive && onPrevious != null) {
                                CinemaActionChip("⏮ Ep. Ant.") { bumpHud(); onPrevious() }
                            }
                            if (!isLive && onNext != null) {
                                CinemaActionChip("⏭ Próx. Ep.") { bumpHud(); onNext() }
                            }
                            if (isLive && onOpenEpg != null) {
                                CinemaActionChip("📋 Guia") { bumpHud(); onOpenEpg() }
                            }
                            if (canChangeSpeed) {
                                CinemaActionChip("⚡ ${CinemaPlayerLogic.formatSpeed(playbackSpeed)}") {
                                    drawerMode = CinemaDrawerMode.SPEED
                                }
                            }
                            if (canChangeTracks && audioAvailable) {
                                CinemaActionChip("🔊 Áudio") { bumpHud(); onOpenTracks(C.TRACK_TYPE_AUDIO) }
                            }
                            if (canChangeTracks && subtitlesAvailable) {
                                CinemaActionChip("💬 Legendas") { bumpHud(); onOpenTracks(C.TRACK_TYPE_TEXT) }
                            }
                            CinemaActionChip("⛶ ${CinemaPlayerLogic.resizeModeLabel(resizeMode)}") {
                                bumpHud()
                                onResizeModeChange(CinemaPlayerLogic.nextResizeMode(resizeMode))
                            }
                            if (activity != null) {
                                CinemaActionChip("🔄 Girar") {
                                    bumpHud()
                                    val isLandscape = activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                                    activity.requestedOrientation = if (isLandscape) {
                                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                                    } else {
                                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 3. Long-Press 2.0x Boost Indicator Pill (DeepCine signature!)
        AnimatedVisibility(
            visible = longPressBoostActive,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 18.dp),
        ) {
            Box(
                Modifier
                    .background(CinemaGlassStrong, RoundedCornerShape(20.dp))
                    .border(1.dp, CinemaCyan, RoundedCornerShape(20.dp))
                    .padding(horizontal = 16.dp, vertical = 7.dp),
            ) {
                Text(
                    "⏩ Reproduzindo em 2.0x",
                    color = CinemaCyan,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        // 4. Double-Tap ±10s Feedback Pill
        doubleTapFeedback?.let { (isRight, label) ->
            Box(
                modifier = Modifier
                    .align(if (isRight) Alignment.CenterEnd else Alignment.CenterStart)
                    .padding(horizontal = 42.dp)
                    .background(CinemaGlassStrong, RoundedCornerShape(24.dp))
                    .border(1.dp, CinemaCyan.copy(alpha = 0.7f), RoundedCornerShape(24.dp))
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            ) {
                Text(label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
        }

        // 5. Drag Gesture HUD (Brightness / Volume / Horizontal Seek)
        gestureHudLabel?.let { label ->
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .width(210.dp)
                    .background(CinemaGlassStrong, RoundedCornerShape(16.dp))
                    .border(1.dp, CinemaGlassBorder, RoundedCornerShape(16.dp))
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(gestureHudIcon, fontSize = 18.sp)
                    Text(label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
                gestureHudSub?.let { sub ->
                    Text(sub, color = Color(0xFFD0D8E4), fontSize = 12.sp)
                }
                if (gestureHudFraction >= 0f) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(5.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Color.White.copy(alpha = 0.22f)),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(gestureHudFraction.coerceIn(0f, 1f))
                                .fillMaxHeight()
                                .background(CinemaCyan, RoundedCornerShape(3.dp)),
                        )
                    }
                }
            }
        }

        // 6. Screen Lock Unlock Pill (DeepCine LockControlView)
        AnimatedVisibility(
            visible = locked && lockHintVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.CenterStart)
                .displayCutoutPadding()
                .padding(start = 20.dp),
        ) {
            Box(
                Modifier
                    .background(CinemaGlassStrong, RoundedCornerShape(24.dp))
                    .border(1.5.dp, CinemaCyan, RoundedCornerShape(24.dp))
                    .clickable {
                        locked = false
                        lockHintVisible = false
                        controlsVisible = true
                        interactionNonce++
                    }
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .testTag("cinema-unlock"),
            ) {
                Text("🔓 Destravar tela", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }

        // 7. Right-Side Cinema Drawer (Channels / Episodes / Speed)
        AnimatedVisibility(
            visible = drawerMode != CinemaDrawerMode.NONE,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            Row(Modifier.fillMaxSize()) {
                // Scrim left side to dismiss drawer
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(Color.Black.copy(alpha = 0.45f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            drawerMode = CinemaDrawerMode.NONE
                            bumpHud()
                        },
                )
                CinemaSideDrawer(
                    mode = drawerMode,
                    currentEntry = entry,
                    items = drawerItems,
                    currentSpeed = playbackSpeed,
                    onSelectEntry = { picked ->
                        drawerMode = CinemaDrawerMode.NONE
                        onSelectDrawerItem?.invoke(picked)
                    },
                    onSelectSpeed = { spd ->
                        if (controller?.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH) == true) {
                            controller.setPlaybackSpeed(spd)
                            playbackSpeed = spd
                        }
                        drawerMode = CinemaDrawerMode.NONE
                        bumpHud()
                    },
                    onClose = {
                        drawerMode = CinemaDrawerMode.NONE
                        bumpHud()
                    },
                )
            }
        }
    }
}

@Composable
private fun CinemaSideDrawer(
    mode: CinemaDrawerMode,
    currentEntry: MediaEntry,
    items: List<MediaEntry>,
    currentSpeed: Float,
    onSelectEntry: (MediaEntry) -> Unit,
    onSelectSpeed: (Float) -> Unit,
    onClose: () -> Unit,
) {
    val activeIdx = remember(items, currentEntry.id) {
        items.indexOfFirst { it.id == currentEntry.id }.coerceAtLeast(0)
    }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (activeIdx - 2).coerceAtLeast(0))
    Column(
        modifier = Modifier
            .width(300.dp)
            .fillMaxHeight()
            .background(Color(0xF00B0F18))
            .border(1.dp, Color(0x26FFFFFF))
            .statusBarsPadding()
            .navigationBarsPadding()
            .displayCutoutPadding()
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                when (mode) {
                    CinemaDrawerMode.CHANNELS -> "Canais (${items.size})"
                    CinemaDrawerMode.EPISODES -> "Episódios (${items.size})"
                    CinemaDrawerMode.SPEED -> "Velocidade"
                    CinemaDrawerMode.NONE -> ""
                },
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            )
            CinemaTopPill("✕") { onClose() }
        }
        Spacer(Modifier.height(10.dp))
        if (mode == CinemaDrawerMode.SPEED) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CinemaPlayerLogic.SPEEDS.forEach { spd ->
                    val selected = kotlin.math.abs(spd - currentSpeed) < 0.05f
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (selected) CinemaCyan.copy(alpha = 0.22f) else Color(0xFF151C2B))
                            .border(
                                1.dp,
                                if (selected) CinemaCyan else Color(0x24FFFFFF),
                                RoundedCornerShape(10.dp),
                            )
                            .clickable { onSelectSpeed(spd) }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                    ) {
                        Text(
                            CinemaPlayerLogic.formatSpeed(spd) + if (spd == 1.0f) " (Normal)" else "",
                            color = if (selected) CinemaCyan else Color.White,
                            fontSize = 14.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        )
                    }
                }
            }
        } else {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(items, key = { idx, it -> "${it.id}_$idx" }) { idx, item ->
                    val selected = item.id == currentEntry.id
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (selected) CinemaCyan.copy(alpha = 0.20f) else Color(0xFF151C2B))
                            .border(
                                1.dp,
                                if (selected) CinemaCyan else Color(0x1EFFFFFF),
                                RoundedCornerShape(10.dp),
                            )
                            .clickable { onSelectEntry(item) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            "${idx + 1}",
                            color = if (selected) CinemaCyan else Color(0xFF8A96A8),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                item.name,
                                color = if (selected) CinemaCyan else Color.White,
                                fontSize = 13.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (item.group.isNotBlank()) {
                                Text(
                                    item.group,
                                    color = Color(0xFF9AA6B8),
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        if (selected) {
                            Text("▶", color = CinemaCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CinemaCircleIconButton(
    size: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(CinemaGlass)
            .border(1.dp, CinemaGlassBorder, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
private fun CinemaTopPill(
    label: String,
    highlighted: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(if (highlighted) CinemaCyan.copy(alpha = 0.25f) else CinemaGlass)
            .border(
                1.dp,
                if (highlighted) CinemaCyan else CinemaGlassBorder,
                RoundedCornerShape(18.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (highlighted) CinemaCyan else Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
private fun CinemaActionChip(
    label: String,
    highlighted: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(if (highlighted) CinemaCyan.copy(alpha = 0.22f) else CinemaGlass)
            .border(
                1.dp,
                if (highlighted) CinemaCyan.copy(alpha = 0.85f) else CinemaGlassBorder,
                RoundedCornerShape(18.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (highlighted) CinemaCyan else Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
private fun CinemaPlayPauseGlyph(
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        if (isPlaying) {
            val barW = w * 0.24f
            val gap = w * 0.18f
            val leftX = (w - (barW * 2 + gap)) / 2f
            drawRect(Color.White, topLeft = Offset(leftX, h * 0.14f), size = androidx.compose.ui.geometry.Size(barW, h * 0.72f))
            drawRect(Color.White, topLeft = Offset(leftX + barW + gap, h * 0.14f), size = androidx.compose.ui.geometry.Size(barW, h * 0.72f))
        } else {
            val path = Path().apply {
                moveTo(w * 0.24f, h * 0.12f)
                lineTo(w * 0.88f, h * 0.50f)
                lineTo(w * 0.24f, h * 0.88f)
                close()
            }
            drawPath(path, Color.White)
        }
    }
}
