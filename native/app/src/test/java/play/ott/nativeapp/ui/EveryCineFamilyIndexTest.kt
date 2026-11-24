package play.ott.nativeapp.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Tests of names shown by real IPTV providers, matching M81 catalog classification. */
class EveryCineFamilyIndexTest {
    @Test fun sportsHaveOneFamilyCardWithDistinctChannels() {
        assertEquals("Esportes", EveryCineFamilyIndex.category("ESPN 2 (1080p)", "Sem categoria"))
        assertEquals("ESPN", EveryCineFamilyIndex.family("ESPN (720p)", "Esportes"))
        assertEquals("ESPN", EveryCineFamilyIndex.family("ESPN 2 (1080p)", "Esportes"))
        assertEquals("SporTV", EveryCineFamilyIndex.family("SporTV 3 FHD", "Esportes"))
        assertEquals("Premiere", EveryCineFamilyIndex.family("PREMIERE 4 HD", "Esportes"))
        assertEquals("BandSports", EveryCineFamilyIndex.family("BandSports FHD", "Esportes"))
        assertEquals("Esportes", EveryCineFamilyIndex.category("BandSports HD", "Canais Abertos"))
        assertNotEquals(EveryCineFamilyIndex.family("ESPN 2", "Esportes"),
            EveryCineFamilyIndex.family("SporTV 2", "Esportes"))
    }

    @Test fun moviesAndSeriesKeepEachOriginalLiveEntryInsideAFranchise() {
        assertEquals("Canais de filmes", EveryCineFamilyIndex.category("Telecine Action FHD", "Filmes"))
        assertEquals("Telecine", EveryCineFamilyIndex.family("Telecine Action FHD", "Filmes"))
        assertEquals("Telecine", EveryCineFamilyIndex.family("Telecine Premium 4K", "Filmes"))
        assertEquals("HBO", EveryCineFamilyIndex.family("HBO 2 HD", "Filmes"))
        assertEquals("Megapix", EveryCineFamilyIndex.family("Megapix HD", "Filmes"))

        assertEquals("Séries 24h", EveryCineFamilyIndex.category("24H: CHAVES FHD", "24 HORAS"))
        assertEquals("Chaves", EveryCineFamilyIndex.family("Chaves T01", "Séries 24h"))
        assertEquals("Chapolin", EveryCineFamilyIndex.family("Chapolin Colorado T02", "Séries 24h"))
        assertEquals("Dois Homens e Meio", EveryCineFamilyIndex.family("Dois Homens e Meio 24h", "Séries 24h"))
    }

    @Test fun sameRegionResolutionsGroupButDifferentRegionsDoNot() {
        assertEquals("Globo", EveryCineFamilyIndex.family("Globo RJ FHD", "Abertos"))
        assertEquals("Globo RJ", EveryCineFamilyIndex.variant("Globo RJ (1080p)"))
        assertEquals("Globo RJ", EveryCineFamilyIndex.variant("Globo RJ SD"))
        assertEquals("Globo SP", EveryCineFamilyIndex.variant("Globo SP HD"))
        assertNotEquals(EveryCineFamilyIndex.variant("Globo RJ FHD"),
            EveryCineFamilyIndex.variant("Globo SP HD"))
        assertEquals("Notícias", EveryCineFamilyIndex.category("GloboNews", "Abertos"))
        assertEquals("Notícias", EveryCineFamilyIndex.category("BandNews", "Abertos"))
        assertEquals("Notícias", EveryCineFamilyIndex.category("Record News", "Abertos"))
        assertNotEquals("Globo", EveryCineFamilyIndex.family("GloboNews", "Notícias"))
    }

    @Test fun categoriesComeFromProviderNamesRatherThanInventedChannels() {
        assertEquals("Esportes", EveryCineFamilyIndex.category("", "ESPORTES HD"))
        assertEquals("Abertos", EveryCineFamilyIndex.category("", "Globos Sudeste"))
        assertEquals("Desenhos", EveryCineFamilyIndex.category("", "ANIMES"))
        assertEquals("Desenhos", EveryCineFamilyIndex.category("", "Infantis"))
        assertEquals("Canais de filmes", EveryCineFamilyIndex.category("", "24h Filmes"))
        assertEquals("Séries 24h", EveryCineFamilyIndex.category("", "Series 24h"))
        assertEquals("Outros", EveryCineFamilyIndex.category("Canal aleatório", "Sem categoria"))
    }

