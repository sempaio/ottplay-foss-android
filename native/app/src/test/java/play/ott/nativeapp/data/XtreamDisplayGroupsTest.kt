package play.ott.nativeapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import play.ott.nativeapp.core.MediaKind
import play.ott.nativeapp.core.XtreamGroup

class XtreamDisplayGroupsTest {
    @Test fun openNetworkBrandsAndSportsAppearUnderSingleLiveGroup() {
        val src = listOf(
            group("a", "TV Brasileira"), group("b", "CANAIS: GLOBO"),
            group("c", "CANAIS: SBT"), group("d", "CANAIS: BAND"),
            group("e", "CANAIS: RECORD"), group("f", "CANAIS: ESPN"),
            group("g", "Canais | Premiere"), group("h", "Esportes"),
            group("i", "CANAIS: HBO"), group("j", "Canais | Telecine"),
            group("k", "Canais | Filmes"),
            group("m", "Canais | Globo News")
        )
        val groups = XtreamDisplayGroups.merge(src)
        assertEquals(listOf("a", "b", "c", "d", "e"),
            groups.first { it.label == "Abertos" }.sources.map { it.id })
        assertEquals(listOf("f", "g", "h"),
            groups.first { it.label == "Esportes" }.sources.map { it.id })
        assertEquals(listOf("i", "j", "k"),
            groups.first { it.label == "Filmes" }.sources.map { it.id })
        assertEquals(listOf("m"), groups.first { it.label == "Notícias" }.sources.map { it.id })
    }

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

    @Test fun mergeLiveMovieNetworksWithoutChangingMoviesOrSeries() {
        val sources = listOf(
            group("1", "CANAIS: HBO ✨"),
            group("2", "Canais | Filmes"),
            group("3", "Canais | Infantis"),
            group("4", "Infantis", MediaKind.MOVIE)
        )
        val output = XtreamDisplayGroups.merge(sources)
        assertEquals(3, output.size)
        assertEquals(listOf("1", "2"), output.first { it.label == "Filmes" }.sources.map { it.id })
        assertTrue(output.any { it.kind == MediaKind.MOVIE && it.label == "Infantis" })
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
