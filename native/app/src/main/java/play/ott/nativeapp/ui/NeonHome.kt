package play.ott.nativeapp.ui

import android.content.Intent
import android.content.res.Configuration
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import play.ott.nativeapp.R
import play.ott.nativeapp.core.MediaEntry
import play.ott.nativeapp.core.MediaKind

/** Original geometric mark, used until the supplied brand master is available as a file. */
@Composable
internal fun NeonEmblem(modifier: Modifier = Modifier) {
    coil.compose.AsyncImage(R.drawable.starlink_logo, contentDescription = "STARLINK IPTV", modifier = modifier, contentScale = ContentScale.Fit)
}

private data class HomeRail(val title: String, val kind: MediaKind, val category: String, val entries: List<MediaEntry>)

@Composable
internal fun NeonHome(state: AppUiState, onAction: (AppAction) -> Unit, initialFocus: FocusRequester, modifier: Modifier = Modifier, customerMode: Boolean = false, onBrowse: (MediaKind, String) -> Unit) {
    val live = stringResource(R.string.library_heading_live)
    val movies = stringResource(R.string.neon_featured_movies)
    val series = stringResource(R.string.neon_featured_series)
    val rails by produceState<List<HomeRail>>(emptyList(), state.entries, live, movies, series) {
        value = withContext(Dispatchers.Default) {
            val grouped = state.entries.groupBy { it.kind }
            buildList {
                listOf(MediaKind.LIVE to live, MediaKind.MOVIE to movies, MediaKind.SERIES to series).forEach { (kind, label) ->
                    val entries = grouped[kind].orEmpty()
                    if (entries.isNotEmpty()) add(HomeRail(label, kind, "", entries.take(20)))
                }
                // Bound the home preview; complete categories remain available in the virtual grid.
                state.entries.groupBy { it.kind to it.group }.entries.asSequence().filter { it.key.second.isNotBlank() }.take(12).forEach { (key, entries) ->
                    add(HomeRail(key.second, key.first, key.second, entries.take(20)))
                }
            }
        }
    }
    val hero = rails.firstOrNull { it.kind != MediaKind.LIVE }?.entries?.firstOrNull() ?: rails.firstOrNull()?.entries?.firstOrNull()
    val sourceIds = remember(state.sources) { state.sources.map { it.id }.toSet() }
    val recent = remember(state.recent, sourceIds) { state.recent.filter { it.entry.sourceId in sourceIds && it.positionMs > 0 }.take(20) }
    LazyColumn(modifier.fillMaxWidth().testTag("neon-home"), verticalArrangement = Arrangement.spacedBy(20.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "welcome") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LibraryHeader(stringResource(R.string.neon_welcome), state.sources.firstOrNull { it.id == state.selectedSourceId }, state.sources, onAction, state.isBusy, customerMode)
                Text(stringResource(R.string.library_brand_tagline), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton(live, { onBrowse(MediaKind.LIVE, "") }, Modifier.weight(1f).focusRequester(initialFocus), selected = true, compact = true)
                    ActionButton(stringResource(R.string.library_tab_movies), { onBrowse(MediaKind.MOVIE, "") }, Modifier.weight(1f), compact = true)
                    ActionButton(stringResource(R.string.library_tab_series), { onBrowse(MediaKind.SERIES, "") }, Modifier.weight(1f), compact = true)
                }
                if (state.isBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        if (state.updateAvailable) item(key = "update") {
            Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.neon_update_available), style = MaterialTheme.typography.titleMedium)
                ActionButton(stringResource(R.string.neon_update_install), { onAction(AppAction.InstallUpdate) }, enabled = !state.checkingUpdate)
            } }
        }
        hero?.let { item(key = "hero") { FeaturedContent(it, onAction) } }
        if (recent.isNotEmpty()) item(key = "recent") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.neon_continue), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(recent, key = { it.entry.sourceId + ":" + it.entry.id }) { progress ->
                        Column(Modifier.width(280.dp)) {
                            PosterCard(progress.entry, { onAction(AppAction.Play(progress.entry)) }, landscape = true)
                            val duration = progress.durationMs
                            if (duration != null && duration > progress.positionMs) {
                                LinearProgressIndicator(progress = { (progress.positionMs.toFloat() / duration).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                                Text(stringResource(R.string.neon_remaining, ((duration - progress.positionMs + 59_999) / 60_000).toInt()), style = MaterialTheme.typography.labelSmall)
                            } else Text(stringResource(R.string.neon_resume), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
        items(rails, key = { "${it.kind}:${it.category}:${it.title}" }) { rail ->
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(rail.title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    ActionButton(stringResource(R.string.neon_see_all), { onBrowse(rail.kind, rail.category) }, compact = true)
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(rail.entries, key = { it.id }) { entry ->
                        PosterCard(entry, { onAction(when (entry.kind) { MediaKind.SERIES -> AppAction.OpenSeries(entry); MediaKind.MOVIE -> AppAction.OpenMovie(entry); else -> AppAction.Play(entry) }) }, Modifier.width(if (entry.kind == MediaKind.LIVE) 220.dp else 152.dp), entry.kind == MediaKind.LIVE)
                    }
                }
            }
        }
    }
}

@Composable
internal fun PosterCard(entry: MediaEntry, onClick: () -> Unit, modifier: Modifier = Modifier, landscape: Boolean = false) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    Card(onClick = onClick, modifier = modifier.onFocusChanged { focused = it.hasFocus }
        .border(if (focused) 3.dp else 0.dp, if (focused) MaterialTheme.colorScheme.primary else Color.Transparent, shape)
        .testTag("poster-${entry.id}"), shape = shape) {
        Box(Modifier.fillMaxWidth().aspectRatio(if (landscape) 16f / 9 else 2f / 3)
            .background(Brush.linearGradient(listOf(Color(0xFF12344B), Color(0xFF251330)))), contentAlignment = Alignment.Center) {
            NeonEmblem(Modifier.size(48.dp))
            if (entry.logo.isNotBlank()) AsyncImage(entry.logo, null, Modifier.fillMaxSize(), contentScale = if (landscape) ContentScale.Fit else ContentScale.Crop)
        }
        Text(entry.name, Modifier.padding(10.dp), maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
internal fun SupportButton() {
    val uri = LocalUriHandler.current
    val config = LocalConfiguration.current
    val tv = config.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION
    Column {
        Text("(21) 98142-8144", style = MaterialTheme.typography.bodyMedium)
        if (!tv) ActionButton(stringResource(R.string.neon_support), { runCatching { uri.openUri("https://wa.me/5521981428144") } }, Modifier.fillMaxWidth())
    }
}
