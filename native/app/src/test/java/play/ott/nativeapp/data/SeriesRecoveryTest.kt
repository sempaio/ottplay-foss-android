package play.ott.nativeapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import play.ott.nativeapp.core.MediaEntry
import play.ott.nativeapp.core.MediaKind

class SeriesRecoveryTest {
    private fun series(url: String) = MediaEntry(
        id = "series-1", sourceId = "list", name = "Série Brasileira",
        kind = MediaKind.SERIES, url = url)

    @Test fun onlyOwnPlayableUrlAllowsSeriesFallback() {
        val original = series("https://example.org/episodes/season.mp4")
        val direct = SeriesRecovery.directPlayCandidate(original, emptyList())
        assertEquals(MediaKind.EPISODE, direct?.kind)
        assertEquals(original.url, direct?.url)
        assertEquals(original.id, original.id)
        assertNull(SeriesRecovery.directPlayCandidate(series(""), emptyList()))
        assertNull(SeriesRecovery.directPlayCandidate(series("invalid"), emptyList()))
    }

    @Test fun onlyXtreamSeriesCanRequestXtreamEpisodes() {
        assertEquals(true, SeriesRecovery.needsEpisodeLookup(
            play.ott.nativeapp.core.SourceKind.XTREAM, series("")))
        assertEquals(false, SeriesRecovery.needsEpisodeLookup(
            play.ott.nativeapp.core.SourceKind.M3U, series("https://example.org/a.mp4")))
        assertEquals(false, SeriesRecovery.needsEpisodeLookup(
            play.ott.nativeapp.core.SourceKind.XTREAM,
            series("").copy(kind = MediaKind.LIVE)))
    }

    @Test fun validEpisodesArePreferredOverFallback() {
        val original = series("https://example.org/film.mp4")
        val episode = original.copy(id = "ep-1", kind = MediaKind.EPISODE)
        assertNull(SeriesRecovery.directPlayCandidate(original, listOf(episode)))
        assertNull(SeriesRecovery.directPlayCandidate(original.copy(kind = MediaKind.MOVIE), emptyList()))
    }
}
