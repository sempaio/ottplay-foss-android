package play.ott.nativeapp.data

import java.text.Normalizer
import java.util.Locale
import play.ott.nativeapp.core.MediaKind
import play.ott.nativeapp.core.MediaEntry

/**
 * A presentation-only taxonomy overlay.
 *
 * Xtream/M3U and the on-disk catalogue keep the original category, URLs, IDs,
 * media kind, EPG mapping and publication intact. Ambiguous titles are never moved.
 * Expand rules only after a manually checked, unambiguous example.
 */
object CatalogDisplayGroup {
    private val unspecific = setOf(
        "", "variedades", "variedade", "outros", "outro", "diversos",
        "geral", "sem categoria", "uncategorized", "unknown", "misc",
        "miscellaneous", "other", "others"
    )

    private val animation = listOf(
        "as tartarugas ninja", "tartarugas ninja", "teenage mutant ninja turtles",
        "inspetor bugiganga", "inspector bugiganga", "inspector gadget",
        "avatar a lenda de aang", "avatar the last airbender",
        "bob esponja", "spongebob", "sponge bob", "peppa pig",
        "pocoyo", "turma da monica", "tom e jerry", "looney tunes",
        "scooby doo", "ben 10", "as meninas superpoderosas",
        "the powerpuff girls", "hora de aventura", "adventure time"
    )
    private val sports = listOf(
        "nfl channel", "nba tv", "espn", "sportv", "premiere", "tnt sports",
        "fox sports", "combate"
    )
    private val football = listOf("fifa", "fifa+", "premiere futebol")
    private val clearCartoonNetworks = listOf(
        "cartoon network", "boomerang", "tooncast", "nick jr",
        "discovery kids", "disney junior", "gloobinho"
    )
    private val diacritics = Regex("\\p{M}+")
    private val nonAlphaNumeric = Regex("[^a-z0-9]+")
    private val knownAnimation = animation + clearCartoonNetworks
    private val anime = listOf(
        "hunter x hunter", "beyblade", "pokemon", "naruto",
        "dragon ball", "one piece", "demon slayer", "attack on titan",
        "jujutsu kaisen", "my hero academia", "one punch man", "bleach"
    )

    private fun normalize(value: String): String =
        Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(diacritics, "")
            .replace(nonAlphaNumeric, " ")
            .trim()

    private fun startsWithTitle(title: String, name: String): Boolean =
        title == name || title.startsWith("$name ") || title.startsWith("$name:")

    /** Keep meaningful categories; keep an ambiguous "Variedades" unchanged. */
    fun group(entry: MediaEntry): String {
        val original = entry.group.trim()
        val title = normalize(entry.name)
        // Known live sports mismatch, not a title-based change of media type.
        if (entry.kind == MediaKind.LIVE && startsWithTitle(title, "nfl channel") &&
            normalize(original) in setOf("futebol", "football", "soccer")) return "Esportes"
        if (original.lowercase(Locale.ROOT) !in unspecific) return original
        return when {
            anime.any { startsWithTitle(title, it) } -> "Animes"
            knownAnimation.any { startsWithTitle(title, it) } -> "Desenhos"
            football.any { startsWithTitle(title, it) } -> "Futebol"
            sports.any { startsWithTitle(title, it) } -> "Esportes"
            else -> original
        }
    }
}
