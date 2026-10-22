package play.ott.nativeapp.ui

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.media3.session.MediaController
import coil.compose.AsyncImage
import play.ott.nativeapp.R
import play.ott.nativeapp.core.MediaEntry
import play.ott.nativeapp.core.MediaKind
import play.ott.nativeapp.core.SourceConfig
import play.ott.nativeapp.i18n.AppLanguages
import kotlinx.coroutines.launch

private enum class LibraryTab(@param:StringRes val title: Int, @param:StringRes val heading: Int) {
    LIVE(R.string.library_tab_live, R.string.library_heading_live),
    MOVIES(R.string.library_tab_movies, R.string.library_heading_movies),
    SERIES(R.string.library_tab_series, R.string.library_heading_series),
    FAVORITES(R.string.library_tab_favorites, R.string.library_heading_favorites),
}

@Composable
fun OttNativeApp(
    state: AppUiState,
    onAction: (AppAction) -> Unit,
    controller: MediaController?,
    onPictureInPicture: () -> Unit,
    onFullscreenChanged: (Boolean) -> Unit,
    inPictureInPicture: Boolean = false,
) {
    val configuration = LocalConfiguration.current
    val isTv = configuration.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION
    val keyToCatalog = if (LocalLayoutDirection.current == LayoutDirection.Rtl) Key.DirectionLeft else Key.DirectionRight
    var tab by rememberSaveable { mutableStateOf(LibraryTab.LIVE) }
    var search by rememberSaveable { mutableStateOf("") }
    var group by rememberSaveable(state.selectedSourceId, tab) { mutableStateOf("") }
    var showSources by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showPrivacy by rememberSaveable { mutableStateOf(false) }
    var sourceEditorOpen by rememberSaveable { mutableStateOf(false) }
    var sourceEditorId by rememberSaveable { mutableStateOf<String?>(null) }
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var autoOpenedEntry by rememberSaveable { mutableStateOf<String?>(null) }
    var initializedSource by rememberSaveable { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val selectedSource = state.sources.firstOrNull { it.id == state.selectedSourceId }
    val tabEntries = remember(state.entries, state.favoriteIds, tab) {
        state.entries.filter { entry ->
            when (tab) {
                LibraryTab.LIVE -> entry.kind == MediaKind.LIVE
                LibraryTab.MOVIES -> entry.kind == MediaKind.MOVIE
                LibraryTab.SERIES -> entry.kind == MediaKind.SERIES
                LibraryTab.FAVORITES -> entry.id in state.favoriteIds
            }
        }
    }
    val groups = remember(tabEntries) { tabEntries.map { it.group }.filter { it.isNotBlank() }.distinct().sorted() }
    LaunchedEffect(groups, state.isBusy) {
        // Generated group labels change with the app language. Keep existing provider filters,
        // but do not let a saved, obsolete label hide the newly localized catalog.
        if (!state.isBusy && tabEntries.isNotEmpty() && group.isNotBlank() && group !in groups) group = ""
    }
    val visibleEntries = remember(tabEntries, search, group) {
        tabEntries.filter { (group.isBlank() || it.group == group) && (search.isBlank() || it.name.contains(search, ignoreCase = true) || it.group.contains(search, ignoreCase = true)) }
    }
    val railFocus = remember { FocusRequester() }
    val welcomeFocus = remember { FocusRequester() }
    val catalogFocus = remember { FocusRequester() }
    val gridState = rememberLazyGridState()
    val focusScope = rememberCoroutineScope()
    var lastFocusedEntryId by rememberSaveable { mutableStateOf<String?>(null) }
    val focusEntryId = visibleEntries.firstOrNull { it.id == lastFocusedEntryId }?.id ?: visibleEntries.firstOrNull()?.id
    var libraryFocusSet by remember(isTv, fullscreen, state.sources.isEmpty()) { mutableStateOf(false) }
    LaunchedEffect(isTv, fullscreen, state.isBusy, state.sources.isEmpty()) {
        if (isTv && !fullscreen && !state.isBusy && !libraryFocusSet) {
            // Request only on entering the library, after layout and automatic tab selection.
            // Later state updates must not pull focus out of search or a dialog.
            withFrameNanos { }
            if (state.sources.isEmpty()) welcomeFocus.requestFocus()
            else if (lastFocusedEntryId != null && focusEntryId != null) {
                gridState.scrollToItem(visibleEntries.indexOfFirst { it.id == focusEntryId })
                withFrameNanos { }
                catalogFocus.requestFocus()
            } else railFocus.requestFocus()
            libraryFocusSet = true
        }
    }
    LaunchedEffect(state.selectedSourceId, state.entries) {
        if (initializedSource != state.selectedSourceId && state.entries.isNotEmpty()) {
            initializedSource = state.selectedSourceId
            tab = when {
                state.entries.any { it.kind == MediaKind.LIVE } -> LibraryTab.LIVE
                state.entries.any { it.kind == MediaKind.MOVIE } -> LibraryTab.MOVIES
                state.entries.any { it.kind == MediaKind.SERIES } -> LibraryTab.SERIES
                else -> LibraryTab.FAVORITES
            }
        }
    }
    LaunchedEffect(state.playingEntry?.id) {
        val id = state.playingEntry?.id
        if (id == null) { fullscreen = false; autoOpenedEntry = null }
        else if (id != autoOpenedEntry) { fullscreen = true; autoOpenedEntry = id }
    }
    LaunchedEffect(fullscreen) { onFullscreenChanged(fullscreen) }
    LaunchedEffect(state.notice) {
        state.notice?.let { snackbar.showSnackbar(it); onAction(AppAction.DismissNotice) }
    }
    BackHandler(enabled = state.playingEntry != null && state.epgEntry == null && state.seriesEntry == null && !showSources && !showSettings && !showPrivacy && !sourceEditorOpen) {
        if (fullscreen) fullscreen = false else onAction(AppAction.StopPlayback)
    }
    OttTheme {
        Surface(modifier = Modifier.tvRemoteInput().fillMaxSize().then(if (inPictureInPicture) Modifier else Modifier.safeDrawingPadding()), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize()) {
                if ((fullscreen || inPictureInPicture) && state.playingEntry != null) {
                    NativePlayerPane(state.playingEntry, controller, true, { fullscreen = false }, onPictureInPicture,
                        { onAction(AppAction.StopPlayback) }, { onAction(AppAction.Play(state.playingEntry)) },
                        { audio, preference -> onAction(AppAction.SetTrackPreference(audio, preference)) }, Modifier.fillMaxSize(), inPictureInPicture)
                } else {
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        val wide = isTv || maxWidth >= 840.dp
                        Row(Modifier.fillMaxSize().padding(if (isTv) 28.dp else 16.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                            if (wide) {
                                Column(Modifier.width(188.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Brand()
                                    Spacer(Modifier.height(18.dp))
                                    LibraryTab.entries.forEach { option ->
                                        ActionButton(stringResource(option.title), { tab = option }, Modifier.fillMaxWidth()
                                            .testTag("library-tab-${option.name}")
                                            .then(if (tab == option) Modifier.focusRequester(railFocus) else Modifier)
                                            .onPreviewKeyEvent { event ->
                                                if (isTv && event.type == KeyEventType.KeyDown && event.key == keyToCatalog &&
                                                    (state.sources.isEmpty() || focusEntryId != null)) {
                                                    if (event.nativeKeyEvent.repeatCount == 0) {
                                                        if (state.sources.isEmpty()) welcomeFocus.requestFocus()
                                                        else focusScope.launch {
                                                            // A remembered grid position can put a new tab's target
                                                            // outside the lazy viewport. Compose it before focusing.
                                                            gridState.scrollToItem(visibleEntries.indexOfFirst { it.id == focusEntryId })
                                                            withFrameNanos { }
                                                            catalogFocus.requestFocus()
                                                        }
                                                    }
                                                    true
                                                } else false
                                            }, selected = tab == option)
                                    }
                                    Spacer(Modifier.weight(1f))
                                    Text(stringResource(R.string.library_section_label), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    ActionButton(stringResource(R.string.library_sources), { showSources = true }, Modifier.fillMaxWidth())
                                    ActionButton(stringResource(R.string.library_settings), { showSettings = true }, Modifier.fillMaxWidth().testTag("open-settings"))
                                }
                            }
                            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                if (!wide) {
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Box(Modifier.weight(1f)) { Brand() }
                                        ActionButton(stringResource(R.string.library_sources), { showSources = true }, compact = true)
                                        ActionButton(stringResource(R.string.library_more), { showSettings = true }, Modifier.testTag("open-settings"), compact = true)
                                    }
                                }
                                if (state.sources.isEmpty()) {
                                    Welcome(
                                        modifier = Modifier.weight(1f),
                                        onAdd = { sourceEditorId = null; sourceEditorOpen = true },
                                        onImport = { onAction(AppAction.ImportPlaylist) },
                                        onDemo = { onAction(AppAction.AddDemo) },
                                        onPrivacy = { showPrivacy = true },
                                        initialFocus = welcomeFocus,
                                    )
                                } else {
                                    LibraryHeader(stringResource(tab.heading), selectedSource, state.sources, onAction, state.isBusy)
                                    state.playingEntry?.let { entry ->
                                        Card(onClick = { fullscreen = true }, modifier = Modifier.testTag("now-playing-bar"), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                                            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                                Column(Modifier.weight(1f)) {
                                                    Text(stringResource(R.string.library_now_playing), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                                    Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                                                }
                                                ActionButton(stringResource(R.string.library_open), { fullscreen = true }, compact = true)
                                            }
                                        }
                                    }
                                    if (!wide) {
                                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            items(LibraryTab.entries) { option ->
                                                ActionButton(stringResource(option.title), { tab = option }, selected = tab == option, compact = true)
                                            }
                                        }
                                    }
                                    OutlinedTextField(
                                        value = search, onValueChange = { search = it }, singleLine = true,
                                        placeholder = { Text(stringResource(R.string.library_search_placeholder)) },
                                        label = { Text(stringResource(R.string.library_search)) },
                                        trailingIcon = { if (search.isNotEmpty()) TextButton(onClick = { search = "" }) { Text(stringResource(R.string.library_clear)) } },
                                        shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(),
                                    )
                                    if (groups.isNotEmpty()) {
                                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            item { FilterChip(selected = group.isBlank(), onClick = { group = "" }, label = { Text(stringResource(R.string.library_all_groups)) }) }
                                            items(groups) { name -> FilterChip(selected = group == name, onClick = { group = name }, label = { Text(name) }, modifier = Modifier.testTag("library-group-$name")) }
                                        }
                                    }
                                    if (state.isBusy) {
                                        LinearProgressIndicator(Modifier.fillMaxWidth())
                                        if (state.loadingMessage.isNotBlank()) Text(state.loadingMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(pluralStringResource(R.plurals.library_entry_count, visibleEntries.size, visibleEntries.size), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        if (isTv) Text(stringResource(R.string.library_remote_hint), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    if (visibleEntries.isEmpty()) {
                                        EmptyLibrary(tab, search.isNotEmpty() || group.isNotEmpty(), state.isBusy,
                                            { search = ""; group = "" }, { onAction(AppAction.Refresh) }, Modifier.weight(1f))
                                    } else {
                                        LazyVerticalGrid(
                                            state = gridState,
                                            columns = GridCells.Adaptive(if (wide) 218.dp else 152.dp),
                                            modifier = Modifier.weight(1f).testTag("library-grid"),
                                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                                            verticalArrangement = Arrangement.spacedBy(12.dp),
                                            contentPadding = PaddingValues(bottom = 24.dp),
                                        ) {
                                            if (tab == LibraryTab.MOVIES || tab == LibraryTab.SERIES) item(span = { GridItemSpan(maxLineSpan) }) {
                                                FeaturedContent(visibleEntries.first(), onAction)
                                            }
                                            items(visibleEntries, key = { it.id }) { entry ->
                                                MediaCard(entry, entry.id in state.favoriteIds, state.playingEntry?.id == entry.id, onAction,
                                                    Modifier.then(if (entry.id == focusEntryId) Modifier.focusRequester(catalogFocus) else Modifier)
                                                        .onFocusChanged { if (it.hasFocus) lastFocusedEntryId = entry.id })
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
            }
        }
        if (showSources) {
            SourcesDialog(state, onAction, onDismiss = { showSources = false }, onEdit = { source ->
                sourceEditorId = source?.id; sourceEditorOpen = true
            })
        }
        if (showSettings && !showPrivacy) SettingsDialog(state, onAction, onPrivacy = { showPrivacy = true }) { showSettings = false }
        if (showPrivacy) PrivacyPolicyDialog { showPrivacy = false }
        if (sourceEditorOpen) {
            SourceEditor(state.sources.firstOrNull { it.id == sourceEditorId }, { sourceEditorOpen = false }) {
                onAction(AppAction.SaveSource(it)); sourceEditorOpen = false; showSources = false
            }
        }
        state.epgEntry?.let { GuideDialog(it, state.programmes, state.isBusy, onAction) }
        state.seriesEntry?.let { SeriesDialog(it, state.episodes, state.isBusy, onAction) }
        state.error?.let { message ->
            AlertDialog(
                modifier = Modifier.tvRemoteInput(),
                onDismissRequest = { onAction(AppAction.DismissError) },
                title = { Text(stringResource(R.string.library_action_failed)) },
                text = { Text(message) },
                confirmButton = { ActionButton(stringResource(R.string.library_understood), { onAction(AppAction.DismissError) }, selected = true) },
            )
        }
    }
}

@Composable
private fun Brand() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(32.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.library_brand_icon), color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
        }
        Column {
            Text(stringResource(R.string.library_brand_name), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
            Text(stringResource(R.string.library_brand_tagline), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LibraryHeader(title: String, source: SourceConfig?, sources: List<SourceConfig>, onAction: (AppAction) -> Unit, loading: Boolean) {
    var menuOpen by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) {
                ActionButton(source?.name ?: stringResource(R.string.library_choose_source), { menuOpen = true }, Modifier.fillMaxWidth(), compact = true)
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    sources.forEach { candidate ->
                        DropdownMenuItem(text = { Text(candidate.name) }, onClick = { onAction(AppAction.SelectSource(candidate.id)); menuOpen = false })
                    }
                }
            }
            ActionButton(stringResource(R.string.library_refresh), { onAction(AppAction.Refresh) }, enabled = !loading, compact = true)
        }
    }
}

@Composable
private fun Welcome(modifier: Modifier, onAdd: () -> Unit, onImport: () -> Unit, onDemo: () -> Unit, onPrivacy: () -> Unit, initialFocus: FocusRequester) {
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.Start) {
        Text(stringResource(R.string.library_welcome_label), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.library_welcome_heading), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.library_welcome_description), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(28.dp))
        ActionButton(stringResource(R.string.library_add_source), onAdd, Modifier.focusRequester(initialFocus).testTag("welcome-add-source"), selected = true)
        Spacer(Modifier.height(8.dp))
        ActionButton(stringResource(R.string.library_open_m3u), onImport)
        Spacer(Modifier.height(8.dp))
        ActionButton(stringResource(R.string.library_try_demo), onDemo)
        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.library_demo_description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        ActionButton(stringResource(R.string.library_privacy_policy), onPrivacy, Modifier.testTag("welcome-privacy"), compact = true)
    }
}

@Composable
private fun EmptyLibrary(tab: LibraryTab, filtered: Boolean, loading: Boolean, onClear: () -> Unit, onRefresh: () -> Unit, modifier: Modifier) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(when { loading -> stringResource(R.string.library_loading); filtered -> stringResource(R.string.library_nothing_found); tab == LibraryTab.FAVORITES -> stringResource(R.string.library_favorites_empty); else -> stringResource(R.string.library_section_empty) }, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(when { filtered -> stringResource(R.string.library_search_suggestion); tab == LibraryTab.FAVORITES -> stringResource(R.string.library_favorites_suggestion); else -> stringResource(R.string.library_refresh_suggestion) }, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        if (!loading && filtered) ActionButton(stringResource(R.string.library_clear_filters), onClear, Modifier.padding(top = 16.dp))
        if (!loading && !filtered && tab != LibraryTab.FAVORITES) ActionButton(stringResource(R.string.library_refresh), onRefresh, Modifier.padding(top = 16.dp))
    }
}

@Composable
internal fun MediaCard(entry: MediaEntry, favorite: Boolean, playing: Boolean, onAction: (AppAction) -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(18.dp)
    val favoriteDescription = stringResource(
        if (favorite) R.string.library_favorite_remove_description else R.string.library_favorite_add_description,
        entry.name,
    )
    Card(
        onClick = { onAction(if (entry.kind == MediaKind.SERIES) AppAction.OpenSeries(entry) else AppAction.Play(entry)) },
        modifier = modifier.fillMaxWidth().testTag("catalog-item-${entry.id}").onFocusChanged { focused = it.isFocused }
            .border(BorderStroke(if (focused || playing) 2.dp else 0.dp, if (focused || playing) MaterialTheme.colorScheme.primary else Color.Transparent), shape),
        colors = CardDefaults.cardColors(containerColor = if (focused) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface),
        shape = shape,
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(if (entry.kind == MediaKind.LIVE) 2.15f else .72f)
                .background(Brush.linearGradient(listOf(Color(0xFF381217), Color(0xFF141418)))),
            contentAlignment = Alignment.Center,
        ) {
            Text(entry.name.take(2).uppercase(), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
            if (entry.logo.isNotBlank()) AsyncImage(entry.logo, contentDescription = null, modifier = Modifier.fillMaxSize().padding(12.dp), contentScale = ContentScale.Fit)
            if (playing) Text(stringResource(R.string.library_now_playing), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.align(Alignment.BottomStart).background(MaterialTheme.colorScheme.background.copy(alpha = .85f)).padding(6.dp))
        }
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(entry.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis)
            Text(entry.group.ifBlank { if (entry.kind == MediaKind.LIVE) stringResource(R.string.library_live_video) else stringResource(R.string.library_video) }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            ActionButton(if (favorite) stringResource(R.string.library_favorite_selected) else stringResource(R.string.library_favorite_add), { onAction(AppAction.ToggleFavorite(entry)) }, Modifier.fillMaxWidth().semantics { contentDescription = favoriteDescription }, compact = true, selected = favorite)
            if (entry.kind == MediaKind.LIVE) ActionButton(stringResource(R.string.library_tv_guide), { onAction(AppAction.OpenEpg(entry)) }, Modifier.fillMaxWidth(), compact = true)
        }
    }
}

@Composable
private fun SourcesDialog(state: AppUiState, onAction: (AppAction) -> Unit, onDismiss: () -> Unit, onEdit: (SourceConfig?) -> Unit) {
    var deleting by remember { mutableStateOf<SourceConfig?>(null) }
    AlertDialog(
        modifier = Modifier.tvRemoteInput(),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.library_sources)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.sources.isEmpty()) item { Text(stringResource(R.string.library_sources_empty)) }
                items(state.sources, key = { it.id }) { source ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(source.name, style = MaterialTheme.typography.titleMedium)
                        Text(if (source.id == state.selectedSourceId) stringResource(R.string.library_source_selected, source.kind.label()) else source.kind.label(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ActionButton(stringResource(R.string.library_select), { onAction(AppAction.SelectSource(source.id)); onDismiss() }, Modifier.weight(1f), compact = true)
                            ActionButton(stringResource(R.string.library_edit), { onEdit(source) }, Modifier.weight(1f), compact = true)
                        }
                        TextButton(onClick = { deleting = source }) { Text(stringResource(R.string.library_delete_source), color = MaterialTheme.colorScheme.error) }
                        HorizontalDivider()
                    }
                }
                item { ActionButton(stringResource(R.string.library_add_by_url), { onEdit(null) }, Modifier.fillMaxWidth(), selected = true) }
                item { ActionButton(stringResource(R.string.library_open_m3u), { onAction(AppAction.ImportPlaylist); onDismiss() }, Modifier.fillMaxWidth()) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.library_done)) } },
    )
    deleting?.let { source ->
        AlertDialog(
            modifier = Modifier.tvRemoteInput(),
            onDismissRequest = { deleting = null }, title = { Text(stringResource(R.string.library_delete_source_title, source.name)) },
            text = { Text(stringResource(R.string.library_delete_source_description)) },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.library_cancel)) } },
            confirmButton = { ActionButton(stringResource(R.string.library_delete), { onAction(AppAction.DeleteSource(source.id)); deleting = null }) },
        )
    }
}

