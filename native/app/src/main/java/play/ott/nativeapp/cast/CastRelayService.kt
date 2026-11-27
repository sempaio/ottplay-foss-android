package play.ott.nativeapp.cast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import play.ott.nativeapp.MainActivity
import play.ott.nativeapp.R

/**
 * High-priority Foreground Service backed by an active platform [MediaSession] (`STATE_PLAYING`)
 * + `MediaStyle` notification + `PARTIAL_WAKE_LOCK` + `WifiLock`.
 *
 * Holding an active MediaSession in `STATE_PLAYING` is essential on Android 13–16 (Samsung OneUI,
 * Pixel, Xiaomi, Motorola) so the OS keeps CPU scheduling and Wi-Fi socket throughput at full
 * foreground priority when the user minimizes the app, opens WhatsApp, or locks the screen.
 */
class CastRelayService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var watchdogJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var mediaSession: MediaSession? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE_PLAY_PAUSE -> {
                serviceScope.launch {
                    CastSessionHost.controller.togglePlayPause(applicationContext)
                    val st = CastSessionHost.controller.state.value
                    val tv = st.connectedDevice?.friendlyName
                    if (tv != null) {
                        updateMediaSession(tv, st.castingTitle, st.isPlayingOnTv)
                        updateNotification(tv, st.castingTitle, st.isPlayingOnTv)
                    }
                }
                return START_NOT_STICKY
            }
            ACTION_DISCONNECT -> {
                serviceScope.launch {
                    CastSessionHost.controller.disconnect(applicationContext)
                }
                stopSelfCleanly()
                return START_NOT_STICKY
            }
            ACTION_STOP_SERVICE -> {
                stopSelfCleanly()
                return START_NOT_STICKY
            }
            else -> {
                val tvName = intent?.getStringExtra(EXTRA_TV_NAME)
                    ?: CastSessionHost.controller.state.value.connectedDevice?.friendlyName
                    ?: "Smart TV"
                val mediaTitle = intent?.getStringExtra(EXTRA_MEDIA_TITLE)
                    ?: CastSessionHost.controller.state.value.castingTitle
                val isPlaying = intent?.getBooleanExtra(EXTRA_IS_PLAYING, true) ?: true

                acquireLocks()
                ensureMediaSession()
                updateMediaSession(tvName, mediaTitle, isPlaying)
                ensureChannel()
                val notification = buildNotification(tvName, mediaTitle, isPlaying)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val fgsTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    runCatching {
                        startForeground(NOTIFICATION_ID, notification, fgsTypes)
                    }.onFailure {
                        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                    }
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
                isServiceActive = true
                ensureWatchdogRunning()
                return START_STICKY
            }
        }
    }

    private fun ensureMediaSession() {
        if (mediaSession != null) return
        val session = MediaSession(this, "StarlinkCastRelaySession")
        session.setCallback(
            object : MediaSession.Callback() {
                override fun onPlay() {
                    serviceScope.launch {
                        val st = CastSessionHost.controller.state.value
                        if (!st.isPlayingOnTv) CastSessionHost.controller.togglePlayPause(applicationContext)
                    }
                }

                override fun onPause() {
                    serviceScope.launch {
                        val st = CastSessionHost.controller.state.value
                        if (st.isPlayingOnTv) CastSessionHost.controller.togglePlayPause(applicationContext)
                    }
                }

                override fun onStop() {
                    serviceScope.launch {
                        CastSessionHost.controller.disconnect(applicationContext)
                    }
                    stopSelfCleanly()
                }
            },
        )
        session.isActive = true
        mediaSession = session
    }

    private fun updateMediaSession(tvName: String, mediaTitle: String, isPlaying: Boolean) {
        val session = mediaSession ?: return
        val actions = PlaybackState.ACTION_PLAY or
            PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_STOP
        val stateCode = if (isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
        val state = PlaybackState.Builder()
            .setActions(actions)
            .setState(stateCode, PlaybackState.PLAYBACK_POSITION_UNKNOWN, if (isPlaying) 1.0f else 0.0f)
            .build()
        session.setPlaybackState(state)
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, mediaTitle.ifBlank { "STARLINK IPTV" })
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "Transmitindo para $tvName")
                .build(),
        )
        session.isActive = true
    }

    private fun ensureWatchdogRunning() {
        if (watchdogJob?.isActive == true) return
        watchdogJob = serviceScope.launch {
            while (isActive) {
                // Gentle 12s interval avoids hammering Samsung/LG embedded UPnP daemons while streaming
                delay(12_000L)
                if (!CastSessionHost.isCastingActive) {
                    stopSelfCleanly()
                    break
                }
                runCatching { CastSessionHost.controller.watchdogTick(applicationContext) }
            }
        }
    }

    override fun onDestroy() {
        isServiceActive = false
        watchdogJob?.cancel()
        releaseMediaSession()
        releaseLocks()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun stopSelfCleanly() {
        isServiceActive = false
        watchdogJob?.cancel()
        watchdogJob = null
        releaseMediaSession()
        releaseLocks()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        }
        stopSelf()
    }

    private fun releaseMediaSession() {
        runCatching {
            mediaSession?.isActive = false
            mediaSession?.release()
        }
        mediaSession = null
    }

    private fun acquireLocks() {
        if (wakeLock?.isHeld != true) {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = runCatching {
                pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Starlink:CastRelayWakeLock")?.apply {
                    setReferenceCounted(false)
                    acquire(6 * 60 * 60 * 1000L) // max 6h safety timeout
                }
            }.getOrNull()
        }
        if (wifiLock?.isHeld != true) {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = runCatching {
                wm?.createWifiLock(mode, "Starlink:CastRelayWifiLock")?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }.getOrNull()
        }
    }

    private fun releaseLocks() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
        wifiLock = null
    }

    private fun ensureChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            // IMPORTANCE_DEFAULT (with sound=null, vibration=false) keeps OEM schedulers from
            // demoting the active media cast notification into low-priority background throttling.
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Transmissão para TV",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Mantém a transmissão para Smart TV / Chromecast fluida em segundo plano"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun updateNotification(tvName: String, mediaTitle: String, isPlaying: Boolean) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        manager.notify(NOTIFICATION_ID, buildNotification(tvName, mediaTitle, isPlaying))
    }

    private fun buildNotification(tvName: String, mediaTitle: String, isPlaying: Boolean): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            this,
            10,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val toggleIntent = Intent(this, CastRelayService::class.java).apply { action = ACTION_TOGGLE_PLAY_PAUSE }
        val togglePending = PendingIntent.getService(
            this,
            11,
            toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val stopIntent = Intent(this, CastRelayService::class.java).apply { action = ACTION_DISCONNECT }
        val stopPending = PendingIntent.getService(
            this,
            12,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val isDirectMode = CastSessionHost.controller.state.value.castingDirectMode
        val notifTitle = if (isDirectMode) "☁️ Direto em $tvName" else "Transmitindo para $tvName"
        val notifText = if (isDirectMode) {
            val titlePart = mediaTitle.ifBlank { "Reproduzindo direto na TV" }
            "$titlePart • Pode desconectar o celular"
        } else {
            mediaTitle.ifBlank { "Você pode usar o celular normalmente" }
        }
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.starlink_logo)
            .setContentTitle(notifTitle)
            .setContentText(notifText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setContentIntent(openPending)
            .addAction(
                Notification.Action.Builder(
                    null,
                    if (isPlaying) "Pausar" else "Retomar",
                    togglePending,
                ).build(),
            )
            .addAction(
                Notification.Action.Builder(
                    null,
                    if (isDirectMode) "Parar TV" else "Desconectar",
                    stopPending,
                ).build(),
            )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }

        val token = mediaSession?.sessionToken
        if (token != null) {
            builder.setStyle(
                Notification.MediaStyle()
                    .setMediaSession(token)
                    .setShowActionsInCompactView(0, 1),
            )
        }

        return builder.build()
    }

    companion object {
        @Volatile
        var isServiceActive: Boolean = false
            private set

        private const val CHANNEL_ID = "starlink_cast_relay_v2"
        private const val NOTIFICATION_ID = 4108
        private const val ACTION_START_OR_UPDATE = "play.ott.nativeapp.cast.START_OR_UPDATE"
        private const val ACTION_TOGGLE_PLAY_PAUSE = "play.ott.nativeapp.cast.TOGGLE_PLAY_PAUSE"
        private const val ACTION_DISCONNECT = "play.ott.nativeapp.cast.DISCONNECT"
        private const val ACTION_STOP_SERVICE = "play.ott.nativeapp.cast.STOP_SERVICE"
        private const val EXTRA_TV_NAME = "tv_name"
        private const val EXTRA_MEDIA_TITLE = "media_title"
        private const val EXTRA_IS_PLAYING = "is_playing"

        fun startOrUpdate(context: Context, tvName: String, mediaTitle: String, isPlaying: Boolean) {
            val appCtx = context.applicationContext
            val intent = Intent(appCtx, CastRelayService::class.java).apply {
                action = ACTION_START_OR_UPDATE
                putExtra(EXTRA_TV_NAME, tvName)
                putExtra(EXTRA_MEDIA_TITLE, mediaTitle)
                putExtra(EXTRA_IS_PLAYING, isPlaying)
            }
            runCatching { ContextCompat.startForegroundService(appCtx, intent) }
        }

        fun stop(context: Context) {
            val appCtx = context.applicationContext
            isServiceActive = false
            runCatching {
                appCtx.stopService(Intent(appCtx, CastRelayService::class.java))
            }
        }
    }
}

/**
 * Checks and requests Android's unrestricted background battery exemption so OEM App Freezers
 * do not throttle the local Wi-Fi DLNA/Cast relay 30 seconds after the user minimizes the app.
 */
internal object CastPowerPolicy {
    fun isBatteryUnrestricted(context: Context): Boolean {
        val pm = context.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return runCatching { pm.isIgnoringBatteryOptimizations(context.packageName) }.getOrDefault(true)
    }

    fun requestUnrestrictedBattery(context: Context) {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val opened = runCatching {
            context.startActivity(direct)
            true
        }.getOrDefault(false)
        if (!opened) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    },
                )
            }
        }
    }
}
