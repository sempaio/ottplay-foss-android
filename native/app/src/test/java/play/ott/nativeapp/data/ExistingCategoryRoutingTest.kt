package play.ott.nativeapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import play.ott.nativeapp.core.MediaEntry
import play.ott.nativeapp.core.MediaKind

/** The user's M81 categories: the Android presentation MUST NOT create others. */
class ExistingCategoryRoutingTest {
    private val actualM81Groups = setOf(
        "Sem categoria", "CANAIS: TELECINE ✨", "CANAIS: HBO ✨",
        "Canais | Prime Video", "Canais | Paramount +", "Canais | [24H] Animes",
        "Canais | [24H] Series", "Canais | [24H] Desenhos",
        "Canais | Infantis", "Anime", "Nickelodeon", "Retrô",
        "Música", "Esportes", "Notícias", "Filmes", "Séries",
        "Novelas", "Canais |  Abertos", "Canais |  Religiosos",
        "Canais |  Variedades", "Estilo de Vida", "Canais | Documentarios",
        "Canais | Globos Norte", "Canais | Legendados"
    )
    private fun live(name: String, group: String = "Sem categoria") = MediaEntry(
        id = name, sourceId = "m81", name = name, kind = MediaKind.LIVE,
        group = group, url = "https://example.invalid/original.m3u8"
    )

    @Test fun telecineHboAmazonAndParamountUseOnlyExistingProviderGroups() {
        val cases = mapOf(
            "Telecine Action HD" to "CANAIS: TELECINE ✨",
            "Telecine Cult HD" to "CANAIS: TELECINE ✨",
            "HBO Family HD" to "CANAIS: HBO ✨",
            "HBO 2 FHD" to "CANAIS: HBO ✨",
            "Prime Video 01" to "Canais | Prime Video",
            "Amazon Prime Video 02" to "Canais | Prime Video",
            "Paramount+ 4" to "Canais | Paramount +",
            "Megapix (720p)" to "Filmes"
        )
        cases.forEach { (name, expected) ->
            assertEquals(name, expected, CatalogDisplayGroup.group(live(name), actualM81Groups))
        }
    }

    @Test fun forceClearlyMislabeledAnimationWithoutBreakingStreamIdentity() {
        val cases = listOf(
            live("Popeye", "Música") to "Canais | Infantis",
            live("Popeye", "Retrô") to "Canais | Infantis",
            live("[24H] O Marinheiro Popeye [S01]", "Música") to "Canais | [24H] Desenhos",
            live("[24H] One Piece [S01]", "Canais | [24H] Series") to "Canais | [24H] Animes",
            live("[24H] Dragon Ball Z - Saga do Freeza", "Canais | [24H] Desenhos") to "Canais | [24H] Animes",
            live("[24H] Pokemon [S01]", "Canais | [24H] Desenhos") to "Canais | [24H] Animes",
            live("[24H] Beyblade [S03]", "Canais | [24H] Desenhos") to "Canais | [24H] Animes",
            live("Naruto", "Música") to "Anime",
            live("Cartoon Network HD") to "Canais | Infantis"
        )
        for ((entry, expected) in cases) {
            assertEquals(entry.name, expected, CatalogDisplayGroup.group(entry, actualM81Groups))
            assertEquals("https://example.invalid/original.m3u8", entry.url)
        }
    }

    @Test fun existingClearSpecialistProviderGroupAndGloboRegionalRemainIntact() {
        assertEquals("Canais | Legendados", CatalogDisplayGroup.group(
            live("HBO LEG FHD", "Canais | Legendados"), actualM81Groups))
        assertEquals("Canais | Globos Norte", CatalogDisplayGroup.group(
            live("Globo Rede Amazonica Manaus SD", "Canais | Globos Norte"), actualM81Groups))
        assertEquals("Esportes", CatalogDisplayGroup.group(
            live("Sportv 1 HD", "Esportes"), actualM81Groups))
        assertEquals("CANAIS: TELECINE ✨", CatalogDisplayGroup.group(
            live("Telecine Fun HD", "Canais |  Variedades"), actualM81Groups))
    }

    @Test fun unknownChannelsUseAlreadyExistingGeneralGroupAndNeverDisappear() {
        val entries = listOf(
            live("HBO Family HD"), live("Telecine Action HD"),
            live("Canal misterioso HD"), live("Band Sports (720p)")
        )
        val grouped = entries.map { CatalogDisplayGroup.group(it, actualM81Groups) }
        assertEquals("Canais |  Variedades", grouped[2])
        assertEquals("Esportes", grouped[3])
        assertTrue(grouped.all { it in actualM81Groups })
        assertEquals("Sem categoria", entries[0].group)
        assertEquals(4, entries.size)
    }

    @Test fun neverInventAbsentCategoryOnOtherProviders() {
        val available = setOf("Sem categoria", "Variedades", "Esportes")
        val channel = live("HBO Family HD")
        assertEquals("Variedades", CatalogDisplayGroup.group(channel, available))
        assertTrue(CatalogDisplayGroup.group(channel, available) in available)
    }
}
