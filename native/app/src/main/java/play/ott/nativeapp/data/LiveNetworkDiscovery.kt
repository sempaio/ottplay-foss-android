package play.ott.nativeapp.data

import java.text.Normalizer
import java.util.Locale
import play.ott.nativeapp.core.MediaKind
import play.ott.nativeapp.core.XtreamGroup

/**
 * Explicitly selected network discovery, never a global catalogue import.
 * Provider group names are only hints; no automatic arbitrary category scan.
 */
object LiveNetworkDiscovery {
    private fun normalize(raw: String): String =
        Normalizer.normalize(raw.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9]+"), " ").trim()

    private fun isRelevant(value: String, network: String): Boolean {
        val words = normalize(value).split(" ")
        val name = normalize(value)
        val requested = normalize(network)
        if (words.any { it in setOf("news", "noticias", "esportes", "sport", "sports") } ||
            name.contains("globonews") || name.contains("bandnews") ||
            name.contains("recordnews") || name.contains("sbtnews")) return false
        return when (requested) {
            "globo" -> words.any { it == "globo" || it == "globos" } && !name.contains("globoplay")
            "sbt" -> "sbt" in words
            "band" -> "band" in words
            "record" -> words.any { it == "record" || it == "recordtv" }
            "redetv" -> words.any { it == "redetv" || it == "rede" && "tv" in words }
            else -> false
        }
    }

    fun related(groups: List<XtreamGroup>, network: String, maxGroups: Int = 8): List<XtreamGroup> =
        groups.asSequence()
            .filter { it.kind == MediaKind.LIVE && isRelevant(it.name, network) }
            .distinctBy { it.id }
            .take(maxGroups.coerceIn(1, 8))
            .toList()
}
