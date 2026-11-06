package play.ott.nativeapp.data

import java.text.Normalizer
import java.util.Locale
import play.ott.nativeapp.core.MediaKind
import play.ott.nativeapp.core.XtreamGroup

/**
 * Display-only category aliases. Retain every original Xtream category_id:
 * one displayed chip may open several real categories on demand.
 */
data class XtreamDisplayGroup(
    val kind: MediaKind,
    val label: String,
    val sources: List<XtreamGroup>,
)

object XtreamDisplayGroups {
    private val marks = Regex("\\p{M}+")
    private val nonWords = Regex("[^a-z0-9]+")
    private val channelPrefix = Regex("^(?i:canais?|tv)\\s*[:|\\-]?\\s*")
    private val hourPrefix = Regex("^(?i:\\[?24h\\]?)\\s*[:|\\-]?\\s*")

    private fun fold(text: String): String =
        Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(marks, "")
            .replace(nonWords, " ")
            .trim()

    fun label(name: String, kind: MediaKind): String {
        val raw = name.trim()
        val normalized = fold(raw)
        val trimmed = normalized.replace(Regex("^canais? "), "")
            .replace(Regex("^24h "), "")
        if (kind == MediaKind.LIVE) {
            when (trimmed) {
                "abertos", "canais abertos", "tv brasileira", "brasileira", "tv aberta", "abertas" -> return "Abertos"
                "infantil", "infantis", "kids", "criancas" -> return "Infantis"
                "noticia", "noticias" -> return "Notícias"
                "religioso", "religiosos", "gospel", "evangelicos" -> return "Religiosos"
                "desenho", "desenhos" -> return "Desenhos"
                "anime", "animes" -> return "Animes"
                "documentario", "documentarios" -> return "Documentários"
                "musica", "musicas" -> return "Música"
                "serie", "series" -> return "Séries"
                "variedade", "variedades" -> return "Variedades"
                "filme", "filmes" -> return "Filmes"
                "esporte", "esportes" -> return "Esportes"
                "novela", "novelas" -> return "Novelas"
            }
            if (normalized == "tv brasileira") return "Abertos"
        }
        // Normalize common wrappers; preserve provider-specific identities.
        val withoutPrefix = raw.replace(channelPrefix, "")
            .replace(hourPrefix, "")
            .trim().trimEnd { !it.isLetterOrDigit() }
        return withoutPrefix.takeIf { it.isNotBlank() } ?: raw
    }

    fun merge(groups: List<XtreamGroup>): List<XtreamDisplayGroup> {
        val merged = LinkedHashMap<Pair<MediaKind, String>, MutableList<XtreamGroup>>()
        val titles = LinkedHashMap<Pair<MediaKind, String>, String>()
        for (source in groups) {
            val title = label(source.name, source.kind)
            if (title.isBlank()) continue
            val key = source.kind to fold(title)
            val list = merged.getOrPut(key) { ArrayList() }
            if (source.id !in list.map { it.id }) list.add(source)
            titles.putIfAbsent(key, title)
        }
        return merged.map { (key, sources) ->
            XtreamDisplayGroup(kind = key.first, label = titles.getValue(key), sources = sources)
        }
    }
}
