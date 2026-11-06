package play.ott.nativeapp.data

import java.text.Normalizer
import java.util.Locale
import play.ott.nativeapp.core.MediaEntry
import play.ott.nativeapp.core.MediaKind

/**
 * Classify LIVE display cards only. Supplier data and VOD/SERIES categories stay untouched.
 * Never invent channels or change source URL, stream ID, playback type or regional identity.
 * A proposed category must already exist in the provider's visible category vocabulary.
 */
object SmartLiveCatalog {
    private val accents = Regex("\\p{M}+")
    private val punctuation = Regex("[^a-z0-9]+")
    private fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(accents, "").replace(punctuation, " ").trim()

    private fun begins(title: String, vararg prefixes: String): Boolean =
        prefixes.any { prefix -> title == prefix || title.startsWith("$prefix ") }

    private fun containsTitle(title: String, vararg terms: String): Boolean =
        terms.any { term -> (" $title ").contains(" $term ") }

    private fun categoryCandidate(name: String): String? {
        val text = normalize(name)
        val title = text.replace(Regex("^24h\\s+"), "")
        // Strong identities first, before broad Globo/Band/Record/SBT rules.
        return when {
            begins(title, "globo news", "globonews", "band news", "bandnews",
                "record news", "sbt news", "cnn", "jp news", "jovem pan news") -> "Notícias"
            (title.startsWith("sportv") && title.drop(6).firstOrNull()?.let { it.isDigit() || it == ' ' } != false) ||
                begins(title, "sportv", "espn", "premiere", "band sports", "bandsports",
                "onefootball", "combate", "tnt sports", "ge tv", "fifa",
                "nba tv", "nfl channel") -> "Esportes"
            begins(title, "hbo", "telecine", "megapix", "cinemax", "amc",
                "sony movies", "studio universal", "space") ||
                begins(title, "filmes", "filme") -> "Filmes"
            begins(title, "dragon ball", "pokemon", "naruto", "one piece", "bleach",
                "beyblade", "cavaleiros do zodiaco", "boruto", "demon slayer",
                "attack on titan", "jujutsu kaisen") ||
                begins(title, "anime") -> "Animes"
            begins(title, "cartoonito", "cartoon network", "animaniacs", "pica pau",
                "popeye", "o marinheiro popeye", "bob esponja", "scooby doo",
                "ben 10", "tom e jerry", "chaves em desenho", "discovery kids",
                "gloob", "gloobinho", "nickelodeon", "boomerang",
                "tooncast", "taz mania", "desenhos") -> "Desenhos"
            begins(title, "friends", "chaves", "chapolin", "dois homens e meio",
                "kenan e kel", "kenan kel", "jovem sheldon", "the big bang theory",
                "a grande familia", "eu a patroa e as criancas", "er plantao medico",
                "um maluco no pedaco", "sbt series", "tnt series",
                "warner channel", "universal tv", "series") -> "Séries"
            begins(title, "rede gospel", "tv aparecida", "tv pai eterno", "rede vida",
                "canal gospel", "gospel") || containsTitle(title, "culto ao vivo", "louvor gospel") -> "Religiosos"
            begins(title, "globo", "tv globo", "sbt", "band", "record",
                "rede tv", "redetv", "tv bahia") -> "Abertos"
            begins(title, "bis", "mtv", "trace brasil", "top 100 gospel") -> "Música"
            begins(title, "discovery", "animal planet", "national geographic",
                "history channel", "history", "nat geo") -> "Documentários"
            else -> null
        }
    }

    fun category(entry: MediaEntry, availableLabels: Set<String>): String {
        if (entry.kind != MediaKind.LIVE) return entry.group.trim()
        val original = XtreamDisplayGroups.label(entry.group, MediaKind.LIVE)
        val target = categoryCandidate(entry.name) ?: return original
        val exists = availableLabels.firstOrNull { normalize(it) == normalize(target) }
        return exists ?: original
    }

    /**
     * Network is a second-level visual grouping inside Abertos.
     * Globo RJ and Globo SP are two cards under Globo, not the same stream.
     */
    fun network(entry: MediaEntry): String? {
        if (entry.kind != MediaKind.LIVE) return null
        val title = normalize(entry.name)
        if (begins(title, "globo news", "globonews", "band news", "bandnews",
                "band sports", "bandsports", "record news", "sbt news")) return null
        return when {
            begins(title, "globo", "tv globo") -> "Globo"
            begins(title, "sbt") -> "SBT"
            begins(title, "band") -> "Band"
            begins(title, "record") -> "Record"
            begins(title, "redetv", "rede tv") -> "RedeTV"
            else -> null
        }
    }

    private val networkOrder = listOf("Globo", "SBT", "Band", "Record", "RedeTV")

    fun networks(entries: List<MediaEntry>): List<String> =
        networkOrder.filter { network -> entries.any { network(it) == network } }

    fun prominentFirst(entries: List<MediaEntry>): List<MediaEntry> =
        entries.sortedWith(compareBy<MediaEntry> {
            val n = network(it)
            if (n == null) networkOrder.size else networkOrder.indexOf(n)
        }.thenBy { normalize(it.name) })
}
