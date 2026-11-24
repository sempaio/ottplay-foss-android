package play.ott.nativeapp.ui

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.platform.LocalContext
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
    val pagePager = rememberPagerState(pageCount = { EveryCinePage.entries.size })
    val snackbar = remember { SnackbarHostState() }
    // MainFragment in the reference is a ViewPager2. Keep swipe navigation
    // and bottom-nav selection synchronized, without dropping each page state.
    LaunchedEffect(pageName) {
        val index = EveryCinePage.entries.indexOf(EveryCinePage.valueOf(pageName))
        if (pagePager.currentPage != index) pagePager.scrollToPage(index)
    }
    LaunchedEffect(pagePager.currentPage) {
        val current = EveryCinePage.entries[pagePager.currentPage].name
        if (current != pageName) {
            pageName = current
            selectedCategory = ""
            search = ""
        }
    }

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
        if (kind != MediaKind.LIVE && hasRemoteCategories && category.isNotBlank()) {
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
    BackHandler(enabled = searchOpened) {
        searchOpened = false
        search = ""
    }
    BackHandler(enabled = !fullscreen && page != EveryCinePage.HOME && !showSettings && !showSource && !showGuide && !showForYou && !searchOpened && !inPictureInPicture) {
        pageName = EveryCinePage.HOME.name
    }
    val rootContext = LocalContext.current
    val liveChannelsForZap = remember(state.entries) { state.entries.filter { it.kind == MediaKind.LIVE } }
    Box(Modifier.fillMaxSize().background(cinemaBlack).testTag("everycine-visual-shell")) {
        if ((fullscreen || inPictureInPicture) && state.playingEntry != null) {
            val currentLiveIndex = if (state.playingEntry.kind == MediaKind.LIVE) {
                liveChannelsForZap.indexOfFirst { it.id == state.playingEntry.id }
            } else -1
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
                onPrevious = if (state.playingEntry.kind == MediaKind.LIVE && liveChannelsForZap.size > 1) {
                    {
                        val prevIdx = if (currentLiveIndex > 0) currentLiveIndex - 1 else liveChannelsForZap.lastIndex
                        onAction(AppAction.Play(liveChannelsForZap[prevIdx]))
                    }
                } else null,
                onNext = if (state.playingEntry.kind == MediaKind.LIVE && liveChannelsForZap.size > 1) {
                    {
                        val nextIdx = if (currentLiveIndex in 0 until liveChannelsForZap.lastIndex) currentLiveIndex + 1 else 0
                        onAction(AppAction.Play(liveChannelsForZap[nextIdx]))
                    }
                } else null,
                onCast = { openCastOrMirroring(rootContext, state.playingEntry) },
            )
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
            ) {
                EveryCineHeader(
                    search = search,
                    searchOpened = searchOpened,
                    onSearchToggle = {
                        searchOpened = !searchOpened
                        if (!searchOpened) search = ""
                    },
                    onSearchChange = { search = it },
                )
                if (searchOpened && search.isNotBlank()) {
                    EveryCineGlobalSearchResults(
                        search = search,
                        state = state,
                        onAction = onAction,
                        onFullscreen = { fullscreen = true },
                        onSelectEntry = { entry ->
                            when (entry.kind) {
                                MediaKind.LIVE -> {
                                    onAction(AppAction.Play(entry))
                                    pageName = EveryCinePage.LIVE.name
                                }
                                MediaKind.MOVIE -> onAction(AppAction.OpenMovie(entry))
                                MediaKind.SERIES -> onAction(AppAction.OpenSeries(entry))
                                else -> onAction(AppAction.Play(entry))
                            }
                        },
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    HorizontalPager(
                        state = pagePager, modifier = Modifier.weight(1f)
                            .testTag("everycine-main-pager"),
                    ) { pageIndex ->
                        val pane = EveryCinePage.entries[pageIndex]
                        savedPages.SaveableStateProvider(pane.name) {
                            when (pane) {
                                EveryCinePage.HOME -> EveryCineHome(
                                    state, recent, onAction, search,
                                    onBrowse = ::browse,
                                )
                                EveryCinePage.LIVE -> EveryCineLive(
                                    state, controller, onAction, search, selectedCategory,
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
                }
                EveryCineBottomNavigation(
                    page = page,
                    onSelect = {
                        pageName = it.name
                        search = ""
                        searchOpened = false
                        selectedCategory = ""
                    },
                )
            }
        }
        SnackbarHost(
            snackbar,
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 64.dp),
        )
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
private fun EveryCineHeader(
    search: String,
    searchOpened: Boolean,
    onSearchToggle: () -> Unit,
    onSearchChange: (String) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(cinemaBlack)
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().height(48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NeonEmblem(Modifier.size(38.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                "STARLINK",
                color = cinemaWhite,
                fontWeight = FontWeight.Black,
                fontSize = 20.sp,
                modifier = Modifier.weight(1f),
                letterSpacing = 1.sp
            )
            Text(
                if (searchOpened) "✕" else "⌕",
                color = cinemaWhite,
                fontSize = 26.sp,
                modifier = Modifier
                    .size(40.dp)
                    .clickable(onClick = onSearchToggle)
                    .testTag("everycine-search-entry")
                    .padding(4.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
        if (searchOpened) {
            OutlinedTextField(
                value = search,
                onValueChange = onSearchChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .testTag("everycine-search"),
                placeholder = { Text("Pesquisar canais, filmes e séries no catálogo...", fontSize = 13.sp) },
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = cinemaAccent,
                    unfocusedBorderColor = Color(0xFF2C2C35),
                    focusedContainerColor = Color(0xFF141418),
                    unfocusedContainerColor = Color(0xFF141418),
                    focusedTextColor = cinemaWhite,
                    unfocusedTextColor = cinemaWhite,
                ),
                trailingIcon = {
                    if (search.isNotBlank()) {
                        Text(
                            "✕",
                            color = cinemaGrey,
                            modifier = Modifier
                                .clickable { onSearchChange("") }
                                .padding(8.dp)
                        )
                    }
                }
            )
        }
    }
}

@Composable
private fun EveryCineBottomNavigation(
    page: EveryCinePage,
    onSelect: (EveryCinePage) -> Unit,
    modifier: Modifier = Modifier,
) {
    val items = listOf(
        Triple(EveryCinePage.HOME, "Início", "⌂"),
        Triple(EveryCinePage.LIVE, "Ao vivo", "▣"),
        Triple(EveryCinePage.CATALOG, "Catálogo", "▤"),
        Triple(EveryCinePage.PROFILE, "Perfil", "◉"),
    )
    Surface(
        color = Color(0xFF111115),
        modifier = modifier.fillMaxWidth().testTag("everycine-bottom-navigation"),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(56.dp),
        ) {
            items.forEach { (destination, label, symbol) ->
                val selected = page == destination
                Column(
                    Modifier.weight(1f).fillMaxHeight().clickable { onSelect(destination) }
                        .testTag("everycine-tab-" + destination.name),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(symbol, fontSize = 23.sp, color = if (selected) cinemaAccent else cinemaGrey)
                    Text(label, fontSize = 11.sp, color = if (selected) cinemaAccent else cinemaGrey,
                        maxLines = 1, softWrap = false)
                }
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
    val homeCategories = remember(state.xtreamGroups, state.entries) {
        val live = EveryCineFamilyIndex.available(state.entries, state.xtreamGroups)
            .map { MediaKind.LIVE to it }
        val vod = XtreamDisplayGroups.merge(state.xtreamGroups)
            .filter { it.kind == MediaKind.MOVIE || it.kind == MediaKind.SERIES }
            .take(16).map { it.kind to it.label }
        live + vod
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
        if (homeCategories.isNotEmpty()) item(key = "categories") {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("Explore as categorias", color = cinemaWhite,
                    fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(start = 12.dp))
                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(homeCategories, key = { it.first.name + it.second }) { group ->
                        EveryCineCategoryChip(group.second, false) { onBrowse(group.first, group.second) }
                    }
                }
            }
        }
        items(sections.size, key = { "rail-" + it }) { sectionIndex ->
            val section = sections[sectionIndex]
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
private fun EveryCineGlobalSearchResults(
    search: String,
    state: AppUiState,
    onAction: (AppAction) -> Unit,
    onFullscreen: () -> Unit,
    onSelectEntry: (MediaEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val query = search.trim()

    // Execute full-catalog search across ALL live channels, movies, and series via ViewModel/CoreAPI
    LaunchedEffect(query) {
        onAction(AppAction.SearchCatalog(query))
    }

    val allMatches = remember(state.searchResults, state.searchQuery, state.entries, query) {
        val local = state.entries.filter {
            it.name.contains(query, ignoreCase = true) || it.group.contains(query, ignoreCase = true)
        }
        val remote = if (state.searchQuery.equals(query, ignoreCase = true)) state.searchResults else emptyList()
        (remote + local).distinctBy { it.sourceId to it.id }
    }
    val liveMatches = remember(allMatches) { allMatches.filter { it.kind == MediaKind.LIVE } }
    val movieMatches = remember(allMatches) { allMatches.filter { it.kind == MediaKind.MOVIE } }
    val seriesMatches = remember(allMatches) { allMatches.filter { it.kind == MediaKind.SERIES } }

    var selectedFilter by rememberSaveable { mutableStateOf("Todos") }
    LaunchedEffect(query) { selectedFilter = "Todos" }

    val displayedMatches = when (selectedFilter) {
        "Canais" -> liveMatches
        "Filmes" -> movieMatches
        "Séries" -> seriesMatches
        else -> allMatches
    }

    Column(modifier.fillMaxSize().background(cinemaBlack)) {
        if (state.searchBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
        // Quick filter pills
        LazyRow(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                EveryCineCategoryChip("Todos (${allMatches.size})", selectedFilter == "Todos") {
                    selectedFilter = "Todos"
                }
            }
            if (liveMatches.isNotEmpty()) item {
                EveryCineCategoryChip("Canais (${liveMatches.size})", selectedFilter == "Canais") {
                    selectedFilter = "Canais"
                }
            }
            if (movieMatches.isNotEmpty()) item {
                EveryCineCategoryChip("Filmes (${movieMatches.size})", selectedFilter == "Filmes") {
                    selectedFilter = "Filmes"
                }
            }
            if (seriesMatches.isNotEmpty()) item {
                EveryCineCategoryChip("Séries (${seriesMatches.size})", selectedFilter == "Séries") {
                    selectedFilter = "Séries"
                }
            }
        }

        if (displayedMatches.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    when {
                        query.length < 2 -> "Digite pelo menos 2 letras para buscar em todo o catálogo."
                        state.searchBusy -> "Buscando \"$search\" em canais, filmes e séries..."
                        else -> "Nenhum resultado para \"$search\" no catálogo."
                    },
                    color = cinemaGrey,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(24.dp)
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp, top = 4.dp),
            ) {
                items(displayedMatches, key = { it.sourceId + ":" + it.id }) { item ->
                    val isPlaying = state.playingEntry?.id == item.id
                    val isFav = item.id in state.favoriteIds

                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onSelectEntry(item) }
                            .background(if (isPlaying) Color(0xFF0C242E) else Color.Transparent)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        // Badge / Poster (initials shown until logo loads or if URL fails)
                        var searchLogoLoaded by remember(item.logo) { mutableStateOf(false) }
                        Box(
                            Modifier
                                .size(if (item.kind == MediaKind.LIVE) 50.dp else 45.dp, 50.dp)
                                .background(Color(0xFF1B1B22), RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            if (!searchLogoLoaded) {
                                val initials = item.name.split(" ")
                                    .filter { it.isNotBlank() }
                                    .take(2)
                                    .map { it.first().uppercaseChar() }
                                    .joinToString("")
                                    .ifBlank { "TV" }
                                Text(
                                    initials,
                                    color = Color(0xFFA5B4FC),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            if (item.logo.isNotBlank()) {
                                AsyncImage(
                                    item.logo,
                                    contentDescription = item.name,
                                    modifier = Modifier.fillMaxSize().padding(3.dp),
                                    contentScale = ContentScale.Fit,
                                    onSuccess = { searchLogoLoaded = true },
                                    onError = { searchLogoLoaded = false },
                                )
                            }
                        }

                        // Text details
                        Column(Modifier.weight(1f)) {
                            Text(
                                item.name,
                                color = if (isPlaying) cinemaAccent else cinemaWhite,
                                fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.SemiBold,
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            val typeLabel = when (item.kind) {
                                MediaKind.LIVE -> "Canal Ao Vivo"
                                MediaKind.MOVIE -> "Filme"
                                MediaKind.SERIES -> "Série"
                                else -> "Vídeo"
                            }
                            Text(
                                "$typeLabel • ${item.group.ifBlank { "Catálogo" }}",
                                color = cinemaGrey,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        // Actions for Live vs VOD
                        if (item.kind == MediaKind.LIVE) {
                            Box(
                                Modifier
                                    .background(Color(0xFF22222A), RoundedCornerShape(12.dp))
                                    .clickable { onAction(AppAction.OpenEpg(item)) }
                                    .padding(horizontal = 10.dp, vertical = 4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("Guia", color = cinemaWhite, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                            }
                            Text(
                                if (isPlaying) "▶" else "▷",
                                color = cinemaAccent,
                                fontSize = 20.sp,
                                modifier = Modifier.clickable {
                                    if (isPlaying) onFullscreen() else onAction(AppAction.Play(item))
                                }
                            )
                        } else {
                            Text("Abrir ›", color = cinemaAccent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }

                        Text(
                            if (isFav) "♥" else "♡",
                            color = if (isFav) Color(0xFFEF4444) else cinemaWhite,
                            fontSize = 20.sp,
                            modifier = Modifier
                                .clickable { onAction(AppAction.ToggleFavorite(item)) }
                                .padding(4.dp)
                        )
                    }
                    HorizontalDivider(color = Color(0xFF1E1E24), thickness = 0.5.dp)
                }
            }
        }
    }
}

@Composable
private fun EveryCineLive(
    state: AppUiState,
    controller: MediaController?,
    onAction: (AppAction) -> Unit,
    search: String,
    selectedCategory: String,
    onSelectCategory: (String) -> Unit,
    onFullscreen: () -> Unit,
) {
    val selected = selectedCategory.ifBlank { EveryCineFamilyIndex.ALL }
    val categories = remember(state.entries, state.xtreamGroups) {
        EveryCineFamilyIndex.available(state.entries, state.xtreamGroups)
    }
    var selectedSubcategory by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(selected) { selectedSubcategory = "" }

    // Identify remote groups for this category and trigger on-demand load if needed
    val categoryRemoteGroups = remember(state.xtreamGroups, selected) {
        state.xtreamGroups.filter { group ->
            group.kind == MediaKind.LIVE &&
                (selected == EveryCineFamilyIndex.ALL || EveryCineFamilyIndex.category("", group.name) == selected)
        }
    }
    LaunchedEffect(selected, categoryRemoteGroups.size) {
        if (categoryRemoteGroups.isNotEmpty()) {
            val neededIds = if (selected == EveryCineFamilyIndex.ALL) {
                categoryRemoteGroups.take(16).map { it.id }
            } else {
                categoryRemoteGroups.map { it.id }
            }
            onAction(AppAction.OpenXtreamGroups(MediaKind.LIVE, neededIds))
        }
    }

    // Channels matching category and search (preserves ALL channels: 1, 2, 3, 4, FHD, HD, etc.)
    val categoryChannels = remember(state.entries, selected, search) {
        state.entries.filter { entry ->
            entry.kind == MediaKind.LIVE &&
                (selected == EveryCineFamilyIndex.ALL || EveryCineFamilyIndex.category(entry.name, entry.group) == selected) &&
                (search.isBlank() || entry.name.contains(search, ignoreCase = true) || entry.group.contains(search, ignoreCase = true))
        }
    }

    // Available subcategories pills for this category (clean franchises only, only for specific categories)
    val subcategories = remember(categoryChannels, selected) {
        if (selected == EveryCineFamilyIndex.ALL) {
            emptyList<String>()
        } else {
            val found = LinkedHashSet<String>()
            for (channel in categoryChannels) {
                val sub = EveryCineFamilyIndex.subcategory(channel.name, channel.group)
                if (sub.isNotBlank()) found.add(sub)
            }
            found.toList().sortedWith(String.CASE_INSENSITIVE_ORDER)
        }
    }

    // Channels filtered by selected subcategory pill
    val filteredChannels = remember(categoryChannels, selectedSubcategory) {
        if (selectedSubcategory.isBlank() || selectedSubcategory == "Todos os grupos") {
            categoryChannels
        } else {
            categoryChannels.filter { entry ->
                EveryCineFamilyIndex.subcategory(entry.name, entry.group).equals(selectedSubcategory, ignoreCase = true)
            }
        }
    }

    @Composable fun liveChannelBrowser(modifier: Modifier) {
        Column(modifier) {
            // Level 1: Category Pills
            LazyRow(
                Modifier
                    .fillMaxWidth()
                    .testTag("everycine-live-categories")
                    .padding(top = 6.dp, bottom = 4.dp),
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    EveryCineCategoryChip("Todos", selected == EveryCineFamilyIndex.ALL) {
                        selectedSubcategory = ""
                        onSelectCategory("")
                    }
                }
                items(categories, key = { it }) { name ->
                    EveryCineCategoryChip(name, selected == name) {
                        selectedSubcategory = ""
                        onSelectCategory(name)
                    }
                }
            }
            if (state.isBusy) LinearProgressIndicator(Modifier.fillMaxWidth())

            // Level 2: Subcategories (only when available and non-empty)
            if (subcategories.isNotEmpty()) {
                LazyRow(
                    Modifier
                        .fillMaxWidth()
                        .testTag("everycine-live-subcategories")
                        .padding(vertical = 4.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        EveryCineCategoryChip(
                            "Todos os grupos",
                            selectedSubcategory.isBlank() || selectedSubcategory == "Todos os grupos"
                        ) {
                            selectedSubcategory = ""
                        }
                    }
                    items(subcategories, key = { it }) { subcat ->
                        EveryCineCategoryChip(
                            subcat,
                            selectedSubcategory.equals(subcat, ignoreCase = true)
                        ) {
                            selectedSubcategory = subcat
                        }
                    }
                }
            }

            // Level 3: Channel Rows (vertical list with logo fallback, Guia button, play & fav)
            EveryCineChannelRows(
                channels = filteredChannels,
                state = state,
                onAction = onAction,
                onFullscreen = onFullscreen,
                modifier = Modifier.weight(1f)
            )
        }
    }

    val compact = LocalConfiguration.current.screenHeightDp < 420
    if (compact) {
        Row(Modifier.fillMaxSize().testTag("everycine-live-screen")) {
            EveryCineInlinePlayer(
                state = state,
                controller = controller,
                channels = filteredChannels,
                onAction = onAction,
                onFullscreen = onFullscreen,
                modifier = Modifier.weight(1f).fillMaxHeight()
            )
            liveChannelBrowser(Modifier.weight(1f).fillMaxHeight())
        }
    } else {
        Column(Modifier.fillMaxSize().testTag("everycine-live-screen")) {
            EveryCineInlinePlayer(
                state = state,
                controller = controller,
                channels = filteredChannels,
                onAction = onAction,
                onFullscreen = onFullscreen,
                modifier = Modifier.fillMaxWidth().height(240.dp)
            )
            liveChannelBrowser(Modifier.weight(1f))
        }
    }
}

@Composable
private fun EveryCineInlinePlayer(
    state: AppUiState,
    controller: MediaController?,
    channels: List<MediaEntry>,
    onAction: (AppAction) -> Unit,
    onFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val playing = state.playingEntry?.takeIf { it.kind == MediaKind.LIVE }
    var resizeMode by rememberSaveable { mutableIntStateOf(androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT) }

    Column(modifier.background(Color.Black).testTag("everycine-inline-player")) {
        // Player Video View
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (playing == null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    NeonEmblem(Modifier.size(46.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("Selecione um canal para reproduzir", color = cinemaGrey, fontSize = 13.sp)
                }
            } else {
                NativePlayerPane(
                    playing, controller, false,
                    onFullscreen = onFullscreen,
                    onPictureInPicture = {},
                    onStop = { onAction(AppAction.StopPlayback) },
                    onRetry = { onAction(AppAction.Play(playing)) },
                    onTrackPreference = { audio, pref -> onAction(AppAction.SetTrackPreference(audio, pref)) },
                    modifier = Modifier.fillMaxSize(),
                    showHeaderBar = false,
                    externalResizeMode = resizeMode,
                    onResizeModeChange = { resizeMode = it },
                )
            }
        }

        // Top Player Controls Bar (Horizontally scrollable so buttons NEVER get cut off or wrap!)
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color(0xFF0F0F14))
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val currentIndex = remember(channels, playing?.id) {
                if (playing != null) channels.indexOfFirst { it.id == playing.id } else -1
            }

            // Ant (Previous Channel)
            PlayerBarButton(
                icon = "⏮",
                label = "Ant",
                enabled = channels.isNotEmpty(),
                onClick = {
                    if (channels.isNotEmpty()) {
                        val prevIndex = if (currentIndex > 0) currentIndex - 1 else channels.size - 1
                        onAction(AppAction.Play(channels[prevIndex]))
                    }
                }
            )

            // Próx (Next Channel)
            PlayerBarButton(
                icon = "⏭",
                label = "Próx",
                enabled = channels.isNotEmpty(),
                onClick = {
                    if (channels.isNotEmpty()) {
                        val nextIndex = if (currentIndex in 0 until channels.size - 1) currentIndex + 1 else 0
                        onAction(AppAction.Play(channels[nextIndex]))
                    }
                }
            )

            // Ajustar (Aspect Ratio: Fit -> Zoom -> Fill)
            val aspectLabel = when (resizeMode) {
                androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT -> "Ajustar"
                androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "Zoom"
                else -> "Preencher"
            }
            PlayerBarButton(
                icon = "📺",
                label = aspectLabel,
                enabled = playing != null,
                onClick = {
                    resizeMode = when (resizeMode) {
                        androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
                        else -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }
                }
            )

            // Cast (Native Android Cast Settings or external player, never shows "Nenhum app pode realizar esta ação")
            PlayerBarButton(
                icon = "📡",
                label = "Cast",
                enabled = playing != null,
                onClick = {
                    if (playing != null) openCastOrMirroring(context, playing)
                }
            )

            // Fullscreen
            PlayerBarButton(
                icon = "⛶",
                label = "Tela cheia",
                enabled = playing != null,
                onClick = onFullscreen
            )
        }
    }
}

private fun openCastOrMirroring(context: Context, entry: MediaEntry) {
    try {
        val castSettings = Intent(Settings.ACTION_CAST_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (castSettings.resolveActivity(context.packageManager) != null) {
            context.startActivity(castSettings)
            return
        }
    } catch (_: Exception) {
    }
    if (entry.url.startsWith("http://") || entry.url.startsWith("https://")) {
        try {
            val viewVideo = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(entry.url), "video/*")
                putExtra("title", entry.name)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val externalHandlers = context.packageManager.queryIntentActivities(viewVideo, 0)
                .filter { it.activityInfo.packageName != context.packageName }
            if (externalHandlers.isNotEmpty()) {
                context.startActivity(Intent.createChooser(viewVideo, "Transmitir: ${entry.name}"))
                return
            }
        } catch (_: Exception) {
        }
    }
    Toast.makeText(
        context,
        "Use o espelhamento de tela (Transmitir / Smart View) do aparelho para enviar para a TV.",
        Toast.LENGTH_LONG,
    ).show()
}

@Composable
private fun PlayerBarButton(
    icon: String,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .background(
                if (enabled) Color(0xFF1F1F27) else Color(0xFF141418),
                RoundedCornerShape(8.dp)
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(icon, fontSize = 12.sp, color = if (enabled) cinemaWhite else cinemaGrey, maxLines = 1, softWrap = false)
            Text(
                label,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (enabled) cinemaWhite else cinemaGrey,
                maxLines = 1,
                softWrap = false,
            )
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
            val isFavorite = channel.id in state.favoriteIds

            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        if (selected) onFullscreen() else onAction(AppAction.Play(channel))
                    }
                    .background(if (selected) Color(0xFF0C242E) else Color.Transparent)
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Channel Logo with Fallback Acronym Badge (shown when blank or failed)
                var channelLogoLoaded by remember(channel.logo) { mutableStateOf(false) }
                Box(
                    Modifier
                        .size(48.dp)
                        .background(Color(0xFF18181E), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    if (!channelLogoLoaded) {
                        val initials = channel.name.split(" ")
                            .filter { it.isNotBlank() }
                            .take(2)
                            .map { it.first().uppercaseChar() }
                            .joinToString("")
                            .ifBlank { "TV" }
                        Text(
                            initials,
                            color = Color(0xFFA5B4FC),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    if (channel.logo.isNotBlank()) {
                        AsyncImage(
                            channel.logo,
                            contentDescription = channel.name,
                            modifier = Modifier.fillMaxSize().padding(4.dp),
                            contentScale = ContentScale.Fit,
                            onSuccess = { channelLogoLoaded = true },
                            onError = { channelLogoLoaded = false },
                        )
                    }
                }

                // Name and Subtitle
                Column(Modifier.weight(1f)) {
                    Text(
                        channel.name,
                        color = if (selected) cinemaAccent else cinemaWhite,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val groupText = formatChannelGroup(channel.name, channel.group)
                    Text(
                        groupText,
                        color = cinemaGrey,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Guia EPG button
                Box(
                    Modifier
                        .background(Color(0xFF1F1F27), RoundedCornerShape(12.dp))
                        .clickable { onAction(AppAction.OpenEpg(channel)) }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Guia", color = cinemaWhite, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                }

                // Play / Active indicator
                Text(
                    if (selected) "▶" else "▷",
                    color = cinemaAccent,
                    fontSize = 20.sp,
                    modifier = Modifier.clickable {
                        if (selected) onFullscreen() else onAction(AppAction.Play(channel))
                    }
                )

                // Favorite
                Text(
                    if (isFavorite) "♥" else "♡",
                    color = if (isFavorite) Color(0xFFEF4444) else cinemaWhite.copy(alpha = 0.8f),
                    fontSize = 20.sp,
                    modifier = Modifier
                        .clickable { onAction(AppAction.ToggleFavorite(channel)) }
                        .padding(2.dp)
                )
            }
            HorizontalDivider(color = Color(0xFF1B1B20), thickness = 0.5.dp)
        }
        if (channels.isEmpty() && !state.isBusy) item {
            Text(
                "Nenhum canal disponível nesta seleção.",
                color = cinemaGrey,
                modifier = Modifier.padding(20.dp)
            )
        }
    }
}

private fun formatChannelGroup(name: String, group: String): String {
    val lower = (name + " " + group).lowercase()
    val base = group.ifBlank { "Ao vivo" }.uppercase()
    return when {
        lower.contains("esporte") || lower.contains("sport") || lower.contains("premiere") || lower.contains("combate") -> "CANAIS: ESPORTES ⚽"
        lower.contains("filme") || lower.contains("telecine") || lower.contains("hbo") || lower.contains("cinema") -> "CANAIS: FILMES 🎬"
        lower.contains("24h") || lower.contains("24 horas") -> "CANAIS: 24 HORAS ✨"
        lower.contains("desenho") || lower.contains("kids") || lower.contains("anime") -> "CANAIS: DESENHOS 🎨"
        lower.contains("noticia") || lower.contains("news") -> "CANAIS: NOTÍCIAS 📰"
        lower.contains("aberto") || lower.contains("globo") || lower.contains("sbt") || lower.contains("band") -> "CANAIS: ABERTOS 📺"
        else -> "CANAIS: $base"
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
    val remoteGroupNames = remember(state.xtreamGroups, kind, selectedCategory) {
        XtreamDisplayGroups.merge(state.xtreamGroups)
            .firstOrNull { it.kind == kind && it.label == selectedCategory }
            ?.sources?.map { it.name }?.toSet().orEmpty()
    }
    val visible = remember(state.entries, kind, search, selectedCategory, remoteCategories, remoteGroupNames) {
        if (hasRemoteCategories && selectedCategory.isNotBlank())
            everyCineVisibleRemote(state.entries, kind, remoteGroupNames, search)
        else everyCineVisible(state.entries, kind,
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
            LazyColumn(
                Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
                contentPadding = PaddingValues(bottom = 22.dp),
            ) {
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
