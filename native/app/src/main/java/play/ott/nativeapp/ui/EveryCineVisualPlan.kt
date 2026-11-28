package play.ott.nativeapp.ui

import play.ott.nativeapp.core.ArtworkUrls
import play.ott.nativeapp.core.MediaEntry
import play.ott.nativeapp.core.MediaKind
import play.ott.nativeapp.core.XtreamGroup
import play.ott.nativeapp.data.RecentPlayback

/** Five persistent bottom pages: Home, Live, Movies, Series, Profile. */
internal enum class EveryCinePage { HOME, LIVE, MOVIES, SERIES, PROFILE }

/** Restores saved tab names safely across upgrades (legacy CATALOG maps to MOVIES). */
internal fun parseEveryCinePage(name: String?): EveryCinePage = when (name) {
    "CATALOG" -> EveryCinePage.MOVIES
    else -> EveryCinePage.entries.firstOrNull { it.name == name } ?: EveryCinePage.HOME
}

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
    // Keep the number of rails bounded; never allocate an unlimited category feed,
    // and never emit a category rail whose title collides with a built-in section
    // (e.g. an upstream group literally named "Filmes" or "Séries"), which would
    // duplicate LazyColumn keys and crash Home.
    val seen = sections.mapTo(HashSet()) { it.title.lowercase() }
    entries.asSequence()
        .filter { it.kind == MediaKind.MOVIE || it.kind == MediaKind.SERIES }
        .map { it.group.trim() }.filter { it.isNotEmpty() && seen.add(it.lowercase()) }
        .take(8).forEach { category ->
            val selected = entries.asSequence()
                .filter {
                    (it.kind == MediaKind.MOVIE || it.kind == MediaKind.SERIES) &&
                        it.group.trim().equals(category, ignoreCase = true)
                }
                .take(20).toList()
            if (selected.isNotEmpty()) sections += EveryCineSection(category, selected)
        }
    return sections
}

/**
 * Builds Home-style horizontal poster rails exclusively for a dedicated VOD tab
 * (Movies or Series), ordering category rails by the upstream Xtream category order.
 */
internal fun everyCineKindSections(
    entries: List<MediaEntry>,
    recent: List<RecentPlayback>,
    favoriteIds: Set<String>,
    kind: MediaKind,
    preferredCategoryOrder: List<String> = emptyList(),
    maxCategoryRails: Int = 12,
): List<EveryCineSection> {
    val sections = ArrayList<EveryCineSection>(maxCategoryRails + 2)
    val resumable = recent.asSequence()
        .filter { it.positionMs > 0 }
        .map { it.entry }
        .filter { entry ->
            entry.kind == kind || (kind == MediaKind.SERIES && entry.kind == MediaKind.EPISODE)
        }
        .distinctBy { it.sourceId to it.id }
        .take(20)
        .toList()
    if (resumable.isNotEmpty()) sections += EveryCineSection("Continuar assistindo", resumable)

    val kindEntries = entries.filter { it.kind == kind }
    val favorites = kindEntries.asSequence()
        .filter { it.id in favoriteIds }
        .distinctBy { it.sourceId to it.id }
        .take(20)
        .toList()
    if (favorites.isNotEmpty()) sections += EveryCineSection("Minha lista", favorites)

    val grouped = LinkedHashMap<String, MutableList<MediaEntry>>()
    val ungrouped = ArrayList<MediaEntry>()
    kindEntries.forEach { entry ->
        val group = entry.group.trim()
        if (group.isNotEmpty()) {
            grouped.getOrPut(group) { ArrayList() }.add(entry)
        } else {
            ungrouped.add(entry)
        }
    }

    val orderedGroups = ArrayList<String>(grouped.size)
    val addedKeys = sections.mapTo(HashSet()) { it.title.lowercase() }
    val byLower = grouped.keys.associateBy { it.lowercase() }
    preferredCategoryOrder.forEach { preferred ->
        val matched = byLower[preferred.trim().lowercase()]
        if (matched != null && addedKeys.add(matched.lowercase())) {
            orderedGroups.add(matched)
        }
    }
    grouped.keys.forEach { existing ->
        if (addedKeys.add(existing.lowercase())) orderedGroups.add(existing)
    }

    orderedGroups.asSequence().take(maxCategoryRails).forEach { category ->
        val selected = grouped[category].orEmpty()
            .asSequence()
            .distinctBy { it.sourceId to it.id }
            .take(20)
            .toList()
        if (selected.isNotEmpty()) sections += EveryCineSection(category, selected)
    }

    if (orderedGroups.isEmpty() && ungrouped.isNotEmpty()) {
        val label = when (kind) {
            MediaKind.SERIES -> "Séries"
            MediaKind.LIVE -> "TV ao vivo"
            else -> "Filmes"
        }
        sections += EveryCineSection(label, ungrouped.distinctBy { it.sourceId to it.id }.take(20))
    }

    return sections
}

