package play.ott.nativeapp.ui

import play.ott.nativeapp.core.MediaEntry
import play.ott.nativeapp.core.MediaKind
import java.util.Locale

/** Display-only grouping. Each media entry retains its ID, URL and playback metadata. */
internal data class LiveQualityCard(
    val entry: MediaEntry,
    val displayName: String,
    val options: List<MediaEntry>,
)

private data class QualityLabel(val base: String, val quality: String?, val codec: String?)
private val codecSuffix = Regex("""\s*\[(H\.?265|HEVC|H\.?264|AVC)\]\s*$""", RegexOption.IGNORE_CASE)
private val qualitySuffix = Regex("""\s+(4K|UHD|FULL[\s-]*HD|FHD|HD|SD)\s*$""", RegexOption.IGNORE_CASE)
private val resolutionSuffix = Regex("""\s*\((2160p|1080p|720p|480p)\)\s*$""", RegexOption.IGNORE_CASE)
private fun canonicalChannelName(name: String): String = when {
    name.matches(Regex("""(?i)^(TV\s+)?Globo\s+Rio\s+de\s+Janeiro$""")) -> "Globo RJ"
    name.matches(Regex("""(?i)^(TV\s+)?Globo\s+S[aã]o\s+Paulo$""")) -> "Globo SP"
    else -> name
}

private fun describe(name: String): QualityLabel {
    val raw = name.trim()
    val codecMatch = codecSuffix.find(raw)
    val codec = when (codecMatch?.groupValues?.get(1)?.uppercase(Locale.ROOT)?.replace(".", "")) {
        "H265", "HEVC" -> "H.265"
        "H264", "AVC" -> "H.264"
        else -> null
    }
    val withoutCodec = if (codecMatch == null) raw else raw.substring(0, codecMatch.range.first).trimEnd()
    val qualityMatch = qualitySuffix.find(withoutCodec)
    val resolutionMatch = if (qualityMatch == null) resolutionSuffix.find(withoutCodec) else null
    val quality = when (qualityMatch?.groupValues?.get(1)?.uppercase(Locale.ROOT)?.replace(" ", "")?.replace("-", "")) {
        "UHD", "4K" -> "4K"
        "FULLHD", "FHD" -> "FHD"
        "HD" -> "HD"
        "SD" -> "SD"
        else -> null
    }
    val resolvedQuality = quality ?: resolutionMatch?.groupValues?.get(1)?.lowercase(Locale.ROOT)
    val base = when {
        qualityMatch != null -> withoutCodec.substring(0, qualityMatch.range.first).trimEnd()
        resolutionMatch != null -> withoutCodec.substring(0, resolutionMatch.range.first).trimEnd()
        else -> withoutCodec
    }
    return QualityLabel(canonicalChannelName(base), resolvedQuality, codec)
}

internal fun qualityName(name: String): String? {
    val label = describe(name)
    return label.quality?.let { quality ->
        if (label.codec == null) quality else "${quality} ${label.codec}"
    }
}

private fun rank(label: String?): Int = when {
    label?.startsWith("4K") == true || label?.startsWith("2160p") == true -> 4
    label?.startsWith("FHD") == true || label?.startsWith("1080p") == true -> 3
    label?.startsWith("HD") == true || label?.startsWith("720p") == true -> 2
    label?.startsWith("SD") == true || label?.startsWith("480p") == true -> 1
    else -> 0
}

/** Merge only explicit renditions of the same channel within the same source/category.
 *  Different program numbers and regional affiliates are distinct channel identities.
 *  VOD/series, untagged streams and same-quality duplicates remain distinct.
 */
internal fun liveQualityCards(
    entries: List<MediaEntry>,
    categoryKey: (MediaEntry) -> String = { it.group.trim().lowercase(Locale.ROOT) },
): List<LiveQualityCard> {
    val groups = LinkedHashMap<String, MutableList<MediaEntry>>()
    for ((index, entry) in entries.withIndex()) {
        val label = describe(entry.name)
        val key = if (entry.kind == MediaKind.LIVE && label.quality != null && label.base.isNotBlank()) {
            listOf(entry.sourceId, categoryKey(entry).lowercase(Locale.ROOT),
                label.base.lowercase(Locale.ROOT)).joinToString("\u0000")
        } else "single:$index"
        groups.getOrPut(key) { mutableListOf() }.add(entry)
    }
    val output = ArrayList<LiveQualityCard>(groups.size)
    for (items in groups.values) {
        val distinctQualities = items.mapNotNull { qualityName(it.name) }.toSet()
        if (items.size > 1 && distinctQualities.size > 1) {
            val sorted = items.sortedWith(compareByDescending<MediaEntry> { rank(qualityName(it.name)) })
            output += LiveQualityCard(items.first(), describe(items.first().name).base, sorted)
        } else {
            output += items.map { LiveQualityCard(it, it.name, listOf(it)) }
        }
    }
    return output
}
