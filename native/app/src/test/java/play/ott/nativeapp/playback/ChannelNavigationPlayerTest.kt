package play.ott.nativeapp.playback

import android.content.Context
import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import play.ott.nativeapp.core.MediaEntry
import play.ott.nativeapp.core.PlaybackStream

@UnstableApi
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = PlaybackTestApplication::class)
class ChannelNavigationPlayerTest {
    @Test fun `next is advertised through player events and reaches resolver with single decoder item`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val exoPlayer = ExoPlayer.Builder(context).build()
        val requested = mutableListOf<String>()
        val response = CompletableDeferred<PlaybackStream>()
        val wrapper = ChannelNavigationPlayer(
            StopClearsPlaylistPlayer(exoPlayer), this,
            loadChannels = { listOf(MediaEntry("A", "s", "A"), MediaEntry("B", "s", "B")) },
            resolve = { entry -> requested.add(entry.id); response.await() },
            onFailure = { throw AssertionError("Unexpected channel resolution failure") },
        )
        var advertised = false
        wrapper.addListener(object : Player.Listener {
            override fun onAvailableCommandsChanged(availableCommands: Player.Commands) {
                if (availableCommands.contains(Player.COMMAND_SEEK_TO_NEXT)) advertised = true
            }
        })
        try {
            wrapper.setMediaItem(PlaybackItems.build("A", "A", "https://test.invalid/a.m3u8", isLive = true))
            shadowOf(Looper.getMainLooper()).idle()
            runCurrent()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, exoPlayer.mediaItemCount)
            assertTrue(wrapper.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT))
            assertTrue("MediaSession needs an available-commands event", advertised)
            wrapper.seekToNext()
            runCurrent()
            assertEquals(listOf("B"), requested)
            wrapper.stop()
            runCurrent()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(0, exoPlayer.mediaItemCount)
            assertFalse(wrapper.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT))
        } finally {
            wrapper.release()
        }
    }
}
