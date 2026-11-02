package play.ott.nativeapp.data

import java.text.Normalizer
import java.util.Locale
import java.util.PriorityQueue
import kotlinx.serialization.Serializable
import play.ott.nativeapp.core.MediaEntry
import play.ott.nativeapp.core.MediaKind
import play.ott.nativeapp.data.CatalogDisplayGroup

/** Stored locally only: no provider URLs, credentials, or viewing history. */
@Serializable
data class ForYouProfile(
    val id: String = "default",
    val name: String = "Principal",
    val onboardingDone: Boolean = false,
    val interests: Set<String> = emptySet(),
    val subInterests: Set<String> = emptySet(),
    val dislikedInterests: Set<String> = emptySet(),
    val likedEntries: Set<String> = emptySet(),
    val dislikedEntries: Set<String> = emptySet(),
    val allowViewingSignals: Boolean = false,
)

object ForYouEngine {
    // Only explicit, user-editable topical interests. Never infer sensitive
    // personal characteristics from channel titles or viewing behavior.
    val topics = listOf(
        "futebol", "gospel", "filmes", "series", "desenhos", "animes",
        "novelas", "documentarios", "comedia", "acao", "romance",
        "infantil", "esportes", "natureza", "ficcao cientifica"
    )

    /** Subtopics are explicitly chosen, never inferred from sensitive attributes. */
    val subtopics: Map<String, List<Pair<String, String>>> = linkedMapOf(
        "futebol" to listOf("brasileiro" to "Futebol brasileiro", "europeu" to "Futebol europeu",
            "selecoes" to "Seleções", "ao-vivo" to "Jogos ao vivo", "programas" to "Programas esportivos"),
        "gospel" to listOf("louvor" to "Louvor e música", "cultos" to "Cultos ao vivo",
            "pregacoes" to "Pregações", "filmes" to "Filmes gospel"),
        "desenhos" to listOf("classicos" to "Clássicos", "infantis" to "Infantis",
            "educativos" to "Educativos", "animacoes" to "Animações"),
        "filmes" to listOf("acao" to "Ação", "comedia" to "Comédia", "romance" to "Romance",
            "suspense" to "Suspense", "terror" to "Terror", "infantil" to "Infantil"),
        "series" to listOf("drama" to "Drama", "comedia" to "Comédia", "crime" to "Crime",
            "ficcao" to "Ficção científica", "coreanas" to "Séries coreanas"),
        "documentarios" to listOf("natureza" to "Natureza", "ciencia" to "Ciência",
            "historia" to "História", "esportes" to "Esportes"),
        "animes" to listOf("acao" to "Ação", "aventura" to "Aventura",
            "fantasia" to "Fantasia", "infantis" to "Infantis"),
        "novelas" to listOf("brasileiras" to "Brasileiras", "latinas" to "Latinas",
            "classicas" to "Clássicas")
    )
    val validSubInterests: Set<String> = subtopics.flatMap { (topic, values) ->
        values.map { item -> topic + ":" + item.first }
    }.toSet()
    private val subTerms = mapOf(
        "futebol:brasileiro" to listOf("brasileirao", "copa do brasil", "futebol brasileiro"),
        "futebol:europeu" to listOf("champions", "premier league", "la liga", "bundesliga", "serie a italiana"),
        "futebol:selecoes" to listOf("selecao", "copa do mundo", "nations league"),
        "futebol:ao-vivo" to listOf("ao vivo", "live"),
        "futebol:programas" to listOf("mesa redonda", "debate esportivo", "esporte espetacular"),
        "gospel:louvor" to listOf("louvor", "musica gospel", "adoracao"),
        "gospel:cultos" to listOf("culto ao vivo", "culto", "live gospel"),
        "gospel:pregacoes" to listOf("pregacao", "sermao"),
        "gospel:filmes" to listOf("filme gospel", "cinema cristao"),
        "desenhos:classicos" to listOf("desenho classico", "classicos"),
        "desenhos:infantis" to listOf("infantil", "kids", "criancas"),
        "desenhos:educativos" to listOf("educativo", "aprender", "educacional"),
        "desenhos:animacoes" to listOf("animacao", "animation"),
        "filmes:acao" to listOf("acao", "action"),
        "filmes:comedia" to listOf("comedia", "humor"),
        "filmes:romance" to listOf("romance", "romantico"),
        "filmes:suspense" to listOf("suspense", "thriller"),
        "filmes:terror" to listOf("terror", "horror"),
        "filmes:infantil" to listOf("infantil", "kids"),
        "series:drama" to listOf("drama"),
        "series:comedia" to listOf("comedia"),
        "series:crime" to listOf("crime", "policial"),
        "series:ficcao" to listOf("ficcao cientifica", "sci fi"),
        "series:coreanas" to listOf("dorama", "kdrama", "coreana"),
        "documentarios:natureza" to listOf("natureza", "animal", "selvagem"),
        "documentarios:ciencia" to listOf("ciencia", "science"),
        "documentarios:historia" to listOf("historia", "history"),
        "documentarios:esportes" to listOf("esporte", "sport"),
        "animes:acao" to listOf("acao", "action"),
        "animes:aventura" to listOf("aventura", "adventure"),
        "animes:fantasia" to listOf("fantasia", "fantasy"),
        "animes:infantis" to listOf("infantil", "kids"),
        "novelas:brasileiras" to listOf("novela brasileira", "globoplay"),
        "novelas:latinas" to listOf("novela mexicana", "latina", "televisa"),
        "novelas:classicas" to listOf("novela classica", "classicas")
    )

