package play.ott.nativeapp.ui

import java.text.Normalizer
import java.util.Locale
import play.ott.nativeapp.core.MediaEntry
import play.ott.nativeapp.core.MediaKind
import play.ott.nativeapp.core.XtreamGroup

/** Display-only IPTV families. No source IDs, metadata, or original playback URLs are modified. */
internal object EveryCineFamilyIndex {
    const val ALL = "Todos"
    const val OPEN = "Canais abertos"
    const val SPORTS = "Esportes"
    const val KIDS = "Infantil"
    const val FILMS_24H = "Filmes 24h"
    const val SERIES_24H = "Séries 24h"
    const val NEWS = "Notícias"
    const val OTHERS = "Outros"
    val categoryOrder = listOf(OPEN, SPORTS, KIDS, FILMS_24H, SERIES_24H, NEWS, OTHERS)
    private val marks = Regex("\\p{M}+")
    private val nonLetters = Regex("[^a-z0-9]+")
    private fun fold(value: String) = Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(marks, "").replace(nonLetters, " ").trim()
    private fun begins(value: String, prefix: String) = value == prefix || value.startsWith(prefix + " ")
    private fun sportsChannel(value: String) =
        value.startsWith("sportv") && value.drop(6).firstOrNull()?.let { it.isDigit() || it.isWhitespace() } != false
    private fun anime(value: String) = listOf(
        "naruto", "boruto", "dragon ball", "pokemon", "one piece", "bleach",
        "cavaleiros do zodiaco", "beyblade", "demon slayer", "attack on titan",
        "jujutsu kaisen", "anime", "animes"
    ).any { begins(value, it) }
    private fun cartoon(value: String) = listOf(
        "desenho", "desenhos", "pica pau", "tom e jerry", "scooby doo",
        "bob esponja", "cartoon network", "tooncast", "boomerang",
        "discovery kids", "gloob", "gloobinho", "nickelodeon",
        "popeye", "animaniacs", "ben 10", "cartoonito"
    ).any { begins(value, it) }

    fun category(name: String, providerGroup: String): String {
        val title = fold(name).removePrefix("24h ").trim()
        val group = fold(providerGroup).removePrefix("canais ").trim()
        val news = listOf("globo news", "globonews", "band news", "bandnews",
            "record news", "sbt news", "cnn", "noticias", "noticia", "jovem pan news")
        if (news.any { begins(title, it) || begins(group, it) }) return NEWS
        if (sportsChannel(title) || sportsChannel(group) ||
            listOf("espn", "premiere", "bandsports", "band sports", "combate",
                "ge tv", "caze tv", "cazetv", "tnt sports", "onefootball",
                "esportes", "esportivo", "esportivos", "futebol").any {
                begins(title, it) || begins(group, it)
            }) return SPORTS
        if (anime(title) || cartoon(title) || anime(group) || cartoon(group) ||
            listOf("infantil", "infantis", "kids", "criancas").any { begins(group, it) }) return KIDS
        if (begins(title, "transformers")) return FILMS_24H
        if (begins(title, "blitz") && (group.contains("24h") || group.contains("serie"))) return SERIES_24H
        if (group.contains("24h") && (group.contains("filme") ||
                group.contains("movie") || group.contains("cinema"))) return FILMS_24H
        if (group.contains("24h") && (group.contains("serie") ||
                group.contains("temporada"))) return SERIES_24H
        if (listOf("globo", "globos", "tv globo", "sbt", "band", "record",
                "rede tv", "redetv", "tv cultura", "abertos", "abertas",
                "tv aberta", "tv brasileira").any { begins(title, it) || begins(group, it) }) return OPEN
        if (group == "filmes" || group == "cinema") return FILMS_24H
        if (group == "series" || group == "seriados") return SERIES_24H
        return OTHERS
    }

