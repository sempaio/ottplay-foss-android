package play.ott.nativeapp.playback

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import play.ott.nativeapp.core.MediaEntry
import play.ott.nativeapp.core.MediaKind
import play.ott.nativeapp.core.PlaybackStream

@OptIn(ExperimentalCoroutinesApi::class)
class ChannelNavigatorTest {
    @Test fun `pausing while catalog loads does not permanently disable channel navigation`() = runTest {
        val catalog = CompletableDeferred<List<MediaEntry>>()
        val navigator = ChannelNavigator(this, { "A" }, { catalog.await() },
            resolve = { PlaybackStream("https://test.invalid/channel") }, commit = { _, _ -> }, changed = {}, failed = {})
        navigator.currentItemChanged("A"); runCurrent()
        navigator.cancelSwitch()
        catalog.complete(listOf(MediaEntry("A", "s", "A"), MediaEntry("B", "s", "B")))
        runCurrent()
        assertTrue(navigator.available)
    }

    @Test fun `late catalog for old item cannot replace new source queue`() = runTest {
        var current = "A"
        val catalogs = mapOf("A" to CompletableDeferred<List<MediaEntry>>(), "D" to CompletableDeferred())
        val selected = mutableListOf<String>()
        val navigator = ChannelNavigator(this, { current },
            loadChannels = { id -> withContext(NonCancellable) { catalogs.getValue(id).await() } },
            resolve = { PlaybackStream("https://test.invalid/channel") },
            commit = { entry, _ -> selected.add(entry.id) }, changed = {}, failed = {})
        navigator.currentItemChanged("A"); runCurrent()
        current = "D"
        navigator.currentItemChanged("D"); runCurrent()
        catalogs.getValue("D").complete(listOf(MediaEntry("D", "s2", "D"), MediaEntry("E", "s2", "E")))
        runCurrent()
        catalogs.getValue("A").complete(listOf(MediaEntry("A", "s1", "A"), MediaEntry("B", "s1", "B")))
        runCurrent()
        navigator.step(1); runCurrent()
        assertEquals(listOf("E"), selected)
    }

    @Test fun `rapid next presses resolve B then C and only newest C may play`() = runTest {
        val fixture = Fixture(this)
        fixture.navigator.currentItemChanged("A")
        runCurrent()
        assertTrue(fixture.navigator.available)
        fixture.navigator.step(1); runCurrent()
        fixture.navigator.step(1); runCurrent()
        assertEquals(listOf("B", "C"), fixture.requested)
        fixture.complete("C"); runCurrent()
        fixture.complete("B"); runCurrent()
        assertEquals(listOf("C"), fixture.committed)
        assertEquals("C", fixture.current)
    }

    @Test fun `pause or stop invalidates a late noncooperative provider response`() = runTest {
        val fixture = Fixture(this)
        fixture.navigator.currentItemChanged("A"); runCurrent()
        fixture.navigator.step(1); runCurrent()
        fixture.navigator.cancelSwitch()
        fixture.complete("B"); runCurrent()
        assertTrue(fixture.committed.isEmpty())
        assertEquals("A", fixture.current)
    }

    @Test fun `direct item replacement cannot be overwritten by an old next request`() = runTest {
        val fixture = Fixture(this)
        fixture.navigator.currentItemChanged("A"); runCurrent()
        fixture.navigator.step(1); runCurrent()
        fixture.current = "movie"
        fixture.navigator.currentItemChanged("movie"); runCurrent()
        fixture.complete("B"); runCurrent()
        assertFalse(fixture.navigator.available)
        assertTrue(fixture.committed.isEmpty())
        assertEquals("movie", fixture.current)
    }

    @Test fun `previous wraps within live channels and skips VOD entries`() = runTest {
        val fixture = Fixture(this)
        fixture.navigator.currentItemChanged("A"); runCurrent()
        fixture.navigator.step(-1); runCurrent()
        assertEquals(listOf("C"), fixture.requested)
        fixture.complete("C"); runCurrent()
        assertEquals(listOf("C"), fixture.committed)
    }

    @Test fun `failed switch keeps current playback and retry starts from current channel`() = runTest {
        val fixture = Fixture(this)
        fixture.navigator.currentItemChanged("A"); runCurrent()
        fixture.navigator.step(1); runCurrent()
        fixture.responses.getValue("B").completeExceptionally(IllegalStateException("credential-bearing internal error"))
        runCurrent()
        assertEquals(1, fixture.failures)
        assertEquals("A", fixture.current)
        fixture.responses.remove("B")
        fixture.navigator.step(1); runCurrent()
        assertEquals(listOf("B", "B"), fixture.requested)
        fixture.complete("B"); runCurrent()
        assertEquals(listOf("B"), fixture.committed)
    }

    private class Fixture(scope: CoroutineScope) {
        var current: String? = "A"
        val requested = mutableListOf<String>()
        val committed = mutableListOf<String>()
        val responses = mutableMapOf<String, CompletableDeferred<PlaybackStream>>()
        var failures = 0
        private val entries = listOf(
            MediaEntry("A", "source", "A"),
            MediaEntry("movie", "source", "Movie", kind = MediaKind.MOVIE),
            MediaEntry("B", "source", "B"),
            MediaEntry("C", "source", "C"),
        )
        val navigator: ChannelNavigator = ChannelNavigator(
            scope, { current }, { entries },
            resolve = { entry ->
                requested.add(entry.id)
                withContext(NonCancellable) { responses.getOrPut(entry.id) { CompletableDeferred() }.await() }
            },
            commit = { entry, _ ->
                committed.add(entry.id)
                current = entry.id
                itemChanged(entry.id)
            },
            changed = {},
            failed = { ++failures },
        )
        private fun itemChanged(id: String) { navigator.currentItemChanged(id) }
        fun complete(id: String) { responses.getValue(id).complete(PlaybackStream("https://test.invalid/$id")) }
    }
}
