package play.ott.nativeapp.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import play.ott.nativeapp.core.MediaEntry
import play.ott.nativeapp.core.MediaKind

class LiveSeasonGroupsTest {
    private fun entry(name: String, kind: MediaKind = MediaKind.LIVE, source: String = "xtream") =
        MediaEntry(id = "$source:$name", sourceId = source, kind = kind, name = name, group = "Séries",
            url = "https://test.invalid/live/" + name.length)

    @Test fun live24HourSeasonChannelsBecomeOneSeriesTileWithoutBecomingVod() {
        val entries = listOf(entry("[24H] Chaves [S01]"), entry("[24H] Chaves [S02]"),
            entry("[24H] Chaves [S03]"), entry("[24H] Chapolin [S01]"))
        val cards = LiveSeasonGroups.join(liveQualityCards(entries))
        assertEquals(2, cards.size)
        val chaves = cards.first { it.displayName == "Chaves" }
        assertEquals(3, chaves.options.size)
        assertTrue(chaves.options.all { it.kind == MediaKind.LIVE })
        assertEquals(entries.take(3).map { it.id }, chaves.options.map { it.id })
        assertEquals(listOf(1, 2, 3), chaves.options.map { LiveSeasonGroups.season(it.name)?.number })
        assertTrue(LiveSeasonGroups.isSeasonBundle(chaves.options))
    }

    @Test fun seriesDifferentSourcesAndVODNeverMerge() {
        val result = LiveSeasonGroups.join(liveQualityCards(listOf(
            entry("[24H] Chaves [S01]"), entry("[24H] Chaves [S02]", source = "second"),
            entry("Chaves [S03]", MediaKind.SERIES),
            entry("[24H] Chaves [S04]", MediaKind.MOVIE))))
        assertEquals(4, result.size)
        assertFalse(result.any { LiveSeasonGroups.isSeasonBundle(it.options) })
    }

    @Test fun sameSeasonQualitiesRemainStreamsAndStandaloneLiveChannelsStayStandalone() {
        val channels = listOf(entry("[24H] Friends [S01] SD"), entry("[24H] Friends [S01] HD"),
            entry("[24H] Friends [S02]"), entry("Band HD"))
        val result = LiveSeasonGroups.join(liveQualityCards(channels))
        val friends = result.first { it.displayName == "Friends" }
        assertEquals(3, friends.options.size)
        assertEquals(channels.take(3).map { it.id }.toSet(), friends.options.map { it.id }.toSet())
        assertTrue(result.any { it.displayName == "Band HD" })
    }
}
