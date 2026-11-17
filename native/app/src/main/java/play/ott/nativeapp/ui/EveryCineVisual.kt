package play.ott.nativeapp.ui

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.session.MediaController
import coil.compose.AsyncImage
import play.ott.nativeapp.R
import play.ott.nativeapp.core.MediaEntry
import play.ott.nativeapp.core.MediaKind
import play.ott.nativeapp.data.XtreamDisplayGroups

private val cinemaBlack = Color(0xFF050506)
private val cinemaPanel = Color(0xFF151519)
private val cinemaGrey = Color(0xFFA6A6AD)
private val cinemaWhite = Color(0xFFF7F7F8)
private val cinemaAccent = Color(0xFF29D8EB)

private fun openContent(entry: MediaEntry, onAction: (AppAction) -> Unit) {
    onAction(when (entry.kind) {
        MediaKind.SERIES -> AppAction.OpenSeries(entry)
        MediaKind.MOVIE -> AppAction.OpenMovie(entry)
        else -> AppAction.Play(entry)
    })
}

/**
 * Separate customer-mode visual shell, adapted from the actual Every Cine XML navigation:
 * ViewPager2 pages with kept state; banner + nested horizontal rails; live video above
 * its category/channel list; profile as the fourth destination.
 * Reuses STARLINK's models, Xtream/M81 provider, Media3 player and existing actions.
 */
