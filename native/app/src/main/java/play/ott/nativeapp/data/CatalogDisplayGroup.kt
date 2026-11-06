package play.ott.nativeapp.data

import java.text.Normalizer
import java.util.Locale
import play.ott.nativeapp.core.MediaEntry
import play.ott.nativeapp.core.MediaKind

/**
 * Client-only catalogue presentation. Sources, Xtream IDs, stream URLs, EPG,
 * playback, favorite keys and the stored original group remain unchanged.
 *
 * For LIVE, only categories already supplied by the current catalogue may
 * appear. The caller passes the set of provider groups for the selected kind.
 * The default list is the verified M81 category vocabulary, for legacy callers.
 */
object CatalogDisplayGroup {
    private val missing = setOf(
        "", "sem categoria", "variedade", "variedades", "outros", "outro",
        "diversos", "geral", "unknown", "uncategorized", "misc", "other", "others"
    )
    private val defaultLiveGroups = setOf(
        "Sem categoria", "Retr\u00f4", "Anime", "Infantil", "Esportes",
        "Not\u00edcias", "Filmes", "S\u00e9ries", "Novelas", "M\u00fasica",
        "Natureza", "Curiosidades", "Estilo de Vida",
        "Canais |  Variedades", "Canais | [24H] Outros",
        "Canais | [24H] Desenhos", "Canais | [24H] Animes",
        "Canais | [24H] Series", "Canais | Infantis",
        "Canais |  Religiosos", "Canais | Documentarios", "Canais |  Abertos",
        "Canais | Prime Video", "Canais | Paramount +",
        "CANAIS: HBO \u2728", "CANAIS: TELECINE \u2728",
        "CANAIS: DISCOVERY \u2728", "CANAIS: SBT \u2728",
        "CANAIS: BAND \u2728", "CANAIS: RECORD \u2728",
        "CANAIS: REDETV \u2728", "Canais | Legendados"
    )

    /** Variant for the native UI and category rails. Always uses live groups on the device. */
    fun resolve(entry: MediaEntry, availableGroups: Set<String>): String =
        routeExisting(entry, availableGroups)

    /** Used by the home shelves and category filters; never creates source groups. */
    fun group(entry: MediaEntry, availableGroups: Set<String>): String =
        routeExisting(entry, availableGroups)

    private val marks = Regex("\\p{M}+")
    private val punct = Regex("[^a-z0-9]+")
    private val prefix = Regex("^(?:24h|us|usa|pt|br|uk)\\s+")

    private val animeTitles = listOf(
        "naruto", "boruto", "one piece", "pokemon", "beyblade",
        "dragon ball", "bleach", "hunter x hunter", "demon slayer",
        "attack on titan", "jujutsu kaisen", "one punch man", "my hero academia",
        "cavaleiros do zodiaco", "inuyasha", "death note", "fullmetal alchemist"
    )
    private val cartoonTitles = listOf(
        "popeye", "marinheiro popeye", "tartarugas ninja", "bob esponja",
        "tom e jerry", "pica pau", "peppa pig", "scooby doo", "cartoonito",
        "cartoon network", "gloob", "gloobinho", "discovery kids",
        "nick jr", "boomerang", "tooncast", "retro cartoon",
        "turma da monica", "pantera cor de rosa", "ben 10",
        "animaniacs", "flintstones", "looney tunes", "superman a serie animada",
        "mr bean a serie animada", "chaves em desenho", "hora de aventura",
        "avatar a lenda de aang", "inspetor bugiganga", "inspector gadget",
        "as meninas superpoderosas", "x men serie animada", "tv ra tim bum"
    )
    private val sports = listOf(
        "sportv", "band sports", "bandsports", "n sports", "ge fast",
        "ge tv", "espn", "tnt sports", "fox sports", "combate",
        "acc network", "sporting tv", "premiere", "nfl channel", "nba tv",
        "fifa", "fifa+", "futebol"
    )
    private val news = listOf(
        "globonews", "globo news", "bandnews", "band news",
        "record news", "jovem pan news", "jp news", "cnn international",
        "cnn brasil", "times brasil", "cn bc", "cnbc", "bm c", "canal uol"
    )

    private fun normalized(value: String): String =
        Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(marks, "")
            .replace(punct, " ")
            .trim()

    private fun named(title: String, term: String): Boolean =
        title == term || title.startsWith("$term ") ||
            title.endsWith(" $term") || title.contains(" $term ")

