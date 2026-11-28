package play.ott.nativeapp.ui

import kotlin.test.*
import play.ott.nativeapp.core.MediaEntry
import play.ott.nativeapp.core.MediaKind
import play.ott.nativeapp.core.XtreamGroup
import play.ott.nativeapp.data.RecentPlayback

/** Visual behavior comes from inspected Every Cine XML and user navigation requirements. */
class EveryCineVisualPlanTest {
    private fun media(id: String, kind: MediaKind, group: String = "", logo: String = "") =
        MediaEntry(id = id, sourceId = "m81", name = id, kind = kind, group = group, logo = logo)

    @Test fun bottomBarSplitsCatalogIntoMoviesAndSeriesPages() {
        assertEquals(
            listOf(
                EveryCinePage.HOME,
                EveryCinePage.LIVE,
                EveryCinePage.MOVIES,
                EveryCinePage.SERIES,
                EveryCinePage.PROFILE,
            ),
            EveryCinePage.entries.toList(),
        )
        assertEquals(EveryCinePage.MOVIES, parseEveryCinePage("CATALOG"))
        assertEquals(EveryCinePage.SERIES, parseEveryCinePage("SERIES"))
        assertEquals(EveryCinePage.HOME, parseEveryCinePage("UNKNOWN"))
        assertEquals(EveryCinePage.HOME, parseEveryCinePage(null))
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

    @Test fun moviesAndSeriesTabsBuildHomeStyleRailsStrictlyScopedToTheirKind() {
        val m1 = media("m1", MediaKind.MOVIE, "FILMES | Ação", "https://image.tmdb.org/t/p/w500/m1.jpg")
        val m2 = media("m2", MediaKind.MOVIE, "FILMES | Lançamentos", "https://image.tmdb.org/t/p/w500/m2.jpg")
        val s1 = media("s1", MediaKind.SERIES, "SÉRIES | Drama", "https://image.tmdb.org/t/p/w500/s1.jpg")
        val ep1 = media("ep1", MediaKind.EPISODE, "SÉRIES | Drama", "https://image.tmdb.org/t/p/w500/ep1.jpg")
        val live = media("c1", MediaKind.LIVE, "Esportes")
        val all = listOf(m1, m2, s1, live)
        val recent = listOf(RecentPlayback(m1, 5000L), RecentPlayback(ep1, 4000L))

        val movieSections = everyCineKindSections(
            entries = all,
            recent = recent,
            favoriteIds = setOf("m2", "s1"),
            kind = MediaKind.MOVIE,
            preferredCategoryOrder = listOf("FILMES | Lançamentos", "FILMES | Ação"),
        )
        assertEquals(
            listOf("Continuar assistindo", "Minha lista", "FILMES | Lançamentos", "FILMES | Ação"),
            movieSections.map { it.title },
        )
        assertTrue(movieSections.flatMap { it.entries }.all { it.kind == MediaKind.MOVIE })

        val seriesSections = everyCineKindSections(
            entries = all,
            recent = recent,
            favoriteIds = setOf("m2", "s1"),
            kind = MediaKind.SERIES,
            preferredCategoryOrder = listOf("SÉRIES | Drama"),
        )
        assertEquals(
            listOf("Continuar assistindo", "Minha lista", "SÉRIES | Drama"),
            seriesSections.map { it.title },
        )
        assertEquals(listOf(ep1), seriesSections.first().entries)
        assertEquals(listOf(s1), seriesSections[1].entries)
        assertEquals(listOf(s1), seriesSections[2].entries)
    }

    @Test fun showcaseGroupLoaderFetchesOnlyMissingInitialCategoriesAndExpandsIncrementally() {
        val groups = listOf(
            XtreamGroup("1", "FILMES | Lançamentos", MediaKind.MOVIE),
            XtreamGroup("2", "FILMES | Ação", MediaKind.MOVIE),
            XtreamGroup("3", "FILMES | Comédia", MediaKind.MOVIE),
            XtreamGroup("4", "FILMES | Drama", MediaKind.MOVIE),
            XtreamGroup("5", "FILMES | Terror", MediaKind.MOVIE),
            XtreamGroup("s1", "SÉRIES | Netflix", MediaKind.SERIES),
        )
        assertEquals(
            listOf("1", "2", "3", "4"),
            everyCineShowcaseGroupIdsToLoad(groups, emptyList(), MediaKind.MOVIE, 4),
        )
        val loadedFirstTwo = listOf(
            media("m1", MediaKind.MOVIE, "FILMES | Lançamentos"),
            media("m2", MediaKind.MOVIE, "FILMES | Ação"),
        )
        assertEquals(
            listOf("3", "4"),
            everyCineShowcaseGroupIdsToLoad(groups, loadedFirstTwo, MediaKind.MOVIE, 4),
        )
        assertEquals(
            listOf("3", "4", "5"),
            everyCineShowcaseGroupIdsToLoad(groups, loadedFirstTwo, MediaKind.MOVIE, 8),
        )
        assertEquals(
            listOf("s1"),
            everyCineShowcaseGroupIdsToLoad(groups, loadedFirstTwo, MediaKind.SERIES, 4),
        )
        assertTrue(everyCineShowcaseGroupIdsToLoad(groups, loadedFirstTwo, MediaKind.MOVIE, 2).isEmpty())
        assertTrue(everyCineShowcaseGroupIdsToLoad(groups, loadedFirstTwo, MediaKind.MOVIE, 0).isEmpty())
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
        assertTrue(everyCineKindSections(entries, emptyList(), emptySet(), MediaKind.MOVIE).all { it.entries.size <= 20 })
    }

    @Test fun bannerPagerUsesUniqueRealMoviesAndKeepsOnlyFive() {
        val first = media("filme-1", MediaKind.MOVIE)
        val many = (1..9).map { media("filme-$it", MediaKind.MOVIE) }
        val banner = everyCineBannerEntries(listOf(first) + many, emptyList())
        assertEquals(5, banner.size)
        assertEquals(5, banner.map { it.id }.distinct().size)
        assertEquals("filme-1", banner.first().id)
    }

    @Test fun bannerPagerPrioritizesArtworkAndRespectsKind() {
        val noCover = media("m-no-cover", MediaKind.MOVIE, logo = "")
        val withCover = media("m-cover", MediaKind.MOVIE, logo = "http://image.tmdb.org/t/p/w500/a.jpg")
        val seriesWithCover = media("s-cover", MediaKind.SERIES, logo = "https://image.tmdb.org/t/p/w500/b.jpg")
        val homeBanner = everyCineBannerEntries(listOf(noCover, withCover, seriesWithCover), emptyList())
        assertEquals(listOf("m-cover", "s-cover", "m-no-cover"), homeBanner.map { it.id })

        val movieBanner = everyCineBannerEntriesForKind(listOf(noCover, seriesWithCover, withCover), emptyList(), MediaKind.MOVIE)
        assertEquals(listOf("m-cover", "m-no-cover"), movieBanner.map { it.id })

        val seriesBanner = everyCineBannerEntriesForKind(listOf(noCover, seriesWithCover, withCover), emptyList(), MediaKind.SERIES)
        assertEquals(listOf("s-cover"), seriesBanner.map { it.id })
    }

    @Test fun featuredSelectionReusesRealCatalogOrRecentItem() {
        val movie = media("film", MediaKind.MOVIE)
        val channel = media("channel", MediaKind.LIVE)
        assertEquals(movie, everyCineFeatured(listOf(channel, movie), emptyList()))
        assertEquals(movie, everyCineFeatured(emptyList(), listOf(RecentPlayback(movie, 1200))))
        assertNull(everyCineFeatured(emptyList(), emptyList()))
    }

    @Test fun homeSectionsNeverEmitDuplicateTitlesWhenCategoryMatchesBuiltInName() {
        val entries = listOf(
            media("m1", MediaKind.MOVIE, "Filmes"),
            media("m2", MediaKind.MOVIE, "FILMES | Lançamentos"),
            media("s1", MediaKind.SERIES, "Séries"),
            media("s2", MediaKind.SERIES, "SÉRIES | Drama"),
            media("l1", MediaKind.LIVE, "TV ao vivo"),
        )
        val sections = everyCineSections(entries, emptyList(), emptySet())
        val titles = sections.map { it.title }
        assertEquals(titles.map { it.lowercase() }.distinct().size, titles.size, "Duplicate rail titles crash LazyColumn: $titles")
        assertTrue("Filmes" in titles)
        assertTrue("Séries" in titles)
        assertTrue("FILMES | Lançamentos" in titles)
        assertTrue("SÉRIES | Drama" in titles)
    }

    @Test fun kindSectionsNeverCollideWithReservedSyntheticTitles() {
        val m1 = media("m1", MediaKind.MOVIE, "Continuar assistindo")
        val m2 = media("m2", MediaKind.MOVIE, "Minha lista")
        val m3 = media("m3", MediaKind.MOVIE, "FILMES | Ação")
        val sections = everyCineKindSections(
            entries = listOf(m1, m2, m3),
            recent = listOf(RecentPlayback(m1, 3000L)),
            favoriteIds = setOf("m2"),
            kind = MediaKind.MOVIE,
            preferredCategoryOrder = listOf("Continuar assistindo", "Minha lista", "FILMES | Ação"),
        )
        val titles = sections.map { it.title }
        assertEquals(titles.map { it.lowercase() }.distinct().size, titles.size, "Duplicate kind rail titles: $titles")
        assertEquals(listOf("Continuar assistindo", "Minha lista", "FILMES | Ação"), titles)
    }

    @Test fun homeCategoryShortcutsBalancesLiveMoviesAndSeries() {
        val groups = (1..15).map { XtreamGroup("l$it", "Live $it", MediaKind.LIVE) } +
            (1..20).map { XtreamGroup("m$it", "Movie $it", MediaKind.MOVIE) } +
            (1..12).map { XtreamGroup("s$it", "Series $it", MediaKind.SERIES) }
        val shortcuts = everyCineHomeCategoryShortcuts(groups, emptyList())
        assertEquals(8, shortcuts.count { it.first == MediaKind.LIVE })
        assertEquals(8, shortcuts.count { it.first == MediaKind.MOVIE })
        assertEquals(8, shortcuts.count { it.first == MediaKind.SERIES })
    }

    @Test fun fullscreenExitStopsLiveOnlyWhenNotOnLiveTabAndNotCasting() {
        assertFalse(shouldStopOnFullscreenExit(isLive = true, isOnLivePage = true, isCastingToTv = false))
        assertTrue(shouldStopOnFullscreenExit(isLive = true, isOnLivePage = false, isCastingToTv = false))
        assertTrue(shouldStopOnFullscreenExit(isLive = false, isOnLivePage = true, isCastingToTv = false))
        assertTrue(shouldStopOnFullscreenExit(isLive = false, isOnLivePage = false, isCastingToTv = false))
        assertFalse(shouldStopOnFullscreenExit(isLive = true, isOnLivePage = false, isCastingToTv = true))
        assertFalse(shouldStopOnFullscreenExit(isLive = false, isOnLivePage = false, isCastingToTv = true))
    }

    @Test fun visitedGroupsEvictionProtectsActiveShowcaseBatchAgainstGiantCategory() {
        val visited = LinkedHashMap<String, List<MediaEntry>>()
        visited["old:live:1"] = List(500) { media("l-$it", MediaKind.LIVE, "Esportes") }
        visited["m81:MOVIE:1"] = List(1_235) { media("launch-$it", MediaKind.MOVIE, "FILMES | Lançamentos") }
        visited["m81:MOVIE:2"] = List(26_692) { media("all-$it", MediaKind.MOVIE, "Filmes") }
        visited["m81:MOVIE:3"] = List(38) { media("anime-$it", MediaKind.MOVIE, "Anime") }
        visited["m81:MOVIE:4"] = List(14) { media("cinema-$it", MediaKind.MOVIE, "Cinema") }
        val activeBatch = setOf("m81:MOVIE:1", "m81:MOVIE:2", "m81:MOVIE:3", "m81:MOVIE:4")

        play.ott.nativeapp.PlayerViewModel.trimVisitedGroups(
            visited = visited,
            protectedKeys = activeBatch,
            maxGroups = 40,
            maxEntries = 20_000,
        )

        assertFalse("old:live:1" in visited, "Older non-protected group should be evicted first")
        assertEquals(activeBatch, visited.keys, "Active showcase batch must never self-evict mid-load")
    }
}
