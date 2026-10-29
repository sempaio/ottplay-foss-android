package play.ott.nativeapp

import java.io.IOException
import java.net.URI
import java.util.ArrayDeque
import java.util.Locale
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Response
import play.ott.nativeapp.core.MediaEntry

/** An OkHttp tag is local metadata; it is never sent as an HTTP header. */
internal class ArtworkTag(val kind: String, val surface: String) {
    @Volatile var ticket: ArtworkOrigins.Ticket? = null
}

/** Keep only coarse source labels. Never keep paths, queries, account names or raw errors. */
internal fun artworkOrigin(url: String): String {
    val uri = runCatching { URI(url) }.getOrNull() ?: return "não identificada"
    val scheme = uri.scheme?.lowercase(Locale.ROOT)
    return when (scheme) {
        "http", "https" -> {
            val host = uri.host?.lowercase(Locale.ROOT)
                ?.takeIf { it.matches(Regex("[a-z0-9.:-]{1,253}")) } ?: return "não identificada"
            val route = if (uri.path.orEmpty().startsWith("/channel-logo/")) "cache-painel" else "imagem"
            "$scheme | $host | $route"
        }
        "android.resource", "res" -> "recurso do APK"
        "file" -> "arquivo local"
        "content" -> "provedor local"
        "data" -> "dados embutidos"
        else -> if (url.isBlank()) "sem imagem" else "não identificada"
    }
}

/** Observational only: does not change cache policy, requests, redirects, URLs or image sizes. */
internal class ArtworkOrigins(private val capacity: Int = 300, private val clock: () -> Long = System::nanoTime) {
    init { require(capacity > 0) }
    data class Ticket(val kind: String, val surface: String, val origin: String, val started: Long, val generation: Long, val number: Long)
    private data class Result(val ticket: Ticket, val result: String, val ms: Long, val dimensions: String)
    private data class Hop(val ticket: Ticket, val status: Int, val bytes: Long, val headersMs: Long, val ms: Long, val completed: Boolean)
    private val images = ArrayDeque<Result>()
    private val hops = ArrayDeque<Hop>()
    private var generation = 0L
    private var nextNumber = 0L

    @Synchronized fun start(kind: String, surface: String, url: String): Ticket = Ticket(
        kind.takeIf { it in setOf("LIVE", "MOVIE", "SERIES", "EPISODE") } ?: "OUTRO",
        surface.takeIf { it in setOf("grade", "inicio", "destaque", "detalhe", "episodio") } ?: "OUTRO",
        artworkOrigin(url), clock(), generation, ++nextNumber)

    @Synchronized fun image(ticket: Ticket, result: String, width: Int = 0, height: Int = 0) {
        if (ticket.generation != generation) return
        val safe = result.takeIf { it in setOf("MEMORY_CACHE", "MEMORY", "DISK", "NETWORK", "ERROR", "CANCELLED") } ?: "OUTRO"
        if (images.size == capacity) images.removeFirst()
        images.addLast(Result(ticket, safe, elapsed(ticket), if (width > 0 && height > 0) "${width}x$height" else "não medida"))
    }

    @Synchronized private fun network(ticket: Ticket, status: Int, bytes: Long, headersMs: Long, completed: Boolean) {
        if (ticket.generation != generation) return
        if (hops.size == capacity) hops.removeFirst()
        hops.addLast(Hop(ticket, status.takeIf { it in 100..599 } ?: 0, bytes.coerceAtLeast(-1), headersMs, elapsed(ticket), completed))
    }

    private fun elapsed(ticket: Ticket) = ((clock() - ticket.started) / 1_000_000).coerceAtLeast(0)

    @Synchronized fun clear() { images.clear(); hops.clear(); generation++ }

