package play.ott.nativeapp.data

import java.text.Normalizer
import java.util.PriorityQueue
import kotlinx.serialization.Serializable
import play.ott.nativeapp.core.MediaEntry

/** Stored locally only: no provider URLs, credentials, or viewing history. */
@Serializable
data class ForYouProfile(
    val id: String = "default",
    val name: String = "Principal",
    val onboardingDone: Boolean = false,
    val interests: Set<String> = emptySet(),
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

    private val terms = mapOf(
        "futebol" to listOf("futebol", "soccer", "football"),
        "gospel" to listOf("gospel", "louvor", "musica crista"),
        "filmes" to listOf("filme", "movie", "cinema"),
        "series" to listOf("serie", "series", "seriado"),
        "desenhos" to listOf("desenho", "animacao", "cartoon", "animation"),
        "animes" to listOf("anime", "animacao japonesa"),
        "novelas" to listOf("novela", "telenovela"),
        "documentarios" to listOf("documentario", "documentary"),
        "comedia" to listOf("comedia", "humor"),
        "acao" to listOf("acao", "action"),
        "romance" to listOf("romance", "romantico"),
        "infantil" to listOf("infantil", "kids", "crianca"),
        "esportes" to listOf("esporte", "sport", "nba", "ufc"),
        "natureza" to listOf("natureza", "nature", "animal", "selvagem"),
        "ficcao cientifica" to listOf("ficcao cientifica", "sci fi", "scifi"),
    )

    fun key(entry: MediaEntry) = "${entry.sourceId}:${entry.id}"

    private fun normalized(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()

    private fun matches(topic: String, text: String): Boolean =
        terms[topic].orEmpty().any { term ->
            val needle = normalized(term)
            (" $text ").contains(" $needle ")
        }

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
            val group = normalized(entry.group)
            val title = normalized(entry.name)
            if (profile.dislikedInterests.any { matches(it, group) || matches(it, title) }) return@forEachIndexed
            var score = 0
            profile.interests.forEach { topic ->
                if (matches(topic, group)) score += 12
                else if (matches(topic, title)) score += 6
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