@Composable
internal fun EveryCineVisualApp(
    state: AppUiState,
    onAction: (AppAction) -> Unit,
    controller: MediaController?,
    onPictureInPicture: () -> Unit,
    onFullscreenChanged: (Boolean) -> Unit,
    inPictureInPicture: Boolean,
) {
    var pageName by rememberSaveable { mutableStateOf(EveryCinePage.HOME.name) }
    val page = EveryCinePage.valueOf(pageName)
    var catalogKindName by rememberSaveable { mutableStateOf(MediaKind.MOVIE.name) }
    val catalogKind = MediaKind.valueOf(catalogKindName)
    var selectedCategory by rememberSaveable { mutableStateOf("") }
    var searchOpened by rememberSaveable { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf("") }
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var lastPlayingId by rememberSaveable { mutableStateOf<String?>(null) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showSource by rememberSaveable { mutableStateOf(false) }
    var showGuide by rememberSaveable { mutableStateOf(false) }
    var showForYou by rememberSaveable { mutableStateOf(false) }
    var showPrivacy by rememberSaveable { mutableStateOf(false) }
    val savedPages = rememberSaveableStateHolder()
    val snackbar = remember { SnackbarHostState() }

    val isTv = LocalConfiguration.current.uiMode and Configuration.UI_MODE_TYPE_MASK ==
        Configuration.UI_MODE_TYPE_TELEVISION
    val mergedGroups = remember(state.xtreamGroups) { XtreamDisplayGroups.merge(state.xtreamGroups) }
    val hasRemoteCategories = mergedGroups.isNotEmpty()
    val sourceIds = remember(state.sources) { state.sources.map { it.id }.toSet() }
    val recent = remember(state.recent, sourceIds) {
        state.recent.filter { it.entry.sourceId in sourceIds }.take(24)
    }
    fun browse(kind: MediaKind, category: String) {
        if (kind == MediaKind.LIVE) pageName = EveryCinePage.LIVE.name
        else {
            pageName = EveryCinePage.CATALOG.name
            catalogKindName = if (kind == MediaKind.SERIES) MediaKind.SERIES.name else MediaKind.MOVIE.name
        }
        selectedCategory = category
        search = ""
        if (hasRemoteCategories && category.isNotBlank()) {
            mergedGroups.firstOrNull { it.kind == kind && it.label == category }?.let { group ->
                onAction(AppAction.OpenXtreamGroups(kind, group.sources.map { it.id }))
            }
        }
    }
    LaunchedEffect(state.playingEntry?.id) {
        val current = state.playingEntry
        if (current == null) {
            lastPlayingId = null
            fullscreen = false
        } else if (lastPlayingId != current.id) {
            lastPlayingId = current.id
            fullscreen = current.kind != MediaKind.LIVE || pageName != EveryCinePage.LIVE.name
        }
    }
    LaunchedEffect(fullscreen, inPictureInPicture) { onFullscreenChanged(fullscreen || inPictureInPicture) }
    LaunchedEffect(state.notice) {
        state.notice?.let { snackbar.showSnackbar(it); onAction(AppAction.DismissNotice) }
    }
    BackHandler(enabled = fullscreen && state.playingEntry != null && !inPictureInPicture) {
        if (state.playingEntry?.kind == MediaKind.LIVE) fullscreen = false else onAction(AppAction.StopPlayback)
    }
    BackHandler(enabled = !fullscreen && page != EveryCinePage.HOME && !showSettings && !searchOpened && !inPictureInPicture) {
        pageName = EveryCinePage.HOME.name
    }
    Box(Modifier.fillMaxSize().background(cinemaBlack).testTag("everycine-visual-shell")) {
        if ((fullscreen || inPictureInPicture) && state.playingEntry != null) {
            NativePlayerPane(
                state.playingEntry, controller, true,
                onFullscreen = {
                    if (state.playingEntry.kind == MediaKind.LIVE) fullscreen = false
                    else onAction(AppAction.StopPlayback)
                },
                onPictureInPicture = onPictureInPicture,
                onStop = { onAction(AppAction.StopPlayback) },
                onRetry = { onAction(AppAction.Play(state.playingEntry)) },
                onTrackPreference = { audio, pref -> onAction(AppAction.SetTrackPreference(audio, pref)) },
                modifier = Modifier.fillMaxSize(), inPictureInPicture = inPictureInPicture,
            )
        } else {
            Column(Modifier.fillMaxSize()) {
                EveryCineHeader(onSearch = { searchOpened = !searchOpened; if (!searchOpened) search = "" })
                if (searchOpened) {
                    OutlinedTextField(
                        value = search, onValueChange = { search = it },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag("everycine-search"),
                        placeholder = { Text("Buscar no STARLINK") }, singleLine = true,
                        shape = RoundedCornerShape(8.dp),
                    )
                }
                Box(Modifier.weight(1f)) {
                    savedPages.SaveableStateProvider(pageName) {
                        when (page) {
                            EveryCinePage.HOME -> EveryCineHome(
                                state, recent, onAction, search,
                                onBrowse = ::browse,
                            )
                            EveryCinePage.LIVE -> EveryCineLive(
                                state, controller, onAction, search, selectedCategory,
                                mergedGroups.filter { it.kind == MediaKind.LIVE }.map { it.label },
                                onSelectCategory = { browse(MediaKind.LIVE, it) },
                                onFullscreen = { fullscreen = true },
                            )
                            EveryCinePage.CATALOG -> EveryCineCatalog(
                                state, onAction, search, catalogKind, selectedCategory,
                                mergedGroups.filter { it.kind == catalogKind }.map { it.label },
                                onKind = { catalogKindName = it.name; selectedCategory = "" },
                                onCategory = { browse(catalogKind, it) },
                                hasRemoteCategories = hasRemoteCategories,
                            )
                            EveryCinePage.PROFILE -> EveryCineProfile(
                                state, recent, onAction,
                                onSettings = { showSettings = true },
                                onBrowse = ::browse,
                            )
                        }
                    }
                }
                EveryCineBottomNavigation(page, onSelect = {
                    pageName = it.name
                    search = ""
                    searchOpened = false
                    selectedCategory = ""
                })
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }

    state.epgEntry?.let { GuideDialog(it, state.programmes, state.epgLoading, onAction) }
    state.movieEntry?.let {
        EveryCineMovieDetail(it, state.isBusy, it.id in state.favoriteIds, onAction)
    }
    state.seriesEntry?.let { SeriesDialog(it, state.episodes, state.isBusy, onAction) }
    state.error?.let { CustomerErrorDialog(it) { onAction(AppAction.DismissError) } }
    if (showGuide) {
        NeonGuidePicker(state.entries, {
            showGuide = false
            onAction(AppAction.OpenEpg(it))
        }, { showGuide = false })
    }
    if (showSettings) SettingsDialog(
        state, onAction, customerMode = true,
        onGuide = { showGuide = true; showSettings = false },
        onFavorites = { pageName = EveryCinePage.PROFILE.name; showSettings = false },
        onSources = { showSource = true; showSettings = false },
        onPrivacy = { showPrivacy = true },
        onForYou = { showForYou = true; showSettings = false },
        onDismiss = { showSettings = false },
    )
    if (showPrivacy) PrivacyPolicyDialog { showPrivacy = false }
    if (showForYou) ForYouDialog(
        state.forYouProfile,
        onSave = { topics, subs, dislikes, consent ->
            onAction(AppAction.SaveForYou(topics, subs, dislikes, consent)); showForYou = false
        },
        onSkip = { onAction(AppAction.SkipForYou); showForYou = false },
    )
    if (showSource) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { showSource = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        ) {
            CustomerLoginScreen(
                state.sources.firstOrNull { it.kind == play.ott.nativeapp.core.SourceKind.XTREAM },
                state.isBusy, { onAction(AppAction.SaveSource(it)); showSource = false },
                { showSource = false },
            )
        }
    }
}

@Composable
private fun EveryCineHeader(onSearch: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(52.dp).background(cinemaBlack).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NeonEmblem(Modifier.size(42.dp))
        Spacer(Modifier.width(8.dp))
        Text("STARLINK", color = cinemaWhite, fontWeight = FontWeight.Black, fontSize = 20.sp,
            modifier = Modifier.weight(1f), letterSpacing = 1.sp)
        Text("⌕", color = cinemaWhite, fontSize = 32.sp, modifier = Modifier
            .size(44.dp).clickable(onClick = onSearch).testTag("everycine-search-entry"),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
private fun EveryCineBottomNavigation(page: EveryCinePage, onSelect: (EveryCinePage) -> Unit) {
    val items = listOf(
        Triple(EveryCinePage.HOME, "Início", "⌂"),
        Triple(EveryCinePage.LIVE, "Ao vivo", "▣"),
        Triple(EveryCinePage.CATALOG, "Catálogo", "▤"),
        Triple(EveryCinePage.PROFILE, "Perfil", "◉"),
    )
    Row(
        Modifier.fillMaxWidth().height(56.dp).background(Color(0xFF111115))
            .testTag("everycine-bottom-navigation"),
    ) {
        items.forEach { (destination, label, symbol) ->
            val selected = page == destination
            Column(
                Modifier.weight(1f).fillMaxHeight().clickable { onSelect(destination) }
                    .testTag("everycine-tab-" + destination.name),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(symbol, fontSize = 23.sp, color = if (selected) cinemaWhite else cinemaGrey)
                Text(label, fontSize = 11.sp, color = if (selected) cinemaWhite else cinemaGrey,
                    maxLines = 1)
            }
        }
    }
}

@Composable
private fun EveryCineHome(
    state: AppUiState,
    recent: List<play.ott.nativeapp.data.RecentPlayback>,
    onAction: (AppAction) -> Unit,
    search: String,
    onBrowse: (MediaKind, String) -> Unit,
) {
    val content = remember(state.entries, recent, search) {
        state.entries.filter { search.isBlank() || it.name.contains(search, ignoreCase = true) }
    }
    val banners = remember(content, recent) { everyCineBannerEntries(content, recent) }
    val sections = remember(content, recent, state.favoriteIds) {
        everyCineSections(content, recent, state.favoriteIds)
    }
    val remoteCategories = remember(state.xtreamGroups) {
        XtreamDisplayGroups.merge(state.xtreamGroups).filter {
            it.kind == MediaKind.MOVIE || it.kind == MediaKind.SERIES || it.kind == MediaKind.LIVE
        }.take(24)
    }
    LazyColumn(
        Modifier.fillMaxSize().testTag("everycine-home"),
        contentPadding = PaddingValues(bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "banner") {
            EveryCineFeatureBanner(banners, onClick = { selected ->
                if (selected != null) openContent(selected, onAction)
                else onBrowse(MediaKind.MOVIE, "")
            })
        }
        if (state.isBusy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (remoteCategories.isNotEmpty()) item(key = "categories") {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("Explore as categorias", color = cinemaWhite,
                    fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(start = 12.dp))
                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(remoteCategories, key = { it.kind.name + it.label }) { group ->
                        EveryCineCategoryChip(group.label, false) { onBrowse(group.kind, group.label) }
                    }
                }
            }
        }
        items(sections, key = { it.title }) { section ->
            Column(Modifier.fillMaxWidth().testTag("everycine-section-" + section.title)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(section.title, Modifier.weight(1f), color = cinemaWhite,
                        fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text("Ver tudo", color = cinemaGrey, fontSize = 13.sp,
                        modifier = Modifier.clickable {
                            val first = section.entries.first()
                            onBrowse(first.kind, if (section.title == first.group) first.group else "")
                        }.padding(6.dp))
                }
                Spacer(Modifier.height(14.dp))
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(section.entries, key = { it.sourceId + ":" + it.id }) { entry ->
                        EveryCinePoster(entry, onClick = { openContent(entry, onAction) })
                    }
                }
            }
        }
        if (sections.isEmpty() && !state.isBusy) item {
            Text("Escolha uma categoria acima para carregar o catálogo STARLINK.",
                color = cinemaGrey, modifier = Modifier.padding(20.dp))
        }
    }
}

@Composable
private fun EveryCineFeatureBanner(entries: List<MediaEntry>, onClick: (MediaEntry?) -> Unit) {
    val screenHeight = LocalConfiguration.current.screenHeightDp
    val heroHeight = if (screenHeight < 420) 190.dp else 454.dp
    val pager = rememberPagerState(pageCount = { maxOf(1, entries.size) })
    Box(Modifier.fillMaxWidth().height(heroHeight).testTag("everycine-feature-banner")) {
        HorizontalPager(
            state = pager, modifier = Modifier.fillMaxSize().testTag("everycine-feature-pager"),
        ) { index ->
            val entry = entries.getOrNull(index)
            Box(Modifier.fillMaxSize().background(cinemaPanel).clickable { onClick(entry) }) {
                if (entry?.logo?.isNotBlank() == true) AsyncImage(
                    entry.logo, contentDescription = null, modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = .15f),
                    .45f to Color.Black.copy(alpha = .03f),
                    1f to cinemaBlack,
                )))
                Column(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 30.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("STARLINK  •  PARA VOCÊ", fontSize = 11.sp, color = cinemaWhite,
                        letterSpacing = 1.6.sp)
                    Text(entry?.name ?: "Seus filmes e séries em um só lugar",
                        fontSize = 27.sp, fontWeight = FontWeight.Black, color = cinemaWhite,
                        maxLines = 2, lineHeight = 30.sp)
                    Text(if (entry != null) "Ver detalhes  ▶" else "Explorar catálogo  ›",
                        color = cinemaWhite, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                }
            }
        }
        if (entries.size > 1) Row(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 13.dp)
                .testTag("everycine-banner-indicator"),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            entries.indices.forEach { i ->
                Box(Modifier.width(if (pager.currentPage == i) 30.dp else 20.dp)
                    .height(6.dp).background(
                        if (pager.currentPage == i) Color.White else Color.White.copy(alpha = .5f),
                        RoundedCornerShape(4.dp),
                    ))
            }
        }
    }
}

