package play.ott.nativeapp.data

import java.text.Normalizer
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
    private val anime = listOf(
        "hunter x hunter", "beyblade", "pokemon", "naruto",
        "dragon ball", "one piece", "demon slayer", "attack on titan",
        "jujutsu kaisen", "my hero academia", "one punch man", "bleach"
    )

    private fun normalize(value: String): String =
        Normalizer.normalize(value.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()

    private fun startsWithTitle(title: String, name: String): Boolean =
        title == name || title.startsWith("$name ") || title.startsWith("$name:")

    /** Keep meaningful provider categories, including a genuine category "Variedades". */
    fun group(entry: MediaEntry): String {
        val original = entry.group.trim()
        if (normalize(original) !in unspecific) return original

        val title = normalize(entry.name)
        return when {
            anime.any { startsWithTitle(title, it) } -> "Animes"
            animation.any { startsWithTitle(title, it) } -> "Desenhos"
            else -> original
        }
    }
}