    fun httpFactory() = EventListener.Factory { call ->
        val tag = call.request().tag(ArtworkTag::class.java)
        if (tag == null) EventListener.NONE else {
            val root = tag.ticket ?: start(tag.kind, tag.surface, call.request().url.toString())
            object : EventListener() {
                private var pending: Triple<Ticket, Int, Long>? = null
                private var sawResponse = false
                private fun finish(bytes: Long, completed: Boolean) {
                    val current = pending ?: return
                    pending = null
                    network(current.first, current.second, bytes, current.third, completed)
                }
                override fun responseHeadersEnd(call: Call, response: Response) {
                    // Each redirect/revalidation response has its own destination and status.
                    // Preserve the generation captured when the call began across report resets.
                    finish(-1, false)
                    val destination = root.copy(origin = artworkOrigin(response.request.url.toString()))
                    pending = Triple(destination, response.code, elapsed(root))
                    sawResponse = true
                }
                override fun responseBodyEnd(call: Call, byteCount: Long) { finish(byteCount, true) }
                override fun callEnd(call: Call) { finish(-1, true) }
                override fun callFailed(call: Call, ioe: IOException) {
                    if (pending != null) finish(-1, false)
                    else if (!sawResponse) network(root, 0, -1, -1, false)
                }
            }
        }
    }

    @Synchronized fun report(): String = buildString {
        appendLine("CAPAS: origem e cache")
        appendLine("Últimas ${images.size}/$capacity imagens e ${hops.size}/$capacity respostas HTTP desta sessão.")
        appendLine("MEMORY_CACHE = memória; DISK = disco; NETWORK pode incluir revalidação 304.")
        appendLine("HTTP registra cada destino de rede, inclusive redirecionamentos. Bytes são os reportados pelo OkHttp; -1 = não medidos.")
        appendLine("Tempo de cabeçalhos contado desde o início da chamada, incluindo conexão e saltos anteriores.")
        appendLine("Sem títulos, IDs, URLs completas, parâmetros, cabeçalhos de conta ou erros brutos.")
        appendLine()
        images.groupBy { listOf(it.ticket.kind, it.ticket.surface, it.ticket.origin, it.result) }.forEach { (key, rows) ->
            val times = rows.map { it.ms }.sorted()
            appendLine("${key.joinToString(" | ")}: ${rows.size}; mediana ${times[times.size / 2]}ms; máximo ${times.last()}ms")
        }
        appendLine()
        hops.forEach { h ->
            appendLine("HTTP | R${h.ticket.number} | ${h.ticket.kind} | ${h.ticket.surface} | ${h.ticket.origin} | ${h.status} | ${h.bytes} bytes | cabeçalhos ${h.headersMs}ms | total ${h.ms}ms | ${if (h.completed) "concluído" else "incompleto"}")
        }
        appendLine()
        images.forEach { r -> appendLine("IMAGEM | R${r.ticket.number} | ${r.ticket.kind} | ${r.ticket.surface} | ${r.ticket.origin} | ${r.result} | ${r.ms}ms | decodificada ${r.dimensions}") }
    }.trimEnd()

    companion object { val session = ArtworkOrigins() }
}

/** Inventory the URLs actually saved for this source, without guessing a provider from the title. */
internal fun artworkInventory(sourceId: String?, entries: List<MediaEntry>): String {
    val sources = linkedMapOf<Pair<String, String>, Pair<Int, MutableSet<String>>>()
    for (entry in entries) {
        if (entry.sourceId != sourceId) continue
        val key = entry.kind.name to artworkOrigin(entry.logo)
        val old = sources.getOrPut(key) { 0 to hashSetOf() }
        if (entry.logo.isNotBlank()) old.second.add(entry.logo)
        sources[key] = old.first + 1 to old.second
    }
    return buildString {
        appendLine("Endereços de capas no catálogo LOCAL da fonte selecionada:")
        appendLine("São endereços recebidos, não prova de download nem da versão atual no servidor.")
        for ((key, value) in sources) appendLine("${key.first} | ${key.second}: ${value.first} itens; ${value.second.size} URLs distintas")
    }.trimEnd()
}
