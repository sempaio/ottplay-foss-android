package play.ott.nativeapp.ui

import java.text.Normalizer
import java.util.Locale
import play.ott.nativeapp.core.MediaEntry
import play.ott.nativeapp.core.MediaKind

/**
 * Live-only seasonal 24h channels. This is navigation, not a VOD episode conversion:
 * each option retains its original LIVE identity, URL, source and metadata.
 */
internal object LiveSeasonGroups {
    data class Season(val title: String, val number: Int)

    private val explicitSeason = Regex(
        """(?i)^\s*(?:\[24h\]|24h)\s*[:|\-]?\s*(.+?)\s+(?:\[S(\d{1,2})\]|S(\d{1,2})|T(\d{1,2})|temporada\s+(\d{1,2}))(?:\s+(?:SD|HD|FHD|4K|UHD))?(?:\s*\[(?:H265|HEVC|H264)\])?\s*$"""
    )

    fun season(name: String): Season? {
        val match = explicitSeason.matchEntire(name) ?: return null
        val title = match.groupValues[1].trim().takeIf { it.isNotEmpty() } ?: return null
        val number = match.groupValues.drop(2).firstOrNull { it.isNotBlank() }
            ?.toIntOrNull()?.takeIf { it in 1..99 } ?: return null
        return Season(title, number)
    }

    private fun normalizedName(name: String) =
        Normalizer.normalize(name.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9]+"), " ").trim()

    fun isSeasonBundle(options: List<MediaEntry>) =
        options.all { it.kind == MediaKind.LIVE && season(it.name) != null } &&
            options.mapNotNull { season(it.name)?.number }.distinct().size > 1

    fun join(cards: List<LiveQualityCard>): List<LiveQualityCard> {
        val groups = LinkedHashMap<String, MutableList<LiveQualityCard>>()
        for ((index, card) in cards.withIndex()) {
            val season = card.options.firstOrNull()?.let { season(it.name) }
            val key = if (season != null && card.options.all { it.kind == MediaKind.LIVE })
                card.entry.sourceId + "\u0000" + normalizedName(season.title)
            else "single:$index"
            groups.getOrPut(key) { arrayListOf() }.add(card)
        }
        return groups.values.flatMap { sameSeries ->
            val all = sameSeries.flatMap { it.options }
            val seasons = all.mapNotNull { season(it.name)?.number }.distinct()
            if (sameSeries.size > 1 && seasons.size > 1) {
                val sorted = all.sortedWith(compareBy({ season(it.name)?.number ?: Int.MAX_VALUE }, { it.name }))
                listOf(LiveQualityCard(sameSeries.first().entry,
                    season(sameSeries.first().entry.name)?.title ?: sameSeries.first().displayName,
                    sorted))
            } else sameSeries
        }
    }
}
