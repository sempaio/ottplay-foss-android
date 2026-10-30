package play.ott.nativeapp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackActivityVisibilityTest {
    private val states = mutableListOf<Boolean>()
    private val expirations = mutableListOf<() -> Unit>()
    private val tracker = PlaybackActivityVisibility(states::add) { expire ->
        expirations += expire
        // Keep callbacks available to prove that a late cancelled callback is harmless.
        val cancel: () -> Unit = {}
        cancel
    }

    @Test fun `locale recreation preserves playback but Home on its replacement hides immediately`() {
        val old = Any(); val replacement = Any()
        tracker.started(old)
        tracker.stopped(old, true)
        tracker.stopped(old, false) // onDestroy follows onStop.
        assertTrue(states.all { it })
        tracker.started(replacement)
        expirations.single().invoke()
        assertTrue(states.all { it })
        tracker.stopped(replacement, false)
        assertFalse(states.last())
    }

    @Test fun `failed recreation cannot retain visible playback indefinitely`() {
        val old = Any()
        tracker.started(old); tracker.stopped(old, true)
        expirations.single().invoke()
        assertFalse(states.last())
    }

    @Test fun `ordinary Home and last Activity destruction hide without a delay`() {
        val first = Any(); val second = Any()
        tracker.started(first); tracker.started(second)
        tracker.stopped(first, false)
        assertTrue(states.last())
        tracker.stopped(second, false)
        assertFalse(states.last())
        assertEquals(0, expirations.size)
    }
}
