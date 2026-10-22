package play.ott.nativeapp.core

import java.net.URLDecoder
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import play.ott.core.Playlist
import play.ott.core.PlaylistFormat
import play.ott.core.PlaylistFailure

/** Extended M3U parser. Directives between EXTINF and its URI belong to that entry. */
object M3uParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String, config: SourceConfig, baseUrl: String = config.url): Catalog {
        val notes = linkedSetOf<String>()
        val converted = mutableMapOf<String, MediaEntry>()
        var entryHeaders = emptyMap<String, String>()
        var kodi = KodiDrmParser()
        val parsed = try {
            Playlist.read(text, PlaylistFormat.ANDROID, config.id, { resolveHttp(baseUrl, it) },
                { stableId(*it.toTypedArray()) }, filename = { httpUrl(it).pathSegments.lastOrNull().orEmpty() },
                epgUrl = config.epgUrl, fallbackDays = config.catchupDaysFallback,
                onDirective = { directive ->
                    when (directive.kind) {
                        "reset" -> { entryHeaders = emptyMap(); kodi = KodiDrmParser() }
                        "header" -> entryHeaders = mergedHeaders(entryHeaders, mapOf(directive.name to directive.value))
                        "query" -> entryHeaders = mergedHeaders(entryHeaders, queryHeaders(directive.value))
                        "property" -> kodi.property(directive.name, directive.value)
                        "json" -> try {
                            val data = json.parseToJsonElement(directive.value) as? JsonObject
                            val headers = data?.mapNotNull { (key, value) -> (value as? JsonPrimitive)?.content?.let { key to it } }?.toMap().orEmpty()
                            entryHeaders = mergedHeaders(entryHeaders, headers)
                        } catch (_: Exception) { throw ProviderException("Playlist contains invalid HTTP headers") }
                    }
                },
                onEntry = { entry ->
                    val drm = kodi.result()
                    drm.unsupportedReason?.let(notes::add)
                    converted.putIfAbsent(entry.id, MediaEntry(entry.id, config.id, entry.name, entry.url,
                        kind = if (entry.movie) MediaKind.MOVIE else MediaKind.LIVE,
                        group = entry.group, logo = entry.logo, epgId = entry.epgId,
                        headers = mergedHeaders(config.headers, entryHeaders),
                        catchup = entry.archive?.let { Catchup(it.mode, it.source, it.days) },
                        drm = drm.drm, mimeType = drm.mimeType, playbackUnsupportedReason = drm.unsupportedReason,
                        headerOrigins = headerOrigins(config.headers, config.url) + headerOrigins(entryHeaders, entry.url),
                        nameMessage = if (entry.generatedTitle) CoreMessage(CoreMessageKey.STREAM, listOf(entry.generatedTitleIndex.toString())) else null))
                })
        } catch (failure: PlaylistFailure) {
            throw ProviderException(when (failure.code) {
                "SIZE" -> "Playlist exceeds the supported size limit"
                "LINE_SIZE" -> "Playlist contains an oversized line"
                "COUNT" -> "Playlist contains more than 100,000 entries"
                else -> "Playlist contains no playable HTTP or HTTPS entries"
            })
        }
        if (parsed.hls) {
            parsed.hlsProperties.forEach { kodi.property(it.name, it.value) }
            val drm = kodi.result()
            val url = httpUrl(baseUrl).toString()
            return Catalog(listOf(MediaEntry(stableId(config.id, url), config.id, config.name.ifBlank { "HLS stream" }, url,
                nameMessage = config.nameMessage ?: if (config.name.isBlank()) CoreMessage(CoreMessageKey.HLS_STREAM) else null,
                headers = mergedHeaders(config.headers), drm = drm.drm, mimeType = "application/x-mpegURL",
                headerOrigins = headerOrigins(config.headers, config.url),
                playbackUnsupportedReason = drm.unsupportedReason)), listOfNotNull(config.epgUrl.takeIf(String::isNotBlank)),
                listOfNotNull(drm.unsupportedReason),
                if (drm.unsupportedReason != null) listOf(CoreMessage(CoreMessageKey.DRM_UNSUPPORTED)) else emptyList())
        }
        return Catalog(parsed.entries.map { converted.getValue(it.id) }, parsed.epgUrls, notes.toList(),
            if (notes.isNotEmpty()) listOf(CoreMessage(CoreMessageKey.DRM_UNSUPPORTED)) else emptyList())
    }

    internal fun queryHeaders(text: String): Map<String, String> = text.split('&').filter { '=' in it }.associate {
        try { URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        catch (_: IllegalArgumentException) { throw ProviderException("Invalid encoded playlist header") }
    }.let { mergedHeaders(it) }
}

internal fun stableId(vararg parts: String): String = MessageDigest.getInstance("SHA-256")
    .digest(parts.joinToString("\u0000").toByteArray()).take(16).joinToString("") { "%02x".format(it) }
