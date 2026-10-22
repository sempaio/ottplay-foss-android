package play.ott.nativeapp

import android.app.PictureInPictureParams
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Rational
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import play.ott.nativeapp.playback.PlaybackService
import play.ott.nativeapp.i18n.AppLanguages
import play.ott.nativeapp.ui.AppAction
import play.ott.nativeapp.ui.OttNativeApp

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
open class MainActivity : AppCompatActivity() {
    private val model: PlayerViewModel by viewModels()
    private var controller by mutableStateOf<MediaController?>(null)
    private var pip by mutableStateOf(false)
    private var future: ListenableFuture<MediaController>? = null
    private var fullScreen = false
    private val reconnectHandler = Handler(Looper.getMainLooper())
    private var reconnectAttempts = 0
    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            updatePip()
        }
        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) { updatePip() }
    }
    private val importPlaylist = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: SecurityException) { }
            val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: getString(R.string.local_playlist)
            model.importPlaylist(uri, name)
        }
    }
    private val importSettings = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(model::importSettings) }
    private val exportSettings = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let(model::exportSettings) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLanguages.onActivityCreated()
        model.onLocaleChanged()
        setContent {
            val state by model.state.collectAsStateWithLifecycle()
            OttNativeApp(
                state = state,
                onAction = { action ->
                    when (action) {
                        AppAction.ImportPlaylist -> importPlaylist.launch(arrayOf("*/*"))
                        AppAction.ImportSettings -> importSettings.launch(arrayOf("application/json", "text/plain"))
                        AppAction.ExportSettings -> exportSettings.launch("ottplay-native-settings.json")
                        else -> model.dispatch(action)
                    }
                },
                controller = controller,
                onPictureInPicture = { enterPip() },
                onFullscreenChanged = { enabled -> setFullscreen(enabled) },
                inPictureInPicture = pip
            )
        }
        connectController()
    }

    private fun connectController() {
        if (isDestroyed) return
        val result = MediaController.Builder(this, SessionToken(this, ComponentName(this, PlaybackService::class.java)))
            .setListener(object : MediaController.Listener {
                override fun onDisconnected(value: MediaController) {
                    if (isDestroyed || controller !== value) return
                    model.detachController(value)
                    controller = null
                    future = null
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    if (reconnectAttempts < 3) {
                        val delayMillis = 500L shl reconnectAttempts++
                        reconnectHandler.postDelayed({ if (!isDestroyed && controller == null) connectController() }, delayMillis)
                    } else model.onControllerFailure()
                }
                override fun onError(controller: MediaController, sessionError: androidx.media3.session.SessionError) {
                    model.onPlaybackSessionError()
                }
            }).buildAsync()
        future = result
        result.addListener({
            if (isDestroyed || future !== result) return@addListener
            try {
                val value = result.get()
                controller = value
                reconnectAttempts = 0
                value.addListener(playerListener)
                model.attachController(value)
                playerListener.onIsPlayingChanged(value.isPlaying)
            } catch (_: Exception) { model.onControllerFailure() }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun setFullscreen(enabled: Boolean) {
        fullScreen = enabled
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (enabled) hide(WindowInsetsCompat.Type.systemBars()) else show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private val isTelevision: Boolean
        get() = resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION

    private fun supportsPip(): Boolean = !isTelevision && packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    private fun pipParameters(): PictureInPictureParams {
        val size = controller?.videoSize
        val ratio = if (size != null && size.width > 0 && size.height > 0) {
            val aspect = (size.width.toFloat() * size.pixelWidthHeightRatio / size.height).coerceIn(0.42f, 2.38f)
            Rational((aspect * 1000).toInt(), 1000)
        } else Rational(16, 9)
        return PictureInPictureParams.Builder().setAspectRatio(ratio).apply {
            val video = window.decorView.findViewWithTag<android.view.View>("ott-native-video")
            val rectangle = android.graphics.Rect()
            if (video != null && video.getGlobalVisibleRect(rectangle)) setSourceRectHint(rectangle)
            if (Build.VERSION.SDK_INT >= 31) {
                setAutoEnterEnabled(controller?.isPlaying == true)
                setSeamlessResizeEnabled(true)
            }
        }.build()
    }
    private fun updatePip() { if (supportsPip()) setPictureInPictureParams(pipParameters()) }
    private fun enterPip() {
        if (supportsPip() && controller?.currentMediaItem != null) enterPictureInPictureMode(pipParameters())
    }
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < 31 && controller?.isPlaying == true) enterPip()
    }
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pip = isInPictureInPictureMode
        if (!pip) setFullscreen(fullScreen)
    }
    override fun onStop() {
        // The service owns TV visibility, including after this Activity is destroyed.
        if (!isTelevision && !isChangingConfigurations && !isInPictureInPictureMode && !model.state.value.backgroundPlayback) {
            controller?.pause()
        }
        super.onStop()
    }
    override fun onDestroy() {
        reconnectHandler.removeCallbacksAndMessages(null)
        val value = controller
        value?.removeListener(playerListener)
        model.detachController(value)
        future?.let(MediaController::releaseFuture)
        controller = null
        super.onDestroy()
    }
}
