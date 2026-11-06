package play.ott.nativeapp.data

import org.junit.Assert.*
import org.junit.Test
import play.ott.nativeapp.core.MediaEntry
import play.ott.nativeapp.core.MediaKind

class ForYouEngineTest {
    private fun entry(id: String, name: String, group: String = "", kind: MediaKind = MediaKind.MOVIE) =
        MediaEntry(id = id, sourceId = "m81", name = name, group = group, kind = kind)

    @Test fun onboardingIsOptionalAndEmptyCatalogIsSafe() {
        val profile = ForYouProfile()
        assertTrue(ForYouEngine.recommend(listOf(entry("1", "Gospel")), profile).isEmpty())
        assertTrue(ForYouEngine.recommend(emptyList(), profile.copy(interests = setOf("futebol"))).isEmpty())
    }

    @Test fun interestsMatchRealGroupsWithoutInventingMedia() {
        val football = entry("1", "Jogo de Hoje", "Futebol")
        val cartoon = entry("2", "Desenho A", "Desenhos")
        val result = ForYouEngine.recommend(listOf(cartoon, football), ForYouProfile(interests = setOf("futebol")))
        assertEquals(listOf(football), result)
    }

    @Test fun explicitlyLikedContentCanBeRecommended() {
        val documentary = entry("1", "A Terra", "Documentários")
        val profile = ForYouProfile(likedEntries = setOf(ForYouEngine.key(documentary)))
        assertEquals(listOf(documentary), ForYouEngine.recommend(listOf(documentary), profile))
    }

    @Test fun negativeFeedbackExcludesFromRecommendationsNotCatalog() {
        val sport = entry("1", "Futebol Livre", "Futebol")
        val profile = ForYouProfile(interests = setOf("futebol"), dislikedEntries = setOf(ForYouEngine.key(sport)))
        val catalog = listOf(sport)
        assertTrue(ForYouEngine.recommend(catalog, profile).isEmpty())
        assertEquals(1, catalog.size)
    }

    @Test fun viewingSignalsRequireExplicitConsent() {
        val favorite = entry("1", "Futebol", "Futebol")
        val ordinary = entry("2", "Futebol", "Futebol")
        val profile = ForYouProfile(interests = setOf("futebol"))
        val keys = setOf(ForYouEngine.key(ordinary))
        assertEquals(favorite, ForYouEngine.recommend(listOf(favorite, ordinary), profile, recentlyViewedIds = keys).first())
        assertEquals(ordinary, ForYouEngine.recommend(listOf(favorite, ordinary), profile.copy(allowViewingSignals = true), recentlyViewedIds = keys).first())
    }

    @Test fun accountsAndProfilesRemainSeparate() {
        val football = entry("1", "Futebol", "Futebol")
        val gospel = entry("2", "Gospel", "Gospel")
        val p1 = ForYouProfile(id = "default", interests = setOf("futebol"))
        val p2 = ForYouProfile(id = "secondary", interests = setOf("gospel"))
        assertEquals(football, ForYouEngine.recommend(listOf(football, gospel), p1).first())
        assertEquals(gospel, ForYouEngine.recommend(listOf(football, gospel), p2).first())
        assertTrue(ForYouEngine.recommend(listOf(football), p2).isEmpty())
    }

    @Test fun boundedRecommendationsKeepStableOrder() {
        val items = (0 until 500).map { entry(it.toString(), "Desenho $it", "Desenhos") }
        val picks = ForYouEngine.recommend(items, ForYouProfile(interests = setOf("desenhos")), limit = 20)
        assertEquals(20, picks.size)
        assertEquals(items.take(20), picks)
    }

    @Test fun negativeCategoryPreferencesOverridePositiveOnes() {
        val movie = entry("1", "Filme de ação", "Ação")
        val profile = ForYouProfile(interests = setOf("acao"), dislikedInterests = setOf("acao"))
        assertTrue(ForYouEngine.recommend(listOf(movie), profile).isEmpty())
    }

