package play.ott.nativeapp.data

import java.net.URI
import play.ott.nativeapp.core.MediaEntry
import play.ott.nativeapp.core.MediaKind
import play.ott.nativeapp.core.SourceKind

/** Never invent episodes or turn a series into a live TV channel. */
object SeriesRecovery {
    fun needsEpisodeLookup(sourceKind: SourceKind, series: MediaEntry): Boolean =
        sourceKind == SourceKind.XTREAM && series.kind == MediaKind.SERIES

    fun directPlayCandidate(series: MediaEntry, episodes: List<MediaEntry>): MediaEntry? {
        if (series.kind != MediaKind.SERIES || episodes.isNotEmpty()) return null
        val uri = runCatching { URI(series.url) }.getOrNull() ?: return null
        if (uri.scheme !in listOf("http", "https") || uri.host.isNullOrBlank()) return null
        // Only an actual media URL offered by the source; no guessed Xtream episode path.
        return series.copy(kind = MediaKind.EPISODE)
    }
}
