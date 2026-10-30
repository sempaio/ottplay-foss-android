package play.ott.nativeapp.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import play.ott.nativeapp.data.TrackPreference
import play.ott.nativeapp.data.UserPreferences

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = PlaybackTestApplication::class)
class TrackPreferencesTest {
    private fun audio(language: String, role: Int = 0) = Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_AAC).setLanguage(language).setRoleFlags(role).build()
    private fun text(language: String) = Format.Builder().setSampleMimeType(MimeTypes.TEXT_VTT).setLanguage(language).build()
    private fun tracks(vararg groups: TrackGroup) = Tracks(groups.map { group ->
        Tracks.Group(group, false, IntArray(group.length) { C.FORMAT_HANDLED }, BooleanArray(group.length))
    })

    @Test fun `saved language follows reordered tracks instead of saved index`() {
        val original = TrackGroup("old", audio("en"), audio("ru"))
        val selected = TrackSelectionParameters.DEFAULT.buildUpon()
            .setOverrideForType(TrackSelectionOverride(original, 1)).build()
        val choice = TrackPreferences.capture(selected, C.TRACK_TYPE_AUDIO)
        val replacement = TrackGroup("new", audio("ru"), audio("en"))
        val applied = TrackPreferences.apply(selected, tracks(replacement), listOf(choice, TrackPreference()))
        assertEquals("ru", choice.language)
        assertEquals(listOf(0), applied.overrides[replacement]?.trackIndices?.toList())
        assertFalse(applied.overrides.containsKey(original))
    }

    @Test fun `imported language tags are normalized and unlabelled tracks stay local`() {
        val group = TrackGroup("audio", audio("ru"), audio("en"))
        val applied = TrackPreferences.apply(TrackSelectionParameters.DEFAULT, tracks(group),
            listOf(TrackPreference("track", "RUS"), TrackPreference()))
        assertEquals(listOf(0), applied.overrides[group]?.trackIndices?.toList())
        val unknown = TrackGroup("unknown", audio("und"))
        val selection = applied.buildUpon().setOverrideForType(TrackSelectionOverride(unknown, 0)).build()
        assertEquals(TrackPreference(), TrackPreferences.capture(selection, C.TRACK_TYPE_AUDIO))
    }

    @Test fun `commentary role does not replace normal audio in same language`() {
        val group = TrackGroup("audio", audio("en", C.ROLE_FLAG_COMMENTARY), audio("en"))
        val applied = TrackPreferences.apply(TrackSelectionParameters.DEFAULT, tracks(group),
            listOf(TrackPreference("track", "en"), TrackPreference()))
        assertEquals(listOf(1), applied.overrides[group]?.trackIndices?.toList())
    }

    @Test fun `missing requested subtitles stay off then recover when their language returns`() {
        val choices = listOf(TrackPreference(), TrackPreference("track", "ru"))
        val english = TrackGroup("english", text("en"))
        val missing = TrackPreferences.apply(TrackSelectionParameters.DEFAULT, tracks(english), choices)
        assertTrue(C.TRACK_TYPE_TEXT in missing.disabledTrackTypes)
        val russian = TrackGroup("russian", text("ru"))
        val recovered = TrackPreferences.apply(missing, tracks(russian), choices)
        assertFalse(C.TRACK_TYPE_TEXT in recovered.disabledTrackTypes)
        assertEquals(listOf(0), recovered.overrides[russian]?.trackIndices?.toList())
    }

    @Test fun `unavailable or unsupported preferred audio keeps platform fallback enabled`() {
        val original = TrackGroup("old", audio("ru"))
        val selected = TrackSelectionParameters.DEFAULT.buildUpon()
            .setOverrideForType(TrackSelectionOverride(original, 0)).build()
        val next = TrackGroup("new", audio("ru"), audio("en", C.ROLE_FLAG_COMMENTARY))
        val available = Tracks(listOf(Tracks.Group(next, false,
            intArrayOf(C.FORMAT_UNSUPPORTED_TYPE, C.FORMAT_HANDLED), BooleanArray(2))))
        val result = TrackPreferences.apply(selected, available,
            listOf(TrackPreference("track", "ru"), TrackPreference()))
        assertTrue(result.overrides.isEmpty())
        assertFalse(C.TRACK_TYPE_AUDIO in result.disabledTrackTypes)
    }

    @Test fun `off persists and auto removes explicit overrides`() {
        val group = TrackGroup("text", text("en"))
        val parameters = TrackSelectionParameters.DEFAULT.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group, 0)).build()
        val off = TrackPreferences.apply(parameters, tracks(group), listOf(TrackPreference(), TrackPreference("off")))
        assertTrue(C.TRACK_TYPE_TEXT in off.disabledTrackTypes)
        assertEquals(TrackPreference("off"), TrackPreferences.capture(off, C.TRACK_TYPE_TEXT))
        val auto = TrackPreferences.apply(off, tracks(group), listOf(TrackPreference(), TrackPreference()))
        assertFalse(C.TRACK_TYPE_TEXT in auto.disabledTrackTypes)
        assertTrue(auto.overrides.isEmpty())
    }
}

