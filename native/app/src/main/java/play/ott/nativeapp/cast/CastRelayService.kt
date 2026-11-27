package play.ott.nativeapp.cast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
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
 * Foreground service that keeps the local Wi-Fi DLNA/Cast relay and control session alive
 * while the user locks the phone screen, opens WhatsApp, or switches apps.
 */
class CastRelayService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var watchdogJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE_PLAY_PAUSE -> {
                serviceScope.launch {
                    CastSessionHost.controller.togglePlayPause()
                    val st = CastSessionHost.controller.state.value
                    val tv = st.connectedDevice?.friendlyName
                    if (tv != null) {
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
                ensureChannel()
                val notification = buildNotification(tvName, mediaTitle, isPlaying)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
                ensureWatchdogRunning()
                return START_STICKY
            }
        }
    }

    private fun ensureWatchdogRunning() {
        if (watchdogJob?.isActive == true) return
        watchdogJob = serviceScope.launch {
            while (isActive) {
                delay(5_000L)
                if (!CastSessionHost.isCastingActive) {
                    stopSelfCleanly()
                    break
                }
                runCatching { CastSessionHost.controller.watchdogTick(applicationContext) }
            }
        }
    }

    override fun onDestroy() {
        watchdogJob?.cancel()
        releaseLocks()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun stopSelfCleanly() {
        watchdogJob?.cancel()
        watchdogJob = null
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
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Transmissão para TV",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Mantém a transmissão para Smart TV / Chromecast ativa em segundo plano"
                setShowBadge(false)
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

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.starlink_logo)
            .setContentTitle("Transmitindo para $tvName")
            .setContentText(mediaTitle.ifBlank { "Você pode usar o celular normalmente" })
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setContentIntent(openPending)
            .addAction(0, if (isPlaying) "Pausar" else "Retomar", togglePending)
            .addAction(0, "Desconectar", stopPending)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "starlink_cast_relay_v1"
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
            runCatching {
                appCtx.stopService(Intent(appCtx, CastRelayService::class.java))
            }
        }
    }
}