    private fun matches(title: String, names: List<String>): Boolean =
        names.any { named(title, it) }

    /**
     * Return the existing group label, verbatim. A missing target never
     * materializes a new category; try another existing one or return original.
     */
    private fun routeExisting(entry: MediaEntry, existingGroups: Set<String>): String {
        val original = entry.group.trim()
        val titleRaw = normalized(entry.name)
        val title = titleRaw.replace(prefix, "")
        val twentyFourHours = entry.name.trimStart().startsWith("[24H]", ignoreCase = true) ||
            normalized(original).contains("24h")

        if (entry.kind != MediaKind.LIVE) {
            if (normalized(original) !in missing) return original
            return when {
                matches(title, animeTitles) -> "Animes"
                matches(title, cartoonTitles) -> "Desenhos"
                else -> original
            }
        }

        val available = if (existingGroups.isEmpty()) defaultLiveGroups else existingGroups
        val byName = available.associateBy { normalized(it) }
        fun pick(vararg names: String): String? =
            names.firstNotNullOfOrNull { byName[normalized(it)] }

        val vague = normalized(original) in missing ||
            normalized(original).contains("24h outros")
        // Legendados is an actual viewing format and should not be overridden
        // by the channel's movie-studio brand.
        if (normalized(original).contains("legendados")) return original

        val recognized = when {
            matches(title, animeTitles) ->
                if (twentyFourHours) pick("Canais | [24H] Animes", "Anime")
                else pick("Anime", "Canais | [24H] Animes")
            matches(title, cartoonTitles) ->
                if (twentyFourHours) pick("Canais | [24H] Desenhos", "Infantil", "Canais | Infantis")
                else pick("Infantil", "Canais | Infantis", "CANAIS: INFANTIS \u2728")
            title.startsWith("hbo") ->
                pick("CANAIS: HBO \u2728", "Canais | HBO", "Filmes")
            title.startsWith("telecine") ->
                pick("CANAIS: TELECINE \u2728", "Filmes")
            title.startsWith("prime video") || title.startsWith("amazon prime") ->
                pick("Canais | Prime Video", "CANAIS: PRIME VIDEO \u26bd", "Filmes")
            title.startsWith("paramount") ->
                pick("Canais | Paramount +", "CANAIS: PARAMOUNT + LIBERTADORES \u26bd")
            matches(title, sports) ->
                pick("Esportes", "Canais | Esportes")
            matches(title, news) ->
                pick("Not\u00edcias", "Canais | Noticias", "CANAIS: NOT\u00cdCIAS \u2728")
            title.startsWith("megapix") || title.startsWith("sony movies") ||
                title.startsWith("amc") || title.startsWith("cinemax") ||
                title.startsWith("studio universal") || title.startsWith("space") ->
                pick("Filmes", "CANAIS: CINE SKY \u2728")
            title.contains("novelas") ->
                pick("Novelas", "CANAIS: NOVELAS GLOBOPLAY \u2728")
            title.startsWith("tnt series") || title.startsWith("universal tv") ||
                title.startsWith("warner channel") ->
                pick("S\u00e9ries", "Canais | [24H] Series")
            title.startsWith("animal planet") || title.startsWith("fish tv") ||
                title.startsWith("agro") || title.startsWith("canal do boi") ||
                title.startsWith("terra viva") ->
                pick("Natureza", "Canais | Documentarios")
            title.startsWith("discovery kids") -> pick("Infantil", "Canais | Infantis")
            title.startsWith("discovery") || title.startsWith("disc home") ->
                pick("CANAIS: DISCOVERY \u2728", "Canais | Documentarios")
            title.startsWith("tv aparecida") || title.startsWith("tv pai eterno") ||
                title.startsWith("rede vida") -> pick("Canais |  Religiosos", "CANAIS: RELIGIOSOS \u2b50")
            title.startsWith("bis") || title.startsWith("mtv") ||
                title.startsWith("trace brasil") ->
                pick("M\u00fasica", "MTV")
            title.startsWith("futura") || title.startsWith("canal futura") ->
                pick("Curiosidades", "Canais | Documentarios")
            title.startsWith("food network") || title.startsWith("hgtv") ||
                title.startsWith("gnt") || title.startsWith("tlc") ||
                title.startsWith("travel box") || title.startsWith("modo viagem") ||
                title.startsWith("canal off") || title.startsWith("off") ->
                pick("Estilo de Vida", "Canais |  Variedades")
            title.startsWith("record") -> pick("CANAIS: RECORD \u2728", "Canais |  Abertos")
            title.startsWith("sbt") -> pick("CANAIS: SBT \u2728", "Canais |  Abertos")
            title.startsWith("band") -> pick("CANAIS: BAND \u2728", "Canais |  Abertos")
            title.startsWith("rede tv") || title.startsWith("redetv") ->
                pick("CANAIS: REDETV \u2728", "Canais |  Abertos")
            title.startsWith("tv bahia") || title.startsWith("tv aberta") ->
                pick("Canais |  Abertos", "TV Brasileira")
            else -> null
        }

        // Explicitly matched identities can correct mistakenly assigned
        // "M\u00fasica", "Retr\u00f4" or Series categories for cartoons and anime.
        if (recognized != null) return recognized

        // If we KNOW this is an anime/cartoon but the provider offers no
        // matching destination, prefer an existing general category over an
        // obviously wrong source label (e.g. Popeye under Music).
        val misplacedAnimation = matches(title, animeTitles) || matches(title, cartoonTitles)
        if (!vague && !misplacedAnimation && original in available) return original
        if (twentyFourHours) {
            pick("Canais | [24H] Outros")?.let { return it }
        }
        return pick("Canais |  Variedades", "CANAIS: VARIEDADES \u2728",
            "Variedades", "Sem categoria") ?: original
    }


