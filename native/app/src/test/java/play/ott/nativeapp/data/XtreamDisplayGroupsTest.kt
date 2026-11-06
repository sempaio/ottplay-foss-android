package play.ott.nativeapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import play.ott.nativeapp.core.MediaKind
import play.ott.nativeapp.core.XtreamGroup

class XtreamDisplayGroupsTest {
    private fun group(id: String, name: String, kind: MediaKind = MediaKind.LIVE) =
        XtreamGroup(id, name, kind)

    @Test fun openChannelsAliasesBecomeOneVisibleCategoryWithAllSourceIds() {
        val sources = listOf(
            group("1", "Canais abertos"), group("2", "TV Brasileira"),
            group("3", "CANAIS: ABERTOS ✨"), group("4", "Canais | Infantis"),
            group("5", "CANAIS: INFANTIS ✨")
        )
        val output = XtreamDisplayGroups.merge(sources)
        assertEquals(2, output.size)
        assertEquals("Abertos", output[0].label)
        assertEquals(listOf("1", "2", "3"), output[0].sources.map { it.id })
        assertEquals("Infantis", output[1].label)
        assertEquals(listOf("4", "5"), output[1].sources.map { it.id })
    }

    @Test fun neverMergeAcrossMediaKindsOrUnrelatedChannelBrands() {
        val sources = listOf(
            group("1", "CANAIS: HBO ✨"),
            group("2", "Canais | Filmes"),
            group("3", "Canais | Infantis"),
            group("4", "Infantis", MediaKind.MOVIE)
        )
        val output = XtreamDisplayGroups.merge(sources)
        assertEquals(4, output.size)
        assertTrue(output.any { it.label == "HBO" })
    }

    @Test fun duplicateNameCaseDiacriticsAndPunctuationDontHideIds() {
        val sources = listOf(
            group("11", "Canais | Notícias"),
            group("12", "CANAIS: NOTICIAS ⭐"),
            group("13", "notícias"),
            group("14", "Notícias", MediaKind.SERIES)
        )
        val output = XtreamDisplayGroups.merge(sources)
        assertEquals(2, output.size)
        assertEquals(listOf("11", "12", "13"), output[0].sources.map { it.id })
    }
}