/** Original home uses a banner pager, prioritizing titles with real cover artwork. */
internal fun everyCineBannerEntries(entries: List<MediaEntry>, recent: List<RecentPlayback>): List<MediaEntry> {
    val candidates = (entries.asSequence().filter { it.kind == MediaKind.MOVIE || it.kind == MediaKind.SERIES } +
        recent.asSequence().map { it.entry }.filter { it.kind == MediaKind.MOVIE || it.kind == MediaKind.SERIES })
        .distinctBy { it.sourceId to it.id }
        .toList()
    return prioritizeWithArtwork(candidates).take(5)
}

/** Dedicated Movies/Series banner pager strictly scoped to a single MediaKind. */
internal fun everyCineBannerEntriesForKind(
    entries: List<MediaEntry>,
    recent: List<RecentPlayback>,
    kind: MediaKind,
): List<MediaEntry> {
    val candidates = (entries.asSequence().filter { it.kind == kind } +
        recent.asSequence().map { it.entry }.filter { it.kind == kind })
        .distinctBy { it.sourceId to it.id }
        .toList()
    return prioritizeWithArtwork(candidates).take(5)
}

private fun prioritizeWithArtwork(candidates: List<MediaEntry>): List<MediaEntry> {
    val withArtwork = ArrayList<MediaEntry>(candidates.size)
    val withoutArtwork = ArrayList<MediaEntry>()
    candidates.forEach { entry ->
        if (ArtworkUrls.normalizeUrl(entry.logo).isNotBlank()) withArtwork.add(entry)
        else withoutArtwork.add(entry)
    }
    return withArtwork + withoutArtwork
}

/**
 * Selects only the missing Xtream category IDs required to populate the first
 * [desiredCategoryCount] showcase rails for [kind], never fetching already-loaded categories.
 */
internal fun everyCineShowcaseGroupIdsToLoad(
    remoteGroups: List<XtreamGroup>,
    loadedEntries: List<MediaEntry>,
    kind: MediaKind,
    desiredCategoryCount: Int,
): List<String> {
    if (desiredCategoryCount <= 0) return emptyList()
    val kindGroups = remoteGroups.filter { it.kind == kind && it.name.isNotBlank() }
    if (kindGroups.isEmpty()) return emptyList()
    val loadedGroupNames = loadedEntries.asSequence()
        .filter { it.kind == kind }
        .map { it.group.trim().lowercase() }
        .filter { it.isNotEmpty() }
        .toHashSet()
    val targetNames = kindGroups.asSequence()
        .map { it.name.trim() }
        .distinctBy { it.lowercase() }
        .take(desiredCategoryCount)
        .filter { it.lowercase() !in loadedGroupNames }
        .map { it.lowercase() }
        .toHashSet()
    if (targetNames.isEmpty()) return emptyList()
    return kindGroups
        .filter { it.name.trim().lowercase() in targetNames }
        .map { it.id }
        .distinct()
}

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

/**
 * Balances Home's "Explore as categorias" shortcuts across Live, Movies and Series so
 * large Movie category lists never crowd Series out of Home shortcuts.
 */
internal fun everyCineHomeCategoryShortcuts(
    xtreamGroups: List<XtreamGroup>,
    entries: List<MediaEntry>,
): List<Pair<MediaKind, String>> {
    if (xtreamGroups.isNotEmpty()) {
        val live = xtreamGroups.asSequence().filter { it.kind == MediaKind.LIVE && it.name.isNotBlank() }
            .map { MediaKind.LIVE to it.name.trim() }.distinctBy { it.first to it.second.lowercase() }.take(8).toList()
        val movies = xtreamGroups.asSequence().filter { it.kind == MediaKind.MOVIE && it.name.isNotBlank() }
            .map { MediaKind.MOVIE to it.name.trim() }.distinctBy { it.first to it.second.lowercase() }.take(8).toList()
        val series = xtreamGroups.asSequence().filter { it.kind == MediaKind.SERIES && it.name.isNotBlank() }
            .map { MediaKind.SERIES to it.name.trim() }.distinctBy { it.first to it.second.lowercase() }.take(8).toList()
        return live + movies + series
    }
    return entries.mapNotNull { entry ->
        entry.group.trim().takeIf { it.isNotEmpty() }?.let { entry.kind to it }
    }.distinctBy { it.first to it.second.lowercase() }.take(24)
}

/**
 * Exiting fullscreen should keep playback running only when casting to TV or returning
 * to the Live tab where the inline live player remains visible.
 */
internal fun shouldStopOnFullscreenExit(
    isLive: Boolean,
    isOnLivePage: Boolean,
    isCastingToTv: Boolean,
): Boolean = !isCastingToTv && (!isLive || !isOnLivePage)
