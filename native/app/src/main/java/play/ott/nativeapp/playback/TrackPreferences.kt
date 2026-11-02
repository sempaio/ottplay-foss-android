package play.ott.nativeapp.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import play.ott.nativeapp.data.TrackPreference
import play.ott.nativeapp.data.UserPreferences

@UnstableApi
internal object TrackPreferences {
    val types = listOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_TEXT)

    fun changed(before: TrackSelectionParameters, after: TrackSelectionParameters, type: Int): Boolean =
        before.disabledTrackTypes.contains(type) != after.disabledTrackTypes.contains(type) ||
            before.overrides.filterValues { it.type == type } != after.overrides.filterValues { it.type == type }

    private fun language(value: String?): String? = value?.let { Util.normalizeLanguageCode(it) }

    fun capture(parameters: TrackSelectionParameters, type: Int): TrackPreference {
        if (type in parameters.disabledTrackTypes) return TrackPreference("off")
        val override = parameters.overrides.values.firstOrNull { it.type == type && it.trackIndices.isNotEmpty() }
            ?: return TrackPreference()
        val format = override.mediaTrackGroup.getFormat(override.trackIndices.first())
        val language = language(format.language)?.takeUnless { it == "und" }
            ?.takeIf { it.matches(Regex("[a-zA-Z0-9]{1,8}(-[a-zA-Z0-9]{1,8}){0,7}")) }
        val label = format.label?.takeIf { it.length in 1..128 && it.none(Char::isISOControl) }
        if (language == null && label == null && format.roleFlags == 0) return TrackPreference()
        return TrackPreference("track", language, format.roleFlags, if (language == null) label else null)
    }

    private fun matches(choice: TrackPreference, format: Format): Boolean =
        (choice.language == null || language(choice.language) == language(format.language)) &&
            (choice.label == null || choice.label == format.label) && choice.roleFlags == format.roleFlags

    fun apply(parameters: TrackSelectionParameters, tracks: Tracks, choices: List<TrackPreference>): TrackSelectionParameters {
        val builder = parameters.buildUpon()
        types.forEachIndexed { index, type ->
            val choice = choices[index]
            builder.clearOverridesOfType(type).setTrackTypeDisabled(type, choice.mode == "off")
            if (choice.mode == "track") {
                val match = tracks.groups.asSequence().filter { it.type == type }.flatMap { group ->
                    (0 until group.length).asSequence().filter { group.isTrackSupported(it) && matches(choice, group.getTrackFormat(it)) }
                        .map { TrackSelectionOverride(group.mediaTrackGroup, it) }
                }.firstOrNull()
                if (match != null) builder.setOverrideForType(match)
                // An unavailable requested subtitle must not silently select another language.
                else if (type == C.TRACK_TYPE_TEXT) builder.setTrackTypeDisabled(type, true)
            }
        }
        return builder.build()
    }
}

/** Apply durable semantic choices while preserving unlabelled selections for this stream. */
@UnstableApi
internal class TrackPreferencePlayer(
    private val delegate: Player,
    preferences: Flow<UserPreferences>,
    scope: CoroutineScope,
) : ForwardingPlayer(delegate) {
    private var choices = listOf(TrackPreference(), TrackPreference())
    private val currentStreamOverrides = mutableMapOf<Int, TrackSelectionOverride>()
    private val preferenceJob: Job
    private val trackListener = object : Player.Listener {
        override fun onTracksChanged(tracks: Tracks) { apply(tracks) }
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            currentStreamOverrides.clear()
            apply(delegate.currentTracks)
        }
    }

    init {
        delegate.addListener(trackListener)
        preferenceJob = scope.launch {
            preferences.map { listOf(it.audioTrack, it.subtitleTrack) }.distinctUntilChanged().collect {
                if (choices != it) {
                    choices = it
                    // An acknowledgement may trail a newer unlabelled selection. Only
                    // explicit selection changes or a media-item transition retire it.
                    apply(delegate.currentTracks)
                }
            }
        }
    }

    override fun setTrackSelectionParameters(parameters: TrackSelectionParameters) {
        val previous = delegate.trackSelectionParameters
        TrackPreferences.types.forEach { type ->
            if (TrackPreferences.changed(previous, parameters, type)) {
                currentStreamOverrides.remove(type)
                if (TrackPreferences.capture(parameters, type).mode == "auto") parameters.overrides.values.firstOrNull {
                    it.type == type && it.trackIndices.isNotEmpty()
                }?.let { currentStreamOverrides[type] = it }
            }
        }
        // Keep the exact user selection until the next manifest publishes its tracks.
        delegate.trackSelectionParameters = parameters
    }

    private fun apply(tracks: Tracks) {
        currentStreamOverrides.entries.removeAll { (_, override) ->
            tracks.groups.none { it.mediaTrackGroup == override.mediaTrackGroup }
        }
        val parameters = TrackPreferences.apply(delegate.trackSelectionParameters, tracks, choices).buildUpon().apply {
            currentStreamOverrides.values.forEach {
                setTrackTypeDisabled(it.type, false)
                setOverrideForType(it)
            }
        }.build()
        if (parameters != delegate.trackSelectionParameters) delegate.trackSelectionParameters = parameters
    }

    override fun release() {
        preferenceJob.cancel()
        delegate.removeListener(trackListener)
        super.release()
    }
}
