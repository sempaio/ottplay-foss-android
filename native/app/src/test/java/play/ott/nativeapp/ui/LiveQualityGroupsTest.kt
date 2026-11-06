package play.ott.nativeapp.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import play.ott.nativeapp.core.MediaEntry
import play.ott.nativeapp.core.MediaKind

class LiveQualityGroupsTest {
    @Test fun equivalentXtreamCategoriesJoinOnlyQualityVariantsOfSameChannel() {
        val input = listOf(
            entry("Globo RJ SD", "Canais abertos"),
            entry("Globo RJ FHD", "CANAIS: ABERTOS ✨"),
            entry("Globo SP HD", "TV Brasileira"),
        )
        val cards = liveQualityCards(input) { media ->
            play.ott.nativeapp.data.XtreamDisplayGroups.label(media.group, media.kind)
        }
        assertEquals(2, cards.size)
        val rj = cards.first { it.displayName == "Globo RJ" }
        assertEquals(2, rj.options.size)
        assertEquals(input.take(2).map { it.id }.toSet(), rj.options.map { it.id }.toSet())
    }

    private fun entry(name: String, group: String = "Esportes", kind: MediaKind = MediaKind.LIVE, source: String = "starlink") =
        MediaEntry(id = "${source}:${group}:${name}", sourceId = source, name = name, group = group, kind = kind)

    @Test fun sportvQualitiesBecomeOneDisplayCardWithoutLosingAnyLinks() {
        val source = listOf(entry("SporTV SD"), entry("SporTV HD"), entry("SporTV FHD"),
            entry("SporTV FHD [H265]"), entry("SporTV 4K"))
        val cards = liveQualityCards(source)
        assertEquals(1, cards.size)
        assertEquals("SporTV", cards.single().displayName)
        assertEquals(5, cards.single().options.size)
        assertEquals(source.map { it.id }.toSet(), cards.single().options.map { it.id }.toSet())
        assertEquals("4K", qualityName(cards.single().options.first().name))
    }

    @Test fun numberedSportvChannelsStayDifferent() {
        val cards = liveQualityCards(listOf(entry("SporTV HD"), entry("SporTV 2 HD"),
            entry("SporTV 3 SD"), entry("SporTV 4 FHD")))
        assertEquals(4, cards.size)
    }

    @Test fun globoRegionalFeedsStayDifferent() {
        val cards = liveQualityCards(listOf(entry("Globo RJ HD"), entry("Globo RJ FHD"),
            entry("Globo SP HD"), entry("Globo SP SD")))
        assertEquals(2, cards.size)
        assertEquals(setOf("Globo RJ", "Globo SP"), cards.map { it.displayName }.toSet())
    }

    @Test fun codecDoesNotRemoveAlternateLink() {
        val cards = liveQualityCards(listOf(entry("Globo News FHD"), entry("Globo News FHD [H265]")))
        assertEquals(1, cards.size)
        assertEquals(2, cards.single().options.size)
        assertEquals(setOf("FHD", "FHD H.265"), cards.single().options.map { qualityName(it.name) }.toSet())
    }

    @Test fun neverMergeDifferentKindsCategoriesOrSources() {
        val cards = liveQualityCards(listOf(entry("SporTV HD"), entry("SporTV FHD", "Variedades"),
            entry("SporTV 4K", source = "other"), entry("SporTV SD", kind = MediaKind.MOVIE)))
        assertEquals(4, cards.size)
    }

    @Test fun unknownQualityOrRepeatedNameIsNotSilentlyMerged() {
        val cards = liveQualityCards(listOf(entry("Canal Local"), entry("Canal Local"),
            entry("HD TV Brasil"), entry("TV Brasil HD")))
        assertEquals(4, cards.size)
    }

    @Test fun originalEntriesAreUnchangedForPlaybackAndFavorite() {
        val original = listOf(entry("Globo RJ SD"), entry("Globo RJ HD"))
        val cards = liveQualityCards(original)
        assertEquals(original.toSet(), cards.single().options.toSet())
        assertTrue(original.all { it.name.endsWith("SD") || it.name.endsWith("HD") })
    }
}
