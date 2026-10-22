package play.ott.nativeapp.playback

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import play.ott.nativeapp.core.MediaEntry
import play.ott.nativeapp.core.MediaKind
import play.ott.nativeapp.core.PlaybackStream

/** Main-thread coordinator. URL resolution may suspend; only the newest intent may commit. */
internal class ChannelNavigator(
    private val scope: CoroutineScope,
    private val currentId: () -> String?,
    private val loadChannels: suspend (String) -> List<MediaEntry>,
    private val resolve: suspend (MediaEntry) -> PlaybackStream,
    private val commit: (MediaEntry, PlaybackStream) -> Unit,
    private val changed: () -> Unit,
    private val failed: () -> Unit,
) {
    private var channels: List<MediaEntry> = emptyList()
    private val policy = play.ott.core.ChannelNavigation()
    private var loadJob: Job? = null
    private var resolveJob: Job? = null

    val available: Boolean
        get() = policy.available(currentId())

    fun currentItemChanged(id: String?) {
        cancelSwitch()
        loadJob?.cancel()
        val token = policy.beginCatalog()
        if (id == null) {
            channels = emptyList()
            changed()
            return
        }
        channels = emptyList()
        changed()
        loadJob = scope.launch {
            try {
                val loaded = loadChannels(id)
                ensureActive()
                val accepted = policy.acceptCatalog(token, id, currentId(), loaded.map {
                    play.ott.core.ChannelCandidate(it.id, it.kind == MediaKind.LIVE)
                })
                if (accepted != null) {
                    channels = accepted.map { loaded[it] }
                    changed()
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Playback remains available even when the local catalogue is unavailable. */ }
        }
    }

    fun step(direction: Int): Boolean {
        val nextIndex = policy.nextIndex(direction, currentId())
        if (nextIndex < 0) return false
        val entry = channels[nextIndex]
        resolveJob?.cancel()
        val token = policy.beginSwitch(nextIndex)
        val origin = currentId()
        // Advance the target now, rather than when the slow provider resolves its stream link.
        changed()
        resolveJob = scope.launch {
            try {
                val stream = resolve(entry)
                ensureActive()
                if (!policy.canCommit(token, origin, currentId())) return@launch
                commit(entry, stream)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (policy.fail(token)) {
                    changed()
                    failed()
                }
            }
        }
        return true
    }

    /** Pause, Stop and a direct UI selection invalidate both cooperative and late callbacks. */
    fun cancelSwitch() {
        policy.cancel()
        resolveJob?.cancel()
        resolveJob = null
        changed()
    }

    fun close() {
        cancelSwitch()
        policy.beginCatalog()
        loadJob?.cancel()
        channels = emptyList()
    }
}