    /** Family identity: strict known brands or explicit seasonal names; never fuzzy grouping. */
    fun family(name: String, providerGroup: String): String {
        val title = fold(name).removePrefix("24h ").trim()
        val group = fold(providerGroup)
        if (begins(title, "globo news") || begins(title, "globonews")) return "GloboNews"
        if (begins(title, "band news") || begins(title, "bandnews")) return "BandNews"
        if (begins(title, "espn")) return "ESPN"
        if (sportsChannel(title)) return "SporTV"
        if (begins(title, "premiere")) return "Premiere"
        if (begins(title, "globo") || begins(title, "globos") || begins(title, "tv globo")) return "Globo"
        if (begins(title, "sbt")) return "SBT"
        if (begins(title, "record")) return "Record"
        if (begins(title, "band")) return "Band"
        if (begins(title, "rede tv") || begins(title, "redetv")) return "RedeTV"
        if (begins(title, "transformers")) return "Transformers"
        if (begins(title, "blitz")) return "Blitz"
        if (category(name, providerGroup) == KIDS) {
            if (anime(title) || anime(group)) return "Animes"
            if (cartoon(title) || cartoon(group)) return "Desenhos"
            return "Infantil"
        }
        if (begins(title, "esportes") || begins(title, "esportivos")) return "Outros esportes"
        if (begins(title, "abertos") || begins(title, "canais abertos")) return "Outros canais abertos"
        val cleaned = variant(name)
            .replace(Regex("(?i)\\s+(?:temporada|season|t)\\s*\\d{1,3}\\s*$"), "")
            .trim()
        return cleaned.ifBlank { name.trim().ifBlank { "Canais" } }
    }

    /** Same region + different quality are selectable variants; different regions stay distinct. */
    fun variant(name: String): String {
        var value = name.trim()
        value = value.replace(Regex("(?i)^\\s*\\[?24h\\]?\\s*[:|\\-]?\\s*"), "")
        value = value.replace(Regex("(?i)\\s*\\(?\\b(?:sd|hd|fhd|uhd|4k|720p|1080p|2160p|480p|1080i)\\b\\)?\\s*$"), "")
        value = value.replace(Regex("(?i)\\s*\\(?24h\\)?\\s*$"), "")
        return value.trim().ifBlank { name.trim() }
    }

    fun available(entries: List<MediaEntry>, sources: List<XtreamGroup>): List<String> {
        val found = HashSet<String>()
        for (source in sources) if (source.kind == MediaKind.LIVE) found.add(category("", source.name))
        for (entry in entries) if (entry.kind == MediaKind.LIVE) found.add(category(entry.name, entry.group))
        return categoryOrder.filter { it in found }
    }

    internal data class FamilyCard(
        val key: String,
        val title: String,
        val category: String,
        val entries: List<MediaEntry>,
        val remoteIds: List<String>,
    )

    fun cards(
        entries: List<MediaEntry>, remote: List<XtreamGroup>, selected: String, query: String,
    ): List<FamilyCard> {
        class Working(val title: String, val category: String) {
            val entries = ArrayList<MediaEntry>()
            val ids = LinkedHashSet<String>()
        }
        val buckets = LinkedHashMap<String, Working>()
        val normalizedQuery = fold(query)
        fun key(category: String, title: String) = category + "|" + fold(title)
        for (entry in entries) {
            if (entry.kind != MediaKind.LIVE) continue
            val category = category(entry.name, entry.group)
            if (selected != ALL && selected != category) continue
            val label = family(entry.name, entry.group)
            if (normalizedQuery.isNotEmpty() && !fold(label).contains(normalizedQuery) &&
                !fold(entry.name).contains(normalizedQuery)) continue
            buckets.getOrPut(key(category, label)) { Working(label, category) }.entries.add(entry)
        }
        // Remote categories are *lazy* family cards. Tapping them fetches only their
        // original Xtream groups; all streams retain their original identity.
        for (group in remote) {
            if (group.kind != MediaKind.LIVE) continue
            val category = category("", group.name)
            if (selected != ALL && selected != category) continue
            val label = family(group.name, group.name)
            if (normalizedQuery.isNotEmpty() && !fold(label).contains(normalizedQuery) &&
                !fold(group.name).contains(normalizedQuery)) continue
            buckets.getOrPut(key(category, label)) { Working(label, category) }.ids.add(group.id)
        }
        return buckets.map { (key, value) ->
            FamilyCard(key, value.title, value.category, value.entries, value.ids.toList())
        }.sortedWith(compareBy<FamilyCard> { categoryOrder.indexOf(it.category) }
            .thenBy { it.title.lowercase(Locale.ROOT) })
    }
}
