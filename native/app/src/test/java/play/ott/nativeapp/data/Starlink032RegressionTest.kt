package play.ott.nativeapp.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import play.ott.nativeapp.ui.AppUiState

/** Regression coverage for the 0.3.2 Para Voce player safety defaults. */
class Starlink032RegressionTest {
    @Test fun backgroundPlaybackIsOptInForNewInstalls() {
        assertFalse(firstRunPreferences().backgroundPlayback)
        assertTrue(UserPreferences().backgroundPlayback) // legacy serialized backup receipts remain compatible
        assertFalse(AppUiState().backgroundPlayback)
    }

    @Test fun favoritesAndRecommendationsHaveIndependentState() {
        val state = AppUiState(
            favoriteIds = setOf("movie1"),
            forYouProfile = ForYouProfile(onboardingDone = true, interests = setOf("filmes"))
        )
        assertEquals(setOf("movie1"), state.favoriteIds)
        assertTrue(state.forYouProfile.onboardingDone)
    }

    @Test fun skippingPersonalizationNeverRequiresPlaybackHistory() {
        val profile = ForYouProfile(onboardingDone = true)
        assertFalse(profile.allowViewingSignals)
        assertTrue(profile.interests.isEmpty())
    }

    @Test fun newProfileStartsWithoutLegacyHistoryOrFavorites() {
        val preferences = UserPreferences(activeForYouProfileId = "secondary", favorites = setOf("legacy_movie"))
        assertTrue(preferences.favoritesByProfile["secondary"].orEmpty().isEmpty())
        assertTrue(preferences.resumeByProfile["secondary"].orEmpty().isEmpty())
    }
}
