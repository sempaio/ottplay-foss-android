package play.ott.nativeapp.ui

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import play.ott.nativeapp.core.MediaEntry
import play.ott.nativeapp.core.MediaKind
import play.ott.nativeapp.core.Programme

class TvDpadAndEpgGridTest {
    @Test
    fun mapsLiveDpadKeysWhenControlsHiddenAndRespectsFocusWhenVisible() {
        assertEquals(
            TvPlayerRemoteAction.NEXT_CHANNEL,
            TvDpadPolicy.mapPlayerKey(KeyEvent.KEYCODE_DPAD_UP, isLive = true, controlsVisible = false, drawerOpen = false, locked = false),
        )
        assertEquals(
            TvPlayerRemoteAction.PREVIOUS_CHANNEL,
            TvDpadPolicy.mapPlayerKey(KeyEvent.KEYCODE_DPAD_DOWN, isLive = true, controlsVisible = false, drawerOpen = false, locked = false),
        )
        // When HUD controls are visible, DPAD_UP passes through to Compose focus traversal
        assertEquals(
            TvPlayerRemoteAction.NONE,
            TvDpadPolicy.mapPlayerKey(KeyEvent.KEYCODE_DPAD_UP, isLive = true, controlsVisible = true, drawerOpen = false, locked = false),
        )
        // Dedicated CHANNEL_UP always zaps live channels
        assertEquals(
            TvPlayerRemoteAction.NEXT_CHANNEL,
            TvDpadPolicy.mapPlayerKey(KeyEvent.KEYCODE_CHANNEL_UP, isLive = true, controlsVisible = true, drawerOpen = false, locked = false),
        )
        // GUIDE opens EPG on live
        assertEquals(
            TvPlayerRemoteAction.OPEN_EPG,
            TvDpadPolicy.mapPlayerKey(KeyEvent.KEYCODE_GUIDE, isLive = true, controlsVisible = false, drawerOpen = false, locked = false),
        )
    }

    @Test
    fun mapsVodSeekAndLockSafely() {
        assertEquals(
            TvPlayerRemoteAction.SEEK_FORWARD_10S,
            TvDpadPolicy.mapPlayerKey(KeyEvent.KEYCODE_DPAD_RIGHT, isLive = false, controlsVisible = false, drawerOpen = false, locked = false),
        )
        assertEquals(
            TvPlayerRemoteAction.SEEK_BACK_10S,
            TvDpadPolicy.mapPlayerKey(KeyEvent.KEYCODE_DPAD_LEFT, isLive = false, controlsVisible = false, drawerOpen = false, locked = false),
        )
        // When locked, directional keys do nothing; center only reveals lock hint
        assertEquals(
            TvPlayerRemoteAction.NONE,
            TvDpadPolicy.mapPlayerKey(KeyEvent.KEYCODE_CHANNEL_UP, isLive = true, controlsVisible = false, drawerOpen = false, locked = true),
        )
        assertEquals(
            TvPlayerRemoteAction.REVEAL_HUD,
            TvDpadPolicy.mapPlayerKey(KeyEvent.KEYCODE_DPAD_CENTER, isLive = true, controlsVisible = false, drawerOpen = false, locked = true),
        )
    }

    @Test
    fun computesEpgProgressRemainingMinutesAndFiltering() {
        val base = 1_760_000_000_000L
        val past = Programme("ch1", "Jornal Anterior", base - 3_600_000L, base - 600_000L)
        val live = Programme("ch1", "Jogo Ao Vivo", base - 1_800_000L, base + 1_800_000L)
        val next = Programme("ch1", "Filme da Noite", base + 1_800_000L, base + 5_400_000L)
        val farFuture = Programme("ch1", "Outro Dia", base + 48 * 3_600_000L, base + 49 * 3_600_000L)

        assertEquals(0.5f, EpgGridLogic.progressFraction(live, base), 0.01f)
        assertEquals(30, EpgGridLogic.remainingMinutes(live, base))
        assertEquals(0f, EpgGridLogic.progressFraction(next, base), 0.001f)
        assertEquals(1f, EpgGridLogic.progressFraction(past, base), 0.001f)

        val nowNext = EpgGridLogic.filterProgrammes(listOf(farFuture, next, past, live), EpgWindowFilter.NOW_AND_NEXT, base)
        assertEquals(listOf("Jogo Ao Vivo", "Filme da Noite", "Outro Dia"), nowNext.map { it.title })

        val today = EpgGridLogic.filterProgrammes(listOf(farFuture, next, past, live), EpgWindowFilter.TODAY, base)
        assertEquals(listOf("Jornal Anterior", "Jogo Ao Vivo", "Filme da Noite"), today.map { it.title })
    }

    @Test
    fun wrapsAdjacentChannelsCleanly() {
        val channels = listOf(
            MediaEntry(id = "c1", sourceId = "s", name = "Globo", kind = MediaKind.LIVE),
            MediaEntry(id = "c2", sourceId = "s", name = "SBT", kind = MediaKind.LIVE),
            MediaEntry(id = "c3", sourceId = "s", name = "Band", kind = MediaKind.LIVE),
        )
        assertEquals("c2", EpgGridLogic.adjacentChannel(channels, "c1", +1)?.id)
        assertEquals("c3", EpgGridLogic.adjacentChannel(channels, "c1", -1)?.id)
        assertEquals("c1", EpgGridLogic.adjacentChannel(channels, "c3", +1)?.id)
        assertNull(EpgGridLogic.adjacentChannel(listOf(channels.first()), "c1", +1))
    }
}
