package play.ott.nativeapp.playback

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LivePlaybackRecoveryPolicyJUnitTest {
    private fun LivePlaybackRecoveryPolicy.checkAt(
        millis: Long, state: LiveRecoveryState, live: Boolean = true,
        playingIntent: Boolean = true, moving: Boolean = false,
        position: Long = 0L, ended: Boolean = false,
        retryableError: Boolean = false, id: String = "channel-a",
    ) = evaluate(millis, id, live, playingIntent, ended, state, moving, position, retryableError)

    @Test fun prolongedBufferingReconnectsAfterGracePeriod() {
        val policy = LivePlaybackRecoveryPolicy()
        assertFalse(policy.checkAt(0, LiveRecoveryState.BUFFERING))
        assertFalse(policy.checkAt(6_999, LiveRecoveryState.BUFFERING))
        assertTrue(policy.checkAt(7_000, LiveRecoveryState.BUFFERING))
    }

    @Test fun frozenReadyFrameReconnectsQuickly() {
        val policy = LivePlaybackRecoveryPolicy()
        assertFalse(policy.checkAt(0, LiveRecoveryState.READY, moving = true, position = 1_000))
        assertFalse(policy.checkAt(8_999, LiveRecoveryState.READY, moving = true, position = 1_000))
        assertTrue(policy.checkAt(9_000, LiveRecoveryState.READY, moving = true, position = 1_000))
    }

    @Test fun pausingManuallyDisablesRecovery() {
        val policy = LivePlaybackRecoveryPolicy()
        policy.checkAt(0, LiveRecoveryState.BUFFERING)
        assertFalse(policy.checkAt(20_000, LiveRecoveryState.BUFFERING, playingIntent = false))
        assertFalse(policy.checkAt(20_001, LiveRecoveryState.BUFFERING))
    }

    @Test fun onlyLiveStreamsAutoReconnect() {
        val policy = LivePlaybackRecoveryPolicy()
        assertFalse(policy.checkAt(0, LiveRecoveryState.BUFFERING, live = false))
        assertFalse(policy.checkAt(120_000, LiveRecoveryState.BUFFERING, live = false))
    }

    @Test fun interruptedLiveEndReconnects() {
        val policy = LivePlaybackRecoveryPolicy()
        policy.checkAt(0, LiveRecoveryState.ENDED, playingIntent = false, ended = true)
        assertTrue(policy.checkAt(1_000, LiveRecoveryState.ENDED, playingIntent = false, ended = true))
    }

    @Test fun manualStopDoesNotRestart() {
        val policy = LivePlaybackRecoveryPolicy()
        policy.checkAt(0, LiveRecoveryState.ENDED, playingIntent = false)
        assertFalse(policy.checkAt(30_000, LiveRecoveryState.ENDED, playingIntent = false))
    }

    @Test fun onlyTransientErrorCanReconnectFromIdle() {
        val policy = LivePlaybackRecoveryPolicy()
        policy.checkAt(0, LiveRecoveryState.IDLE, retryableError = false)
        assertFalse(policy.checkAt(90_000, LiveRecoveryState.IDLE, retryableError = false))
        policy.checkAt(90_001, LiveRecoveryState.IDLE, retryableError = true)
        assertTrue(policy.checkAt(91_001, LiveRecoveryState.IDLE, retryableError = true))
    }

    @Test fun switchingChannelsResetsTheStallClock() {
        val policy = LivePlaybackRecoveryPolicy()
        policy.checkAt(0, LiveRecoveryState.BUFFERING)
        assertFalse(policy.checkAt(6_999, LiveRecoveryState.BUFFERING, id = "channel-b"))
        assertFalse(policy.checkAt(7_000, LiveRecoveryState.BUFFERING, id = "channel-b"))
        assertTrue(policy.checkAt(13_999, LiveRecoveryState.BUFFERING, id = "channel-b"))
    }

    @Test fun repeatedFailuresBackoffWithoutHammeringSource() {
        val policy = LivePlaybackRecoveryPolicy()
        policy.checkAt(0, LiveRecoveryState.ENDED)
        assertTrue(policy.checkAt(1_000, LiveRecoveryState.ENDED))
        assertFalse(policy.checkAt(1_500, LiveRecoveryState.ENDED))
        assertTrue(policy.checkAt(2_500, LiveRecoveryState.ENDED))
        assertFalse(policy.checkAt(4_000, LiveRecoveryState.ENDED))
        assertTrue(policy.checkAt(5_500, LiveRecoveryState.ENDED))
        assertFalse(policy.checkAt(10_000, LiveRecoveryState.ENDED))
        assertTrue(policy.checkAt(11_500, LiveRecoveryState.ENDED))
        assertFalse(policy.checkAt(20_000, LiveRecoveryState.ENDED))
        assertTrue(policy.checkAt(26_500, LiveRecoveryState.ENDED))
    }

    @Test fun sustainedPlaybackResetsRetryBudget() {
        val policy = LivePlaybackRecoveryPolicy()
        policy.checkAt(0, LiveRecoveryState.ENDED)
        assertTrue(policy.checkAt(1_000, LiveRecoveryState.ENDED))
        policy.checkAt(2_000, LiveRecoveryState.READY, moving = true, position = 1_000)
        policy.checkAt(4_000, LiveRecoveryState.READY, moving = true, position = 3_000)
        policy.checkAt(11_000, LiveRecoveryState.READY, moving = true, position = 10_000)
        policy.checkAt(12_000, LiveRecoveryState.ENDED)
        assertTrue(policy.checkAt(13_000, LiveRecoveryState.ENDED))
    }
}
