package play.ott.nativeapp.core

import kotlinx.serialization.Serializable

/** Generated text retains its identity across cache storage and language changes. */
@Serializable
enum class CoreMessageKey {
    DEMO_SOURCE, UNTITLED, EPISODE, OTHER_GROUP, CHANNEL, STREAM, HLS_STREAM,
    XTREAM_SECTION_UNAVAILABLE, XTREAM_GROUPS_UNAVAILABLE, DRM_UNSUPPORTED, HEADER_ORIGIN_REFRESH,
    LEGACY_PLAYLIST_UNSUPPORTED, LEGACY_LIBRARY_SKIPPED, LEGACY_XTREAM_INVALID, LEGACY_STALKER_INVALID,
    LEGACY_BACKUP_WITHOUT_SOURCES, LEGACY_NO_SOURCES, LEGACY_FAVORITES_SKIPPED,
    IMPORTED_PLAYLIST, IMPORTED_XTREAM, IMPORTED_STALKER, LOCAL_PLAYLIST_RESELECT,
}

@Serializable
data class CoreMessage(val key: CoreMessageKey, val args: List<String> = emptyList()) {
    fun english(): String {
        val first = args.getOrElse(0) { "" }
        val section = when (first) { "LIVE" -> "TV"; "MOVIE" -> "Movies"; "SERIES" -> "Series"; else -> first }
        val status = args.getOrElse(1) { "" }
        return when (key) {
            CoreMessageKey.DEMO_SOURCE -> "Offline demo"
            CoreMessageKey.UNTITLED -> "Untitled $first"
            CoreMessageKey.EPISODE -> "Episode $first"
            CoreMessageKey.OTHER_GROUP -> "Other"
            CoreMessageKey.CHANNEL -> "Channel $first"
            CoreMessageKey.STREAM -> "Stream $first"
            CoreMessageKey.HLS_STREAM -> "HLS stream"
            CoreMessageKey.XTREAM_SECTION_UNAVAILABLE -> "$section: this section is not supported by the server (HTTP $status). Other sections were loaded."
            CoreMessageKey.XTREAM_GROUPS_UNAVAILABLE -> "$section: the server does not provide groups (HTTP $status); entries are available without provider groups."
            CoreMessageKey.DRM_UNSUPPORTED -> "Some entries use an unsupported DRM or media configuration."
            CoreMessageKey.HEADER_ORIGIN_REFRESH -> "Refresh the catalogue to verify where credentials are sent."
            CoreMessageKey.LEGACY_PLAYLIST_UNSUPPORTED -> "Playlist $first: local file or unsupported address. Import the original M3U file separately."
            CoreMessageKey.LEGACY_LIBRARY_SKIPPED -> "Playlist $first: the separate legacy media library was not imported as M3U."
            CoreMessageKey.LEGACY_XTREAM_INVALID -> "Legacy Xtream settings have missing credentials or an invalid server address."
            CoreMessageKey.LEGACY_STALKER_INVALID -> "Legacy Stalker settings have an invalid portal address or MAC identifier."
            CoreMessageKey.LEGACY_BACKUP_WITHOUT_SOURCES -> "The FOSS settings-v1 backup contains preferences and old channel IDs, but no provider addresses or credentials. Add a source or import the original M3U file."
            CoreMessageKey.LEGACY_NO_SOURCES -> "No supported M3U, Xtream or Stalker source settings were found in the file."
            CoreMessageKey.LEGACY_FAVORITES_SKIPPED -> "Old favorite and blocked channel IDs are incompatible with the new catalogue. Select those channels again after importing sources."
            CoreMessageKey.IMPORTED_PLAYLIST -> "Imported playlist $first"
            CoreMessageKey.IMPORTED_XTREAM -> "Imported Xtream"
            CoreMessageKey.IMPORTED_STALKER -> "Imported Stalker"
            CoreMessageKey.LOCAL_PLAYLIST_RESELECT -> "Select the local playlist “$first” again using the system file picker."
        }
    }
}

fun interface CoreTextResolver {
    fun resolve(message: CoreMessage): String

    companion object { val ENGLISH = CoreTextResolver { it.english() } }
}