    private val terms = mapOf(
        "futebol" to listOf("futebol", "soccer", "football"),
        "gospel" to listOf("gospel", "louvor", "musica crista"),
        "filmes" to listOf("filme", "movie", "cinema"),
        "series" to listOf("serie", "series", "seriado"),
        "desenhos" to listOf("desenho", "desenhos", "animacao", "cartoon", "animation"),
        "animes" to listOf("anime", "animes", "animacao japonesa"),
        "novelas" to listOf("novela", "novelas", "telenovela"),
        "documentarios" to listOf("documentario", "documentarios", "documentary", "documentaries"),
        "comedia" to listOf("comedia", "humor"),
        "acao" to listOf("acao", "action"),
        "romance" to listOf("romance", "romantico"),
        "infantil" to listOf("infantil", "kids", "crianca"),
        "esportes" to listOf("esporte", "esportes", "sport", "sports", "nba", "ufc"),
        "natureza" to listOf("natureza", "nature", "animal", "selvagem"),
        "ficcao cientifica" to listOf("ficcao cientifica", "sci fi", "scifi"),
    )

    fun key(entry: MediaEntry) = "${entry.sourceId}:${entry.id}"

    private val marks = Regex("\\p{M}+")
    private val separators = Regex("[^a-z0-9]+")
    private fun normalized(text: String): String =
        Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(marks, "")
            .replace(separators, " ")
            .trim()

    private val normalizedTerms by lazy { terms.mapValues { (_, value) -> value.map(::normalized) } }
    private val normalizedSubTerms by lazy { subTerms.mapValues { (_, value) -> value.map(::normalized) } }
    private fun matches(topic: String, text: String): Boolean =
        normalizedTerms[topic].orEmpty().any { (" " + text + " ").contains(" " + it + " ") }

    /** Return only a bounded set of real catalogue entries without sorting all 30k items. */
    fun recommend(
        entries: List<MediaEntry>,
        profile: ForYouProfile,
        favoriteIds: Set<String> = emptySet(),
        recentlyViewedIds: Set<String> = emptySet(),
        limit: Int = 20,
    ): List<MediaEntry> {
        if (limit <= 0 || entries.isEmpty()) return emptyList()
        if (profile.interests.isEmpty() && profile.likedEntries.isEmpty()) return emptyList()
        data class Candidate(val score: Int, val order: Int, val entry: MediaEntry)
        val compare = compareBy<Candidate>({ it.score }, { -it.order })
        val top = PriorityQueue(compare)
        val maxCount = limit.coerceAtMost(30)
        entries.forEachIndexed { index, entry ->
            val key = key(entry)
            if (key in profile.dislikedEntries) return@forEachIndexed
            val group = normalized(CatalogDisplayGroup.group(entry))
            val title = normalized(entry.name)
            if (profile.dislikedInterests.any { topic ->
                matches(topic, group) || matches(topic, title) ||
                    (topic == "filmes" && entry.kind == MediaKind.MOVIE) ||
                    (topic == "series" && entry.kind == MediaKind.SERIES)
            }) return@forEachIndexed
            var score = 0
            profile.interests.forEach { topic ->
                val kindMatches = (topic == "filmes" && entry.kind == MediaKind.MOVIE) ||
                    (topic == "series" && entry.kind == MediaKind.SERIES)
                if (matches(topic, group) || kindMatches) score += 12
                else if (matches(topic, title)) score += 6
            }
            // Subtopics refine only a matched parent interest, never create a
            // fabricated item or override a rejected topic.
            if (score > 0) {
                profile.subInterests.forEach { sub ->
                    val parent = sub.substringBefore(":")
                    if (parent in profile.interests && sub in validSubInterests) {
                        val matched = normalizedSubTerms[sub].orEmpty().any { needle ->
                            (" " + group + " ").contains(" " + needle + " ") ||
                                (" " + title + " ").contains(" " + needle + " ")
                        }
                        if (matched) score += 9
                    }
                }
            }
            if (key in profile.likedEntries) score += 30
            if (entry.id in favoriteIds) score += 9
            if (profile.allowViewingSignals && key in recentlyViewedIds) score += 5
            if (score <= 0) return@forEachIndexed
            val candidate = Candidate(score, index, entry)
            if (top.size < maxCount) top.add(candidate)
            else if (compare.compare(candidate, top.peek()) > 0) {
                top.poll()
                top.add(candidate)
            }
        }
        return top.toList().sortedWith(compareByDescending<Candidate> { it.score }.thenBy { it.order }).map { it.entry }
    }
}
