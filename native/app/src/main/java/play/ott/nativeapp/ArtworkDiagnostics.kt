package play.ott.nativeapp

import java.net.URI
import java.util.ArrayDeque
import java.util.Locale

/** Bounded, in-memory diagnostics. Never retain titles, IDs, URL paths, queries or error messages. */
internal class ArtworkDiagnostics(private val capacity: Int = 300, private val clock: () -> Long = System::nanoTime) {
    init { require(capacity > 0) }
    data class Ticket(val kind: String, val surface: String, val host: String, val route: String,
                      val started: Long, val generation: Long)
    private data class Event(val ticket: Ticket, val result: String, val elapsedMs: Long, val failure: String)
    private val events = ArrayDeque<Event>()
    private var generation = 0L

    @Synchronized fun begin(kind: String, surface: String, url: String): Ticket {
        val uri = runCatching { URI(url) }.getOrNull()
        val host = uri?.host?.lowercase(Locale.ROOT)?.takeIf { it.matches(Regex("[a-z0-9.-]{1,253}")) } ?: "sem-host"
        val route = when {
            uri?.path?.startsWith("/channel-logo/") == true -> "channel-logo"
            host == "image.tmdb.org" || host == "www.themoviedb.org" || host == "media.themoviedb.org" -> "TMDB"
            else -> "imagem"
        }
        return Ticket(allowed(kind, setOf("LIVE", "MOVIE", "SERIES", "EPISODE")),
            allowed(surface, setOf("grade", "inicio", "destaque", "detalhe", "episodio")), host, route, clock(), generation)
    }

    @Synchronized fun finish(ticket: Ticket, result: String, failure: String = ""): String? {
        if (ticket.generation != generation) return null
        val safeResult = allowed(result, setOf("MEMORY_CACHE", "MEMORY", "DISK", "NETWORK", "ERROR", "CANCELLED"))
        // Accept only our own fixed classifications, never Throwable.message or a URL.
        val safeFailure = allowed(failure, setOf("", "HTTP", "REDE", "DECODIFICACAO", "OUTRO"))
        val elapsed = ((clock() - ticket.started) / 1_000_000).coerceAtLeast(0)
        if (events.size == capacity) events.removeFirst()
        events.addLast(Event(ticket, safeResult, elapsed, safeFailure))
        return "${ticket.kind} ${ticket.surface} ${ticket.host} ${ticket.route} $safeResult ${elapsed}ms $safeFailure".trim()
    }

    @Synchronized fun clear() { events.clear(); generation++ }

    @Synchronized fun report(): String = buildString {
        appendLine("STARLINK — diagnóstico das capas")
        appendLine("Últimos ${events.size}/$capacity resultados desta sessão; tempos até a imagem ficar pronta.")
        appendLine("MEMORY/MEMORY_CACHE = memória; DISK = disco; NETWORK = rede.")
        appendLine("Sem títulos, IDs, links completos ou credenciais. Reiniciar o app apaga este relatório.")
        appendLine()
        events.groupBy { listOf(it.ticket.kind, it.ticket.host, it.ticket.route, it.result) }.forEach { (key, rows) ->
            val timings = rows.map { it.elapsedMs }.sorted()
            appendLine("${key.joinToString(" | ")}: ${rows.size} imagens; mediana ${timings[timings.size / 2]}ms; máximo ${timings.last()}ms")
        }
        appendLine()
        events.forEach { event ->
            val t = event.ticket
            appendLine("${t.kind} | ${t.surface} | ${t.host} | ${t.route} | ${event.result} | ${event.elapsedMs}ms | ${event.failure}")
        }
    }.trimEnd()

    private fun allowed(value: String, values: Set<String>) = if (value in values) value else "OUTRO"

    companion object { val session = ArtworkDiagnostics() }
}
