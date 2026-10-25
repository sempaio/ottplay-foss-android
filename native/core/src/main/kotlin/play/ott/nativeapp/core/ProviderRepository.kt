package play.ott.nativeapp.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import play.ott.core.OperatorSources

/** Network/catalog operations run off the main thread; cancellation cancels the active HTTP call. */
class ProviderRepository(
    client: OkHttpClient = ProviderHttp.defaultClient(),
    transportPolicy: RemoteTransportPolicy = RemoteTransportPolicy.HTTP_COMPATIBLE,
) {
    private val http = ProviderHttp(client, transportPolicy)
    private val xtream = XtreamProvider(http)
    private val stalker = StalkerProvider(http)

    // Only the most recently opened movie is retained, briefly, in this repository instance.
    // Do not persist Xtream URLs/credentials or share them across accounts and sources.
    private data class MovieDetail(
        val source: SourceConfig,
        val original: MediaEntry,
        val enriched: MediaEntry,
        val loadedAtNanos: Long,
    )
    @Volatile private var recentMovieDetail: MovieDetail? = null

    /** Called on source edits/removal; the refresh path also invalidates before loading. */
    fun invalidateMovieDetails(sourceId: String) = synchronized(this) {
        if (recentMovieDetail?.source?.id == sourceId) recentMovieDetail = null
    }

    private fun takeMovieDetail(source: SourceConfig, entry: MediaEntry): MediaEntry? = synchronized(this) {
        val saved = recentMovieDetail ?: return@synchronized null
        val elapsed = System.nanoTime() - saved.loadedAtNanos
        if (elapsed < 0 || elapsed >= MOVIE_DETAIL_TTL_NANOS) {
            recentMovieDetail = null
            return@synchronized null
        }
        if (saved.source != source || entry.kind != MediaKind.MOVIE ||
            entry.id != saved.original.id || entry.sourceId != saved.original.sourceId ||
            entry.providerId != saved.original.providerId ||
            (entry.url != saved.original.url && entry.url != saved.enriched.url)) return@synchronized null
        // Consume once so a later playback re-resolves a potentially expiring media URL.
        recentMovieDetail = null
        saved.enriched
    }

    private companion object {
        const val MOVIE_DETAIL_TTL_NANOS = 30_000_000_000L
    }

    suspend fun authenticate(config: SourceConfig) = withContext(Dispatchers.IO) {
        validate(config)
        require(config.kind == SourceKind.XTREAM)
        xtream.authenticate(config)
    }

    suspend fun load(config: SourceConfig): Catalog = withContext(Dispatchers.IO) {
        invalidateMovieDetails(config.id)
        validate(config)
        when (config.kind) {
            SourceKind.M3U -> {
                val body = http.get(config.url, config.headers)
                M3uParser.parse(body.text(), config, body.url)
            }
            SourceKind.XTREAM -> xtream.load(config)
            SourceKind.STALKER -> stalker.load(config)
        }
    }

    suspend fun resolve(config: SourceConfig, entry: MediaEntry): PlaybackStream = withContext(Dispatchers.IO) {
        // A locally imported M3U has a content:// configuration URL; its individual streams
        // are still HTTP(S). NativeRepository owns opening local files, not this HTTP layer.
        if (config.kind != SourceKind.M3U) validate(config)
        when (OperatorSources.relationship("android-resolve", config.kind.name.lowercase(), config.id, entry.kind.name.lowercase(), entry.sourceId)) {
            "SOURCE_MISMATCH" -> throw ProviderException("This item belongs to another source")
            "FOLDER_REQUIRED" -> throw ProviderException("Choose an episode before playing a series")
        }
        val stream = if (config.kind == SourceKind.STALKER) stalker.resolve(config, entry) else {
            val resolved = if (config.kind == SourceKind.XTREAM && entry.kind == MediaKind.MOVIE)
                takeMovieDetail(config, entry) ?: xtream.vod(config, entry)
            else entry
            val url = http.checkedUrl(resolved.url).toString()
            PlaybackStream(url, mimeType = inferMimeType(url))
        }
        http.checkedUrl(stream.url)
        stream.copy(headers = playbackHeaders(config, entry, stream.url))
    }

    suspend fun loadMovieInfo(config: SourceConfig, entry: MediaEntry): MediaEntry = withContext(Dispatchers.IO) {
        require(config.id == entry.sourceId && entry.kind == MediaKind.MOVIE)
        if (config.kind == SourceKind.XTREAM && entry.providerId.isNotBlank()) {
            val detail = xtream.vod(config, entry)
            // An HTTP error/unsupported detail endpoint returns the original entry:
            // only a genuinely enriched result is safe to reuse as a successful lookup.
            synchronized(this@ProviderRepository) {
                recentMovieDetail = if (detail != entry) MovieDetail(config, entry, detail, System.nanoTime()) else null
            }
            detail
        } else entry
    }

    suspend fun loadEpisodes(config: SourceConfig, series: MediaEntry): List<MediaEntry> = withContext(Dispatchers.IO) {
        validate(config)
        if (OperatorSources.relationship("android-episodes", config.kind.name.lowercase(), config.id, series.kind.name.lowercase(), series.sourceId).isNotEmpty())
            throw ProviderException("Episode lists require a series from this Xtream source")
        xtream.episodes(config, series)
    }

    fun epgSources(config: SourceConfig, catalog: Catalog): List<String> =
        play.ott.core.NativeGuideSources.urls(catalog.epgUrls, config.epgUrl, false, play.ott.core.NativeSourceFormat.ANDROID_RAW)

    suspend fun loadShortEpg(config: SourceConfig, entry: MediaEntry): List<Programme> = withContext(Dispatchers.IO) {
        validate(config)
        require(config.id == entry.sourceId && config.kind == SourceKind.XTREAM && entry.kind == MediaKind.LIVE)
        xtream.shortEpg(config, entry)
    }

    suspend fun loadEpg(url: String, headers: Map<String, String> = emptyMap()): List<Programme> = withContext(Dispatchers.IO) {
        XmltvParser.parse(http.get(url, headers, MAX_EPG_BYTES).bytes)
    }

    private fun validate(config: SourceConfig) {
        if (!OperatorSources.validId(config.id, true)) throw ProviderException("Source identifier is required")
        http.checkedUrl(config.url)
        mergedHeaders(config.headers)
        when (OperatorSources.credentials(config.kind.name.lowercase(), config.username, config.password, config.mac, true)) {
            "SOURCE_CREDENTIALS" -> throw ProviderException("Xtream username and password are required")
            "STALKER_MAC" -> throw ProviderException("Enter a MAC address in 00:1A:79:00:00:00 format")
        }
    }
}

private val providerJson = Json { ignoreUnknownKeys = true }
internal fun parseProviderJson(text: String): JsonElement = try { providerJson.parseToJsonElement(text) }
    catch (_: Exception) { throw ProviderException("Provider returned invalid JSON", failure = ProviderFailure.INVALID_RESPONSE) }
internal fun JsonObject.string(key: String): String = (get(key) as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content.orEmpty()
internal fun JsonObject.int(key: String): Int? = string(key).toIntOrNull()
internal fun JsonArray.objects(): List<JsonObject> = filterIsInstance<JsonObject>()
