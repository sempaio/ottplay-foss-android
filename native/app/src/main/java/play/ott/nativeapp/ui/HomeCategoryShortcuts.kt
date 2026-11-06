package play.ott.nativeapp.ui

import play.ott.nativeapp.core.MediaEntry
import play.ott.nativeapp.core.MediaKind
import play.ott.nativeapp.data.CatalogDisplayGroup

/**
 * Presentation-only navigation. Only real, distinct categories are shown.
 * Stream IDs, original provider groups, playback URLs and the local catalog stay unchanged.
 */
internal object HomeCategoryShortcuts {
    fun select(
        rails: List<Pair<MediaKind, String>>,
        limit: Int = 48,
    ): List<Pair<MediaKind, String>> =
        if (limit <= 0) emptyList()
        else rails.asSequence()
            .map { (kind, group) -> kind to group.trim() }
            .filter { it.second.isNotBlank() }
            .distinct()
            .take(limit)
            .toList()

    /** Scan a catalog once, without producing a second list of all its items. */
    fun fromEntries(entries: List<MediaEntry>, maxPerKind: Int = 128): List<Pair<MediaKind, String>> {
        if (maxPerKind <= 0) return emptyList()
        val order = listOf(MediaKind.MOVIE, MediaKind.LIVE, MediaKind.SERIES)
        val names = order.associateWith { LinkedHashSet<String>() }
        for (entry in entries) {
            val groups = names[entry.kind] ?: continue
            if (groups.size >= maxPerKind) {
                if (names.values.all { it.size >= maxPerKind }) break
                continue
            }
            val group = CatalogDisplayGroup.group(entry).trim()
            if (group.isNotEmpty()) groups.add(group)
        }
        return order.flatMap { kind -> names.getValue(kind).map { kind to it } }
    }

    /** Only a few cards for the chosen category are prepared for the home screen. */
    fun preview(
        entries: List<MediaEntry>,
        category: Pair<MediaKind, String>?,
        limit: Int = 80,
    ): List<MediaEntry> {
        if (category == null || limit <= 0) return emptyList()
        val ids = HashSet<String>()
        return entries.asSequence()
            .filter { it.kind == category.first && CatalogDisplayGroup.group(it) == category.second }
            .filter { ids.add(it.sourceId + ":" + it.id) }
            .take(limit)
            .toList()
    }
}
