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
import androidx.compose.foundation.lazy.grid.GridItemSpan
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
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
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import play.ott.nativeapp.R
import play.ott.nativeapp.core.MediaEntry
import play.ott.nativeapp.core.MediaKind
import play.ott.nativeapp.data.ForYouEngine
import play.ott.nativeapp.data.SeriesRecovery
import play.ott.nativeapp.data.SmartLiveCatalog
import play.ott.nativeapp.data.XtreamDisplayGroup
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
    var programmaticPageTarget by remember { mutableStateOf<Int?>(null) }
    // MainFragment in the reference is a ViewPager2. Keep swipe navigation
    // and bottom-nav selection synchronized, without dropping programmatic category browsing.
    LaunchedEffect(pageName) {
        val index = EveryCinePage.entries.indexOf(EveryCinePage.valueOf(pageName))
        if (pagePager.currentPage != index) {
            programmaticPageTarget = index
            try {
                pagePager.scrollToPage(index)
            } finally {
                programmaticPageTarget = null
            }
        }
    }
    LaunchedEffect(pagePager) {
        snapshotFlow { pagePager.settledPage }.collect { settled ->
            if (programmaticPageTarget != null) return@collect
            val current = EveryCinePage.entries[settled].name
            if (current != pageName) {
                pageName = current
                selectedCategory = ""
                search = ""
            }
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
        if (hasRemoteCategories && category.isNotBlank()) {
            mergedGroups.firstOrNull { it.kind == kind && it.label == category }?.let { group ->
                onAction(AppAction.OpenXtreamGroups(kind, group.sources.map { it.id }))
            }
        }
    }
    var autoLoadedKinds by remember(state.selectedSourceId) { mutableStateOf(emptySet<MediaKind>()) }
    LaunchedEffect(page, catalogKind, hasRemoteCategories, mergedGroups, selectedCategory, search, state.selectedSourceId, state.entries.isEmpty()) {
        if (!hasRemoteCategories || search.isNotBlank()) return@LaunchedEffect
        if (page == EveryCinePage.HOME && state.entries.isEmpty() && MediaKind.MOVIE !in autoLoadedKinds) {
            everyCineDefaultRemoteGroup(mergedGroups, MediaKind.MOVIE)?.let { group ->
                autoLoadedKinds = autoLoadedKinds + MediaKind.MOVIE
                onAction(AppAction.OpenXtreamGroups(MediaKind.MOVIE, group.sources.map { it.id }))
            }
            return@LaunchedEffect
        }
        if (selectedCategory.isNotBlank()) return@LaunchedEffect
        val activeKind = when (page) {
            EveryCinePage.CATALOG -> catalogKind
            EveryCinePage.LIVE -> MediaKind.LIVE
            else -> null
        } ?: return@LaunchedEffect
        if (activeKind !in autoLoadedKinds && state.entries.none { it.kind == activeKind }) {
            everyCineDefaultRemoteGroup(mergedGroups, activeKind)?.let { group ->
                autoLoadedKinds = autoLoadedKinds + activeKind
                browse(activeKind, group.label)
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
    BackHandler(enabled = !fullscreen && page != EveryCinePage.HOME && !showSettings && !showSource && !showGuide && !showForYou && !searchOpened && !inPictureInPicture) {
        pageName = EveryCinePage.HOME.name
    }
    Box(Modifier.tvRemoteInput().fillMaxSize().background(cinemaBlack).testTag("everycine-visual-shell")) {
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
                HorizontalPager(
                    state = pagePager, modifier = Modifier.weight(1f)
                        .testTag("everycine-main-pager"),
                ) { pageIndex ->
                    val pane = EveryCinePage.entries[pageIndex]
                    savedPages.SaveableStateProvider(pane.name) {
                        when (pane) {
                            EveryCinePage.HOME -> EveryCineHome(
                                state, recent, mergedGroups, onAction, search,
                                onBrowse = ::browse,
                                onForYou = { showForYou = true },
                            )
                            EveryCinePage.LIVE -> EveryCineLive(
                                state, controller, mergedGroups, onAction, search, selectedCategory,
                                onSelectCategory = { browse(MediaKind.LIVE, it) },
                                onFullscreen = { fullscreen = true },
                                onPictureInPicture = onPictureInPicture,
                            )
                            EveryCinePage.CATALOG -> EveryCineCatalog(
                                state, mergedGroups, onAction, search, catalogKind, selectedCategory,
                                onKind = { catalogKindName = it.name; selectedCategory = "" },
                                onCategory = { browse(catalogKind, it) },
                                hasRemoteCategories = hasRemoteCategories,
                            )
                            EveryCinePage.PROFILE -> EveryCineProfile(
                                state, recent, onAction,
                                onSettings = { showSettings = true },
                                onForYou = { showForYou = true },
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
    state.seriesEntry?.let {
        EveryCineSeriesDetail(it, state.episodes, state.isBusy, it.id in state.favoriteIds, onAction)
    }
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
        Modifier.fillMaxWidth().height(58.dp).background(Color(0xFF111115))
            .testTag("everycine-bottom-navigation"),
    ) {
        items.forEach { (destination, label, symbol) ->
            val selected = page == destination
            var focused by remember { mutableStateOf(false) }
            Column(
                Modifier.weight(1f).fillMaxHeight()
                    .onFocusChanged { focused = it.isFocused }
                    .border(if (focused) 1.5.dp else 0.dp, if (focused) cinemaAccent else Color.Transparent)
                    .clickable { onSelect(destination) }
                    .testTag("everycine-tab-" + destination.name),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Top,
            ) {
                Box(
                    Modifier.fillMaxWidth(.45f).height(2.5.dp)
                        .background(if (selected) cinemaAccent else Color.Transparent, RoundedCornerShape(2.dp))
                )
                Spacer(Modifier.weight(1f))
                Text(symbol, fontSize = 22.sp, color = if (selected) cinemaAccent else cinemaGrey)
                Text(
                    label, fontSize = 11.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) cinemaAccent else cinemaGrey,
                    maxLines = 1,
                )
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

private data class EveryCineHomeModel(
    val banners: List<MediaEntry> = emptyList(),
    val sections: List<EveryCineSection> = emptyList(),
    val recommendations: List<MediaEntry> = emptyList(),
    val ready: Boolean = false,
)

private data class EveryCineListState<T>(
    val items: List<T> = emptyList(),
    val ready: Boolean = false,
)

@Composable
private fun EveryCineHome(
    state: AppUiState,
    recent: List<play.ott.nativeapp.data.RecentPlayback>,
    mergedGroups: List<XtreamDisplayGroup>,
    onAction: (AppAction) -> Unit,
    search: String,
    onBrowse: (MediaKind, String) -> Unit,
    onForYou: () -> Unit,
) {
    val homeModel by produceState(
        initialValue = EveryCineHomeModel(),
        state.entries, recent, search, state.forYouProfile, state.favoriteIds, state.forYouRecentlyViewedKeys,
    ) {
        if (search.isNotBlank()) delay(250)
        value = withContext(Dispatchers.Default) {
            val content = if (search.isBlank()) state.entries else state.entries.filter {
                it.name.contains(search, ignoreCase = true) ||
                    it.group.contains(search, ignoreCase = true)
            }
            val banners = everyCineBannerEntries(content, recent)
            val sections = everyCineSections(content, recent, state.favoriteIds)
            val recommendations = if (!state.forYouProfile.onboardingDone) emptyList()
            else ForYouEngine.recommend(content, state.forYouProfile, state.favoriteIds, state.forYouRecentlyViewedKeys)
            EveryCineHomeModel(banners, sections, recommendations, ready = true)
        }
    }
    val banners = homeModel.banners
    val sections = homeModel.sections
    val recommendations = homeModel.recommendations
    val remoteCategories = remember(mergedGroups) {
        mergedGroups.filter {
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
        if (state.isBusy) item { LinearProgressIndicator(Modifier.fillMaxWidth(), color = cinemaAccent) }
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
        if (recommendations.isNotEmpty()) item(key = "for-you-rail") {
            Column(Modifier.fillMaxWidth().testTag("everycine-section-Para Você")) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Para Você", Modifier.weight(1f), color = cinemaAccent,
                        fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                    )
                    Text(
                        "Ajustar", color = cinemaGrey, fontSize = 13.sp,
                        modifier = Modifier.clickable(onClick = onForYou).padding(6.dp),
                    )
                }
                Spacer(Modifier.height(14.dp))
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(recommendations, key = { "foryou:" + it.sourceId + ":" + it.id }) { entry ->
                        EveryCinePoster(entry, onClick = { openContent(entry, onAction) })
                    }
                }
            }
        } else if (!state.forYouProfile.onboardingDone) item(key = "for-you-prompt") {
            Surface(
                color = cinemaPanel,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
                    .border(1.dp, cinemaAccent.copy(alpha = .45f), RoundedCornerShape(12.dp))
                    .clickable(onClick = onForYou)
                    .testTag("everycine-for-you-banner"),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("STARLINK PARA VOCÊ", color = cinemaAccent, fontSize = 11.sp,
                            fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        Text("Personalize suas recomendações locais", color = cinemaWhite,
                            fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Text("Configurar ›", color = cinemaAccent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
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
                    Text("Ver tudo", color = cinemaAccent, fontSize = 13.sp,
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
        if (homeModel.ready && sections.isEmpty() && !state.isBusy) item {
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
                    Text("STARLINK  •  PARA VOCÊ", fontSize = 11.sp, color = cinemaAccent,
                        fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
                    Text(entry?.name ?: "Seus filmes e séries em um só lugar",
                        fontSize = 27.sp, fontWeight = FontWeight.Black, color = cinemaWhite,
                        maxLines = 2, lineHeight = 30.sp)
                    Surface(
                        color = cinemaAccent,
                        shape = RoundedCornerShape(20.dp),
                    ) {
                        Text(
                            if (entry != null) "▶  Ver detalhes" else "Explorar catálogo  ›",
                            color = cinemaBlack, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                        )
                    }
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
                        if (pager.currentPage == i) cinemaAccent else Color.White.copy(alpha = .45f),
                        RoundedCornerShape(4.dp),
                    ))
            }
        }
    }
}

@Composable
private fun EveryCinePoster(entry: MediaEntry, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    Column(
        Modifier.width(134.dp)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .testTag("everycine-poster-" + entry.id),
    ) {
        var imageLoaded by remember(entry.logo) { mutableStateOf(false) }
        Box(
            Modifier.size(width = 134.dp, height = 186.dp)
                .clip(shape)
                .background(cinemaPanel, shape)
                .border(if (focused) 2.dp else 0.dp, if (focused) cinemaAccent else Color.Transparent, shape),
        ) {
            if (entry.logo.isBlank() || !imageLoaded) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(entry.name.take(2).uppercase(), color = cinemaGrey, fontSize = 26.sp)
                }
            }
            if (entry.logo.isNotBlank()) {
                val context = LocalContext.current
                val imageRequest = remember(entry.logo, context) {
                    ImageRequest.Builder(context)
                        .data(entry.logo)
                        .size(268, 372)
                        .crossfade(150)
                        .build()
                }
                AsyncImage(
                    model = imageRequest, contentDescription = entry.name, contentScale = ContentScale.Crop,
                    onSuccess = { imageLoaded = true },
                    onError = { imageLoaded = false },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (entry.kind == MediaKind.SERIES) Text(
                "SÉRIE", color = cinemaAccent, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.BottomStart)
                    .background(Color.Black.copy(alpha = .78f)).padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }
        Text(entry.name, modifier = Modifier.padding(top = 8.dp),
            color = if (focused) cinemaAccent else cinemaWhite, fontSize = 14.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun EveryCineCategoryChip(label: String, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(30.dp)
    Surface(
        color = if (selected) cinemaAccent else Color(0xFF1D1E24),
        shape = shape,
        modifier = Modifier
            .onFocusChanged { focused = it.isFocused }
            .border(
                width = if (focused) 2.dp else if (selected) 0.dp else 1.dp,
                color = if (focused) cinemaWhite else if (selected) Color.Transparent else Color(0xFF2E303A),
                shape = shape,
            )
            .clickable(onClick = onClick),
    ) {
        Text(
            label,
            color = if (selected) cinemaBlack else cinemaWhite,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
        )
    }
}

@Composable
private fun EveryCineLive(
    state: AppUiState,
    controller: MediaController?,
    mergedGroups: List<XtreamDisplayGroup>,
    onAction: (AppAction) -> Unit,
    search: String,
    selectedCategory: String,
    onSelectCategory: (String) -> Unit,
    onFullscreen: () -> Unit,
    onPictureInPicture: () -> Unit,
) {
    val liveGroups = remember(mergedGroups) { mergedGroups.filter { it.kind == MediaKind.LIVE } }
    val remoteCategories = remember(liveGroups) { liveGroups.map { it.label } }
    val categories = remember(remoteCategories, state.entries) {
        if (remoteCategories.isNotEmpty()) remoteCategories
        else state.entries.filter { it.kind == MediaKind.LIVE }.map { it.group }.filter { it.isNotBlank() }.distinct()
    }
    val remoteGroupNames = remember(liveGroups, selectedCategory) {
        liveGroups.firstOrNull { it.label == selectedCategory }
            ?.sources?.map { it.name }?.toSet().orEmpty()
    }
    val channelCardsState by produceState(
        initialValue = EveryCineListState<LiveQualityCard>(),
        state.entries, search, selectedCategory, remoteCategories, remoteGroupNames,
    ) {
        if (search.isNotBlank()) delay(250)
        value = withContext(Dispatchers.Default) {
            val visible = if (remoteCategories.isNotEmpty() && selectedCategory.isNotBlank())
                everyCineVisibleRemote(state.entries, MediaKind.LIVE, remoteGroupNames, search)
            else everyCineVisible(state.entries, MediaKind.LIVE,
                if (remoteCategories.isNotEmpty()) "" else selectedCategory, search)
            EveryCineListState(liveQualityCards(SmartLiveCatalog.prominentFirst(visible)), ready = true)
        }
    }
    val channelCards = channelCardsState.items
    var qualityPicker by remember { mutableStateOf<LiveQualityCard?>(null) }
    qualityPicker?.let { card ->
        AlertDialog(
            onDismissRequest = { qualityPicker = null },
            modifier = Modifier.tvRemoteInput(),
            containerColor = cinemaPanel,
            title = { Text(card.displayName, color = cinemaWhite) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    card.options.forEach { option ->
                        ActionButton(
                            option.name,
                            onClick = {
                                qualityPicker = null
                                onAction(AppAction.Play(option))
                            },
                            modifier = Modifier.fillMaxWidth(),
                            selected = state.playingEntry?.id == option.id,
                            compact = true,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { qualityPicker = null }) {
                    Text(stringResource(R.string.dialog_close), color = cinemaAccent)
                }
            },
        )
    }
    val compact = LocalConfiguration.current.screenHeightDp < 420
    if (compact) {
        // Rotate or shrink: preserve the two functional parts side-by-side.
        Row(Modifier.fillMaxSize().testTag("everycine-live-screen")) {
            EveryCineInlinePlayer(state, controller, onAction, onFullscreen, onPictureInPicture,
                Modifier.weight(1f).fillMaxHeight())
            Column(Modifier.weight(1f).fillMaxHeight()) {
                EveryCineLiveCategories(categories, selectedCategory, onSelectCategory)
                if (state.isBusy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = cinemaAccent)
                EveryCineChannelRows(channelCards, channelCardsState.ready, state, onAction, onFullscreen, { qualityPicker = it }, Modifier.weight(1f))
            }
        }
    } else {
        Column(Modifier.fillMaxSize().testTag("everycine-live-screen")) {
            EveryCineInlinePlayer(state, controller, onAction, onFullscreen, onPictureInPicture,
                Modifier.fillMaxWidth().height(209.dp))
            EveryCineLiveCategories(categories, selectedCategory, onSelectCategory)
            if (state.isBusy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = cinemaAccent)
            EveryCineChannelRows(channelCards, channelCardsState.ready, state, onAction, onFullscreen, { qualityPicker = it }, Modifier.weight(1f))
        }
    }
}

@Composable
private fun EveryCineInlinePlayer(
    state: AppUiState,
    controller: MediaController?,
    onAction: (AppAction) -> Unit,
    onFullscreen: () -> Unit,
    onPictureInPicture: () -> Unit,
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
            onPictureInPicture = onPictureInPicture,
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
    cards: List<LiveQualityCard>,
    ready: Boolean,
    state: AppUiState,
    onAction: (AppAction) -> Unit,
    onFullscreen: () -> Unit,
    onOpenQualities: (LiveQualityCard) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier.testTag("everycine-channel-list"),
        contentPadding = PaddingValues(bottom = 20.dp),
    ) {
        items(cards, key = { it.entry.sourceId + ":" + it.entry.id }) { card ->
            val channel = card.entry
            val selected = card.options.any { it.id == state.playingEntry?.id }
            var focused by remember { mutableStateOf(false) }
            Row(
                Modifier.fillMaxWidth()
                    .onFocusChanged { focused = it.isFocused }
                    .background(if (selected) Color(0xFF13222A) else Color.Transparent)
                    .border(if (focused) 1.5.dp else 0.dp, if (focused) cinemaAccent else Color.Transparent)
                    .clickable {
                        if (selected) onFullscreen() else onAction(AppAction.Play(channel))
                    }.padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.size(52.dp).background(cinemaPanel, RoundedCornerShape(6.dp)),
                    contentAlignment = Alignment.Center) {
                    if (channel.logo.isNotBlank()) AsyncImage(
                        channel.logo, contentDescription = null,
                        modifier = Modifier.fillMaxSize().padding(3.dp),
                        contentScale = ContentScale.Fit,
                    ) else Text("TV", color = cinemaGrey, fontSize = 14.sp)
                }
                Column(Modifier.weight(1f)) {
                    Text(card.displayName, color = if (selected) cinemaAccent else cinemaWhite,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(channel.group.ifBlank { "Ao vivo" }, color = cinemaGrey,
                            fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false))
                        if (card.options.size > 1) Surface(
                            color = Color(0xFF1F2B33),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.clickable { onOpenQualities(card) },
                        ) {
                            Text(
                                "${card.options.size} qualidades",
                                color = cinemaAccent, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                Surface(
                    color = Color(0xFF1B1C22),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.clickable { onAction(AppAction.OpenEpg(channel)) }
                        .testTag("everycine-epg-" + channel.id),
                ) {
                    Text("Guia", color = cinemaGrey, fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp))
                }
                Text(if (selected) "▶" else "▷", color = cinemaAccent, fontSize = 20.sp)
                Text(if (channel.id in state.favoriteIds) "♥" else "♡",
                    color = if (channel.id in state.favoriteIds) cinemaAccent else cinemaWhite,
                    fontSize = 21.sp, modifier = Modifier
                        .clickable { onAction(AppAction.ToggleFavorite(channel)) }
                        .padding(4.dp))
            }
            HorizontalDivider(color = Color(0xFF222225), thickness = .5.dp)
        }
        if (state.isBusy && cards.isEmpty()) item(key = "live-loading") {
            Column(
                Modifier.fillMaxWidth().padding(vertical = 48.dp).testTag("everycine-live-loading"),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator(color = cinemaAccent)
                Text("Carregando canais...", color = cinemaGrey, fontSize = 14.sp)
            }
        }
        if (ready && cards.isEmpty() && !state.isBusy) item {
            Text("Selecione uma categoria para carregar os canais.",
                color = cinemaGrey, modifier = Modifier.padding(20.dp))
        }
    }
}

@Composable
private fun EveryCineCatalog(
    state: AppUiState,
    mergedGroups: List<XtreamDisplayGroup>,
    onAction: (AppAction) -> Unit,
    search: String,
    kind: MediaKind,
    selectedCategory: String,
    onKind: (MediaKind) -> Unit,
    onCategory: (String) -> Unit,
    hasRemoteCategories: Boolean,
) {
    val kindGroups = remember(mergedGroups, kind) { mergedGroups.filter { it.kind == kind } }
    val remoteCategories = remember(kindGroups) { kindGroups.map { it.label } }
    val categories = remember(remoteCategories, state.entries, kind) {
        if (remoteCategories.isNotEmpty()) remoteCategories
        else state.entries.filter { it.kind == kind }.map { it.group }.filter { it.isNotBlank() }.distinct()
    }
    val remoteGroupNames = remember(kindGroups, selectedCategory) {
        kindGroups.firstOrNull { it.label == selectedCategory }
            ?.sources?.map { it.name }?.toSet().orEmpty()
    }
    val visibleState by produceState(
        initialValue = EveryCineListState<MediaEntry>(),
        state.entries, kind, search, selectedCategory, hasRemoteCategories, remoteGroupNames,
    ) {
        if (search.isNotBlank()) delay(250)
        value = withContext(Dispatchers.Default) {
            val entries = if (hasRemoteCategories && selectedCategory.isNotBlank())
                everyCineVisibleRemote(state.entries, kind, remoteGroupNames, search)
            else everyCineVisible(state.entries, kind,
                if (hasRemoteCategories) "" else selectedCategory, search)
            EveryCineListState(entries, ready = true)
        }
    }
    val visible = visibleState.items
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
        if (state.isBusy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = cinemaAccent)
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
            if (state.isBusy && visible.isEmpty()) item(key = "catalog-loading", span = { GridItemSpan(maxLineSpan) }) {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 48.dp).testTag("everycine-catalog-loading"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(color = cinemaAccent)
                    Text(
                        if (kind == MediaKind.SERIES) "Carregando séries..." else "Carregando filmes...",
                        color = cinemaGrey, fontSize = 14.sp,
                    )
                }
            }
            if (visibleState.ready && visible.isEmpty() && !state.isBusy) item(key = "catalog-empty", span = { GridItemSpan(maxLineSpan) }) {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 36.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Escolha uma categoria acima para carregar o catálogo.", color = cinemaGrey)
                    if (hasRemoteCategories) {
                        everyCineDefaultRemoteGroup(kindGroups, kind)?.let { defaultGroup ->
                            ActionButton("Carregar catálogo", { onCategory(defaultGroup.label) }, compact = true)
                        }
                    }
                }
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
    onForYou: () -> Unit,
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
                    listOf(Color(0xFF102430), cinemaBlack)
                )).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                NeonEmblem(Modifier.size(56.dp))
                Spacer(Modifier.height(8.dp))
                Text(
                    if (state.accountName.isNotBlank()) "Olá, ${state.accountName}" else "Meu STARLINK",
                    color = cinemaWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                )
                Text(
                    "Perfil ativo: ${state.forYouProfile.name}",
                    color = cinemaAccent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EveryCineCategoryChip("Principal", state.activeForYouProfileId == "default") {
                        onAction(AppAction.SwitchForYouProfile("default"))
                    }
                    EveryCineCategoryChip("Perfil 2", state.activeForYouProfileId == "secondary") {
                        onAction(AppAction.SwitchForYouProfile("secondary"))
                    }
                }
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
                ActionButton("Preferências · Para Você", onForYou,
                    Modifier.fillMaxWidth().testTag("everycine-profile-for-you"))
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
                        }, Modifier.fillMaxWidth(), selected = true)
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

@Composable
private fun EveryCineSeriesDetail(
    entry: MediaEntry,
    episodes: List<MediaEntry>,
    loading: Boolean,
    favorite: Boolean,
    onAction: (AppAction) -> Unit,
) {
    var selectedSeason by remember(entry.id) { mutableStateOf<Int?>(null) }
    val ordered = remember(episodes) {
        episodes.sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }, { it.name }))
    }
    val seasons = remember(ordered) { ordered.map { it.season ?: 1 }.distinct() }
    val activeSeason = selectedSeason?.takeIf { it in seasons } ?: seasons.firstOrNull()
    val seasonEpisodes = remember(ordered, activeSeason) {
        ordered.filter { (it.season ?: 1) == activeSeason }
    }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = { onAction(AppAction.CloseSeries) },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(color = cinemaBlack, modifier = Modifier.tvRemoteInput().fillMaxSize()
            .testTag("everycine-series-details")) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                item(key = "hero") {
                    Box(Modifier.fillMaxWidth().height(300.dp)) {
                        if (entry.logo.isNotBlank()) AsyncImage(
                            entry.logo, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                        )
                        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                            listOf(Color.Black.copy(alpha = .2f), cinemaBlack)
                        )))
                        Text("‹", color = cinemaWhite, fontSize = 34.sp,
                            modifier = Modifier.align(Alignment.TopStart)
                                .clickable { onAction(AppAction.CloseSeries) }.padding(14.dp))
                        Text(entry.name, color = cinemaWhite, fontSize = 24.sp,
                            fontWeight = FontWeight.Bold, maxLines = 3,
                            modifier = Modifier.align(Alignment.BottomStart)
                                .padding(horizontal = 16.dp, vertical = 16.dp))
                    }
                }
                item(key = "meta") {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(entry.group.ifBlank { "Séries" }, color = cinemaGrey, fontSize = 13.sp,
                                modifier = Modifier.weight(1f))
                            Text(if (favorite) "♥ Na lista" else "♡ Minha lista",
                                color = if (favorite) cinemaAccent else cinemaWhite,
                                modifier = Modifier.clickable { onAction(AppAction.ToggleFavorite(entry)) })
                        }
                        if (entry.description.isNotBlank()) Text(entry.description,
                            color = cinemaWhite, fontSize = 14.sp)
                        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = cinemaAccent)
                        if (ordered.isEmpty() && !loading) {
                            val direct = SeriesRecovery.directPlayCandidate(entry, episodes)
                            if (direct != null) {
                                ActionButton("▶  Reproduzir conteúdo", {
                                    onAction(AppAction.Play(direct))
                                    onAction(AppAction.CloseSeries)
                                }, Modifier.fillMaxWidth(), selected = true)
                            } else {
                                Text(stringResource(R.string.dialog_series_empty), color = cinemaGrey)
                            }
                        }
                    }
                }
                if (seasons.isNotEmpty()) item(key = "seasons") {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(seasons, key = { it }) { season ->
                            EveryCineCategoryChip(
                                stringResource(R.string.dialog_season, season),
                                season == activeSeason,
                            ) { selectedSeason = season }
                        }
                    }
                }
                items(seasonEpisodes, key = { it.sourceId + ":" + it.id }) { episode ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable {
                                onAction(AppAction.Play(episode))
                                onAction(AppAction.CloseSeries)
                            }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(Modifier.size(width = 104.dp, height = 62.dp)
                            .clip(RoundedCornerShape(6.dp)).background(cinemaPanel),
                            contentAlignment = Alignment.Center) {
                            if (episode.logo.isNotBlank()) AsyncImage(
                                episode.logo, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                            ) else Text("▶", color = cinemaAccent, fontSize = 20.sp)
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            episode.episode?.let {
                                Text(stringResource(R.string.dialog_episode, it),
                                    color = cinemaAccent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            }
                            Text(episode.name, color = cinemaWhite, fontSize = 14.sp,
                                fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (episode.description.isNotBlank()) Text(episode.description,
                                color = cinemaGrey, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        Text("▶", color = cinemaAccent, fontSize = 18.sp)
                    }
                    HorizontalDivider(color = Color(0xFF222225), thickness = .5.dp)
                }
            }
        }
    }
}
