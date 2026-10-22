package play.ott.nativeapp.core

import kotlinx.serialization.Serializable

@Serializable
enum class SourceKind { M3U, XTREAM, STALKER }

@Serializable
enum class MediaKind { LIVE, MOVIE, SERIES, EPISODE }

/** Credentials are configuration, never diagnostics. Callers should protect persisted configs. */
@Serializable
data class SourceConfig(
    val id: String,
    val name: String,
    val kind: SourceKind,
    val url: String,
    val username: String = "",
    val password: String = "",
    val mac: String = "",
    val epgUrl: String = "",
    val headers: Map<String, String> = emptyMap(),
    val catchupDaysFallback: Double = 0.0,
    val nameMessage: CoreMessage? = null,
    /** Explicit edits must not be mistaken for a historical generated demo label. */
    val nameIsUserDefined: Boolean = false,
) {
    override fun toString(): String = "SourceConfig(id=$id, kind=$kind)"
}

@Serializable
data class Catchup(
    val mode: String = "default",
    val source: String = "",
    val days: Double = 0.0,
    val timeZone: String = "UTC",
)

@Serializable
data class MediaEntry(
    val id: String,
    val sourceId: String,
    val name: String,
    val url: String = "",
    val kind: MediaKind = MediaKind.LIVE,
    val group: String = "",
    val logo: String = "",
    val epgId: String = "",
    val headers: Map<String, String> = emptyMap(),
    val catchup: Catchup? = null,
    val description: String = "",
    val season: Int? = null,
    val episode: Int? = null,
    val providerId: String = "",
    val drm: DrmConfig? = null,
    val mimeType: String? = null,
    val playbackUnsupportedReason: String? = null,
    /** Lowercase header names mapped to allowed origins; null identifies a legacy snapshot. */
    val headerOrigins: Map<String, String>? = null,
    val nameMessage: CoreMessage? = null,
    val groupMessage: CoreMessage? = null,
) {
    override fun toString(): String = "MediaEntry(id=$id, sourceId=$sourceId, kind=$kind)"
}

typealias Channel = MediaEntry

@Serializable
data class Catalog(
    val entries: List<MediaEntry>,
    val epgUrls: List<String> = emptyList(),
    val notes: List<String> = emptyList(),
    val messages: List<CoreMessage> = emptyList(),
)

@Serializable
data class PlaybackStream(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val mimeType: String? = null,
) {
    override fun toString(): String = "PlaybackStream(mimeType=$mimeType)"
}

@Serializable
data class Programme(
    val channelId: String,
    val title: String,
    val startMillis: Long,
    val endMillis: Long,
    val description: String = "",
)

/** Messages deliberately exclude URLs, which commonly contain provider credentials. */
class ProviderException(message: String, val statusCode: Int? = null) : Exception(message)
