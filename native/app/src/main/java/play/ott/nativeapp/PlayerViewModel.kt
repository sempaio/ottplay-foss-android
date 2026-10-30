package play.ott.nativeapp

import play.ott.nativeapp.i18n.AppLanguages
import android.app.Application
import android.net.Uri
import android.os.Bundle
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import play.ott.nativeapp.core.*
import play.ott.nativeapp.data.NativeRepository
import play.ott.nativeapp.data.ForYouProfile
import play.ott.nativeapp.data.ForYouEngine
import play.ott.nativeapp.playback.PlaybackItems
import play.ott.nativeapp.ui.AppAction
import play.ott.nativeapp.ui.AppUiState

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerViewModel(application: Application) : AndroidViewModel(application) {
    private val updates = play.ott.nativeapp.data.UpdateClient(application)
    private var latestUpdate: play.ott.nativeapp.data.AppUpdate? = null
    private var updateJob: Job? = null
    private val repository = (application as OttplayApplication).repository
    private suspend fun availableSources() = StarlinkConfig.visibleSources(repository.sources())
    private fun message(id: Int, vararg args: Any) = AppLanguages.localizedContext(getApplication()).getString(id, *args)
    private var loadingResource = R.string.message_loading_library
    private var errorText: (() -> String)? = null
    private var noticeText: (() -> String)? = null
    private val mutableState = MutableStateFlow(AppUiState(isBusy = true, loadingMessage = message(loadingResource)))
    val state: StateFlow<AppUiState> = mutableState.asStateFlow()
    private fun updateState(forceLocalization: Boolean = false, transform: (AppUiState) -> AppUiState) {
        mutableState.update { old ->
            val next = transform(old)
            next.copy(sources = if (forceLocalization || next.sources !== old.sources) next.sources.map(repository::displaySource) else old.sources,
                entries = if (forceLocalization || next.entries !== old.entries) next.entries.map(repository::displayEntry) else old.entries,
                episodes = if (forceLocalization || next.episodes !== old.episodes) next.episodes.map(repository::displayEntry) else old.episodes,
                playingEntry = if (forceLocalization || next.playingEntry !== old.playingEntry) next.playingEntry?.let(repository::displayEntry) else old.playingEntry,
                epgEntry = if (forceLocalization || next.epgEntry !== old.epgEntry) next.epgEntry?.let(repository::displayEntry) else old.epgEntry,
                seriesEntry = if (forceLocalization || next.seriesEntry !== old.seriesEntry) next.seriesEntry?.let(repository::displayEntry) else old.seriesEntry)
        }
    }

    /** Activity recreation can retain this VM. Refresh labels without networking or touching playback. */
    fun onLocaleChanged() {
        updateState(forceLocalization = true) { it.copy(loadingMessage = message(loadingResource),
            error = errorText?.invoke(), notice = noticeText?.invoke()) }
        viewModelScope.launch {
            try {
                val sources = availableSources()
                updateState { it.copy(sources = sources) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Keep the already displayed catalogue if storage is temporarily unavailable. */ }
        }
    }

    private fun setErrorText(text: () -> String) {
        errorText = text
        updateState { it.copy(error = text()) }
    }

    private fun setNoticeText(text: () -> String) {
        noticeText = text
        updateState { it.copy(notice = text()) }
    }

    private fun rememberCatalogNotes(catalog: Catalog) {
        if (BuildConfig.CUSTOMER_MODE) {
            if (catalog.notes.isNotEmpty() || catalog.messages.isNotEmpty()) noticeText = { message(R.string.customer_service_error) }
            return
        }
        if (catalog.notes.isNotEmpty() || catalog.messages.isNotEmpty()) noticeText = {
            repository.catalogNotes(catalog).joinToString("\n")
        }
    }

    private var controller: MediaController? = null
    private var saveInFlight = false
    private var loadJob: Job? = null
    private var detailJob: Job? = null
    private var playJob: Job? = null
    private var recoveryJob: Job? = null
    private var playGeneration = 0L
    private var resolvingSourceId: String? = null
    private var loadGeneration = 0L
    private var pending: PendingPlay? = null
    private data class PendingPlay(val entry: MediaEntry, val stream: PlaybackStream, val positionMs: Long)
    private val listener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            setErrorText { play.ott.nativeapp.playback.PlaybackErrors.message(getApplication(), error) }
        }
        override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
            controller?.let { restorePlaybackEntry(it, item) }
        }
    }
    init {
        checkUpdate(false)
        viewModelScope.launch {
            repository.recent.collect { recent ->
                updateState {
                    it.copy(recent = if (it.activeForYouProfileId == "default") recent else emptyList(),
                        forYouRecentlyViewedKeys = if (it.activeForYouProfileId == "default")
                            recent.map { item -> ForYouEngine.key(item.entry) }.toSet() else emptySet())
                }
            }
        }
        viewModelScope.launch { runCatching { repository.loadRecent() } }
        viewModelScope.launch {
            repository.preferences.data.collect { preferences ->
                val profileId = preferences.activeForYouProfileId.takeIf { it in setOf("default", "secondary") } ?: "default"
                val profile = preferences.forYouProfiles[profileId] ?: ForYouProfile(
                    id = profileId, name = if (profileId == "default") "Principal" else "Perfil 2")
                val recent = if (profileId == "default") repository.recent.value else emptyList()
                updateState { it.copy(
                    favoriteIds = if (profileId == "default") preferences.favorites else preferences.favoritesByProfile[profileId].orEmpty(),
                    backgroundPlayback = preferences.backgroundPlayback,
                    forYouProfile = profile, activeForYouProfileId = profileId,
                    recent = recent, forYouRecentlyViewedKeys = recent.map { item -> ForYouEngine.key(item.entry) }.toSet()
                ) }
            }
        }
        viewModelScope.launch {
            try {
                val sources = availableSources()
                val selected = repository.preferences.data.first().selectedSourceId
                    ?.takeIf { id -> sources.any { it.id == id } } ?: sources.firstOrNull()?.id
                updateState { it.copy(sources = sources, selectedSourceId = selected, isBusy = false) }
                if (selected != null) load(selected, refresh = false)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                updateState { it.copy(isBusy = false) }; setErrorText { message(R.string.message_saved_sources_unreadable) }
            }
        }
    }

    private fun checkUpdate(manual: Boolean) {
        if (updateJob?.isActive == true) return
        updateJob = viewModelScope.launch {
            updateState { it.copy(checkingUpdate = true) }
            try {
                latestUpdate = updates.latest()
                updateState { it.copy(updateAvailable = latestUpdate != null) }
                if (manual && latestUpdate == null) setNoticeText { message(R.string.neon_no_update) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (manual) setNoticeText { message(R.string.neon_update_failed) } }
            finally { updateState { it.copy(checkingUpdate = false) } }
        }
    }

    fun attachController(value: MediaController) {
        controller?.removeListener(listener)
        controller = value
        value.addListener(listener)
        pending?.let {
            pending = null
            try { begin(it) } catch (error: Exception) { showError(error) }
        }
        restorePlaybackEntry(value, value.currentMediaItem)
    }

    fun detachController(value: MediaController?) {
        if (controller === value) { recoveryJob?.cancel(); value?.removeListener(listener); controller = null }
    }

    /** The player may belong to a different source than the catalogue currently being browsed. */
    private fun restorePlaybackEntry(value: MediaController, item: MediaItem?) {
        recoveryJob?.cancel()
        if (item == null) {
            updateState { it.copy(playingEntry = null) }
            return
        }
        if (state.value.playingEntry?.id == item.mediaId) return
        val generation = playGeneration
        recoveryJob = viewModelScope.launch {
            try {
                val sources = availableSources()
                val sourceIds = sources.mapTo(hashSetOf()) { it.id }
                var entry = state.value.entries.firstOrNull { it.id == item.mediaId && it.sourceId in sourceIds }
                if (entry == null) {
                    // Episodes and archive items are not in the top-level cached catalogue. Keep
                    // their descriptor in private request metadata, alongside the existing URL.
                    entry = item.requestMetadata.extras?.getString(EXTRA_ENTRY)?.let { encoded ->
                        runCatching { entryJson.decodeFromString<MediaEntry>(encoded) }.getOrNull()
                    }?.takeIf { it.id == item.mediaId && it.sourceId in sourceIds }
                }
                if (entry == null) {
                    for (source in sources) {
                        val cached = try { repository.cached(source.id).entries }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { continue } // An unrelated broken cache cannot hide an active player.
                        ensureActive()
                        entry = cached.firstOrNull { it.id == item.mediaId && it.sourceId == source.id }
                        if (entry != null) break
                    }
                }
                val recoveredSourceId = entry?.sourceId
                if (recoveredSourceId != null && availableSources().none { it.id == recoveredSourceId }) entry = null
                ensureActive()
                if (controller !== value || value.currentMediaItem?.mediaId != item.mediaId || generation != playGeneration) return@launch
                updateState { it.copy(playingEntry = entry) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (controller === value && value.currentMediaItem?.mediaId == item.mediaId) {
                    setNoticeText { message(R.string.message_playback_description_unavailable) }
                }
            }
        }
    }

    fun onPlaybackSessionError() { setErrorText { message(R.string.message_channel_switch_failed) } }

    fun onControllerFailure() { updateState { it.copy(isBusy = false) }; setErrorText { message(R.string.message_controller_unavailable) } }

    fun dispatch(action: AppAction) {
        if (BuildConfig.CUSTOMER_MODE && (action is AppAction.DeleteSource || action == AppAction.AddDemo ||
            action == AppAction.ImportPlaylist || action == AppAction.ImportSettings || action == AppAction.ExportSettings ||
            (action is AppAction.SaveSource && !StarlinkConfig.isManagedSource(action.source)))) {
            setErrorText { message(R.string.customer_generic_error) }; return
        }
        when (action) {
            AppAction.Logout -> operation {
                if (saveInFlight) return@operation
                loadJob?.cancelAndJoin(); detailJob?.cancel(); stop()
                availableSources().forEach { repository.remove(it.id) }
                repository.preferences.update { it.copy(selectedSourceId = null) }
                updateState { AppUiState(backgroundPlayback = it.backgroundPlayback, favoriteIds = it.favoriteIds) }
            }
            AppAction.CheckUpdate -> checkUpdate(true)
            AppAction.InstallUpdate -> {
                if (updateJob?.isActive != true) latestUpdate?.let { update ->
                    updateJob = viewModelScope.launch {
                        updateState { it.copy(checkingUpdate = true) }
                        try { updates.requestInstall(updates.downloadAndVerify(update)) }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { setNoticeText { message(R.string.neon_update_failed) } }
                        finally { updateState { it.copy(checkingUpdate = false) } }
                    }
                }
            }
            is AppAction.SelectSource -> if (!BuildConfig.CUSTOMER_MODE || state.value.sources.any { it.id == action.sourceId }) load(action.sourceId, false)
            is AppAction.SaveSource -> {
                if (saveInFlight) return
                saveInFlight = true
                loadingResource = if (action.source.kind == SourceKind.XTREAM) R.string.starlink_authenticating else R.string.message_loading_catalog
                updateState { it.copy(isBusy = true, error = null, loadingMessage = message(loadingResource)) }
                operation { try {
                retirePendingSource(action.source.id)
                loadJob?.cancelAndJoin()
                if (action.source.kind == SourceKind.XTREAM) repository.providers.authenticate(action.source)
                repository.save(action.source)
                if (StarlinkConfig.isStarlinkAccount(action.source) &&
                    availableSources().none { it.id == StarlinkConfig.PLAYLIST_SOURCE_ID }) {
                    repository.save(StarlinkConfig.playlistSource())
                }
                val sources = availableSources()
                updateState { it.copy(sources = sources, sourceSaveRevision = it.sourceSaveRevision + 1) }
                load(action.source.id, true)
                } finally { saveInFlight = false } }
            }
            is AppAction.DeleteSource -> operation {
                retirePendingSource(action.sourceId)
                if (state.value.playingEntry?.sourceId == action.sourceId) stop()
                loadJob?.cancel()
                repository.remove(action.sourceId)
                val sources = availableSources()
                val id = state.value.selectedSourceId?.takeUnless { it == action.sourceId } ?: sources.firstOrNull()?.id
                updateState { it.copy(sources = sources, selectedSourceId = id, entries = emptyList(), isBusy = false) }
                if (id != null) load(id, false)
            }
            AppAction.Refresh -> state.value.selectedSourceId?.let { load(it, true) }
            is AppAction.ToggleFavorite -> operation {
                repository.preferences.update { previous ->
                    val id = previous.activeForYouProfileId
                    if (id == "default") previous.copy(favorites = play.ott.core.DurableSelections.toggle(previous.favorites, action.entry.id))
                    else previous.copy(favoritesByProfile = previous.favoritesByProfile +
                        (id to play.ott.core.DurableSelections.toggle(previous.favoritesByProfile[id].orEmpty(), action.entry.id)))
                }
            }
            is AppAction.SaveForYou -> operation {
                repository.preferences.update { previous ->
                    val id = previous.activeForYouProfileId
                    val before = previous.forYouProfiles[id] ?: ForYouProfile(id = id)
                    previous.copy(forYouProfiles = previous.forYouProfiles + (id to before.copy(
                        onboardingDone = true,
                        interests = action.interests.intersect(ForYouEngine.topics.toSet()),
                        allowViewingSignals = action.allowViewingSignals
                    )))
                }
            }
            AppAction.SkipForYou -> operation {
                repository.preferences.update { previous ->
                    val id = previous.activeForYouProfileId
                    val before = previous.forYouProfiles[id] ?: ForYouProfile(id = id)
                    previous.copy(forYouProfiles = previous.forYouProfiles + (id to before.copy(onboardingDone = true)))
                }
            }
            is AppAction.VoteForYou -> operation {
                repository.preferences.update { previous ->
                    val id = previous.activeForYouProfileId
                    val before = previous.forYouProfiles[id] ?: ForYouProfile(id = id)
                    val key = ForYouEngine.key(action.entry)
                    val likes = (before.likedEntries - key) + if (action.interested) setOf(key) else emptySet()
                    val dislikes = (before.dislikedEntries - key) + if (!action.interested) setOf(key) else emptySet()
                    previous.copy(forYouProfiles = previous.forYouProfiles + (id to before.copy(
                        likedEntries = likes.take(500).toSet(), dislikedEntries = dislikes.take(500).toSet()
                    )))
                }
            }
            AppAction.ResetForYou -> operation {
                repository.preferences.update { previous ->
                    val id = previous.activeForYouProfileId
                    previous.copy(forYouProfiles = previous.forYouProfiles + (id to ForYouProfile(
                        id = id, name = if (id == "default") "Principal" else "Perfil 2")))
                }
            }
            is AppAction.SwitchForYouProfile -> if (action.id in setOf("default", "secondary")) operation {
                // Prevent a playing session from sending new playback checkpoints to a different profile.
                repository.progressWriter.awaitIdle()
                stop()
                repository.preferences.update { it.copy(activeForYouProfileId = action.id) }
            }
            is AppAction.Play -> play(action.entry)
            is AppAction.OpenEpg -> {
                detailJob?.cancel()
                updateState { it.copy(epgEntry = action.entry, programmes = emptyList()) }
                detailJob = operation {
                    val programmes = repository.programmes(action.entry)
                    updateState { if (it.epgEntry?.id == action.entry.id) it.copy(programmes = programmes) else it }
                }
            }
            is AppAction.PlayProgramme -> {
                val stream = CatchupResolver.resolve(action.entry, action.programme)
                if (stream == null) setErrorText { message(R.string.message_archive_unavailable) }
                else play(action.entry.copy(id = action.entry.id + ":archive:" + action.programme.startMillis,
                    name = action.programme.title, nameMessage = null, kind = MediaKind.MOVIE), stream)
            }
            is AppAction.OpenMovie -> {
                detailJob?.cancel()
                updateState { it.copy(movieEntry = action.entry, isBusy = true) }
                detailJob = operation {
                    val source = availableSources().first { it.id == action.entry.sourceId }
                    val movie = repository.providers.loadMovieInfo(source, action.entry)
                    updateState { if (it.movieEntry?.id == action.entry.id) it.copy(movieEntry = movie, isBusy = false) else it }
                }
            }
            AppAction.CloseMovie -> { detailJob?.cancel(); updateState { it.copy(movieEntry = null, isBusy = false) } }
            is AppAction.OpenSeries -> {
                detailJob?.cancel()
                updateState { it.copy(seriesEntry = action.entry, episodes = emptyList(), isBusy = true) }
                detailJob = operation {
                    val source = availableSources().first { it.id == action.entry.sourceId }
                    val episodes = repository.providers.loadEpisodes(source, action.entry)
                    updateState { if (it.seriesEntry?.id == action.entry.id) it.copy(episodes = episodes, isBusy = false) else it }
                }
            }
            AppAction.CloseEpg -> { detailJob?.cancel(); updateState { it.copy(epgEntry = null, programmes = emptyList()) } }
            AppAction.CloseSeries -> { detailJob?.cancel(); updateState { it.copy(seriesEntry = null, episodes = emptyList(), isBusy = false) } }
            AppAction.DismissError -> { errorText = null; updateState { it.copy(error = null) } }
            AppAction.DismissNotice -> { noticeText = null; updateState { it.copy(notice = null) } }
            AppAction.StopPlayback -> stop()
            is AppAction.SetBackgroundPlayback -> operation { repository.preferences.update { it.copy(backgroundPlayback = action.enabled) } }
            is AppAction.SetTrackPreference -> operation {
                action.preference.validate()
                repository.preferences.update {
                    if (action.audio) it.copy(audioTrack = action.preference)
                    else it.copy(subtitleTrack = action.preference)
                }
            }
            AppAction.AddDemo -> operation {
                val source = NativeRepository.demoSource()
                repository.save(source)
                val sources = availableSources()
                updateState { it.copy(sources = sources) }
                load(source.id, true)
            }
            AppAction.ImportPlaylist, AppAction.ImportSettings, AppAction.ExportSettings -> Unit // System document launchers in Activity.
        }
    }

    private fun load(id: String, refresh: Boolean) {
        loadingResource = R.string.message_loading_catalog
        errorText = null
        loadJob?.cancel()
        val generation = ++loadGeneration
        detailJob?.cancel()
        updateState { it.copy(selectedSourceId = id, entries = emptyList(), epgEntry = null,
            movieEntry = null, seriesEntry = null, isBusy = true, loadingMessage = message(loadingResource), error = null) }
        loadJob = viewModelScope.launch {
            try {
                repository.preferences.update { it.copy(selectedSourceId = id) }
                val source = availableSources().first { it.id == id }
                val cached = try { repository.cached(id) } catch (e: Exception) {
                    if (e is CancellationException || !refresh) throw e
                    Catalog(emptyList())
                }
                if (state.value.selectedSourceId != id) return@launch
                rememberCatalogNotes(cached)
                updateState { it.copy(entries = cached.entries, notice = noticeText?.invoke() ?: it.notice) }
                val catalog = if (refresh || cached.entries.isEmpty()) repository.refresh(source) else cached
                ensureActive()
                if (state.value.selectedSourceId != id) return@launch
                rememberCatalogNotes(catalog)
                updateState { it.copy(entries = catalog.entries, isBusy = false, notice = noticeText?.invoke() ?: it.notice) }
                if (refresh || cached.entries.isEmpty()) {
                    try {
                        repository.refreshEpg(source, catalog)
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) {
                        val previous = noticeText
                        setNoticeText { listOfNotNull(previous?.invoke(), message(R.string.message_epg_unavailable)).distinct().joinToString("\n") }
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { showError(e) }
            finally { if (generation == loadGeneration && state.value.selectedSourceId == id) updateState { it.copy(isBusy = false) } }
        }
    }

    private fun play(entry: MediaEntry, resolved: PlaybackStream? = null) {
        if (entry.playbackUnsupportedReason != null) {
            setErrorText { message(R.string.message_unsupported_playback) }
            return
        }
        if (entry.kind == MediaKind.SERIES) { dispatch(AppAction.OpenSeries(entry)); return }
        playJob?.cancel()
        pending = null
        resolvingSourceId = entry.sourceId
        val generation = ++playGeneration
        playJob = operation {
            val source = availableSources().first { it.id == entry.sourceId }
            val stream = resolved ?: repository.stream(source, entry)
            val pref = repository.preferences.data.first()
            val resume = if (pref.activeForYouProfileId == "default") pref.resumePositions else pref.resumeByProfile[pref.activeForYouProfileId].orEmpty()
            val position = if (entry.kind == MediaKind.LIVE) 0L else resume[entry.id] ?: 0L
            ensureActive()
            if (generation != playGeneration) return@operation
            if (availableSources().firstOrNull { it.id == source.id } != source) error("Source changed during playback request")
            val request = PendingPlay(entry, stream, position)
            if (controller == null) {
                pending = request
                updateState { it.copy(playingEntry = entry) }; setNoticeText { message(R.string.message_connecting_playback) }
            } else begin(request)
        }
    }

    private fun begin(request: PendingPlay) {
        val player = controller ?: return
        resolvingSourceId = null
        val item = PlaybackItems.build(
            id = request.entry.id, title = repository.displayEntry(request.entry).name, url = request.stream.url,
            headers = request.stream.headers, artwork = request.entry.logo.takeIf { it.isNotBlank() },
            isLive = request.entry.kind == MediaKind.LIVE, startPositionMs = request.positionMs,
            drm = request.entry.drm, mimeType = request.entry.mimeType ?: request.stream.mimeType,
            unsupportedReason = request.entry.playbackUnsupportedReason,
        ).let { built ->
            val extras = Bundle(built.requestMetadata.extras ?: Bundle()).apply {
                putString(EXTRA_ENTRY, entryJson.encodeToString(request.entry))
            }
            built.buildUpon()
                .setRequestMetadata(built.requestMetadata.buildUpon().setExtras(extras).build())
                .build()
        }
        PlaybackItems.requireSupported(getApplication(), item)
        player.setMediaItem(item, PlaybackItems.startPositionMs(item))
        player.prepare()
        player.play()
        errorText = null; noticeText = null
        updateState { it.copy(playingEntry = request.entry, error = null, notice = null) }
    }

    private fun retirePendingSource(sourceId: String) {
        if (resolvingSourceId == sourceId || pending?.entry?.sourceId == sourceId) {
            playJob?.cancel(); ++playGeneration; pending = null; resolvingSourceId = null
        }
    }

    private fun stop() {
        playJob?.cancel(); ++playGeneration; pending = null; resolvingSourceId = null
        controller?.stop(); controller?.clearMediaItems()
        updateState { it.copy(playingEntry = null) }
    }

    fun importPlaylist(uri: Uri, name: String) = operation {
        check(!BuildConfig.CUSTOMER_MODE)
        val source = NativeRepository.fileSource(uri.toString(), name)
        repository.save(source)
        val sources = availableSources()
        updateState { it.copy(sources = sources) }
        load(source.id, true)
    }

    fun importSettings(uri: Uri) = operation {
        check(!BuildConfig.CUSTOMER_MODE)
        val text = withContext(Dispatchers.IO) {
            getApplication<Application>().contentResolver.openInputStream(uri)?.use { play.ott.nativeapp.data.readBounded(it, 4 * 1024 * 1024).decodeToString() }
                ?: error(message(R.string.message_file_unavailable))
        }
        // Import may replace accounts. A detached controller must not later start a URL resolved
        // with the previous credentials; currently playing media remains owned by the service.
        playJob?.cancelAndJoin()
        ++playGeneration
        pending = null
        resolvingSourceId = null
        loadJob?.cancelAndJoin()
        val result = repository.importSettings(text)
        val sources = availableSources()
        updateState { it.copy(sources = sources) }
        setNoticeText { listOf(message(R.string.message_imported_sources, result.count))
            .plus(repository.importNotes(result)).joinToString(". ") }
        val selected = repository.preferences.data.first().selectedSourceId
        (sources.firstOrNull { it.id == selected } ?: sources.firstOrNull())?.let { load(it.id, false) }
    }

    fun exportSettings(uri: Uri) = operation {
        check(!BuildConfig.CUSTOMER_MODE)
        val text = repository.exportSettings()
        withContext(Dispatchers.IO) {
            getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { it.write(text) }
                ?: error(message(R.string.message_file_write_failed))
        }
        setNoticeText { message(R.string.message_export_complete) }
    }

    private fun operation(block: suspend CoroutineScope.() -> Unit): Job = viewModelScope.launch {
        try { block() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { showError(e) }
    }

    private fun showError(error: Exception) {
        // Network/library exception messages may contain provider URLs and credentials.
        val resource = providerErrorResource(error)
        updateState { it.copy(isBusy = false) }
        setErrorText { message(resource) }
    }

    private companion object {
        const val EXTRA_ENTRY = "play.ott.nativeapp.entry"
        val entryJson = Json { ignoreUnknownKeys = true }
    }
}
