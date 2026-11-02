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
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.delay
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
import play.ott.nativeapp.data.ForYouEngine
import play.ott.nativeapp.data.CatalogDisplayGroup

/** Original geometric mark, used until the supplied brand master is available as a file. */
@Composable
internal fun NeonEmblem(modifier: Modifier = Modifier) {
    coil.compose.AsyncImage(R.drawable.starlink_logo, contentDescription = "STARLINK IPTV", modifier = modifier, contentScale = ContentScale.Fit)
}

private data class HomeRail(val title: String, val kind: MediaKind, val category: String, val entries: List<MediaEntry>)

@Composable
internal fun NeonHome(state: AppUiState, onAction: (AppAction) -> Unit, initialFocus: FocusRequester, modifier: Modifier = Modifier, customerMode: Boolean = false, onBrowse: (MediaKind, String) -> Unit, onPreferences: () -> Unit = {},
    onFavorites: () -> Unit = {}, onGuide: () -> Unit = {}) {
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
                state.entries.groupBy { it.kind to CatalogDisplayGroup.group(it) }.entries.asSequence().filter { it.key.second.isNotBlank() }.take(12).forEach { (key, entries) ->
                    add(HomeRail(key.second, key.first, key.second, entries.take(20)))
                }
            }
        }
    }
    val hero = rails.firstOrNull { it.kind != MediaKind.LIVE }?.entries?.firstOrNull() ?: rails.firstOrNull()?.entries?.firstOrNull()
    val sourceIds = remember(state.sources) { state.sources.map { it.id }.toSet() }
    val recent = remember(state.recent, sourceIds) { state.recent.filter { it.entry.sourceId in sourceIds && it.positionMs > 0 }.take(20) }
    var quickChannelSearch by rememberSaveable { mutableStateOf("") }
    val matchedChannels by produceState<List<MediaEntry>>(emptyList(), state.entries, quickChannelSearch) {
        if (quickChannelSearch.isNotBlank()) delay(180)
        value = withContext(Dispatchers.Default) {
            if (quickChannelSearch.isBlank()) emptyList()
            else state.entries.asSequence().filter {
                it.kind == MediaKind.LIVE &&
                    (it.name.contains(quickChannelSearch, ignoreCase = true) ||
                        CatalogDisplayGroup.group(it).contains(quickChannelSearch, ignoreCase = true))
            }.take(18).toList()
        }
    }
    val favoriteEntries by produceState<List<MediaEntry>>(emptyList(), state.entries, state.favoriteIds) {
        value = withContext(Dispatchers.Default) {
            state.entries.asSequence().filter { it.id in state.favoriteIds }.take(20).toList()
        }
    }
    val recommendations by produceState<List<MediaEntry>>(
        emptyList(), state.entries, state.forYouProfile, state.favoriteIds, state.forYouRecentlyViewedKeys
    ) {
        value = withContext(Dispatchers.Default) {
            if (!state.forYouProfile.onboardingDone) emptyList()
            else ForYouEngine.recommend(state.entries, state.forYouProfile, state.favoriteIds, state.forYouRecentlyViewedKeys)
        }
    }

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
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton("Favoritos", onFavorites, Modifier.weight(1f).testTag("home-favorites-button"), compact = true)
                    ActionButton(stringResource(R.string.library_tv_guide), onGuide,
                        Modifier.weight(1f).testTag("home-guide-button"), compact = true)
                }
                OutlinedTextField(
                    value = quickChannelSearch, onValueChange = { quickChannelSearch = it },
                    singleLine = true,
                    placeholder = { Text("Buscar canal ao vivo") },
                    modifier = Modifier.fillMaxWidth().testTag("home-channel-search")
                )
                if (state.isBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        if (quickChannelSearch.isNotBlank()) item(key = "quick-channels") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Canais encontrados", style = MaterialTheme.typography.titleMedium)
                if (matchedChannels.isEmpty()) Text("Nenhum canal encontrado.", style = MaterialTheme.typography.bodySmall)
                else LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(matchedChannels, key = { ForYouEngine.key(it) }) { entry ->
                        PosterCard(entry, { onAction(AppAction.Play(entry)) }, Modifier.width(220.dp), landscape = true)
                    }
                }
            }
        }
        if (favoriteEntries.isNotEmpty()) item(key = "favorites-home") {
            Column(Modifier.testTag("home-favorites-rail"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Favoritos", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    ActionButton(stringResource(R.string.neon_see_all), onFavorites, compact = true)
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(favoriteEntries, key = { ForYouEngine.key(it) }) { entry ->
                        PosterCard(entry, { onAction(when (entry.kind) {
                            MediaKind.SERIES -> AppAction.OpenSeries(entry)
                            MediaKind.MOVIE -> AppAction.OpenMovie(entry)
                            else -> AppAction.Play(entry)
                        }) }, Modifier.width(if (entry.kind == MediaKind.LIVE) 220.dp else 160.dp),
                            landscape = entry.kind == MediaKind.LIVE)
                    }
                }
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

        if (state.forYouProfile.onboardingDone) item(key = "for-you") {
            Column(Modifier.testTag("for-you-rail"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Para Você", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    ActionButton("Preferências", onPreferences, compact = true)
                }
                if (recommendations.isEmpty()) {
                    Text("Escolha seus interesses para ver recomendações do catálogo.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(recommendations, key = { ForYouEngine.key(it) }) { entry ->
                            Column(Modifier.width(if (entry.kind == MediaKind.LIVE) 220.dp else 160.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                PosterCard(entry, {
                                    onAction(when (entry.kind) {
                                        MediaKind.SERIES -> AppAction.OpenSeries(entry)
                                        MediaKind.MOVIE -> AppAction.OpenMovie(entry)
                                        else -> AppAction.Play(entry)
                                    })
                                }, landscape = entry.kind == MediaKind.LIVE)
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    TextButton(onClick = { onAction(AppAction.VoteForYou(entry, true)) }, modifier = Modifier.weight(1f)) {
                                        Text("Gosto", style = MaterialTheme.typography.labelSmall)
                                    }
                                    TextButton(onClick = { onAction(AppAction.VoteForYou(entry, false)) }, modifier = Modifier.weight(1f)) {
                                        Text("Não gosto", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
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
            var artworkReady by remember(entry.logo) { mutableStateOf(false) }
            if (!artworkReady) Text(entry.name.take(2).uppercase(),
                Modifier.testTag("poster-placeholder-${entry.id}"), color = MaterialTheme.colorScheme.secondary)
            if (entry.logo.isNotBlank()) AsyncImage(entry.logo, null, Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit, onSuccess = { artworkReady = true }, onError = { artworkReady = false })
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
