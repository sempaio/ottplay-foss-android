package play.ott.nativeapp

import play.ott.nativeapp.core.MediaEntry

/**
 * Keeps only successfully loaded, non-empty episode lists in memory.
 * Keying by source AND series prevents episodes from leaking into another title.
 * Empty successful responses remove stale data; failed requests do not mutate it.
 */
internal class SeriesEpisodeCache {
    private val lastValid = mutableMapOf<Pair<String, String>, List<MediaEntry>>()

    fun previous(series: MediaEntry): List<MediaEntry> =
        lastValid[series.sourceId to series.id].orEmpty()

    fun accept(series: MediaEntry, episodes: List<MediaEntry>) {
        val key = series.sourceId to series.id
        if (episodes.isEmpty()) lastValid.remove(key) else lastValid[key] = episodes
    }

    fun discardSource(sourceId: String) {
        lastValid.keys.removeAll { it.first == sourceId }
    }

    fun clear() = lastValid.clear()
}
