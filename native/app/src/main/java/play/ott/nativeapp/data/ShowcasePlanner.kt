package play.ott.nativeapp.data

import play.ott.nativeapp.core.MediaEntry
import play.ott.nativeapp.core.MediaKind
import play.ott.nativeapp.core.XtreamGroup

/** One real category on the STARLINK home screen, strictly mirroring the upstream list. */
data class ShowcaseRow(
    val title: String,
    val kind: MediaKind,
    val items: List<MediaEntry>,
)

/**
 * Raw upstream category listing and batching without any client-side reclassification.
 */
object ShowcasePlanner {
    fun categories(
        entries: List<MediaEntry>,
        kind: MediaKind,
        remoteGroups: List<XtreamGroup> = emptyList(),
    ): List<String> {
        val remoteCategories = remoteGroups.filter { it.kind == kind }
            .map { it.name.trim() }.filter { it.isNotEmpty() }.distinct()
        if (remoteCategories.isNotEmpty()) return remoteCategories
        val counts = HashMap<String, Int>()
        entries.forEach { entry ->
            if (entry.kind == kind) {
                val group = entry.group.trim()
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
        preferSourceGroup: Boolean = false,
        consolidateSourceGroups: Boolean = false,
        availableLiveLabels: Set<String> = emptySet(),
    ): List<ShowcaseRow> {
        if (maxRows <= 0 || batchSize <= 0) return emptyList()
        val grouped = LinkedHashMap<String, MutableList<MediaEntry>>()
        entries.forEach { entry ->
            if (entry.kind == kind) {
                val group = entry.group.trim()
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