@Composable
private fun SettingsDialog(state: AppUiState, onAction: (AppAction) -> Unit, onPrivacy: () -> Unit, onDismiss: () -> Unit) {
    val configuration = LocalConfiguration.current
    val isTv = configuration.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION
    var languageTag by remember(configuration) { mutableStateOf(AppLanguages.currentTag()) }
    var showLanguages by rememberSaveable { mutableStateOf(false) }
    var restoreLanguageFocus by rememberSaveable { mutableStateOf(false) }
    val languageFocus = remember { FocusRequester() }
    if (showLanguages) {
        LanguageDialog(
            languageTag,
            onSelect = { tag ->
                showLanguages = false
                restoreLanguageFocus = isTv
                languageTag = tag
                AppLanguages.setLanguage(tag)
            },
            onDismiss = { showLanguages = false; restoreLanguageFocus = isTv },
        )
        return
    }
    LaunchedEffect(isTv, restoreLanguageFocus) {
        if (isTv && restoreLanguageFocus) {
            withFrameNanos { }
            languageFocus.requestFocus()
            restoreLanguageFocus = false
        }
    }
    val languageName = AppLanguages.supported.firstOrNull { it.tag == languageTag }?.nativeName
        ?: stringResource(R.string.library_language_system)
    val backgroundPlaybackDescription = stringResource(R.string.library_background_playback)
    AlertDialog(
        modifier = Modifier.tvRemoteInput(),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.library_settings)) },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.library_language), style = MaterialTheme.typography.titleSmall)
                ActionButton(languageName, { showLanguages = true },
                    Modifier.fillMaxWidth().focusRequester(languageFocus).testTag("settings-language"))
                HorizontalDivider()
                if (!isTv) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.library_background_playback), style = MaterialTheme.typography.titleSmall)
                            Text(stringResource(R.string.library_background_description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = state.backgroundPlayback, onCheckedChange = { onAction(AppAction.SetBackgroundPlayback(it)) }, modifier = Modifier.semantics { contentDescription = backgroundPlaybackDescription })
                    }
                    HorizontalDivider()
                }
                Text(stringResource(R.string.library_backup), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.library_backup_description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ActionButton(stringResource(R.string.library_export_settings), { onAction(AppAction.ExportSettings); onDismiss() }, Modifier.fillMaxWidth())
                ActionButton(stringResource(R.string.library_import_settings), { onAction(AppAction.ImportSettings); onDismiss() }, Modifier.fillMaxWidth())
                ActionButton(stringResource(R.string.library_add_demo), { onAction(AppAction.AddDemo); onDismiss() }, Modifier.fillMaxWidth())
                HorizontalDivider()
                ActionButton(stringResource(R.string.library_privacy_policy), onPrivacy, Modifier.fillMaxWidth().testTag("settings-privacy"))
            }
        },
        confirmButton = { ActionButton(stringResource(R.string.library_done), onDismiss, selected = true) },
    )
}

