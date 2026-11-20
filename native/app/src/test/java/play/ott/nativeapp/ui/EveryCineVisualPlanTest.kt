package play.ott.nativeapp.ui

import kotlin.test.*
import play.ott.nativeapp.core.MediaEntry
import play.ott.nativeapp.core.MediaKind
import play.ott.nativeapp.data.RecentPlayback

/** Visual behavior comes from inspected Every Cine XML, not from its protected app code. */
class EveryCineVisualPlanTest {
    private fun media(id: String, kind: MediaKind, group: String = "") =
        MediaEntry(id = id, sourceId = "m81", name = id, kind = kind, group = group)

    @Test fun bottomBarUsesFourPersistentPagesWithoutMatchFeature() {
        assertEquals(
            listOf(EveryCinePage.HOME, EveryCinePage.LIVE, EveryCinePage.CATALOG, EveryCinePage.PROFILE),
            EveryCinePage.entries.toList(),
        )
    }

    @Test fun homepageUsesFeatureAndHorizontalRailsInsteadOfOneFlatGrid() {
        val film = media("film-1", MediaKind.MOVIE, "Acao")
        val film2 = media("film-2", MediaKind.MOVIE, "Acao")
        val show = media("series-1", MediaKind.SERIES, "Aventura")
        val channel = media("canal-1", MediaKind.LIVE, "Abertos")
        val sections = everyCineSections(listOf(film, film2, show, channel), emptyList(), emptySet())
        assertEquals(listOf("Filmes", "Séries", "TV ao vivo", "Acao", "Aventura"), sections.map { it.title })
        assertEquals(listOf(film, film2), sections.first().entries)
        assertTrue(sections.all { it.entries.isNotEmpty() })
    }

    @Test fun recentRailAppearsAheadOfRegularCatalogAndHasNoDuplicates() {
        val movie = media("filme", MediaKind.MOVIE, "Acao")
        val recent = listOf(RecentPlayback(movie, 2000), RecentPlayback(movie, 1000))
        val sections = everyCineSections(listOf(movie), recent, emptySet())
        assertEquals("Continuar assistindo", sections.first().title)
        assertEquals(listOf(movie), sections.first().entries)
    }

    @Test fun liveAndCatalogKindsNeverLeakIntoEachOther() {
        val movie = media("film", MediaKind.MOVIE, "Acao")
        val series = media("series", MediaKind.SERIES, "Drama")
        val channel = media("channel", MediaKind.LIVE, "Esportes")
        val entries = listOf(movie, series, channel)
        assertEquals(listOf(movie), everyCineVisible(entries, MediaKind.MOVIE, "", ""))
        assertEquals(listOf(series), everyCineVisible(entries, MediaKind.SERIES, "", ""))
        assertEquals(listOf(channel), everyCineVisible(entries, MediaKind.LIVE, "", ""))
        assertEquals(listOf(series), everyCineVisible(entries, MediaKind.SERIES, "", "ser"))
        assertTrue(everyCineVisible(entries, MediaKind.LIVE, "", "xyz").isEmpty())
    }

    @Test fun selectedRemoteCategoryIncludesOnlyItsOriginalXtreamGroups() {
        val hd = media("esporte-hd", MediaKind.LIVE, "ESPORTES HD")
        val fhd = media("esporte-fhd", MediaKind.LIVE, "Esportes FHD")
        val open = media("globo", MediaKind.LIVE, "Abertos")
        val all = listOf(hd, fhd, open)
        val filtered = everyCineVisibleRemote(all, MediaKind.LIVE,
            setOf(" esportes hd ", "ESPORTES FHD"), "")
        assertEquals(listOf(hd, fhd), filtered)
        assertEquals(listOf(open), everyCineVisibleRemote(all, MediaKind.LIVE, setOf("Abertos"), "globo"))
    }

    @Test fun sectionsAreBoundedForInstantRendering() {
        val entries = (1..300).map { media("film-$it", MediaKind.MOVIE, "Colecao") }
        assertTrue(everyCineSections(entries, emptyList(), emptySet()).all { it.entries.size <= 20 })
    }

    @Test fun bannerPagerUsesUniqueRealMoviesAndKeepsOnlyFive() {
        val first = media("filme-1", MediaKind.MOVIE)
        val many = (1..9).map { media("filme-$it", MediaKind.MOVIE) }
        val banner = everyCineBannerEntries(listOf(first) + many, emptyList())
        assertEquals(5, banner.size)
        assertEquals(5, banner.map { it.id }.distinct().size)
        assertEquals("filme-1", banner.first().id)
    }

    @Test fun featuredSelectionReusesRealCatalogOrRecentItem() {
        val movie = media("film", MediaKind.MOVIE)
        val channel = media("channel", MediaKind.LIVE)
        assertEquals(movie, everyCineFeatured(listOf(channel, movie), emptyList()))
        assertEquals(movie, everyCineFeatured(emptyList(), listOf(RecentPlayback(movie, 1200))))
        assertNull(everyCineFeatured(emptyList(), emptyList()))
    }
}
