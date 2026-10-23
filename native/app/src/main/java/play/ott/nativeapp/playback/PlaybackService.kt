package play.ott.nativeapp.playback

import play.ott.nativeapp.i18n.AppLanguages
import play.ott.nativeapp.R
import android.app.PendingIntent
import android.content.res.Configuration
import android.os.Process
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommands
import androidx.media3.session.SessionError
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import play.ott.nativeapp.OttplayApplication
import play.ott.nativeapp.core.MediaKind
import play.ott.nativeapp.core.SourceConfig

/**
 * The service is the only owner of the decoder and session. Activities attach/detach controllers
 * and video surfaces; releasing a controller never terminates playback. Media3 publishes actual
 * buffering/error/position state and owns the foreground media notification.
 */
@UnstableApi
class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private var player: ExoPlayer? = null
    private var sessionPlayer: ChannelNavigationPlayer? = null
    private var httpClient: OkHttpClient? = null
    private var progress: PlaybackProgressRecorder? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        // SystemUI can process a dismissed notification after a replacement session has started.
        // Reusing its key lets that delayed Stop target the replacement session. Keep one ID for
        // this entire service lifetime, including channel changes, and a new ID after recreation.
        setMediaNotificationProvider(DefaultMediaNotificationProvider.Builder(this)
            .setNotificationId(notificationIds.getAndUpdate { previous ->
                when (previous) {
                    Int.MAX_VALUE -> 1
                    20937 -> 20939 // 20938 is Media3's temporary foreground-service notification.
                    else -> previous + 1
                }
            })
            .build())
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        httpClient = client
        val exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(ItemMediaSourceFactory(this, client))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player = exoPlayer
        val app = application as OttplayApplication
        val repository = app.repository
        val isTelevision = resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION
        val preferencePlayer = TrackPreferencePlayer(exoPlayer, repository.preferences.data, serviceScope)
        val foregroundPlayer = if (isTelevision) ForegroundVideoPlayer(preferencePlayer) { app.playbackActivityVisible.value } else preferencePlayer
        progress = PlaybackProgressRecorder(exoPlayer, serviceScope, repository.progressWriter)
        var queueSource: SourceConfig? = null
        val navigationPlayer = ChannelNavigationPlayer(
            delegate = StopClearsPlaylistPlayer(foregroundPlayer),
            scope = serviceScope,
            loadChannels = { mediaId ->
                queueSource = null
                var found = emptyList<play.ott.nativeapp.core.MediaEntry>()
                for (source in repository.sources()) {
                    val entries = repository.cached(source.id).entries
                    currentCoroutineContext().ensureActive()
                    if (entries.any { it.id == mediaId && it.kind == MediaKind.LIVE }) {
                        queueSource = source
                        found = entries.filter { it.kind == MediaKind.LIVE }
                        break
                    }
                }
                found
            },
            resolve = { entry ->
                require(entry.playbackUnsupportedReason == null) { "Unsupported playback configuration" }
                val source = repository.sources().first { it.id == entry.sourceId }
                check(source == queueSource) { "Source changed" }
                check(repository.cached(source.id).entries.firstOrNull { it.id == entry.id } == entry) { "Catalogue changed" }
                val stream = repository.stream(source, entry)
                check(repository.sources().firstOrNull { it.id == source.id } == source) { "Source changed" }
                stream
            },
            onFailure = {
                mediaSession?.sendError(SessionError(SessionError.ERROR_IO,
                    AppLanguages.localizedContext(this).getString(R.string.message_service_switch_failed)))
            },
            validate = { PlaybackItems.requireSupported(this, it) },
        )
        sessionPlayer = navigationPlayer
        if (isTelevision) serviceScope.launch {
            app.playbackActivityVisible.collect { visible ->
                // Use the navigation player so leaving the app also cancels an unresolved channel switch.
                if (!visible) navigationPlayer.pause()
            }
        }
        val builder = MediaSession.Builder(this, navigationPlayer).setCallback(SessionCallback())
        val launch = if (isTelevision) packageManager.getLeanbackLaunchIntentForPackage(packageName)
            else packageManager.getLaunchIntentForPackage(packageName)
        launch?.let { intent ->
            builder.setSessionActivity(PendingIntent.getActivity(
                this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ))
        }
        mediaSession = builder.build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    // MediaSessionService's default onTaskRemoved retains ongoing playback, and stops an idle
    // service. Do not couple this to Activity onStop/onDestroy or store Activity references here.
    override fun onDestroy() {
        progress?.close()
        progress = null
        mediaSession?.release()
        mediaSession = null
        serviceScope.cancel()
        sessionPlayer?.release()
        sessionPlayer = null
        player = null
        httpClient?.dispatcher?.cancelAll()
        httpClient?.connectionPool?.evictAll()
        httpClient?.dispatcher?.executorService?.shutdown()
        httpClient = null
        super.onDestroy()
    }

    private inner class SessionCallback : MediaSession.Callback {
        override fun onConnectAsync(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.ConnectionResult> {
            val access = controllerAccess(
                controller.uid == Process.myUid(),
                session.isMediaNotificationController(controller),
                controller.isTrusted,
            )
            if (access == ControllerAccess.REJECT) return Futures.immediateFuture(MediaSession.ConnectionResult.reject())
            val commands = if (access == ControllerAccess.OWNER) {
                MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                    .remove(Player.COMMAND_RELEASE)
                    .remove(Player.COMMAND_SET_AUDIO_ATTRIBUTES)
                    .build()
            } else {
                // System/notification controls can operate the current session, not inject URLs,
                // credentials, tracks or a replacement surface into this app's player.
                Player.Commands.Builder().addAll(
                    Player.COMMAND_PLAY_PAUSE, Player.COMMAND_PREPARE, Player.COMMAND_STOP,
                    Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
                    Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD,
                    Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_PREVIOUS,
                    Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                    Player.COMMAND_GET_METADATA,
                ).build()
            }
            return Futures.immediateFuture(MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                .setAvailableSessionCommands(SessionCommands.EMPTY)
                .setAvailablePlayerCommands(commands)
                .build())
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
        ): ListenableFuture<List<MediaItem>> {
            if (controller.uid != Process.myUid()) {
                return Futures.immediateFailedFuture(SecurityException("Only the app can replace playback"))
            }
            return try {
                Futures.immediateFuture(mediaItems.map(PlaybackItems::resolve))
            } catch (_: IllegalArgumentException) {
                // Never forward provider URLs/header values inside public error messages.
                Futures.immediateFailedFuture(IllegalArgumentException("Invalid playback request"))
            }
        }
    }

    private companion object {
        // Never reuse an ID in this process; randomize the starting point across process restarts.
        val notificationIds = AtomicInteger(Random.nextInt(100_000, Int.MAX_VALUE))
    }
}

/** Gate commands before they reach ExoPlayer, even when no Activity/controller listener survives. */
@UnstableApi
private class ForegroundVideoPlayer(player: Player, private val visible: () -> Boolean) : ForwardingPlayer(player) {
    override fun play() { if (visible()) super.play() }
    override fun setPlayWhenReady(playWhenReady: Boolean) { super.setPlayWhenReady(playWhenReady && visible()) }
}

/** Explicit Stop retires the item and notification; Pause deliberately retains both. */
@UnstableApi
internal class StopClearsPlaylistPlayer(player: Player) : ForwardingPlayer(player) {
    override fun stop() {
        super.setPlayWhenReady(false)
        super.stop()
        super.clearMediaItems()
    }
}
