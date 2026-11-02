package play.ott.nativeapp.ui

import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import play.ott.nativeapp.R
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.ui.AspectRatioFrameLayout

/** Both touch and remote actions write to the service player through its controller. */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun PlaybackOptionsDialog(
    controller: MediaController?,
    resizeMode: Int,
    onResize: (Int) -> Unit,
    onTracks: (Int) -> Unit,
    onPictureInPicture: () -> Unit,
    onStop: () -> Unit,
    onDismiss: () -> Unit,
) {
    var speedOptions by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(controller) {
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) { revision++ }
        }
        controller?.addListener(listener)
        onDispose { controller?.removeListener(listener) }
    }
    val speed = remember(controller, revision) { controller?.playbackParameters?.speed ?: 1f }
    val canChangeSpeed = controller?.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH) == true
    val canChangeTracks = controller?.isCommandAvailable(Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS) == true
    val audioAvailable = remember(controller, revision) { controller?.currentTracks?.isTypeSupported(C.TRACK_TYPE_AUDIO) == true }
    val subtitlesAvailable = remember(controller, revision) { controller?.currentTracks?.isTypeSupported(C.TRACK_TYPE_TEXT) == true }
    val isTv = LocalConfiguration.current.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION
    val supportsPip = !isTv && LocalContext.current.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    val initialFocus = remember { FocusRequester() }
    AlertDialog(
        modifier = Modifier.tvRemoteInput(),
        onDismissRequest = onDismiss,
        title = { Text(if (speedOptions) stringResource(R.string.dialog_speed_title) else stringResource(R.string.dialog_options_title)) },
        text = {
            // The dialog owns a separate window and composition. Wait for that window,
            // so the requester is attached before receiving the first remote event.
            val windowFocused = LocalWindowInfo.current.isWindowFocused
            LaunchedEffect(speedOptions, isTv, windowFocused) {
                if (isTv && windowFocused) { withFrameNanos { }; initialFocus.requestFocus() }
            }
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (speedOptions) {
                    val speeds = listOf(.5f, .75f, 1f, 1.25f, 1.5f, 2f)
                    val focusedSpeed = speeds.minBy { kotlin.math.abs(it - speed) }
                    speeds.forEach { option ->
                        ActionButton(stringResource(R.string.dialog_speed_value, option.toString()), {
                            if (controller?.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH) == true) controller.setPlaybackSpeed(option)
                            onDismiss()
                        }, Modifier.fillMaxWidth().testTag("player-speed-$option")
                            .then(if (option == focusedSpeed) Modifier.focusRequester(initialFocus) else Modifier),
                            selected = option == speed, enabled = canChangeSpeed)
                    }
                } else {
                    ActionButton(stringResource(R.string.dialog_speed_current, speed.toString()), { speedOptions = true }, Modifier.fillMaxWidth()
                        .testTag("player-speed-options").then(if (canChangeSpeed) Modifier.focusRequester(initialFocus) else Modifier), enabled = canChangeSpeed)
                    ActionButton(stringResource(R.string.dialog_audio), { onTracks(C.TRACK_TYPE_AUDIO) }, Modifier.fillMaxWidth().testTag("player-audio-options"), enabled = canChangeTracks && audioAvailable)
                    ActionButton(stringResource(R.string.dialog_subtitles), { onTracks(C.TRACK_TYPE_TEXT) }, Modifier.fillMaxWidth().testTag("player-subtitle-options"), enabled = canChangeTracks && subtitlesAvailable)
                    listOf(
                        AspectRatioFrameLayout.RESIZE_MODE_FIT to R.string.dialog_scale_fit,
                        AspectRatioFrameLayout.RESIZE_MODE_ZOOM to R.string.dialog_scale_zoom,
                        AspectRatioFrameLayout.RESIZE_MODE_FILL to R.string.dialog_scale_fill,
                    ).forEach { (mode, label) ->
                        ActionButton(stringResource(label), { onResize(mode); onDismiss() }, Modifier.fillMaxWidth().testTag("player-scale-$mode")
                            .then(if (!canChangeSpeed && mode == AspectRatioFrameLayout.RESIZE_MODE_FIT) Modifier.focusRequester(initialFocus) else Modifier), selected = resizeMode == mode)
                    }
                    if (supportsPip) ActionButton(stringResource(R.string.dialog_pip), { onDismiss(); onPictureInPicture() }, Modifier.fillMaxWidth().testTag("player-pip"))
                    ActionButton(stringResource(R.string.dialog_stop), { onDismiss(); onStop() }, Modifier.fillMaxWidth().testTag("player-stop"))
                }
            }
        },
        confirmButton = { ActionButton(stringResource(R.string.dialog_done), onDismiss) },
    )
}
