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
import play.ott.nativeapp.data.CatalogDisplayGroup
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import androidx.compose.runtime.produceState
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem

private enum class LibraryTab(@param:StringRes val title: Int, @param:StringRes val heading: Int) {
    HOME(R.string.neon_home, R.string.neon_welcome),
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
    customerMode: Boolean = play.ott.nativeapp.BuildConfig.CUSTOMER_MODE,
) {
    val configuration = LocalConfiguration.current
    val isTv = configuration.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION
    if (customerMode && state.sources.isEmpty()) {
        LaunchedEffect(Unit) { onFullscreenChanged(false) }
        OttTheme {
            CustomerLoginScreen(busy = state.isBusy, onSubmit = { onAction(AppAction.SaveSource(it)) })
            state.error?.let { CustomerErrorDialog(it) { onAction(AppAction.DismissError) } }
        }
        return
    }
    val keyToCatalog = if (LocalLayoutDirection.current == LayoutDirection.Rtl) Key.DirectionLeft else Key.DirectionRight
    var tab by rememberSaveable { mutableStateOf(LibraryTab.HOME) }
    var search by rememberSaveable { mutableStateOf("") }
    var group by rememberSaveable(state.selectedSourceId, tab) { mutableStateOf("") }
    var showSources by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showForYouPreferences by rememberSaveable { mutableStateOf(false) }
    var showGuidePicker by rememberSaveable { mutableStateOf(false) }
    var showPrivacy by rememberSaveable { mutableStateOf(false) }
    var sourceEditorOpen by rememberSaveable { mutableStateOf(false) }
    var sourceEditorId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(state.sourceSaveRevision) {
        if (state.sourceSaveRevision > 0) { sourceEditorOpen = false; showSources = false }
    }
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var autoOpenedEntry by rememberSaveable { mutableStateOf<String?>(null) }
    var initializedSource by rememberSaveable { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val selectedSource = state.sources.firstOrNull { it.id == state.selectedSourceId }
    val tabEntries by produceState<List<MediaEntry>>(emptyList(), state.entries, state.favoriteIds, tab) {
        value = withContext(Dispatchers.Default) {
        state.entries.filter { entry ->
            when (tab) {
                LibraryTab.HOME -> true
                LibraryTab.LIVE -> entry.kind == MediaKind.LIVE
                LibraryTab.MOVIES -> entry.kind == MediaKind.MOVIE
                LibraryTab.SERIES -> entry.kind == MediaKind.SERIES
                LibraryTab.FAVORITES -> entry.id in state.favoriteIds
            }
        }
    }
    }
    val groups by produceState<List<String>>(emptyList(), tabEntries) {
        value = withContext(Dispatchers.Default) { tabEntries.map { CatalogDisplayGroup.group(it) }.filter { it.isNotBlank() }.distinct().sorted() }
    }
    LaunchedEffect(state.entries, group, state.isBusy) {
        if (!state.isBusy && state.entries.isNotEmpty() && group.isNotBlank()) {
            val exists = withContext(Dispatchers.Default) { state.entries.any { CatalogDisplayGroup.group(it) == group } }
            if (!exists) group = ""
        }
    }
    val visibleEntries by produceState<List<MediaEntry>>(emptyList(), tabEntries, search, group) {
        if (search.isNotBlank()) delay(250)
        value = withContext(Dispatchers.Default) { tabEntries.filter { (group.isBlank() || CatalogDisplayGroup.group(it) == group) && (search.isBlank() || it.name.contains(search, ignoreCase = true) || CatalogDisplayGroup.group(it).contains(search, ignoreCase = true)) }
    }
    }
    val catalogHeaderCount = 1
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
            if (state.sources.isEmpty() || tab == LibraryTab.HOME) welcomeFocus.requestFocus()
            else if (tab != LibraryTab.HOME && lastFocusedEntryId != null && focusEntryId != null) {
                gridState.scrollToItem(visibleEntries.indexOfFirst { it.id == focusEntryId } + catalogHeaderCount)
                withFrameNanos { }
                catalogFocus.requestFocus()
            } else railFocus.requestFocus()
            libraryFocusSet = true
        }
    }
    LaunchedEffect(state.selectedSourceId, state.entries) {
        if (initializedSource != state.selectedSourceId && state.entries.isNotEmpty()) {
            initializedSource = state.selectedSourceId
            tab = LibraryTab.HOME
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
    BackHandler(enabled = state.playingEntry != null && state.epgEntry == null && state.seriesEntry == null && state.movieEntry == null && !showSources && !showSettings && !showPrivacy && !sourceEditorOpen && !showGuidePicker) {
        onAction(AppAction.StopPlayback)
    }
    OttTheme {
        Surface(modifier = Modifier.tvRemoteInput().fillMaxSize().then(if (inPictureInPicture) Modifier else Modifier.safeDrawingPadding()), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize()) {
                if ((fullscreen || inPictureInPicture) && state.playingEntry != null) {
                    NativePlayerPane(state.playingEntry, controller, true, { onAction(AppAction.StopPlayback) }, onPictureInPicture,
                        { onAction(AppAction.StopPlayback) }, { onAction(AppAction.Play(state.playingEntry)) },
                        { audio, preference -> onAction(AppAction.SetTrackPreference(audio, preference)) }, Modifier.fillMaxSize(), inPictureInPicture)
                } else {
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        val wide = isTv || maxWidth >= 840.dp
                        Row(Modifier.fillMaxSize().padding(if (isTv) 28.dp else 16.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                            if (wide) {
                                Column(Modifier.width(188.dp).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                                                        if (state.sources.isEmpty() || tab == LibraryTab.HOME) welcomeFocus.requestFocus()
                                                        else focusScope.launch {
                                                            // A remembered grid position can put a new tab's target
                                                            // outside the lazy viewport. Compose it before focusing.
                                                            gridState.scrollToItem(visibleEntries.indexOfFirst { it.id == focusEntryId } + catalogHeaderCount)
                                                            withFrameNanos { }
                                                            catalogFocus.requestFocus()
                                                        }
                                                    }
                                                    true
                                                } else false
                                            }, selected = tab == option)
                                    }
                                    ActionButton(stringResource(R.string.library_tv_guide), { showGuidePicker = true }, Modifier.fillMaxWidth(), compact = true)
                                    Spacer(Modifier.height(8.dp))
                                    Text(stringResource(R.string.library_section_label), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (!customerMode) ActionButton(stringResource(R.string.library_sources), { showSources = true }, Modifier.fillMaxWidth())
                                    ActionButton(stringResource(R.string.library_settings), { showSettings = true }, Modifier.fillMaxWidth().testTag("open-settings"))
                                }
                            }
                            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                if (!wide) {
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Box(Modifier.weight(1f)) { Brand() }
                                        if (!customerMode) ActionButton(stringResource(R.string.library_sources), { showSources = true }, compact = true)
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
                                    if (tab == LibraryTab.HOME) {
                                        NeonHome(state, onAction, welcomeFocus, Modifier.weight(1f), customerMode = customerMode, onBrowse = { kind, category ->
                                            tab = when (kind) { MediaKind.LIVE -> LibraryTab.LIVE; MediaKind.SERIES -> LibraryTab.SERIES; else -> LibraryTab.MOVIES }
                                            group = category; search = ""
                                        }, onPreferences = { showForYouPreferences = true },
                                            onFavorites = { tab = LibraryTab.FAVORITES; group = ""; search = "" },
                                            onGuide = { showGuidePicker = true })
                                    } else {
                                    LazyVerticalGrid(
                                        state = gridState,
                                        columns = GridCells.Adaptive(if (wide) 218.dp else 152.dp),
                                        modifier = Modifier.weight(1f).testTag("library-grid"),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(12.dp),
                                        contentPadding = PaddingValues(bottom = 24.dp),
                                    ) {
                                        item(key = "catalog-controls", span = { GridItemSpan(maxLineSpan) }) {
                                            Column(Modifier.testTag("catalog-controls"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    LibraryHeader(stringResource(tab.heading), selectedSource, state.sources, onAction, state.isBusy, customerMode)
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
                                            }
                                        }
                                        if (visibleEntries.isEmpty()) {
                                            item(key = "catalog-empty", span = { GridItemSpan(maxLineSpan) }) {
                                                EmptyLibrary(tab, search.isNotEmpty() || group.isNotEmpty(), state.isBusy,
                                                    { search = ""; group = "" }, { onAction(AppAction.Refresh) }, Modifier.fillMaxWidth().heightIn(min = 180.dp))
                                            }
                                        } else {
                                            items(visibleEntries, key = { it.id }) { entry ->
                                                MediaCard(entry, entry.id in state.favoriteIds, state.playingEntry?.id == entry.id, onAction,
                                                    Modifier.then(if (entry.id == focusEntryId) Modifier.focusRequester(catalogFocus) else Modifier)
                                                        .onFocusChanged { if (it.hasFocus) lastFocusedEntryId = entry.id })
                                            }
                                        }
                                    }
                                    }
                                }
                                if (!wide && state.sources.isNotEmpty()) {
                                    NavigationBar(containerColor = MaterialTheme.colorScheme.background) {
                                        LibraryTab.entries.filter { it != LibraryTab.FAVORITES }.forEach { option ->
                                            NavigationBarItem(selected = tab == option, onClick = { tab = option; search = "" },
                                                icon = { Text(when(option) { LibraryTab.HOME -> "⌂"; LibraryTab.LIVE -> "▣"; LibraryTab.MOVIES -> "▶"; else -> "▤" }) },
                                                label = { Text(stringResource(option.title)) }, modifier = Modifier.testTag("library-tab-${option.name}"))
                                        }
                                        NavigationBarItem(selected = showSettings, onClick = { showSettings = true }, icon = { Text("☰") }, label = { Text(stringResource(R.string.library_more)) })
                                    }
                                }
                            }
                        }
                    }
                }
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
            }
        }
        if (showSources && !customerMode) {
            SourcesDialog(state, onAction, onDismiss = { showSources = false }, onEdit = { source ->
                sourceEditorId = source?.id; sourceEditorOpen = true
            })
        }
        if (showSettings && !showPrivacy) SettingsDialog(state, onAction, customerMode = customerMode, onGuide = { showGuidePicker = true; showSettings = false }, onFavorites = { tab = LibraryTab.FAVORITES; showSettings = false }, onSources = { showSettings = false; if (customerMode) sourceEditorOpen = true else showSources = true }, onPrivacy = { showPrivacy = true }, onForYou = { showSettings = false; showForYouPreferences = true }) { showSettings = false }
        if (showPrivacy) PrivacyPolicyDialog { showPrivacy = false }
        if (state.sources.isNotEmpty() && !state.isBusy && !fullscreen &&
            (showForYouPreferences || !state.forYouProfile.onboardingDone)) {
            ForYouDialog(state.forYouProfile, onSave = { topics, subtopics, dislikes, consent ->
                onAction(AppAction.SaveForYou(topics, subtopics, dislikes, consent))
                showForYouPreferences = false
            }, onSkip = {
                onAction(AppAction.SkipForYou)
                showForYouPreferences = false
            })
        }
        if (sourceEditorOpen && customerMode) {
            androidx.compose.ui.window.Dialog(onDismissRequest = { sourceEditorOpen = false }, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
                CustomerLoginScreen(state.sources.firstOrNull { it.kind == play.ott.nativeapp.core.SourceKind.XTREAM }, state.isBusy,
                    { onAction(AppAction.SaveSource(it)) }, { sourceEditorOpen = false })
            }
        }
        if (sourceEditorOpen && !customerMode) {
            SourceEditor(state.sources.firstOrNull { it.id == sourceEditorId }, { sourceEditorOpen = false }, state.isBusy, state.loadingMessage) {
                onAction(AppAction.SaveSource(it))
            }
        }
        if (showGuidePicker) NeonGuidePicker(state.entries, { showGuidePicker = false; onAction(AppAction.OpenEpg(it)) }, { showGuidePicker = false })
        state.epgEntry?.let { GuideDialog(it, state.programmes, state.epgLoading, onAction) }
        state.movieEntry?.let { MovieDialog(it, state.isBusy, it.id in state.favoriteIds, onAction) }
        state.seriesEntry?.let { SeriesDialog(it, state.episodes, state.isBusy, onAction) }
        state.error?.let { message ->
            if (customerMode) CustomerErrorDialog(message) { onAction(AppAction.DismissError) } else AlertDialog(
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
internal fun Brand() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        NeonEmblem(Modifier.size(46.dp))
        Column {
            Text("STARLINK", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface)
            Text("I P T V", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
        }
    }
}

@Composable
internal fun LibraryHeader(title: String, source: SourceConfig?, sources: List<SourceConfig>, onAction: (AppAction) -> Unit, loading: Boolean, customerMode: Boolean = false) {
    var menuOpen by remember { mutableStateOf(false) }
    val refreshDescription = stringResource(R.string.library_refresh)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (customerMode) androidx.compose.material3.IconButton(onClick = { onAction(AppAction.Refresh) }, enabled = !loading,
                modifier = Modifier.testTag("catalog-refresh").semantics { contentDescription = refreshDescription }) {
                Text("↻", style = MaterialTheme.typography.headlineMedium)
            }
        }
        if (!customerMode) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!customerMode) Box(Modifier.weight(1f)) {
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
        onClick = { onAction(when (entry.kind) { MediaKind.SERIES -> AppAction.OpenSeries(entry); MediaKind.MOVIE -> AppAction.OpenMovie(entry); else -> AppAction.Play(entry) }) },
        modifier = modifier.fillMaxWidth().testTag("catalog-item-${entry.id}").onFocusChanged { focused = it.isFocused }
            .border(BorderStroke(if (focused || playing) 2.dp else 0.dp, if (focused || playing) MaterialTheme.colorScheme.primary else Color.Transparent), shape),
        colors = CardDefaults.cardColors(containerColor = if (focused) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface),
        shape = shape,
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(if (entry.kind == MediaKind.LIVE) 2.15f else .72f)
                .background(Brush.linearGradient(listOf(Color(0xFF122B42), Color(0xFF24132E)))),
            contentAlignment = Alignment.Center,
        ) {
            Text(entry.name.take(2).uppercase(), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
            if (entry.logo.isNotBlank()) AsyncImage(entry.logo, contentDescription = null, modifier = Modifier.fillMaxSize().padding(12.dp), contentScale = ContentScale.Fit)
            if (playing) Text(stringResource(R.string.library_now_playing), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.align(Alignment.BottomStart).background(MaterialTheme.colorScheme.background.copy(alpha = .85f)).padding(6.dp))
        }
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(entry.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis)
            Text(CatalogDisplayGroup.group(entry).ifBlank { if (entry.kind == MediaKind.LIVE) stringResource(R.string.library_live_video) else stringResource(R.string.library_video) }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
private fun SettingsDialog(state: AppUiState, onAction: (AppAction) -> Unit, customerMode: Boolean, onGuide: () -> Unit, onFavorites: () -> Unit, onSources: () -> Unit, onPrivacy: () -> Unit, onForYou: () -> Unit, onDismiss: () -> Unit) {
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
                ActionButton(stringResource(R.string.library_tab_favorites), onFavorites, Modifier.fillMaxWidth())
                ActionButton(stringResource(if (customerMode) R.string.customer_account else R.string.library_sources), onSources, Modifier.fillMaxWidth())
                ActionButton(stringResource(R.string.library_tv_guide), onGuide, Modifier.fillMaxWidth())
                Text("Personalização STARLINK", style = MaterialTheme.typography.titleMedium)
                ActionButton("Editar preferências · Para Você", onForYou, Modifier.fillMaxWidth().testTag("settings-for-you"))
                Text("Perfil local: " + state.forYouProfile.name, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton("Principal", { onAction(AppAction.SwitchForYouProfile("default")); onDismiss() },
                        Modifier.weight(1f), compact = true, selected = state.activeForYouProfileId == "default")
                    ActionButton("Perfil 2", { onAction(AppAction.SwitchForYouProfile("secondary")); onDismiss() },
                        Modifier.weight(1f), compact = true, selected = state.activeForYouProfileId == "secondary")
                }
                ActionButton("Redefinir recomendações", { onAction(AppAction.ResetForYou); onDismiss() }, Modifier.fillMaxWidth(), compact = true)

                SupportButton()
                ActionButton(stringResource(R.string.neon_updates), { onAction(AppAction.CheckUpdate) }, Modifier.fillMaxWidth(), enabled = !state.checkingUpdate)
                if (state.updateAvailable) ActionButton(stringResource(R.string.neon_update_install), { onAction(AppAction.InstallUpdate) }, Modifier.fillMaxWidth(), enabled = !state.checkingUpdate)
                Text("STARLINK IPTV Prévia · " + play.ott.nativeapp.BuildConfig.VERSION_NAME, style = MaterialTheme.typography.labelSmall)
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
                if (!customerMode) {
                Text(stringResource(R.string.library_backup), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.library_backup_description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ActionButton(stringResource(R.string.library_export_settings), { onAction(AppAction.ExportSettings); onDismiss() }, Modifier.fillMaxWidth())
                ActionButton(stringResource(R.string.library_import_settings), { onAction(AppAction.ImportSettings); onDismiss() }, Modifier.fillMaxWidth())
                ActionButton(stringResource(R.string.library_add_demo), { onAction(AppAction.AddDemo); onDismiss() }, Modifier.fillMaxWidth())
                HorizontalDivider()
                }
                if (customerMode) ActionButton(stringResource(R.string.customer_logout), { onAction(AppAction.Logout); onDismiss() }, Modifier.fillMaxWidth())
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
internal fun FeaturedContent(entry: MediaEntry, onAction: (AppAction) -> Unit) {
    Card(onClick = { onAction(when (entry.kind) { MediaKind.SERIES -> AppAction.OpenSeries(entry); MediaKind.MOVIE -> AppAction.OpenMovie(entry); else -> AppAction.Play(entry) }) },
        modifier = Modifier.fillMaxWidth().testTag("featured-content")) {
        Box(Modifier.fillMaxWidth().height(240.dp).background(Brush.horizontalGradient(listOf(Color(0xFF112E46), Color(0xFF211130))))) {
            if (entry.logo.isNotBlank()) AsyncImage(entry.logo, null, Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(320.dp), contentScale = ContentScale.Crop)
            Column(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0xEE080B16), Color.Transparent))).padding(20.dp), verticalArrangement = Arrangement.Center) {
                Text("DESTAQUE", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(CatalogDisplayGroup.group(entry), style = MaterialTheme.typography.bodyMedium)
                Text(if (entry.kind == MediaKind.SERIES) "Ver temporadas e episódios" else "Assistir", color = Color.White)
            }
        }
    }
}
