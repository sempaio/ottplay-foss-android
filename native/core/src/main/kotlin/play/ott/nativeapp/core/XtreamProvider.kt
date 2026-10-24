package play.ott.nativeapp.core

import play.ott.core.XtreamAddresses
import play.ott.core.XtreamCatalogs
import play.ott.core.XtreamFailure
import play.ott.core.XtreamFormat
import play.ott.core.XtreamItem
import play.ott.core.ProviderValue
import play.ott.core.XtreamSeriesParent
import play.ott.core.XtreamLoad
import play.ott.core.XtreamSource
import kotlinx.serialization.json.*
import java.util.Base64
import okhttp3.HttpUrl

/** HTTP, URL codec and native model adapter for the common Xtream session. */
internal class XtreamProvider(private val http: ProviderHttp) {
    suspend fun load(config: SourceConfig): Catalog = translateFailure {
        val addresses = addresses(config)
        val load = XtreamLoad(source(config), addresses, { resolveHttp(config.url, it) }, { stableId(*it.toTypedArray()) })
        val session = load.session
        while (true) {
            val request = session.request ?: break
            try { session.accept(parseProviderJson(http.get(addresses.api(request), config.headers).text()).toProviderValue()) }
            catch (error: ProviderException) { if (!session.reject(error.statusCode)) throw error }
        }
        val normalized = load.catalog()
        val entries = normalized.entries.map { item(it, config, session.timezone) }
        val epg = if (config.epgUrl.isNotBlank()) httpUrl(config.epgUrl).toString() else addresses.epg()
        val messages = session.notices.map { notice -> CoreMessage(
            if (notice.code == "GROUPS") CoreMessageKey.XTREAM_GROUPS_UNAVAILABLE else CoreMessageKey.XTREAM_SECTION_UNAVAILABLE,
            listOf(notice.kind, notice.status.toString())) }
        Catalog(entries, listOf(epg), messages.map { it.english() }, messages)
    }

    suspend fun episodes(config: SourceConfig, series: MediaEntry): List<MediaEntry> = translateFailure {
        val id = series.providerId
        val addresses = addresses(config)
        val request = XtreamCatalogs.seriesRequest(ProviderValue.text(id), "", XtreamFormat.ANDROID)
        val data = normalizeXtreamEpisodeRows(parseProviderJson(http.get(addresses.api(request), config.headers).text())).toProviderValue()
        XtreamCatalogs.episodes(data, XtreamFormat.ANDROID, source(config), addresses,
            XtreamSeriesParent(id, series.name, series.logo), { resolveHttp(config.url, it) }, { it },
            { stableId(*it.toTypedArray()) }).entries.map { item(it, config, "UTC") }
    }

    suspend fun authenticate(config: SourceConfig) {
        val data = api(config, "", "", "") as? JsonObject
            ?: throw ProviderException("Invalid Xtream account response", failure = ProviderFailure.INVALID_RESPONSE)
        val account = data["user_info"] as? JsonObject ?: throw ProviderException("Invalid Xtream account response", failure = ProviderFailure.INVALID_RESPONSE)
        if (account.string("auth") != "1") throw ProviderException("Xtream rejected the username or password", failure = ProviderFailure.AUTHENTICATION)
        if (!account.string("status").trim().equals("Active", ignoreCase = true)) throw ProviderException("Xtream account is not active", failure = ProviderFailure.ACCOUNT_INACTIVE)
    }

    /** VOD detail is lazy: one request only when the user starts a movie. */
    suspend fun vod(config: SourceConfig, entry: MediaEntry): MediaEntry {
        val data = try { api(config, "get_vod_info", "vod_id", entry.providerId) as? JsonObject }
            catch (error: ProviderException) { if (error.statusCode in listOf(404, 405, 501)) return entry else throw error }
            ?: return entry
        val info = data["info"] as? JsonObject
        val movie = data["movie_data"] as? JsonObject
        val extension = movie?.string("container_extension").orEmpty()
        // Never assume a server transcodes: use the advertised container, if valid.
        val url = if (extension.matches(Regex("[A-Za-z0-9]{1,10}"))) {
            val parsed = httpUrl(entry.url)
            val segments = parsed.pathSegments.toMutableList()
            segments[segments.lastIndex] = segments.last().substringBeforeLast('.') + "." + extension
            parsed.newBuilder().encodedPath("/").apply { segments.forEach { addPathSegment(it) } }.build().toString()
        } else entry.url
        return entry.copy(url = url, description = info?.string("description").orEmpty().ifBlank { entry.description },
            logo = info?.string("movie_image").orEmpty().ifBlank { entry.logo })
    }

    suspend fun shortEpg(config: SourceConfig, entry: MediaEntry): List<Programme> {
        val data = api(config, "get_short_epg", "stream_id", entry.providerId) as? JsonObject
            ?: throw ProviderException("Invalid Xtream programme response")
        val rows = data["epg_listings"] as? JsonArray ?: return emptyList()
        fun decode(text: String): String = runCatching {
            String(Base64.getDecoder().decode(text), Charsets.UTF_8)
        }.getOrDefault(text)
        return rows.objects().mapNotNull { row ->
            val start = row.string("start_timestamp").toLongOrNull() ?: return@mapNotNull null
            val end = row.string("stop_timestamp").ifBlank { row.string("end_timestamp") }.toLongOrNull() ?: return@mapNotNull null
            if (end <= start) return@mapNotNull null
            Programme(entry.epgId.ifBlank { entry.providerId }, decode(row.string("title")),
                start * 1000, end * 1000, decode(row.string("description")))
        }
    }

