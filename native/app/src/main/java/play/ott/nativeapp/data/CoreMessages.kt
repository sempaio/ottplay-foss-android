package play.ott.nativeapp.data

import android.content.Context
import play.ott.nativeapp.i18n.AppLanguages
import play.ott.nativeapp.R
import play.ott.nativeapp.core.CoreMessage
import play.ott.nativeapp.core.CoreMessageKey
import java.util.IllegalFormatException

/** Serialized metadata must satisfy the formatter's arity and enum/number domains. */
internal fun CoreMessage.hasValidArguments(): Boolean {
    val count = when (key) {
        CoreMessageKey.UNTITLED, CoreMessageKey.EPISODE, CoreMessageKey.CHANNEL, CoreMessageKey.STREAM,
        CoreMessageKey.LEGACY_PLAYLIST_UNSUPPORTED, CoreMessageKey.LEGACY_LIBRARY_SKIPPED,
        CoreMessageKey.IMPORTED_PLAYLIST, CoreMessageKey.LOCAL_PLAYLIST_RESELECT -> 1
        CoreMessageKey.XTREAM_SECTION_UNAVAILABLE, CoreMessageKey.XTREAM_GROUPS_UNAVAILABLE -> 2
        else -> 0
    }
    if (args.size != count || args.any { it.isBlank() }) return false
    return when (key) {
        CoreMessageKey.UNTITLED -> args[0] in setOf("live", "vod", "series")
        CoreMessageKey.XTREAM_SECTION_UNAVAILABLE, CoreMessageKey.XTREAM_GROUPS_UNAVAILABLE ->
            args[0] in setOf("LIVE", "MOVIE", "SERIES") && args[1].toIntOrNull()?.let { it in 100..599 } == true
        CoreMessageKey.STREAM, CoreMessageKey.LEGACY_PLAYLIST_UNSUPPORTED,
        CoreMessageKey.LEGACY_LIBRARY_SKIPPED, CoreMessageKey.IMPORTED_PLAYLIST ->
            args[0].toIntOrNull()?.let { it > 0 } == true
        else -> true
    }
}

internal fun CoreMessage.isValidSourceName(): Boolean = hasValidArguments() && key in setOf(
    CoreMessageKey.DEMO_SOURCE, CoreMessageKey.IMPORTED_PLAYLIST,
    CoreMessageKey.IMPORTED_XTREAM, CoreMessageKey.IMPORTED_STALKER,
)

/** Rendering is instance-local; core/cache metadata never depends on a process-wide locale. */
internal fun CoreMessage.localized(context: Context, fallback: String = english()): String {
    if (!hasValidArguments()) return fallback
    val localized = AppLanguages.localizedContext(context)
    val resource = when (key) {
        CoreMessageKey.DEMO_SOURCE -> R.string.message_demo_source
        CoreMessageKey.UNTITLED -> R.string.message_core_untitled
        CoreMessageKey.EPISODE -> R.string.message_core_episode
        CoreMessageKey.OTHER_GROUP -> R.string.message_core_other_group
        CoreMessageKey.CHANNEL -> R.string.message_core_channel
        CoreMessageKey.STREAM -> R.string.message_core_stream
        CoreMessageKey.HLS_STREAM -> R.string.message_core_hls_stream

        CoreMessageKey.XTREAM_SECTION_UNAVAILABLE -> R.string.message_core_xtream_section_unavailable
        CoreMessageKey.XTREAM_GROUPS_UNAVAILABLE -> R.string.message_core_xtream_groups_unavailable
        CoreMessageKey.DRM_UNSUPPORTED -> R.string.message_core_drm_unsupported
        CoreMessageKey.HEADER_ORIGIN_REFRESH -> R.string.message_core_header_origin_refresh
        CoreMessageKey.LEGACY_PLAYLIST_UNSUPPORTED -> R.string.message_core_legacy_playlist_unsupported
        CoreMessageKey.LEGACY_LIBRARY_SKIPPED -> R.string.message_core_legacy_library_skipped
        CoreMessageKey.LEGACY_XTREAM_INVALID -> R.string.message_core_legacy_xtream_invalid
        CoreMessageKey.LEGACY_STALKER_INVALID -> R.string.message_core_legacy_stalker_invalid
        CoreMessageKey.LEGACY_BACKUP_WITHOUT_SOURCES -> R.string.message_core_legacy_backup_without_sources
        CoreMessageKey.LEGACY_NO_SOURCES -> R.string.message_core_legacy_no_sources
        CoreMessageKey.LEGACY_FAVORITES_SKIPPED -> R.string.message_core_legacy_favorites_skipped
        CoreMessageKey.IMPORTED_PLAYLIST -> R.string.message_core_imported_playlist
        CoreMessageKey.IMPORTED_XTREAM -> R.string.message_core_imported_xtream
        CoreMessageKey.IMPORTED_STALKER -> R.string.message_core_imported_stalker
        CoreMessageKey.LOCAL_PLAYLIST_RESELECT -> R.string.message_core_local_playlist_reselect
    }
    val values = args.toMutableList()
    if (key == CoreMessageKey.XTREAM_SECTION_UNAVAILABLE || key == CoreMessageKey.XTREAM_GROUPS_UNAVAILABLE) {
        val section = when (values.getOrNull(0)) {
            "LIVE" -> R.string.message_section_live
            "MOVIE" -> R.string.message_section_movie
            else -> R.string.message_section_series
        }
        while (values.size < 2) values += ""
        values[0] = localized.getString(section)
    }
    if (key == CoreMessageKey.UNTITLED && values.isNotEmpty()) {
        val section = when (values[0]) { "live" -> R.string.message_section_live; "vod" -> R.string.message_section_movie; else -> R.string.message_section_series }
        values[0] = localized.getString(section)
    }
    return try { localized.getString(resource, *values.toTypedArray()) }
    catch (_: IllegalFormatException) { fallback }
}