@Composable
private fun LanguageDialog(languageTag: String, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    val configuration = LocalConfiguration.current
    val isTv = configuration.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION
    val options = listOf("" to stringResource(R.string.library_language_system)) +
        AppLanguages.supported.map { it.tag to it.nativeName }
    val selectedIndex = options.indexOfFirst { it.first == languageTag }.coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = selectedIndex)
    val selectedFocus = remember { FocusRequester() }
    LaunchedEffect(isTv) {
        if (isTv) {
            withFrameNanos { }
            selectedFocus.requestFocus()
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.tvRemoteInput().testTag("language-dialog"),
        title = { Text(stringResource(R.string.library_language)) },
        text = {
            LazyColumn(state = listState, modifier = Modifier.heightIn(max = 480.dp).testTag("language-list"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(options, key = { it.first }) { (tag, name) ->
                    val isSelected = tag == languageTag
                    ActionButton(name, { onSelect(tag) },
                        Modifier.fillMaxWidth().testTag("language-${tag.ifEmpty { "system" }}")
                            .then(if (isSelected) Modifier.focusRequester(selectedFocus) else Modifier)
                            .semantics { selected = isSelected },
                        selected = isSelected)
                }
            }
        },
        confirmButton = { ActionButton(stringResource(R.string.library_done), onDismiss) },
    )
}

@Composable
private fun FeaturedContent(entry: MediaEntry, onAction: (AppAction) -> Unit) {
    Card(onClick = { onAction(if (entry.kind == MediaKind.SERIES) AppAction.OpenSeries(entry) else AppAction.Play(entry)) },
        modifier = Modifier.fillMaxWidth().testTag("featured-content")) {
        Box(Modifier.fillMaxWidth().height(160.dp).background(Brush.horizontalGradient(listOf(Color(0xFF461017), Color(0xFF141418))))) {
            if (entry.logo.isNotBlank()) AsyncImage(entry.logo, null, Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(180.dp), contentScale = ContentScale.Crop)
            Column(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0xEE141418), Color.Transparent))).padding(20.dp), verticalArrangement = Arrangement.Center) {
                Text("DESTAQUE", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(entry.group, style = MaterialTheme.typography.bodyMedium)
                Text(if (entry.kind == MediaKind.SERIES) "Ver temporadas e episódios" else "Assistir", color = Color.White)
            }
        }
    }
}
