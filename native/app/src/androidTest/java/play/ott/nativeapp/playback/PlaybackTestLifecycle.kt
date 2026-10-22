package play.ott.nativeapp.playback

import android.app.Activity
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.res.Configuration
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import play.ott.nativeapp.MainActivity
import play.ott.nativeapp.OttplayApplication

/** Completes asynchronous Android teardown before another test reuses the media notification. */
@UnstableApi
internal object PlaybackTestLifecycle {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    fun launchIntent(): Intent {
        val television = context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION
        val intent = if (television) context.packageManager.getLeanbackLaunchIntentForPackage(context.packageName)
            else context.packageManager.getLaunchIntentForPackage(context.packageName)
        return requireNotNull(intent) { "The app must expose the launcher for the device form factor" }
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun awaitDestroyed(activity: Activity) {
        awaitState("Playback Activity was not destroyed") {
            var destroyed = false
            instrumentation.runOnMainSync { destroyed = activity.isDestroyed }
            destroyed to "finishing=${activity.isFinishing}, destroyed=$destroyed"
        }
    }

    @Suppress("DEPRECATION") // Since Android O this query still exposes the calling app's services.
    fun finishPreviousPlayback() {
        val activities = mutableSetOf<Activity>()
        instrumentation.runOnMainSync {
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            Stage.entries.filter { it != Stage.DESTROYED }.forEach { stage ->
                activities += monitor.getActivitiesInStage(stage).filterIsInstance<MainActivity>()
            }
            activities.forEach { it.finish() }
        }
        activities.forEach(::awaitDestroyed)

        val service = ComponentName(context, PlaybackService::class.java)
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        try {
            if (activityManager.getRunningServices(Int.MAX_VALUE).any { it.service == service }) {
                // A running notification controller can keep a service bound after stopService.
                // First stop its actual player through the same command path as the app/system UI.
                stopExistingPlayer(service)
            }
        } finally {
            context.stopService(Intent().setComponent(service))
        }
        awaitState("Previous playback service or media notification survived teardown") {
            val serviceRunning = activityManager.getRunningServices(Int.MAX_VALUE).any { it.service == service }
            val mediaNotifications = notificationManager.activeNotifications.filter {
                it.notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)
            }
            (!serviceRunning && mediaNotifications.isEmpty()) to
                "serviceRunning=$serviceRunning, mediaNotificationIds=${mediaNotifications.map { it.id }}"
        }
        runBlocking {
            withTimeout(10_000) {
                (context.applicationContext as OttplayApplication).repository.preferences.resumeWriter.awaitIdle()
            }
        }
    }

    private fun stopExistingPlayer(service: ComponentName) {
        var future: ListenableFuture<MediaController>? = null
        try {
            instrumentation.runOnMainSync {
                future = MediaController.Builder(context, SessionToken(context, service)).buildAsync()
            }
            val controller = requireNotNull(future).get(10, TimeUnit.SECONDS)
            instrumentation.runOnMainSync { controller.stop() }
            awaitState("Previous session did not acknowledge Stop") {
                var stopped = false
                var description = "not observed"
                instrumentation.runOnMainSync {
                    stopped = controller.playbackState == Player.STATE_IDLE &&
                        controller.mediaItemCount == 0 && !controller.playWhenReady
                    description = "state=${controller.playbackState}, items=${controller.mediaItemCount}, playWhenReady=${controller.playWhenReady}"
                }
                stopped to description
            }
        } finally {
            instrumentation.runOnMainSync { future?.let(MediaController::releaseFuture) }
        }
    }

    private fun awaitState(message: String, state: () -> Pair<Boolean, String>) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        var lastState = "not observed"
        while (System.nanoTime() < deadline) {
            val (ready, description) = state()
            lastState = description
            if (ready) return
            Thread.sleep(50)
        }
        throw AssertionError("$message; $lastState")
    }
}
