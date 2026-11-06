package play.ott.nativeapp.data

import play.ott.nativeapp.core.MediaEntry
import play.ott.nativeapp.core.MediaKind

/** One real category on the STARLINK home screen, never synthetic catalogue entries. */
data class ShowcaseRow(
    val title: String,
    val kind: MediaKind,
    val items: List<MediaEntry>,
)

/**
 * Presentation-only grouping and repeatable browsing batches.
 *
 * Does not change stream IDs, provider URLs, downloaded metadata, playback, EPG,
 * favorites, or the saved catalog. A group only exists if the source catalog
 * actually contains entries that resolve to that group.
 */
object ShowcasePlanner {
    fun categories(entries: List<MediaEntry>, kind: MediaKind): List<String> {
        val counts = HashMap<String, Int>()
        val liveGroups = entries.asSequence().filter { it.kind == MediaKind.LIVE }
            .map { it.group.trim() }.filter { it.isNotEmpty() }.toSet()
        entries.forEach { entry ->
            if (entry.kind == kind) {
                val group = CatalogDisplayGroup.group(entry, liveGroups)
                if (group.isNotBlank()) counts[group] = (counts[group] ?: 0) + 1
            }
        }
        return counts.entries.sortedWith(
            compareByDescending<Map.Entry<String, Int>> { it.value }
                .thenBy { it.key.lowercase() }
        ).map { it.key }
    }

    fun rows(
        entries: List<MediaEntry>,
        kind: MediaKind,
        selectedCategory: String = "",
        batch: Int = 0,
        maxRows: Int = 6,
        batchSize: Int = 12,
    ): List<ShowcaseRow> {
        if (maxRows <= 0 || batchSize <= 0) return emptyList()
        val grouped = LinkedHashMap<String, MutableList<MediaEntry>>()
        val liveGroups = entries.asSequence().filter { it.kind == MediaKind.LIVE }
            .map { it.group.trim() }.filter { it.isNotEmpty() }.toSet()
        entries.forEach { entry ->
            if (entry.kind == kind) {
                val group = CatalogDisplayGroup.group(entry, liveGroups)
                if (group.isNotBlank() && (selectedCategory.isBlank() || selectedCategory == group)) {
                    grouped.getOrPut(group) { ArrayList() }.add(entry)
                }
            }
        }
        return grouped.entries
            .sortedWith(compareByDescending<Map.Entry<String, MutableList<MediaEntry>>> { it.value.size }
                .thenBy { it.key.lowercase() })
            .take(maxRows)
            .map { (group, groupEntries) ->
                val count = minOf(batchSize, groupEntries.size)
                val index = Math.floorMod(batch.toLong() * batchSize, groupEntries.size.toLong()).toInt()
                ShowcaseRow(
                    title = group,
                    kind = kind,
                    items = List(count) { offset -> groupEntries[(index + offset) % groupEntries.size] },
                )
            }
    }
}
