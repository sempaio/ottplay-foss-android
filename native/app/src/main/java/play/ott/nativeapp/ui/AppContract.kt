package play.ott.nativeapp.ui

import play.ott.nativeapp.core.MediaEntry
import play.ott.nativeapp.core.Programme
import play.ott.nativeapp.core.SourceConfig
import play.ott.nativeapp.data.TrackPreference

/** A presentation snapshot. Network, persistence and playback ownership stay outside Compose. */
data class AppUiState(
    val sources: List<SourceConfig> = emptyList(),
    val selectedSourceId: String? = null,
    val entries: List<MediaEntry> = emptyList(),
    val favoriteIds: Set<String> = emptySet(),
    val epgEntry: MediaEntry? = null,
    val programmes: List<Programme> = emptyList(),
    val seriesEntry: MediaEntry? = null,
    val episodes: List<MediaEntry> = emptyList(),
    val playingEntry: MediaEntry? = null,
    val isBusy: Boolean = false,
    val loadingMessage: String = "",
    val error: String? = null,
    val notice: String? = null,
    val backgroundPlayback: Boolean = true,
)

sealed interface AppAction {
    data class SelectSource(val sourceId: String) : AppAction
    data class SaveSource(val source: SourceConfig) : AppAction
    data class DeleteSource(val sourceId: String) : AppAction
    data object Refresh : AppAction
    data class ToggleFavorite(val entry: MediaEntry) : AppAction
    data class Play(val entry: MediaEntry) : AppAction
    data class OpenEpg(val entry: MediaEntry) : AppAction
    data class PlayProgramme(val entry: MediaEntry, val programme: Programme) : AppAction
    data class OpenSeries(val entry: MediaEntry) : AppAction
    data object CloseEpg : AppAction
    data object CloseSeries : AppAction
    data object DismissError : AppAction
    data object DismissNotice : AppAction
    data object StopPlayback : AppAction
    data object ImportPlaylist : AppAction
    data object ExportSettings : AppAction
    data object ImportSettings : AppAction
    data object AddDemo : AppAction
    data class SetBackgroundPlayback(val enabled: Boolean) : AppAction
    data class SetTrackPreference(val audio: Boolean, val preference: TrackPreference) : AppAction
}