@Composable
private fun EveryCinePoster(entry: MediaEntry, onClick: () -> Unit) {
    Column(Modifier.width(134.dp).clickable(onClick = onClick).testTag("everycine-poster-" + entry.id)) {
        Box(Modifier.size(width = 134.dp, height = 186.dp)
            .background(cinemaPanel, RoundedCornerShape(6.dp))) {
            if (entry.logo.isNotBlank()) AsyncImage(
                entry.logo, contentDescription = entry.name, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            ) else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(entry.name.take(2).uppercase(), color = cinemaGrey, fontSize = 26.sp)
            }
            if (entry.kind == MediaKind.SERIES) Text(
                "SÉRIE", color = cinemaWhite, fontSize = 10.sp,
                modifier = Modifier.align(Alignment.BottomStart)
                    .background(Color.Black.copy(alpha = .7f)).padding(5.dp),
            )
        }
        Text(entry.name, modifier = Modifier.padding(top = 8.dp),
            color = cinemaWhite, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun EveryCineCategoryChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) cinemaWhite else Color(0xFF202024),
        shape = RoundedCornerShape(30.dp),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(label, color = if (selected) cinemaBlack else cinemaWhite,
            fontSize = 13.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp))
    }
}

@Composable
private fun EveryCineLive(
    state: AppUiState,
    controller: MediaController?,
    onAction: (AppAction) -> Unit,
    search: String,
    selectedCategory: String,
    remoteCategories: List<String>,
    onSelectCategory: (String) -> Unit,
    onFullscreen: () -> Unit,
) {
    val categories = if (remoteCategories.isNotEmpty()) remoteCategories
        else state.entries.filter { it.kind == MediaKind.LIVE }.map { it.group }.filter { it.isNotBlank() }.distinct()
    val channels = remember(state.entries, search, selectedCategory, remoteCategories) {
        // Xtream category entries can have a different original group name;
        // display their real names without guessing or rewriting the provider group.
        everyCineVisible(state.entries, MediaKind.LIVE,
            if (remoteCategories.isNotEmpty()) "" else selectedCategory, search)
    }
    val compact = LocalConfiguration.current.screenHeightDp < 420
    if (compact) {
        // Rotate or shrink: preserve the two functional parts side-by-side.
        Row(Modifier.fillMaxSize().testTag("everycine-live-screen")) {
            EveryCineInlinePlayer(state, controller, onAction, onFullscreen,
                Modifier.weight(1f).fillMaxHeight())
            Column(Modifier.weight(1f).fillMaxHeight()) {
                EveryCineLiveCategories(categories, selectedCategory, onSelectCategory)
                if (state.isBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                EveryCineChannelRows(channels, state, onAction, onFullscreen, Modifier.weight(1f))
            }
        }
    } else {
        Column(Modifier.fillMaxSize().testTag("everycine-live-screen")) {
            EveryCineInlinePlayer(state, controller, onAction, onFullscreen,
                Modifier.fillMaxWidth().height(209.dp))
            EveryCineLiveCategories(categories, selectedCategory, onSelectCategory)
            if (state.isBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
            EveryCineChannelRows(channels, state, onAction, onFullscreen, Modifier.weight(1f))
        }
    }
}

@Composable
private fun EveryCineInlinePlayer(
    state: AppUiState,
    controller: MediaController?,
    onAction: (AppAction) -> Unit,
    onFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.background(Color.Black).testTag("everycine-inline-player")) {
        val playing = state.playingEntry?.takeIf { it.kind == MediaKind.LIVE }
        if (playing == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                NeonEmblem(Modifier.size(50.dp))
                Spacer(Modifier.height(8.dp))
                Text("Selecione um canal", color = cinemaGrey, fontSize = 13.sp)
            }
        } else NativePlayerPane(
            playing, controller, false,
            onFullscreen = onFullscreen,
            onPictureInPicture = {},
            onStop = { onAction(AppAction.StopPlayback) },
            onRetry = { onAction(AppAction.Play(playing)) },
            onTrackPreference = { audio, pref -> onAction(AppAction.SetTrackPreference(audio, pref)) },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun EveryCineLiveCategories(
    categories: List<String>, selectedCategory: String, onSelectCategory: (String) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().testTag("everycine-live-categories")
            .padding(top = 8.dp, bottom = 5.dp),
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { EveryCineCategoryChip("Todos", selectedCategory.isBlank()) { onSelectCategory("") } }
        items(categories, key = { it }) { name ->
            EveryCineCategoryChip(name, name == selectedCategory) { onSelectCategory(name) }
        }
    }
}

@Composable
private fun EveryCineChannelRows(
    channels: List<MediaEntry>,
    state: AppUiState,
    onAction: (AppAction) -> Unit,
    onFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier.testTag("everycine-channel-list"),
        contentPadding = PaddingValues(bottom = 20.dp),
    ) {
        items(channels, key = { it.sourceId + ":" + it.id }) { channel ->
            val selected = state.playingEntry?.id == channel.id
            Row(
                Modifier.fillMaxWidth().clickable {
                    if (selected) onFullscreen() else onAction(AppAction.Play(channel))
                }.padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.size(55.dp).background(cinemaPanel, RoundedCornerShape(6.dp)),
                    contentAlignment = Alignment.Center) {
                    if (channel.logo.isNotBlank()) AsyncImage(
                        channel.logo, contentDescription = null,
                        modifier = Modifier.fillMaxSize().padding(3.dp),
                        contentScale = ContentScale.Fit,
                    ) else Text("TV", color = cinemaGrey, fontSize = 14.sp)
                }
                Column(Modifier.weight(1f)) {
                    Text(channel.name, color = cinemaWhite,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(channel.group.ifBlank { "Ao vivo" }, color = cinemaGrey,
                        fontSize = 12.sp, maxLines = 1)
                }
                Text(if (selected) "▶" else "▷", color = cinemaAccent, fontSize = 21.sp)
                Text(if (channel.id in state.favoriteIds) "♥" else "♡",
                    color = cinemaWhite, fontSize = 22.sp, modifier = Modifier
                        .clickable { onAction(AppAction.ToggleFavorite(channel)) }
                        .padding(4.dp))
            }
            HorizontalDivider(color = Color(0xFF222225), thickness = .5.dp)
        }
        if (channels.isEmpty() && !state.isBusy) item {
            Text("Selecione uma categoria para carregar os canais.",
                color = cinemaGrey, modifier = Modifier.padding(20.dp))
        }
    }
}

@Composable
private fun EveryCineCatalog(
    state: AppUiState,
    onAction: (AppAction) -> Unit,
    search: String,
    kind: MediaKind,
    selectedCategory: String,
    remoteCategories: List<String>,
    onKind: (MediaKind) -> Unit,
    onCategory: (String) -> Unit,
    hasRemoteCategories: Boolean,
) {
    val categories = if (remoteCategories.isNotEmpty()) remoteCategories
        else state.entries.filter { it.kind == kind }.map { it.group }.filter { it.isNotBlank() }.distinct()
    val visible = remember(state.entries, kind, search, selectedCategory, remoteCategories) {
        everyCineVisible(state.entries, kind,
            if (hasRemoteCategories) "" else selectedCategory, search)
    }
    Column(Modifier.fillMaxSize().testTag("everycine-catalog-screen")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EveryCineCategoryChip("Filmes", kind == MediaKind.MOVIE) { onKind(MediaKind.MOVIE) }
            EveryCineCategoryChip("Séries", kind == MediaKind.SERIES) { onKind(MediaKind.SERIES) }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { EveryCineCategoryChip("Tudo", selectedCategory.isBlank()) { onCategory("") } }
            items(categories, key = { it }) { name ->
                EveryCineCategoryChip(name, name == selectedCategory) { onCategory(name) }
            }
        }
        Spacer(Modifier.height(10.dp))
        if (state.isBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyVerticalGrid(
            columns = GridCells.Adaptive(134.dp),
            modifier = Modifier.fillMaxSize().testTag("everycine-catalog-posters"),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        ) {
            gridItems(visible, key = { it.sourceId + ":" + it.id }) { entry ->
                EveryCinePoster(entry) { openContent(entry, onAction) }
            }
            if (visible.isEmpty() && !state.isBusy) item {
                Text("Escolha uma categoria para carregar o catálogo.", color = cinemaGrey)
            }
        }
    }
}

@Composable
private fun EveryCineProfile(
    state: AppUiState,
    recent: List<play.ott.nativeapp.data.RecentPlayback>,
    onAction: (AppAction) -> Unit,
    onSettings: () -> Unit,
    onBrowse: (MediaKind, String) -> Unit,
) {
    val favorites = remember(state.entries, state.favoriteIds) {
        state.entries.filter { it.id in state.favoriteIds }.take(20)
    }
    LazyColumn(
        Modifier.fillMaxSize().testTag("everycine-profile"),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            Column(
                Modifier.fillMaxWidth().background(Brush.verticalGradient(
                    listOf(Color(0xFF1B2332), cinemaBlack)
                )).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                NeonEmblem(Modifier.size(56.dp))
                Spacer(Modifier.height(8.dp))
                Text("Meu STARLINK", color = cinemaWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("Seu catálogo, seu histórico e suas preferências",
                    color = cinemaGrey, fontSize = 12.sp)
            }
        }
        if (recent.isNotEmpty()) item {
            EveryCineProfileRow("Histórico", recent.map { it.entry }.distinctBy { it.id }.take(20)) {
                openContent(it, onAction)
            }
        }
        item {
            EveryCineProfileRow("Minha lista", favorites) { openContent(it, onAction) }
        }
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Configurações e conta", color = cinemaWhite, fontSize = 18.sp,
                    fontWeight = FontWeight.Bold)
                ActionButton("Minha conta e preferências", onSettings,
                    Modifier.fillMaxWidth().testTag("everycine-profile-settings"))
                ActionButton("Ver canais ao vivo", { onBrowse(MediaKind.LIVE, "") }, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun EveryCineProfileRow(title: String, entries: List<MediaEntry>, onOpen: (MediaEntry) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, color = cinemaWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 12.dp))
        if (entries.isEmpty()) Text("Sua lista está vazia.", color = cinemaGrey,
            modifier = Modifier.padding(horizontal = 12.dp))
        else LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(entries, key = { it.sourceId + ":" + it.id }) { entry ->
                EveryCinePoster(entry) { onOpen(entry) }
            }
        }
    }
}

