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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

/** HTTP, URL codec and native model adapter for the common Xtream session. */
internal class XtreamProvider(private val http: ProviderHttp) {
    suspend fun load(config: SourceConfig): Catalog = translateFailure {
        val addresses = addresses(config)
        val load = XtreamLoad(source(config), addresses, { resolveHttp(config.url, it) }, { stableId(*it.toTypedArray()) })
        val session = load.session
        while (true) {
            val request = session.request ?: break
            try {
                val url = addresses.api(request)
                val payload = getWithTimeoutRecovery(url, config.headers)
                session.accept(parseProviderJson(payload.text()).toProviderValue())
            }
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

    /** Fetch the three compact category lists in parallel; no complete streams request. */
    suspend fun categories(config: SourceConfig): List<XtreamGroup> = translateFailure {
        authenticate(config)
        val types = listOf(
            MediaKind.LIVE to "get_live_categories",
            MediaKind.MOVIE to "get_vod_categories",
            MediaKind.SERIES to "get_series_categories",
        )
        coroutineScope {
            types.map { (kind, action) ->
                async {
                    val rows = api(config, action, "", "") as? JsonArray
                        ?: throw ProviderException("Xtream category response has an unexpected format")
                    if (rows.size > 10000) throw ProviderException("Xtream category response is too large")
                    rows.objects().mapNotNull { row ->
                        val id = row.string("category_id").trim()
                        if (id.isBlank()) null else XtreamGroup(
                            id, row.string("category_name").ifBlank { "Sem categoria" }, kind,
                        )
                    }
                }
            }.awaitAll().flatten()
        }.distinctBy { it.kind to it.id }
    }

    /** One selected group per request; never pull the entire catalog on startup. */
    suspend fun category(config: SourceConfig, group: XtreamGroup): Catalog = translateFailure {
        val action = when (group.kind) {
            MediaKind.LIVE -> "get_live_streams"
            MediaKind.MOVIE -> "get_vod_streams"
            MediaKind.SERIES -> "get_series"
            else -> throw ProviderException("Unsupported Xtream category kind")
        }
        if (group.id.isBlank()) throw ProviderException("Missing Xtream category identifier")
        val rows = api(config, action, "category_id", group.id) as? JsonArray
            ?: throw ProviderException("Xtream streams response has an unexpected format")
        if (rows.size > 100000) throw ProviderException("Xtream category exceeds the safety limit")
        val routes = addresses(config)
        val entries = rows.objects().mapNotNull { row ->
            val providerId = row.string(if (group.kind == MediaKind.SERIES) "series_id" else "stream_id")
            if (providerId.isBlank() || !providerId.all(Char::isDigit)) return@mapNotNull null
            val name = row.string("name").ifBlank { "Conteudo $providerId" }
            val extension = row.string("container_extension").lowercase().takeIf {
                it.matches(Regex("[a-z0-9]{1,10}"))
            } ?: if (group.kind == MediaKind.MOVIE) "mp4" else "ts"
            val pathKind = when (group.kind) {
                MediaKind.LIVE -> "live"
                MediaKind.MOVIE -> "movie"
                else -> "series"
            }
            val url = if (group.kind == MediaKind.SERIES) "" else routes.stream(pathKind, providerId, extension)
            MediaEntry(
                id = stableId(config.id, "xtream", pathKind, providerId),
                sourceId = config.id, name = name, url = url,
                kind = group.kind, group = group.name,
                logo = ArtworkUrls.normalizeUrl(
                    row.string(if (group.kind == MediaKind.SERIES) "cover" else "stream_icon")
                ),
                epgId = row.string("epg_channel_id"),
                providerId = providerId, headers = config.headers,
                headerOrigins = headerOrigins(config.headers, config.url),
            )
        }
        val filtered = if (group.kind == MediaKind.LIVE) entries else withoutSharedArtwork(entries)
        Catalog(filtered, listOf(config.epgUrl.ifBlank { routes.epg() }))
    }

    /** Full-catalog remote search across live channels, movies and series in a single request. */
    suspend fun search(config: SourceConfig, query: String, limitPerKind: Int = 60): List<MediaEntry> = translateFailure {
        val cleaned = query.trim()
        if (cleaned.length < 2) return@translateFailure emptyList()
        val input = httpUrl(config.url)
        val url = input.newBuilder().encodedPath("/").query(null).fragment(null)
            .apply { XtreamAddresses.nativeBaseSegments(input.pathSegments).forEach { addPathSegment(it) } }
            .addPathSegment("player_api.php")
            .addQueryParameter("username", config.username)
            .addQueryParameter("password", config.password)
            .addQueryParameter("action", "search_catalog")
            .addQueryParameter("q", cleaned)
            .addQueryParameter("limit", limitPerKind.coerceIn(1, 200).toString())
            .build().toString()
        val root = parseProviderJson(getWithTimeoutRecovery(url, config.headers).text()) as? JsonObject
            ?: return@translateFailure emptyList()
        val routes = addresses(config)
        val categoriesObj = root["categories"] as? JsonObject

        fun parseBucket(key: String, kind: MediaKind, pathKind: String): List<MediaEntry> {
            val rows = root[key] as? JsonArray ?: return emptyList()
            val catMap = (categoriesObj?.get(key) as? JsonObject)
                ?.mapValues { (_, v) -> (v as? JsonPrimitive)?.content.orEmpty() }
                .orEmpty()
            return rows.objects().mapNotNull { row ->
                val providerId = row.string(if (kind == MediaKind.SERIES) "series_id" else "stream_id")
                if (providerId.isBlank() || !providerId.all(Char::isDigit)) return@mapNotNull null
                val name = row.string("name").ifBlank { "Conteudo $providerId" }
                val extension = row.string("container_extension").lowercase().takeIf {
                    it.matches(Regex("[a-z0-9]{1,10}"))
                } ?: if (kind == MediaKind.MOVIE) "mp4" else "ts"
                val streamUrl = if (kind == MediaKind.SERIES) "" else routes.stream(pathKind, providerId, extension)
                val catId = row.string("category_id")
                val groupName = catMap[catId].orEmpty().ifBlank {
                    when (kind) {
                        MediaKind.LIVE -> "Ao vivo"
                        MediaKind.MOVIE -> "Filmes"
                        else -> "Séries"
                    }
                }
                MediaEntry(
                    id = stableId(config.id, "xtream", pathKind, providerId),
                    sourceId = config.id,
                    name = name,
                    url = streamUrl,
                    kind = kind,
                    group = groupName,
                    logo = ArtworkUrls.normalizeUrl(
                        row.string(if (kind == MediaKind.SERIES) "cover" else "stream_icon")
                    ),
                    epgId = row.string("epg_channel_id"),
                    providerId = providerId,
                    headers = config.headers,
                    headerOrigins = headerOrigins(config.headers, config.url),
                )
            }
        }

        val live = parseBucket("live", MediaKind.LIVE, "live")
        val movies = withoutSharedArtwork(parseBucket("vod", MediaKind.MOVIE, "movie"))
        val series = withoutSharedArtwork(parseBucket("series", MediaKind.SERIES, "series"))
        (live + movies + series).distinctBy { it.sourceId to it.id }
    }

    suspend fun episodes(config: SourceConfig, series: MediaEntry): List<MediaEntry> = translateFailure {
        val id = series.providerId
        val addresses = addresses(config)
        val request = XtreamCatalogs.seriesRequest(ProviderValue.text(id), "", XtreamFormat.ANDROID)
        val data = parseProviderJson(getWithTimeoutRecovery(addresses.api(request), config.headers).text()).toProviderValue()
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
        var data: JsonObject? = null
        // M81 returns quickly while its bounded TMDB lookup continues in the background.
        // Poll only when the server explicitly says that metadata is still pending.
        for (attempt in 0..3) {
            data = try { api(config, "get_vod_info", "vod_id", entry.providerId) as? JsonObject }
                catch (error: ProviderException) { if (error.statusCode in listOf(404, 405, 501)) return entry else throw error }
            val pending = (data?.get("info") as? JsonObject)?.get("metadata_pending")?.jsonPrimitive?.booleanOrNull == true
            if (!pending || attempt == 3) break
            delay(1000)
        }
        val result = data ?: return entry
        val info = result["info"] as? JsonObject
        val movie = result["movie_data"] as? JsonObject
        val extension = movie?.string("container_extension").orEmpty()
        // Never assume a server transcodes: use the advertised container, if valid.
        val url = if (extension.matches(Regex("[A-Za-z0-9]{1,10}"))) {
            val parsed = httpUrl(entry.url)
            val segments = parsed.pathSegments.toMutableList()
            segments[segments.lastIndex] = segments.last().substringBeforeLast('.') + "." + extension
            parsed.newBuilder().encodedPath("/").apply { segments.forEach { addPathSegment(it) } }.build().toString()
        } else entry.url
        val image = ArtworkUrls.normalizeUrl(
            info?.string("movie_image").orEmpty().ifBlank { info?.string("poster_url").orEmpty() }
        )
        val trustedPoster = ArtworkUrls.isTrustedDetailPoster(
            image,
            info?.string("poster_source").orEmpty(),
            info?.string("metadata_source").orEmpty(),
        )
        val existingLogo = ArtworkUrls.normalizeUrl(entry.logo)
        val resolvedLogo = when {
            image.isNotBlank() && trustedPoster -> image
            existingLogo.isNotBlank() -> image.ifBlank { existingLogo }
            else -> ""
        }
        return entry.copy(
            url = url,
            description = info?.string("description").orEmpty().ifBlank { info?.string("plot").orEmpty() }.ifBlank { entry.description },
            logo = resolvedLogo,
        )
    }

    suspend fun seriesInfo(config: SourceConfig, entry: MediaEntry): MediaEntry {
        var data: JsonObject? = null
        for (attempt in 0..3) {
            data = api(config, "get_series_info", "series_id", entry.providerId) as? JsonObject
            val pending = (data?.get("info") as? JsonObject)?.get("metadata_pending")?.jsonPrimitive?.booleanOrNull == true
            if (!pending || attempt == 3) break
            delay(1000)
        }
        val info = data?.get("info") as? JsonObject ?: return entry
        val image = ArtworkUrls.normalizeUrl(
            info.string("cover").ifBlank { info.string("poster_url") }
        )
        val trustedPoster = ArtworkUrls.isTrustedDetailPoster(
            image,
            info.string("poster_source"),
            info.string("metadata_source"),
        )
        val existingLogo = ArtworkUrls.normalizeUrl(entry.logo)
        val resolvedLogo = when {
            image.isNotBlank() && trustedPoster -> image
            existingLogo.isNotBlank() -> image.ifBlank { existingLogo }
            else -> ""
        }
        return entry.copy(
            description = info.string("plot").ifBlank { info.string("description") }.ifBlank { entry.description },
            logo = resolvedLogo,
        )
    }

    /**
     * A VOD/series poster reused across distinct titles is not a trustworthy cover.
     * Live channels are intentionally excluded because regional affiliates and numbered
     * feeds (e.g. Globo SP/RJ, ESPN 1/2/3, Premiere 1..8) legitimately share one brand logo.
     */
    internal fun withoutSharedArtwork(entries: List<MediaEntry>): List<MediaEntry> {
        val titlesByLogo = entries.asSequence()
            .filter { it.kind != MediaKind.LIVE && it.logo.isNotBlank() }
            .groupBy({ it.logo }, { ArtworkUrls.normalizeTitleForArtworkDedupe(it.name) })
        val shared = titlesByLogo.filterValues { names -> names.distinct().size > 1 }.keys
        if (shared.isEmpty()) return entries
        return entries.map { if (it.kind != MediaKind.LIVE && it.logo in shared) it.copy(logo = "") else it }
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
        return parseProviderJson(getWithTimeoutRecovery(url, config.headers).text())
    }

    /** Read-only Xtream metadata only: retry one transport timeout, never media or HTTP/TLS errors. */
    private suspend fun getWithTimeoutRecovery(url: String, headers: Map<String, String>): HttpPayload =
        try { http.get(url, headers) }
        catch (error: ProviderException) {
            if (error.statusCode != null || error.failure != ProviderFailure.TIMEOUT) throw error
            delay(500) // Cancellable: leaving the detail/player must not start another request.
            http.getOnAlternateRoute(url, headers)
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
        return MediaEntry(entry.id, config.id, entry.name, entry.url, kind, group = entry.group, logo = ArtworkUrls.normalizeUrl(entry.logo),
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
