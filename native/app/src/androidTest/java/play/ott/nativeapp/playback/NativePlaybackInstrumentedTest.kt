package play.ott.nativeapp.playback

import android.app.Activity
import android.content.ComponentName
import android.content.res.Configuration
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import play.ott.nativeapp.R
import play.ott.nativeapp.OttplayApplication

/** Exercises real Media3 service, IPC, decoder and surface using a bundled synthetic video. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class NativePlaybackInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val isTv get() = context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION

    @Test fun sessionSurvivesControllerReleaseAndRestoresPlaybackUntilExplicitStop() {
        PlaybackTestLifecycle.finishPreviousPlayback()
        val preferences = (context.applicationContext as OttplayApplication).repository.preferences
        val previousPreferences = runBlocking { preferences.data.first() }
        val launch = PlaybackTestLifecycle.launchIntent()
        var activity = instrumentation.startActivitySync(launch)
        var first: MediaController? = null
        var second: MediaController? = null
        var surface: SurfaceView? = null
        try {
            first = connect()
            surface = attachSurface(activity)
            val original = requireNotNull(first)
            onMain {
                original.setVideoSurfaceHolder(requireNotNull(surface).holder)
                original.repeatMode = Player.REPEAT_MODE_ONE
                original.setMediaItem(PlaybackItems.build(
                    id = "instrumentation-synthetic-demo",
                    title = "Offline decoder test",
                    url = "android.resource://${context.packageName}/${R.raw.demo}",
                ))
                original.prepare()
                original.play()
            }
            awaitPlayer("Local video did not decode", original) {
                it.isPlaying && it.currentPosition > 100 && it.videoSize.width > 0 && it.playerError == null
            }
            // Activity.finish applies the TV pause policy; controller release itself leaves the session intact.
            onMain { original.clearVideoSurface(); original.release() }
            first = null
            onMain { activity.finish() }
            PlaybackTestLifecycle.awaitDestroyed(activity)

            // A fresh controller attaches to the existing decoder/session, without setMediaItem.
            second = connect()
            val replacement = requireNotNull(second)
            awaitPlayer("Controller reconnection lost the session or its platform playback state", replacement) {
                it.currentMediaItem?.mediaId == "instrumentation-synthetic-demo" &&
                    (if (isTv) !it.playWhenReady else it.isPlaying)
            }
            if (isTv) {
                // No Activity or Activity player listener remains. The service must reject hidden Play itself.
                onMain { replacement.play() }
                awaitPlayer("A new controller must not restart TV video after Activity destruction", replacement) {
                    !it.playWhenReady && !it.isPlaying && it.currentMediaItem?.mediaId == "instrumentation-synthetic-demo"
                }
            }
            // There is no Activity or ViewModel now. The service must persist this seek/pause.
            onMain { replacement.pause(); replacement.seekTo(2_000) }
            awaitPlayer("Background seek did not settle", replacement) {
                !it.playWhenReady && it.currentPosition in 1_900L..2_100L
            }
            runBlocking {
                withTimeout(10_000) {
                    preferences.data.first { it.resumePositions["instrumentation-synthetic-demo"] == 2_000L }
                }
            }
            activity = instrumentation.startActivitySync(launch)
            surface = attachSurface(activity)
            onMain {
                replacement.setVideoSurfaceHolder(requireNotNull(surface).holder)
                replacement.pause()
            }
            awaitPlayer("Pause discarded or failed to pause the playlist", replacement) {
                !it.playWhenReady && it.mediaItemCount == 1
            }
            onMain { replacement.play() }
            awaitPlayer("Paused session could not resume", replacement) { it.isPlaying }
            onMain { replacement.stop() }
            awaitPlayer("Stop must clear the playlist and playback intent", replacement) {
                it.mediaItemCount == 0 && !it.playWhenReady && it.playbackState == Player.STATE_IDLE
            }
            onMain { assertEquals(null, replacement.currentMediaItem) }
        } finally {
            onMain {
                first?.run { stop(); release() }
                second?.run { stop(); release() }
                activity.finish()
            }
            PlaybackTestLifecycle.finishPreviousPlayback()
            runBlocking { preferences.update { previousPreferences } }
        }
    }

    private fun attachSurface(activity: Activity): SurfaceView {
        val ready = CountDownLatch(1)
        lateinit var surface: SurfaceView
        onMain {
            surface = SurfaceView(activity).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) { ready.countDown() }
                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
                    override fun surfaceDestroyed(holder: SurfaceHolder) {}
                })
            }
            activity.addContentView(surface, FrameLayout.LayoutParams(320, 180))
        }
        assertTrue("Playback test surface was not created", ready.await(10, TimeUnit.SECONDS))
        return surface
    }

    private fun connect(): MediaController {
        lateinit var future: ListenableFuture<MediaController>
        onMain {
            future = MediaController.Builder(context,
                SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()
        }
        return future.get(10, TimeUnit.SECONDS)
    }

    private fun awaitPlayer(message: String, player: MediaController, condition: (MediaController) -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12)
        var lastState = "not observed"
        while (System.nanoTime() < deadline) {
            var satisfied = false
            var failureCode: Int? = null
            onMain {
                satisfied = condition(player)
                failureCode = player.playerError?.errorCode
                lastState = "connected=${player.isConnected}, state=${player.playbackState}, " +
                    "playWhenReady=${player.playWhenReady}, items=${player.mediaItemCount}, " +
                    "mediaId=${player.currentMediaItem?.mediaId}, position=${player.currentPosition}, " +
                    "repeat=${player.repeatMode}, error=$failureCode"
            }
            assertTrue("$message; $lastState", failureCode == null)
            if (satisfied) return
            Thread.sleep(50)
        }
        throw AssertionError("$message; $lastState")
    }

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)
}
