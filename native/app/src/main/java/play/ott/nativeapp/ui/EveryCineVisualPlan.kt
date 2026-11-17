package play.ott.nativeapp.ui

import play.ott.nativeapp.core.MediaEntry
import play.ott.nativeapp.core.MediaKind
import play.ott.nativeapp.data.RecentPlayback

/** Every Cine has four persistent bottom pages; games are deliberately out of scope. */
internal enum class EveryCinePage { HOME, LIVE, CATALOG, PROFILE }

internal data class EveryCineSection(val title: String, val entries: List<MediaEntry>)

/**
 * Layout contract derived from fragment_vod_page.xml, layout_vod_section.xml,
 * item_vod_movie.xml and main_navigation.xml of the user's Every Cine reference.
 * Uses only STARLINK's own MediaEntry metadata and M81 streams.
 */
internal fun everyCineSections(
    entries: List<MediaEntry>,
    recent: List<RecentPlayback>,
    favoriteIds: Set<String>,
): List<EveryCineSection> {
    val sections = ArrayList<EveryCineSection>(16)
    val resumable = recent.asSequence().filter { it.positionMs > 0 }
        .map { it.entry }.distinctBy { it.sourceId to it.id }.take(20).toList()
    if (resumable.isNotEmpty()) sections += EveryCineSection("Continuar assistindo", resumable)
    val favorite = entries.asSequence().filter { it.id in favoriteIds }.take(20).toList()
    if (favorite.isNotEmpty()) sections += EveryCineSection("Minha lista", favorite)
    listOf(
        MediaKind.MOVIE to "Filmes",
        MediaKind.SERIES to "Séries",
        MediaKind.LIVE to "TV ao vivo",
    ).forEach { (kind, label) ->
        val selected = entries.asSequence().filter { it.kind == kind }.take(20).toList()
        if (selected.isNotEmpty()) sections += EveryCineSection(label, selected)
    }
    // Keep the number of rails bounded; never allocate an unlimited category feed.
    val seen = HashSet<String>()
    entries.asSequence()
        .filter { it.kind == MediaKind.MOVIE || it.kind == MediaKind.SERIES }
        .map { it.group.trim() }.filter { it.isNotEmpty() && seen.add(it) }
        .take(8).forEach { category ->
            val selected = entries.asSequence()
                .filter { (it.kind == MediaKind.MOVIE || it.kind == MediaKind.SERIES) && it.group.trim() == category }
                .take(20).toList()
            if (selected.isNotEmpty()) sections += EveryCineSection(category, selected)
        }
    return sections
}

/** Original home uses a banner pager, with only real catalog titles as pages. */
internal fun everyCineBannerEntries(entries: List<MediaEntry>, recent: List<RecentPlayback>): List<MediaEntry> =
    (entries.asSequence().filter { it.kind == MediaKind.MOVIE || it.kind == MediaKind.SERIES } +
        recent.asSequence().map { it.entry }.filter { it.kind == MediaKind.MOVIE || it.kind == MediaKind.SERIES })
        .distinctBy { it.sourceId to it.id }.take(5).toList()

internal fun everyCineFeatured(entries: List<MediaEntry>, recent: List<RecentPlayback>): MediaEntry? =
    entries.firstOrNull { it.kind == MediaKind.MOVIE || it.kind == MediaKind.SERIES }
        ?: recent.firstOrNull { it.entry.kind == MediaKind.MOVIE || it.entry.kind == MediaKind.SERIES }?.entry
        ?: entries.firstOrNull()

/** Remote category labels can merge several original Xtream category names. */
internal fun everyCineVisibleRemote(
    entries: List<MediaEntry>,
    kind: MediaKind,
    originalGroups: Set<String>,
    query: String,
): List<MediaEntry> {
    val allowed = originalGroups.map { it.trim().lowercase() }.toSet()
    return entries.filter { entry ->
        entry.kind == kind &&
            (allowed.isEmpty() || entry.group.trim().lowercase() in allowed) &&
            (query.isBlank() || entry.name.contains(query, ignoreCase = true) ||
                entry.group.contains(query, ignoreCase = true))
    }
}

internal fun everyCineVisible(
    entries: List<MediaEntry>,
    kind: MediaKind,
    category: String,
    query: String,
): List<MediaEntry> = entries.filter {
    it.kind == kind &&
        (category.isBlank() || it.group.equals(category, ignoreCase = true)) &&
        (query.isBlank() || it.name.contains(query, ignoreCase = true) || it.group.contains(query, ignoreCase = true))
}