@UnstableApi
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = PlaybackTestApplication::class)
class TrackPreferencePlayerTest {
    private class Delegate(group: TrackGroup) {
        var parameters = TrackSelectionParameters.DEFAULT
        var tracks = Tracks(listOf(Tracks.Group(group, false,
            IntArray(group.length) { C.FORMAT_HANDLED }, BooleanArray(group.length))))
        val listeners = mutableListOf<Player.Listener>()
        var writes = 0
        val player = Proxy.newProxyInstance(Player::class.java.classLoader,
            arrayOf(Player::class.java)) { _, method, args ->
            when (method.name) {
                "getTrackSelectionParameters" -> parameters
                "setTrackSelectionParameters" -> { parameters = args!![0] as TrackSelectionParameters; writes++; null }
                "getCurrentTracks" -> tracks
                "addListener" -> { listeners.add(args!![0] as Player.Listener); null }
                "removeListener" -> { listeners.remove(args!![0] as Player.Listener); null }
                "release" -> null
                else -> error("Unexpected player call: ${method.name}")
            }
        } as Player
    }
    private fun group() = TrackGroup("same-id",
        Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).setLanguage("ru").build(),
        Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).build())
    private fun choose(player: TrackPreferencePlayer, group: TrackGroup) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group, 1)).build()
    }

    @Test fun `unlabelled selection survives own Auto acknowledgement and unrelated subtitle changes`() = runTest {
        val group = group()
        val delegate = Delegate(group)
        val preferences = MutableStateFlow(UserPreferences(
            audioTrack = TrackPreference("track", "ru")))
        val wrapper = TrackPreferencePlayer(delegate.player, preferences, backgroundScope)
        try {
            runCurrent()
            choose(wrapper, group)
            preferences.value = preferences.value.copy(audioTrack = TrackPreference())
            runCurrent()
            assertEquals(listOf(1), delegate.parameters.overrides[group]?.trackIndices?.toList())
            preferences.value = preferences.value.copy(subtitleTrack = TrackPreference("off"))
            runCurrent()
            assertEquals(listOf(1), delegate.parameters.overrides[group]?.trackIndices?.toList())
            assertTrue(C.TRACK_TYPE_TEXT in delegate.parameters.disabledTrackTypes)
            wrapper.trackSelectionParameters = delegate.parameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_AUDIO).build()
            delegate.listeners.single().onTracksChanged(delegate.tracks)
            assertFalse(delegate.parameters.overrides.containsKey(group), "explicit Auto removes the temporary choice")
        } finally { wrapper.release() }
    }

    @Test fun `late older preference acknowledgement cannot replace a newer stream-local selection`() = runTest {
        val group = group()
        val delegate = Delegate(group)
        val preferences = MutableStateFlow(UserPreferences())
        val wrapper = TrackPreferencePlayer(delegate.player, preferences, backgroundScope)
        try {
            runCurrent()
            choose(wrapper, group)
            preferences.value = preferences.value.copy(audioTrack = TrackPreference("off"))
            runCurrent()
            assertFalse(C.TRACK_TYPE_AUDIO in delegate.parameters.disabledTrackTypes)
            assertEquals(listOf(1), delegate.parameters.overrides[group]?.trackIndices?.toList())
            preferences.value = preferences.value.copy(audioTrack = TrackPreference())
            runCurrent()
            assertEquals(listOf(1), delegate.parameters.overrides[group]?.trackIndices?.toList())
        } finally { wrapper.release() }
    }

    @Test fun `equal track group in another media item never inherits the local track index`() = runTest {
        val original = group()
        val replacement = group()
        assertEquals(original, replacement, "different streams may publish equal track groups")
        val delegate = Delegate(original)
        val preferences = MutableStateFlow(UserPreferences())
        val wrapper = TrackPreferencePlayer(delegate.player, preferences, backgroundScope)
        try {
            runCurrent()
            choose(wrapper, original)
            delegate.listeners.single().onTracksChanged(delegate.tracks)
            assertEquals(listOf(1), delegate.parameters.overrides[original]?.trackIndices?.toList())
            delegate.tracks = Delegate(replacement).tracks
            delegate.listeners.single().onMediaItemTransition(MediaItem.fromUri("https://example.invalid/new"),
                Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
            delegate.listeners.single().onTracksChanged(delegate.tracks)
            assertTrue(delegate.parameters.overrides.isEmpty())
        } finally { wrapper.release() }
    }

    @Test fun `releasing the wrapper cancels preference observation`() = runTest {
        val delegate = Delegate(group())
        val preferences = MutableStateFlow(UserPreferences())
        val wrapper = TrackPreferencePlayer(delegate.player, preferences, backgroundScope)
        runCurrent()
        wrapper.release()
        val writes = delegate.writes
        preferences.value = preferences.value.copy(subtitleTrack = TrackPreference("off"))
        runCurrent()
        assertEquals(writes, delegate.writes)
        assertTrue(delegate.listeners.isEmpty())
    }
}