    private suspend fun api(config: SourceConfig, action: String, key: String, id: String): JsonElement {
        if (key.isNotBlank() && id.isBlank()) throw ProviderException("Provider identifier is missing")
        val input = httpUrl(config.url)
        val url = input.newBuilder().encodedPath("/").query(null).fragment(null)
            .apply { XtreamAddresses.nativeBaseSegments(input.pathSegments).forEach { addPathSegment(it) } }
            .addPathSegment("player_api.php")
            .addQueryParameter("username", config.username).addQueryParameter("password", config.password)
            .apply { if (action.isNotBlank()) addQueryParameter("action", action); if (key.isNotBlank()) addQueryParameter(key, id) }.build().toString()
        return parseProviderJson(http.get(url, config.headers).text())
    }

    private fun source(config: SourceConfig) = XtreamSource(config.id, config.username, config.password)
    private fun addresses(config: SourceConfig): XtreamAddresses {
        val input = httpUrl(config.url)
        val base = input.newBuilder().encodedPath("/").query(null).fragment(null).apply {
            XtreamAddresses.nativeBaseSegments(input.pathSegments).forEach { addPathSegment(it) }
        }.build()
        return XtreamAddresses(source(config), { path, query -> base.newBuilder().apply {
            path.forEach { addPathSegment(it) }; query.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build().toString() }, { value ->
            HttpUrl.Builder().scheme("https").host("template.invalid").addPathSegment(value).build().encodedPath.removePrefix("/")
        })
    }
    private fun item(entry: XtreamItem, config: SourceConfig, zone: String): MediaEntry {
        val kind = when (entry.kind) { "vod" -> MediaKind.MOVIE; "series" -> MediaKind.SERIES; "episode" -> MediaKind.EPISODE; else -> MediaKind.LIVE }
        return MediaEntry(entry.id, config.id, entry.name, entry.url, kind, group = entry.group, logo = entry.logo,
            epgId = entry.epgId, headers = config.headers,
            catchup = entry.archiveDays?.let { Catchup("xtream", entry.archiveSource, it, zone) },
            description = entry.description, season = entry.season.toIntOrNull(), episode = entry.episode?.toInt(),
            providerId = entry.providerId, headerOrigins = headerOrigins(config.headers, config.url),
            nameMessage = if (entry.generatedName.isEmpty()) null else CoreMessage(
                if (kind == MediaKind.EPISODE) CoreMessageKey.EPISODE else CoreMessageKey.UNTITLED, listOf(entry.generatedName)),
            groupMessage = if (entry.generatedGroup) CoreMessage(CoreMessageKey.OTHER_GROUP) else null)
    }
    private suspend fun <T> translateFailure(block: suspend () -> T): T = try { block() } catch (failure: XtreamFailure) {
        throw ProviderException(when (failure.code) {
            "ACCOUNT_FORMAT" -> "Invalid Xtream account response"
            "AUTH" -> "Xtream rejected the username or password"
            "INACTIVE" -> "Xtream account is not active"
            "CATEGORY_FORMAT" -> "Xtream categories has an unexpected format"
            "CATALOG_FORMAT" -> "Xtream catalogue has an unexpected format"
            "CATALOG_LIMIT" -> "Xtream catalogue contains more than 100,000 entries"
            "SERIES_ID" -> "Series identifier is missing"
            "SERIES_FORMAT" -> "Invalid Xtream series response"
            "EPISODES_FORMAT" -> "Xtream returned no episode list"
            "EPISODES_LIMIT" -> "Series contains too many episodes"
            else -> "Provider returned an unsupported response"
        })
    }
}


/**
 * Some Xtream-compatible servers return episodes as season objects or as a flat
 * array rather than the canonical map keyed by season number. Adapt only
 * explicitly identified episodes; never infer numbers, URLs or series matches.
 */
internal fun normalizeXtreamEpisodeRows(payload: JsonElement): JsonElement {
    val root = payload as? JsonObject ?: return payload
    val raw = root["episodes"]
    if (raw is JsonObject && raw.isNotEmpty()) return payload

    val rows = when {
        raw is JsonArray && raw.isNotEmpty() -> raw
        raw == null || raw is JsonArray || (raw is JsonObject && raw.isEmpty()) -> {
            val seasons = root["seasons"] as? JsonArray ?: return payload
            if (seasons.none { (it as? JsonObject)?.get("episodes") is JsonArray }) return payload
            seasons
        }
        else -> return payload
    }
    val grouped = linkedMapOf<String, MutableList<JsonElement>>()
    fun invalid(): Nothing = throw ProviderException(
        "Invalid Xtream series response", failure = ProviderFailure.INVALID_RESPONSE)
    fun season(row: JsonObject): String =
        listOf("season_number", "season").firstNotNullOfOrNull { key ->
            (row[key] as? JsonPrimitive)?.content?.toIntOrNull()
                ?.takeIf { it in 0..999 }?.toString()
        } ?: invalid()
    fun append(season: String, episode: JsonElement) {
        val item = episode as? JsonObject ?: invalid()
        val id = (item["id"] as? JsonPrimitive)?.content.orEmpty()
        val number = listOf("episode_num", "episode").firstNotNullOfOrNull { key ->
            (item[key] as? JsonPrimitive)?.content?.toIntOrNull()?.takeIf { it > 0 }
        }
        if (id.isBlank() || number == null) invalid()
        grouped.getOrPut(season) { mutableListOf() }.add(item)
    }
    for (row in rows) {
        val record = row as? JsonObject ?: invalid()
        val key = season(record)
        when (val nested = record["episodes"]) {
            is JsonArray -> nested.forEach { append(key, it) }
            null -> append(key, record)
            else -> invalid()
        }
    }
    return JsonObject(root.toMutableMap().apply {
        this["episodes"] = JsonObject(grouped.mapValues { JsonArray(it.value) })
    })
}
