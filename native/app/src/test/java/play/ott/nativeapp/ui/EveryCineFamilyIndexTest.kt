package play.ott.nativeapp.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Tests of names shown by real IPTV providers, not generated artwork or playback URLs. */
class EveryCineFamilyIndexTest {
    @Test fun sportsHaveOneFamilyCardWithDistinctChannels() {
        assertEquals("Esportes", EveryCineFamilyIndex.category("ESPN 2 (1080p)", "Sem categoria"))
        assertEquals("ESPN", EveryCineFamilyIndex.family("ESPN (720p)", "Esportes"))
        assertEquals("ESPN", EveryCineFamilyIndex.family("ESPN 2 (1080p)", "Esportes"))
        assertEquals("SporTV", EveryCineFamilyIndex.family("SporTV 3 FHD", "Esportes"))
        assertEquals("Premiere", EveryCineFamilyIndex.family("PREMIERE 4 HD", "Esportes"))
        assertNotEquals(EveryCineFamilyIndex.family("ESPN 2", "Esportes"),
            EveryCineFamilyIndex.family("SporTV 2", "Esportes"))
    }

    @Test fun transformersAndBlitzKeepEachOriginalLiveEntryInsideAFranchise() {
        val titles = listOf("Transformers (1080p) 24h", "Transformers A Vingança dos Derrotados",
            "TRANSFORMERS O Lado Oculto da Lua", "Transformers Era da Extinção")
        assertEquals(setOf("Transformers"), titles.map {
            EveryCineFamilyIndex.family(it, "Filmes 24h")
        }.toSet())
        assertEquals("Filmes 24h", EveryCineFamilyIndex.category("Transformers 24H", "Filmes 24h"))
        assertEquals("Blitz", EveryCineFamilyIndex.family("Blitz Temporada 3 (720p)", "Séries 24h"))
        assertEquals("Blitz", EveryCineFamilyIndex.family("Blitz T01", "Séries 24h"))
        assertNotEquals("Blitz", EveryCineFamilyIndex.family("Blitzkrieg", "Séries 24h"))
    }

    @Test fun sameRegionResolutionsGroupButDifferentRegionsDoNot() {
        assertEquals("Globo", EveryCineFamilyIndex.family("Globo RJ FHD", "Abertos"))
        assertEquals("Globo RJ", EveryCineFamilyIndex.variant("Globo RJ (1080p)"))
        assertEquals("Globo RJ", EveryCineFamilyIndex.variant("Globo RJ SD"))
        assertEquals("Globo SP", EveryCineFamilyIndex.variant("Globo SP HD"))
        assertNotEquals(EveryCineFamilyIndex.variant("Globo RJ FHD"),
            EveryCineFamilyIndex.variant("Globo SP HD"))
        assertEquals("Notícias", EveryCineFamilyIndex.category("GloboNews", "Abertos"))
        assertNotEquals("Globo", EveryCineFamilyIndex.family("GloboNews", "Notícias"))
    }

    @Test fun categoriesComeFromProviderNamesRatherThanInventedChannels() {
        assertEquals("Esportes", EveryCineFamilyIndex.category("", "ESPORTES HD"))
        assertEquals("Canais abertos", EveryCineFamilyIndex.category("", "Globos Sudeste"))
        assertEquals("Infantil", EveryCineFamilyIndex.category("", "ANIMES"))
        assertEquals("Infantil", EveryCineFamilyIndex.category("", "Infantis"))
        assertEquals("Filmes 24h", EveryCineFamilyIndex.category("", "24h Filmes"))
        assertEquals("Séries 24h", EveryCineFamilyIndex.category("", "Series 24h"))
        assertEquals("Outros", EveryCineFamilyIndex.category("Canal aleatório", "Sem categoria"))
    }

    @Test fun cartoonAndAnimeAreSeparateInsideInfantil() {
        assertEquals("Infantil", EveryCineFamilyIndex.category("Naruto 24h", "Sem categoria"))
        assertEquals("Animes", EveryCineFamilyIndex.family("Naruto T02", "Animes"))
        assertEquals("Desenhos", EveryCineFamilyIndex.family("Pica Pau", "Desenhos"))
        assertNotEquals(EveryCineFamilyIndex.family("Naruto", "Animes"),
            EveryCineFamilyIndex.family("Pica Pau", "Desenhos"))
    }

    @Test fun unknownSimilarNamesRemainDistinctAndOriginalTitlesAreNotLost() {
        assertNotEquals(EveryCineFamilyIndex.family("Blitz", "Variedades"),
            EveryCineFamilyIndex.family("Blitzkrieg", "Variedades"))
        assertNotEquals(EveryCineFamilyIndex.family("Transformers", "Filmes 24h"),
            EveryCineFamilyIndex.family("Transformação", "Filmes 24h"))
        assertTrue(EveryCineFamilyIndex.family("Outro canal 24h", "Outros").isNotBlank())
    }
}