    // Preserve the original public grouping contract for existing consumers.
    private val legacyUnspecific = setOf(
        "", "variedades", "variedade", "outros", "outro", "diversos",
        "geral", "sem categoria", "uncategorized", "unknown", "misc",
        "miscellaneous", "other", "others"
    )

    private val legacyAnimation = listOf(
        "as tartarugas ninja", "tartarugas ninja", "teenage mutant ninja turtles",
        "inspetor bugiganga", "inspector bugiganga", "inspector gadget",
        "avatar a lenda de aang", "avatar the last airbender",
        "bob esponja", "spongebob", "sponge bob", "peppa pig",
        "pocoyo", "turma da monica", "tom e jerry", "looney tunes",
        "scooby doo", "ben 10", "as meninas superpoderosas",
        "the powerpuff girls", "hora de aventura", "adventure time"
    )
    private val legacySports = listOf(
        "nfl channel", "nba tv", "espn", "sportv", "premiere", "tnt sports",
        "fox sports", "combate"
    )
    private val legacyFootball = listOf("fifa", "fifa+", "premiere futebol")
    private val legacyClearCartoonNetworks = listOf(
        "cartoon network", "boomerang", "tooncast", "nick jr",
        "discovery kids", "disney junior", "gloobinho"
    )
    private val legacyDiacritics = Regex("\\p{M}+")
    private val legacyNonAlphaNumeric = Regex("[^a-z0-9]+")
    private val legacyKnownAnimation = legacyAnimation + legacyClearCartoonNetworks
    private val legacyAnime = listOf(
        "hunter x hunter", "beyblade", "pokemon", "naruto",
        "dragon ball", "one piece", "demon slayer", "attack on titan",
        "jujutsu kaisen", "my hero academia", "one punch man", "bleach"
    )

    private fun legacyNormalize(value: String): String =
        Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(legacyDiacritics, "")
            .replace(legacyNonAlphaNumeric, " ")
            .trim()

    private fun legacyStartsWithTitle(title: String, name: String): Boolean =
        title == name || title.startsWith("$name ") || title.startsWith("$name:")

    /** Keep meaningful categories; keep an ambiguous "Variedades" unchanged. */
    fun group(entry: MediaEntry): String {
        val original = entry.group.trim()
        val title = legacyNormalize(entry.name)
        // Known live legacySports mismatch, not a title-based change of media type.
        if (entry.kind == MediaKind.LIVE && legacyStartsWithTitle(title, "nfl channel") &&
            legacyNormalize(original) in setOf("futebol", "football", "soccer")) return "Esportes"
        if (original.lowercase(Locale.ROOT) !in legacyUnspecific) return original
        return when {
            legacyAnime.any { legacyStartsWithTitle(title, it) } -> "Animes"
            legacyKnownAnimation.any { legacyStartsWithTitle(title, it) } -> "Desenhos"
            legacyFootball.any { legacyStartsWithTitle(title, it) } -> "Futebol"
            legacySports.any { legacyStartsWithTitle(title, it) } -> "Esportes"
            else -> original
        }
    }
}
