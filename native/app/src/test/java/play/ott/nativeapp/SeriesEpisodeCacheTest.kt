package play.ott.nativeapp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import play.ott.nativeapp.core.MediaEntry
import play.ott.nativeapp.core.MediaKind

class SeriesEpisodeCacheTest {
    private fun series(source: String, id: String) =
        MediaEntry(id, source, id, kind = MediaKind.SERIES, providerId = id)

    @Test fun preservesLastValidListOnFailedRetryWithoutMixingSeries() {
        val cache = SeriesEpisodeCache()
        val a = series("source-1", "6017")
        val b = series("source-1", "6019")
        val otherAccount = series("source-2", "6017")
        val episode = MediaEntry("ep-1", "source-1", "Episode 1", kind = MediaKind.EPISODE)
        cache.accept(a, listOf(episode))

        // A failed retry makes no accept() call. The UI can still show the last list.
        assertEquals(listOf(episode), cache.previous(a))
        assertTrue(cache.previous(b).isEmpty())
        assertTrue(cache.previous(otherAccount).isEmpty())

        // A successful empty response is not a failure and invalidates stale data.
        cache.accept(a, emptyList())
        assertTrue(cache.previous(a).isEmpty())
    }

    @Test fun discardsPriorAccountDataOnLogoutOrSourceChange() {
        val cache = SeriesEpisodeCache()
        val a = series("source-1", "6017")
        val b = series("source-2", "6017")
        cache.accept(a, listOf(MediaEntry("one", a.sourceId, "One")))
        cache.accept(b, listOf(MediaEntry("two", b.sourceId, "Two")))

        cache.discardSource(a.sourceId)
        assertTrue(cache.previous(a).isEmpty())
        assertEquals("two", cache.previous(b).single().id)
        cache.clear()
        assertTrue(cache.previous(b).isEmpty())
    }
}