    @Test fun subtopicsIncreaseRankOnlyForChosenInterest() {
        val brazil = entry("1", "Brasileirão", "Futebol")
        val europe = entry("2", "Premier League", "Futebol")
        val profile = ForYouProfile(interests = setOf("futebol"), subInterests = setOf("futebol:europeu"))
        assertEquals(europe, ForYouEngine.recommend(listOf(brazil, europe), profile).first())
    }

    @Test fun anUnchosenParentCannotRankAnotherTopic() {
        val europe = entry("1", "Premier League", "Futebol")
        val documentary = entry("2", "Bichos", "Documentários")
        val profile = ForYouProfile(interests = setOf("documentarios"), subInterests = setOf("futebol:europeu"))
        assertEquals(listOf(documentary), ForYouEngine.recommend(listOf(europe, documentary), profile))
    }

    @Test fun questionsDependOnSelectedSubjectsNotOnMovieOrSeriesTab() {
        val animeOnly = ForYouEngine.followUps(setOf("animes"))
        assertEquals(listOf("animes"), animeOnly.map { it.first })
        val animeOptions = animeOnly.single().second.map { it.first }.toSet()
        assertTrue("criancas" in animeOptions)
        assertTrue("jovens" in animeOptions)
        assertTrue("adultos" in animeOptions)
        assertTrue("classicos" in animeOptions)
        assertTrue("lancamentos" in animeOptions)

        val cartoonOnly = ForYouEngine.followUps(setOf("desenhos")).single().second.map { it.first }.toSet()
        assertTrue("filmes-animados" in cartoonOnly)
        assertTrue("antigos" in cartoonOnly)
        assertTrue("recentes" in cartoonOnly)
        assertTrue(ForYouEngine.followUps(emptySet()).isEmpty())
    }

    @Test fun oldCartoonsAndAnimatedMoviesCanBeLikedAtTheSameTime() {
        val old = entry("1", "Desenhos Clássicos", "Desenhos", MediaKind.LIVE)
        val film = entry("2", "Filme de Animação", "Animação", MediaKind.MOVIE)
        val profile = ForYouProfile(interests = setOf("desenhos"),
            subInterests = setOf("desenhos:antigos", "desenhos:filmes-animados"))
        val picked = ForYouEngine.recommend(listOf(old, film), profile)
        assertTrue(old in picked)
        assertTrue(film in picked)
    }

    @Test fun historicalDocumentariesAndSurvivalGetDifferentFollowUpQuestions() {
        val questions = ForYouEngine.followUps(setOf("documentarios")).single().second.map { it.first }
        assertTrue("historia" in questions)
        assertTrue("sobrevivencia" in questions)
        assertTrue("investigacao" in questions)
    }

    @Test fun brazilianMoviesAndSeriesAreSeparateOptionalInterests() {
        val movies = entry("1", "Filme Nacional", "Filmes Nacionais", MediaKind.MOVIE)
        val series = entry("2", "Série Nacional", "Séries Brasileiras", MediaKind.SERIES)
        val foreign = entry("3", "Série Internacional", "Séries Netflix", MediaKind.SERIES)
        val movieProfile = ForYouProfile(interests = setOf("filmes brasileiros"))
        val seriesProfile = ForYouProfile(interests = setOf("series brasileiras"))
        assertEquals(listOf(movies), ForYouEngine.recommend(listOf(movies, series, foreign), movieProfile))
        assertEquals(listOf(series), ForYouEngine.recommend(listOf(movies, series, foreign), seriesProfile))
        val selected = ForYouEngine.followUps(setOf("series brasileiras", "filmes brasileiros"))
        assertEquals(2, selected.size)
    }

    @Test fun everyAvailableSubtopicBelongsToItsParent() {
        assertTrue(ForYouEngine.validSubInterests.isNotEmpty())
        assertTrue(ForYouEngine.subtopics.all { (parent, options) ->
            parent in ForYouEngine.topics && options.all { (id, _) -> parent + ":" + id in ForYouEngine.validSubInterests }
        })
    }
}
