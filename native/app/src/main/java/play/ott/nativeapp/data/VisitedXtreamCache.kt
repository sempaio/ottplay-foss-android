package play.ott.nativeapp.data

import play.ott.nativeapp.core.MediaEntry

/**
 * Bounded LRU memory cache for visited Xtream categories.
 * Keeps at least one category per MediaKind when possible so browsing large
 * movie groups does not evict already-loaded series or live categories.
 */
internal object VisitedXtreamCache {
    const val MAX_GROUPS = 16
    const val MAX_ENTRIES = 35_000

    fun touch(
        visited: LinkedHashMap<String, List<MediaEntry>>,
        key: String,
        entries: List<MediaEntry>? = null,
    ) {
        val existing = visited.remove(key)
        val value = entries ?: existing ?: return
        visited[key] = value
    }

    fun prune(
        visited: LinkedHashMap<String, List<MediaEntry>>,
        protectedKey: String? = null,
        maxGroups: Int = MAX_GROUPS,
        maxEntries: Int = MAX_ENTRIES,
    ) {
        while (visited.size > maxGroups ||
            (visited.size > 1 && visited.values.sumOf { it.size } > maxEntries)
        ) {
            val kindCounts = HashMap<String, Int>()
            for (k in visited.keys) {
                val kind = k.split(':').getOrNull(1).orEmpty()
                kindCounts[kind] = (kindCounts[kind] ?: 0) + 1
            }
            val victim = visited.keys.firstOrNull { k ->
                k != protectedKey && (kindCounts[k.split(':').getOrNull(1).orEmpty()] ?: 0) > 1
            } ?: visited.keys.firstOrNull { k -> k != protectedKey }
                ?: break
            visited.remove(victim)
        }
    }
}