    @Test fun cartoonsAndAnimesClassifyCorrectly() {
        assertEquals("Desenhos", EveryCineFamilyIndex.category("Naruto 24h", "Sem categoria"))
        assertEquals("Desenhos", EveryCineFamilyIndex.category("Bob Esponja", "Desenhos"))
        assertEquals("Desenhos", EveryCineFamilyIndex.category("Nickelodeon HD", "Kids"))
        assertEquals("Animes", EveryCineFamilyIndex.family("Naruto T02", "Animes"))
        assertEquals("Desenhos", EveryCineFamilyIndex.family("Pica Pau", "Desenhos"))
    }

    @Test fun openChannelsIncludeBandAndRedeTvAndCultura() {
        assertEquals("Abertos", EveryCineFamilyIndex.category("Band SP HD", "Abertos"))
        assertEquals("Band", EveryCineFamilyIndex.family("Band SP HD", "Abertos"))
        assertEquals("Abertos", EveryCineFamilyIndex.category("RedeTV! FHD", "Abertos"))
        assertEquals("RedeTV", EveryCineFamilyIndex.family("RedeTV! FHD", "Abertos"))
        assertEquals("Abertos", EveryCineFamilyIndex.category("TV Cultura HD", "Abertos"))
        assertEquals("Cultura", EveryCineFamilyIndex.family("TV Cultura HD", "Abertos"))
    }

    @Test fun subcategoriesPreserveNumberedChannelsAndFilterCorrectly() {
        assertEquals("Chapolin", EveryCineFamilyIndex.subcategory("Chapolin 24h 1", "24 HORAS"))
        assertEquals("Chapolin", EveryCineFamilyIndex.subcategory("Chapolin 24h 2", "24 HORAS"))
        assertEquals("Chapolin", EveryCineFamilyIndex.subcategory("Chapolin 24h 3", "24 HORAS"))
        assertEquals("Chapolin", EveryCineFamilyIndex.subcategory("Chapolin 24h 4", "24 HORAS"))

        assertEquals("Chaves", EveryCineFamilyIndex.subcategory("Chaves 24h 1", "24 HORAS"))
        assertEquals("Chaves", EveryCineFamilyIndex.subcategory("Chaves 24h 2", "24 HORAS"))
        assertEquals("Chaves", EveryCineFamilyIndex.subcategory("Chaves 24h 3", "24 HORAS"))

        assertEquals("Bleach", EveryCineFamilyIndex.subcategory("Bleach 24h 1", "24 HORAS"))
        assertEquals("Bleach", EveryCineFamilyIndex.subcategory("Bleach 24h 2", "24 HORAS"))
        assertEquals("Bleach", EveryCineFamilyIndex.subcategory("Bleach 24h 3", "24 HORAS"))
        assertEquals("Bleach", EveryCineFamilyIndex.subcategory("Bleach 24h 4", "24 HORAS"))

        assertEquals("Dragon Ball", EveryCineFamilyIndex.subcategory("Dragon Ball 1", "24 HORAS"))
        assertEquals("Dragon Ball", EveryCineFamilyIndex.subcategory("Dragon Ball GT", "24 HORAS"))
        assertEquals("Dragon Ball", EveryCineFamilyIndex.subcategory("Dragon Ball Super", "24 HORAS"))
        assertEquals("Dragon Ball", EveryCineFamilyIndex.subcategory("Dragon Ball Z", "24 HORAS"))

        assertEquals("HBO", EveryCineFamilyIndex.subcategory("HBO 2 FHD H.265", "CANAIS: HBO"))
        assertEquals("HBO", EveryCineFamilyIndex.subcategory("HBO 2 HD 1", "CANAIS: HBO"))
        assertEquals("HBO", EveryCineFamilyIndex.subcategory("HBO 2 HD 2", "CANAIS: HBO"))
        assertEquals("Telecine", EveryCineFamilyIndex.subcategory("TELECINE ACTION FHD", "CANAIS: TELECINE"))
        assertEquals("Telecine", EveryCineFamilyIndex.subcategory("TELECINE ACTION HD 1", "CANAIS: TELECINE"))
        assertEquals("Telecine", EveryCineFamilyIndex.subcategory("TELECINE ACTION HD 2", "CANAIS: TELECINE"))
    }
}
