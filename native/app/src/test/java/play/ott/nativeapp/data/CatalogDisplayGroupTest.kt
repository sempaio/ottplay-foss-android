package play.ott.nativeapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import play.ott.nativeapp.core.MediaEntry
import play.ott.nativeapp.core.MediaKind

class CatalogDisplayGroupTest {
    private fun item(name: String, group: String, kind: MediaKind = MediaKind.LIVE) =
        MediaEntry(id = name, sourceId = "m81", name = name, group = group, kind = kind)

    @Test fun animationAndAnimeWronglyListedAsVarietiesGetLocalGroups() {
        val samples = listOf(
            item("As Tartarugas Ninja", "Variedades") to "Desenhos",
            item("Avatar: A Lenda de Aang", "Variedades") to "Desenhos",
            item("Inspector Bugiganga", "Variedades") to "Desenhos",
            item("Bob Esponja", "Variedades") to "Desenhos",
            item("Hunter x Hunter", "Variedades") to "Animes",
            item("Beyblade", "Variedades") to "Animes",
            item("Pokémon", "Variedades") to "Animes"
        )
        samples.forEach { (entry, expected) -> assertEquals(entry.name, expected, CatalogDisplayGroup.group(entry)) }
    }

    @Test fun neverReclassifyMediaTypeOrChangeOriginalProviderGroup() {
        val movie = item("Avatar: A Lenda de Aang", "Variedades", MediaKind.MOVIE)
        assertEquals("Desenhos", CatalogDisplayGroup.group(movie))
        assertEquals("Variedades", movie.group)
        assertEquals(MediaKind.MOVIE, movie.kind)
        val series = item("Hunter x Hunter", "Outros", MediaKind.SERIES)
        assertEquals("Animes", CatalogDisplayGroup.group(series))
        assertEquals(MediaKind.SERIES, series.kind)
    }

    @Test fun meaningfulProviderCategoriesAlwaysWin() {
        assertEquals("Aventura", CatalogDisplayGroup.group(item("Beyblade", "Aventura")))
        assertEquals("Esportes", CatalogDisplayGroup.group(item("Hunter x Hunter", "Esportes")))
        assertEquals("Desenhos", CatalogDisplayGroup.group(item("Canal livre", "Desenhos")))
    }

    @Test fun unclearTitlesRemainInTheirOriginalGroup() {
        assertEquals("Variedades", CatalogDisplayGroup.group(item("Grachi", "Variedades")))
        assertEquals("Variedades", CatalogDisplayGroup.group(item("Homeful", "Variedades")))
        assertEquals("Variedades", CatalogDisplayGroup.group(item("Kenan & Kel", "Variedades")))
        assertEquals("", CatalogDisplayGroup.group(item("Sem indicação", "")))
    }

    @Test fun knownSportsAndCartoonChannelBrandsAreOnlyLocallyGrouped() {
        val fifa = item("FIFA+", "Variedades")
        val nfl = item("NFL Channel", "Outros")
        val cartoon = item("Cartoon Network", "Geral")
        assertEquals("Futebol", CatalogDisplayGroup.group(fifa))
        assertEquals("Esportes", CatalogDisplayGroup.group(nfl))
        assertEquals("Desenhos", CatalogDisplayGroup.group(cartoon))
        assertEquals("Variedades", fifa.group)
        assertEquals(MediaKind.LIVE, nfl.kind)
    }

    @Test fun normalizationSupportsPunctuationAndAccents() {
        assertEquals("Animes", CatalogDisplayGroup.group(item("Pokémon HD", "VARIEDADES")))
        assertEquals("Desenhos", CatalogDisplayGroup.group(item("Inspetor Bugiganga [FHD]", "Diversos")))
    }

    @Test fun recommendationsUsePresentationGroupsWithoutChangingCatalogue() {
        val entry = item("Beyblade", "Variedades")
        val profile = ForYouProfile(onboardingDone = true, interests = setOf("animes"))
        assertEquals(listOf(entry), ForYouEngine.recommend(listOf(entry), profile))
        assertEquals("Variedades", entry.group)
    }
}