@Composable
private fun EveryCineMovieDetail(
    entry: MediaEntry, loading: Boolean, favorite: Boolean, onAction: (AppAction) -> Unit,
) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = { onAction(AppAction.CloseMovie) },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(color = cinemaBlack, modifier = Modifier.fillMaxSize()
            .testTag("everycine-movie-details")) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 22.dp)) {
                item {
                    Box(Modifier.fillMaxWidth().height(330.dp)) {
                        if (entry.logo.isNotBlank()) AsyncImage(entry.logo, null,
                            Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                            listOf(Color.Black.copy(alpha = .2f), cinemaBlack)
                        )))
                        Text("‹", color = cinemaWhite, fontSize = 34.sp,
                            modifier = Modifier.align(Alignment.TopStart)
                                .clickable { onAction(AppAction.CloseMovie) }.padding(14.dp))
                        Text(entry.name, color = cinemaWhite, fontSize = 26.sp,
                            fontWeight = FontWeight.Bold, maxLines = 3,
                            modifier = Modifier.align(Alignment.BottomCenter)
                                .padding(horizontal = 16.dp, vertical = 18.dp))
                    }
                }
                item {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        ActionButton("▶  Reproduzir", {
                            onAction(AppAction.Play(entry)); onAction(AppAction.CloseMovie)
                        }, Modifier.fillMaxWidth(), selected = true, enabled = !loading)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(entry.group, color = cinemaGrey, fontSize = 13.sp,
                                modifier = Modifier.weight(1f))
                            Text(if (favorite) "♥ Na lista" else "♡ Minha lista",
                                color = cinemaWhite, modifier = Modifier.clickable {
                                    onAction(AppAction.ToggleFavorite(entry))
                                })
                        }
                        if (entry.description.isNotBlank()) Text(entry.description,
                            color = cinemaWhite, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}
