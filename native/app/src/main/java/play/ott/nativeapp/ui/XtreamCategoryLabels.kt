package play.ott.nativeapp.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import play.ott.nativeapp.R
import play.ott.nativeapp.core.MediaKind
import play.ott.nativeapp.core.XtreamGroup

/** Only clarify display labels. Keep raw Xtream group names and IDs unchanged. */
@Composable
internal fun xtreamGroupLabel(group: XtreamGroup): String {
    if (group.kind != MediaKind.LIVE) return group.name
    return when (group.name.trim().lowercase()) {
        "filmes" -> stringResource(R.string.starlink_live_movie_channels)
        "séries", "series" -> stringResource(R.string.starlink_live_series_channels)
        "desenhos" -> stringResource(R.string.starlink_live_cartoon_channels)
        else -> group.name
    }
}
