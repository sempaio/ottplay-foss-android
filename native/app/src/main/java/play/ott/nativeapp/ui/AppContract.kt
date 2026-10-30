package play.ott.nativeapp.ui

import play.ott.nativeapp.core.MediaEntry
import play.ott.nativeapp.core.Programme
import play.ott.nativeapp.core.SourceConfig
import play.ott.nativeapp.data.TrackPreference
import play.ott.nativeapp.data.ForYouProfile

/** A presentation snapshot. Network, persistence and playback ownership stay outside Compose. */
data class AppUiState(
    val sourceSaveRevision: Int = 0,
    val sources: List<SourceConfig> = emptyList(),
    val selectedSourceId: String? = null,
    val entries: List<MediaEntry> = emptyList(),
    val updateAvailable: Boolean = false,
    val checkingUpdate: Boolean = false,
    val recent: List<play.ott.nativeapp.data.RecentPlayback> = emptyList(),
    val favoriteIds: Set<String> = emptySet(),
    val forYouProfile: ForYouProfile = ForYouProfile(),
    val activeForYouProfileId: String = "default",
    val forYouRecentlyViewedKeys: Set<String> = emptySet(),
    val epgEntry: MediaEntry? = null,
    val programmes: List<Programme> = emptyList(),
    val movieEntry: MediaEntry? = null,
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
    data object Logout : AppAction
    data object CheckUpdate : AppAction
    data object InstallUpdate : AppAction
    data object Refresh : AppAction
    data class ToggleFavorite(val entry: MediaEntry) : AppAction
    data class SaveForYou(val interests: Set<String>, val allowViewingSignals: Boolean) : AppAction
    data object SkipForYou : AppAction
    data class VoteForYou(val entry: MediaEntry, val interested: Boolean) : AppAction
    data object ResetForYou : AppAction
    data class SwitchForYouProfile(val id: String) : AppAction
    data class Play(val entry: MediaEntry) : AppAction
    data class OpenEpg(val entry: MediaEntry) : AppAction
    data class PlayProgramme(val entry: MediaEntry, val programme: Programme) : AppAction
    data class OpenMovie(val entry: MediaEntry) : AppAction
    data object CloseMovie : AppAction
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
