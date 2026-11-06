package play.ott.nativeapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import play.ott.nativeapp.core.MediaEntry
import play.ott.nativeapp.core.MediaKind

class SmartLiveCatalogTest {
    private fun channel(name: String, group: String) =
        MediaEntry(id = name, sourceId = "m81", name = name, kind = MediaKind.LIVE, group = group)
    private val available = setOf("Abertos", "Esportes", "Filmes", "Séries",
        "Desenhos", "Animes", "Notícias", "Infantis", "Religiosos",
        "Música", "Documentários", "Variedades", "Estilo de Vida")

    @Test fun knownChannelsEscapeWrongProviderGroupsWithoutMovingOutOfLive() {
        val cases = listOf(
            channel("Globo RJ FHD", "Variedades") to "Abertos",
            channel("SBT Rio SD", "Variedades") to "Abertos",
            channel("Band RJ HD", "Variedades") to "Abertos",
            channel("Record Rio 4K", "Variedades") to "Abertos",
            channel("SporTV3 4K", "Estilo de Vida") to "Esportes",
            channel("OneFootball 1 FHD", "Estilo de Vida") to "Esportes",
            channel("Band Sports HD", "Variedades") to "Esportes",
            channel("Record News FHD", "Variedades") to "Notícias",
            channel("SBT News HD", "Documentários") to "Notícias",
            channel("HBO POP 4K", "Música") to "Filmes",
            channel("Telecine Action FHD", "Variedades") to "Filmes",
            channel("Cartoonito 4K", "Variedades") to "Desenhos",
            channel("[24H] Dragon Ball Classico", "Variedades") to "Animes",
            channel("[24H] Pokemon [S09]", "Variedades") to "Animes",
            channel("[24H] Animaniacs (1993)", "Variedades") to "Desenhos",
            channel("[24H] Friends [S07]", "Variedades") to "Séries",
            channel("[24H] Chaves [S01]", "Variedades") to "Séries",
            channel("[24H] Chapolin Colorado [S01]", "Variedades") to "Séries",
            channel("[24H] Dois Homens e Meio [S09]", "Variedades") to "Séries",
            channel("Rede Gospel FHD", "Estilo de Vida") to "Religiosos"
        )
        for ((entry, expected) in cases) {
            assertEquals(entry.name, expected, SmartLiveCatalog.category(entry, available))
            assertEquals(MediaKind.LIVE, entry.kind)
        }
    }

    @Test fun networksAreFamiliesNotSingleChannelsAndNewsIsSeparate() {
        assertEquals("Globo", SmartLiveCatalog.network(channel("Globo RJ HD", "Abertos")))
        assertEquals("Globo", SmartLiveCatalog.network(channel("Globo SP 4K", "Abertos")))
        assertEquals("SBT", SmartLiveCatalog.network(channel("SBT Rio SD", "Abertos")))
        assertEquals("Band", SmartLiveCatalog.network(channel("Band RJ FHD", "Abertos")))
        assertEquals("Record", SmartLiveCatalog.network(channel("Record Rio HD", "Abertos")))
        assertEquals(null, SmartLiveCatalog.network(channel("Band News HD", "Notícias")))
        assertEquals(null, SmartLiveCatalog.network(channel("Record News HD", "Notícias")))
        assertEquals(null, SmartLiveCatalog.network(channel("Band Sports HD", "Esportes")))
        assertEquals(null, SmartLiveCatalog.network(channel("Globo News HD", "Notícias")))
        assertEquals(listOf("Globo", "SBT", "Band", "Record"),
            SmartLiveCatalog.networks(listOf(channel("Globo RJ HD", "Abertos"),
                channel("SBT RJ HD", "Abertos"), channel("Band RJ HD", "Abertos"),
                channel("Record Rio HD", "Abertos"))))
    }

    @Test fun originalsStayIntactWhenClassificationIsUncertain() {
        val cow = channel("Canal do Boi", "Variedades")
        assertEquals("Variedades", SmartLiveCatalog.category(cow, available))
        assertEquals("Variedades", cow.group)
        assertEquals("Sem categoria", SmartLiveCatalog.category(channel("Canal estranho", "Sem categoria"), setOf("Sem categoria")))
    }

    @Test fun filmsAndSeriesKeepOriginalSupplierCategories() {
        val entries = listOf(
            MediaEntry(id = "1", sourceId = "m81", name = "Chaves", kind = MediaKind.SERIES, group = "Crunchyroll"),
            MediaEntry(id = "2", sourceId = "m81", name = "HBO", kind = MediaKind.MOVIE, group = "Ação"))
        assertEquals(entries.map { it.group }, entries.map {
            SmartLiveCatalog.category(it, setOf("Abertos", "Filmes"))
        })
        assertTrue(entries.none { it.kind == MediaKind.LIVE })
    }
}
