package play.ott.nativeapp.ui

import android.content.res.Configuration
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import play.ott.nativeapp.R
import play.ott.nativeapp.cast.CastingActiveOverlay
import play.ott.nativeapp.cast.UniversalCastController
import play.ott.nativeapp.cast.UniversalCastDialog
import play.ott.nativeapp.core.MediaEntry
import play.ott.nativeapp.core.MediaKind

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
    val hasRemoteCategories = state.xtreamGroups.isNotEmpty()
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
            val ids = state.xtreamGroups
                .filter { it.kind == kind && it.name == category }
                .map { it.id }
            if (ids.isNotEmpty()) onAction(AppAction.OpenXtreamGroups(kind, ids))
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
    val rootContext = LocalContext.current
    val castController = remember { UniversalCastController() }
    val castState by castController.state.collectAsState()
    var showCastDialog by rememberSaveable { mutableStateOf(false) }
    var lastCastEntryId by rememberSaveable { mutableStateOf<String?>(null) }
    val castScope = rememberCoroutineScope()
    DisposableEffect(castController) {
        onDispose { castController.shutdown() }
    }

    BackHandler(enabled = fullscreen && state.playingEntry != null && !inPictureInPicture) {
        if (castState.connectedDevice != null || state.playingEntry?.kind == MediaKind.LIVE) {
            fullscreen = false
        } else {
            onAction(AppAction.StopPlayback)
        }
    }
    BackHandler(enabled = searchOpened) {
        searchOpened = false
        search = ""
    }
    BackHandler(enabled = !fullscreen && page != EveryCinePage.HOME && !showSettings && !showSource && !showGuide && !showForYou && !searchOpened && !inPictureInPicture) {
        pageName = EveryCinePage.HOME.name
    }

    LaunchedEffect(showCastDialog) {
        if (showCastDialog) {
            castController.scanDevices(rootContext)
        }
    }

    // Automatically forward channel/episode changes to an already-connected Smart TV
    LaunchedEffect(state.playingEntry?.id, castState.connectedDevice?.avTransportControlUrl) {
        val entry = state.playingEntry ?: return@LaunchedEffect
        val device = castState.connectedDevice ?: return@LaunchedEffect
        if (lastCastEntryId == entry.id) return@LaunchedEffect
        lastCastEntryId = entry.id
        var url = resolveCurrentStreamUrl(entry, controller)
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
            delay(500L)
            url = resolveCurrentStreamUrl(entry, controller)
        }
        if (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)) {
            controller?.pause()
            castController.castMedia(
                device = device,
                rawUrl = url,
                title = entry.name,
                isLive = entry.kind == MediaKind.LIVE,
                startPositionMs = 0L,
            )
        }
    }

    val liveChannelsForZap = remember(state.entries) { state.entries.filter { it.kind == MediaKind.LIVE } }
    Box(Modifier.fillMaxSize().background(cinemaBlack).testTag("everycine-visual-shell")) {
        if ((fullscreen || inPictureInPicture) && state.playingEntry != null) {
            val playing = state.playingEntry
            val isLive = playing.kind == MediaKind.LIVE
            val currentLiveIndex = if (isLive) {
                liveChannelsForZap.indexOfFirst { it.id == playing.id }
            } else -1
            val currentEpisodeIndex = if (!isLive && state.episodes.isNotEmpty()) {
                state.episodes.indexOfFirst { it.id == playing.id }
            } else -1
            val drawerItems = when {
                isLive -> liveChannelsForZap
                currentEpisodeIndex >= 0 -> state.episodes
                else -> emptyList()
            }
            val onPrevItem: (() -> Unit)? = when {
                isLive && liveChannelsForZap.size > 1 -> {
                    {
                        val prevIdx = if (currentLiveIndex > 0) currentLiveIndex - 1 else liveChannelsForZap.lastIndex
                        onAction(AppAction.Play(liveChannelsForZap[prevIdx]))
                    }
                }
                !isLive && currentEpisodeIndex > 0 -> {
                    { onAction(AppAction.Play(state.episodes[currentEpisodeIndex - 1])) }
                }
                else -> null
            }
            val onNextItem: (() -> Unit)? = when {
                isLive && liveChannelsForZap.size > 1 -> {
                    {
                        val nextIdx = if (currentLiveIndex in 0 until liveChannelsForZap.lastIndex) currentLiveIndex + 1 else 0
                        onAction(AppAction.Play(liveChannelsForZap[nextIdx]))
                    }
                }
                !isLive && currentEpisodeIndex in 0 until state.episodes.lastIndex -> {
                    { onAction(AppAction.Play(state.episodes[currentEpisodeIndex + 1])) }
                }
                else -> null
            }
            val liveNowTitle = if (isLive && state.epgEntry?.id == playing.id) {
                CinemaPlayerLogic.activeProgrammeTitle(state.programmes, System.currentTimeMillis())
            } else null

            Box(Modifier.fillMaxSize()) {
                NativePlayerPane(
                    playing, controller, true,
                    onFullscreen = {
                        if (isLive) fullscreen = false
                        else onAction(AppAction.StopPlayback)
                    },
                    onPictureInPicture = onPictureInPicture,
                    onStop = {
                        castScope.launch { castController.disconnect() }
                        onAction(AppAction.StopPlayback)
                    },
                    onRetry = { onAction(AppAction.Play(playing)) },
                    onTrackPreference = { audio, pref -> onAction(AppAction.SetTrackPreference(audio, pref)) },
                    modifier = Modifier.fillMaxSize(), inPictureInPicture = inPictureInPicture,
                    onPrevious = onPrevItem,
                    onNext = onNextItem,
                    onCast = { showCastDialog = true },
                    isCasting = castState.connectedDevice != null,
                    onOpenEpg = if (isLive) { { onAction(AppAction.OpenEpg(playing)) } } else null,
                    liveNowPlayingTitle = liveNowTitle,
                    drawerItems = drawerItems,
                    onSelectDrawerItem = if (drawerItems.isNotEmpty()) { { onAction(AppAction.Play(it)) } } else null,
                )
                if (castState.connectedDevice != null && !inPictureInPicture) {
                    CastingActiveOverlay(
                        castState = castState,
                        onTogglePlayPause = { castScope.launch { castController.togglePlayPause() } },
                        onSeekRelative = { delta -> castScope.launch { castController.seekRelative(delta) } },
                        onSeekTo = { pos -> castScope.launch { castController.seekTo(pos) } },
                        onAdjustVolume = { delta -> castScope.launch { castController.adjustVolume(delta) } },
                        onSwitchTv = { showCastDialog = true },
                        onSystemMirroring = { UniversalCastController.openSystemScreenMirroring(rootContext) },
                        onDisconnect = {
                            lastCastEntryId = null
                            castScope.launch {
                                castController.disconnect()
                                controller?.play()
                            }
                        },
                        onBack = { fullscreen = false },
                        onPollPosition = { castController.pollPosition() },
                        onPrevious = onPrevItem,
                        onNext = onNextItem,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
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
                                    connectedTvName = castState.connectedDevice?.friendlyName,
                                    onOpenCast = { showCastDialog = true },
                                )
                                EveryCinePage.CATALOG -> EveryCineCatalog(
                                    state, onAction, search, catalogKind, selectedCategory,
                                    state.xtreamGroups.filter { it.kind == catalogKind }.map { it.name }.distinct(),
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
                castState.connectedDevice?.let { tv ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF0C1A2B))
                            .border(1.dp, cinemaAccent.copy(alpha = 0.55f))
                            .clickable {
                                if (state.playingEntry != null) fullscreen = true else showCastDialog = true
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("📡", fontSize = 15.sp)
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Transmitindo para ${tv.friendlyName}",
                                color = cinemaAccent,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (castState.castingTitle.isNotBlank()) {
                                Text(
                                    castState.castingTitle,
                                    color = cinemaWhite,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Box(
                            Modifier
                                .background(Color(0xFF17273D), RoundedCornerShape(10.dp))
                                .clickable { castScope.launch { castController.togglePlayPause() } }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        ) {
                            Text(if (castState.isPlayingOnTv) "⏸" else "▶", color = cinemaWhite, fontSize = 13.sp)
                        }
                        Box(
                            Modifier
                                .background(Color(0xFF2B1722), RoundedCornerShape(10.dp))
                                .clickable {
                                    lastCastEntryId = null
                                    castScope.launch {
                                        castController.disconnect()
                                        controller?.play()
                                    }
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        ) {
                            Text("✕", color = Color(0xFFFF8A9B), fontSize = 13.sp, fontWeight = FontWeight.Bold)
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

    if (showCastDialog) {
        val currentPlaying = state.playingEntry
        UniversalCastDialog(
            castState = castState,
            mediaTitle = currentPlaying?.name.orEmpty(),
            onRescan = { castScope.launch { castController.scanDevices(rootContext) } },
            onSelectDevice = { device ->
                if (currentPlaying != null) {
                    val streamUrl = resolveCurrentStreamUrl(currentPlaying, controller)
                    val posMs = if (currentPlaying.kind == MediaKind.LIVE) 0L else (controller?.currentPosition ?: 0L)
                    lastCastEntryId = currentPlaying.id
                    controller?.pause()
                    castScope.launch {
                        val ok = castController.castMedia(
                            device = device,
                            rawUrl = streamUrl,
                            title = currentPlaying.name,
                            isLive = currentPlaying.kind == MediaKind.LIVE,
                            startPositionMs = posMs,
                        )
                        if (ok) {
                            showCastDialog = false
                            fullscreen = true
                        }
                    }
                }
            },
            onDisconnect = {
                lastCastEntryId = null
                castScope.launch {
                    castController.disconnect()
                    controller?.play()
                }
            },
            onSystemMirroring = {
                showCastDialog = false
                UniversalCastController.openSystemScreenMirroring(rootContext)
            },
            onExternalCastApp = {
                if (currentPlaying != null) {
                    val streamUrl = resolveCurrentStreamUrl(currentPlaying, controller)
                    showCastDialog = false
                    UniversalCastController.openExternalCastApp(
                        rootContext,
                        streamUrl,
                        currentPlaying.name,
                        currentPlaying.kind == MediaKind.LIVE,
                    )
                }
            },
            onDismiss = {
                castController.clearError()
                showCastDialog = false
            },
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
        if (state.xtreamGroups.isNotEmpty()) {
            val live = state.xtreamGroups.filter { it.kind == MediaKind.LIVE }
                .map { MediaKind.LIVE to it.name }.distinct().take(12)
            val vod = state.xtreamGroups.filter { it.kind == MediaKind.MOVIE || it.kind == MediaKind.SERIES }
                .map { it.kind to it.name }.distinct().take(16)
            live + vod
        } else {
            state.entries.mapNotNull { e ->
                e.group.trim().takeIf { it.isNotEmpty() }?.let { e.kind to it }
            }.distinct().take(24)
        }
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
    connectedTvName: String? = null,
    onOpenCast: () -> Unit = {},
) {
    val allLabel = "Todos"
    val selected = selectedCategory.ifBlank { allLabel }
    val remoteLiveGroups = remember(state.xtreamGroups) {
        state.xtreamGroups.filter { it.kind == MediaKind.LIVE }
    }
    val categories = remember(remoteLiveGroups, state.entries) {
        if (remoteLiveGroups.isNotEmpty()) {
            remoteLiveGroups.map { it.name }.filter { it.isNotBlank() }.distinct()
        } else {
            state.entries.filter { it.kind == MediaKind.LIVE }
                .map { it.group.trim() }.filter { it.isNotBlank() }.distinct()
        }
    }

    // Load exact remote Xtream category IDs directly from the upstream list (Pluto + IPTV 2026)
    val selectedRemoteIds = remember(remoteLiveGroups, selected) {
        if (selected == allLabel) {
            remoteLiveGroups.take(10).map { it.id }
        } else {
            remoteLiveGroups.filter { it.name.equals(selected, ignoreCase = true) }.map { it.id }
        }
    }
    LaunchedEffect(selected, selectedRemoteIds) {
        if (selectedRemoteIds.isNotEmpty()) {
            onAction(AppAction.OpenXtreamGroups(MediaKind.LIVE, selectedRemoteIds))
        }
    }

    // Channels matching the exact upstream list category (zero artificial regrouping)
    val filteredChannels = remember(state.entries, selected, search) {
        state.entries.filter { entry ->
            entry.kind == MediaKind.LIVE &&
                (selected == allLabel || entry.group.trim().equals(selected, ignoreCase = true)) &&
                (search.isBlank() || entry.name.contains(search, ignoreCase = true) || entry.group.contains(search, ignoreCase = true))
        }
    }

    @Composable fun liveChannelBrowser(modifier: Modifier) {
        Column(modifier) {
            // Exact upstream list categories (Pluto + IPTV 2026), no invented subcategories
            LazyRow(
                Modifier
                    .fillMaxWidth()
                    .testTag("everycine-live-categories")
                    .padding(top = 6.dp, bottom = 4.dp),
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    EveryCineCategoryChip(allLabel, selected == allLabel) {
                        onSelectCategory("")
                    }
                }
                items(categories, key = { it }) { name ->
                    EveryCineCategoryChip(name, selected == name) {
                        onSelectCategory(name)
                    }
                }
            }
            if (state.isBusy) LinearProgressIndicator(Modifier.fillMaxWidth())

            // Channel Rows (vertical list with logo fallback, Guia button, play & fav)
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
                connectedTvName = connectedTvName,
                onOpenCast = onOpenCast,
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
                connectedTvName = connectedTvName,
                onOpenCast = onOpenCast,
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
    connectedTvName: String? = null,
    onOpenCast: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
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
                if (connectedTvName != null) {
                    Box(
                        Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 8.dp)
                            .background(Color(0xDD0B1626), RoundedCornerShape(16.dp))
                            .border(1.dp, cinemaAccent, RoundedCornerShape(16.dp))
                            .clickable(onClick = onFullscreen)
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text(
                            "📡 Na TV: $connectedTvName • Toque para controlar",
                            color = cinemaAccent,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
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

            // Universal Cast (Smart TVs via DLNA/UPnP + Native Screen Mirroring + External Cast Apps)
            PlayerBarButton(
                icon = "📡",
                label = if (connectedTvName != null) "Na TV" else "Transmitir",
                enabled = playing != null,
                onClick = {
                    if (playing != null) onOpenCast()
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

private fun resolveCurrentStreamUrl(entry: MediaEntry, controller: MediaController?): String {
    val item = controller?.currentMediaItem
    val localUri = item?.localConfiguration?.uri?.toString()
    if (!localUri.isNullOrBlank() && (localUri.startsWith("http://", ignoreCase = true) || localUri.startsWith("https://", ignoreCase = true))) {
        return localUri
    }
    val requestUri = item?.requestMetadata?.mediaUri?.toString()
    if (!requestUri.isNullOrBlank() && (requestUri.startsWith("http://", ignoreCase = true) || requestUri.startsWith("https://", ignoreCase = true))) {
        return requestUri
    }
    return entry.url
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
                    Text(
                        channel.group.ifBlank { "Ao vivo" },
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
    val visible = remember(state.entries, kind, search, selectedCategory, hasRemoteCategories) {
        if (hasRemoteCategories && selectedCategory.isNotBlank())
            everyCineVisibleRemote(state.entries, kind, setOf(selectedCategory), search)
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
