package play.ott.nativeapp.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.runner.RunWith
import play.ott.nativeapp.R
import play.ott.nativeapp.core.*
import java.io.File

@RunWith(AndroidJUnit4::class)
class NeonLayoutInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val source = SourceConfig("neon-ui", "STARLINK · Synthetic catalog", SourceKind.M3U, "https://example.invalid/list.m3u")
    private val movie = MediaEntry("neon-movie", source.id, "Horizonte — vídeo de teste", "https://example.invalid/video.mp4", MediaKind.MOVIE, group = "Cinema", description = "Conteúdo sintético para validar a interface.")
    private val series = movie.copy(id = "neon-series", name = "Jornada — série de teste", kind = MediaKind.SERIES)
    private fun click(tag: String) = compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick) { check(it()) }
    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        android.os.SystemClock.sleep(600)
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
        val folder = File(instrumentation.targetContext.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    @Test fun homeAndMovieDetailsUseTheNativeCatalog() {
        var state by mutableStateOf(AppUiState(forYouProfile = play.ott.nativeapp.data.ForYouProfile(onboardingDone = true), sources = listOf(source), selectedSourceId = source.id, entries = listOf(movie, series)))
        compose.setContent { OttNativeApp(state, { action -> when(action) {
            is AppAction.OpenMovie -> state = state.copy(movieEntry = action.entry)
            AppAction.CloseMovie -> state = state.copy(movieEntry = null)
            else -> Unit
        } }, null, {}, {}) }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("featured-content").fetchSemanticsNodes().isNotEmpty() }
        capture("home")
        click("featured-content")
        compose.onNodeWithText(movie.description).assertIsDisplayed()
        capture("movie-details")
    }
    @Test fun seasonsShowOnlyTheirOwnEpisodes() {
        val first = movie.copy(id = "s1e1", name = "Primeiro episódio", kind = MediaKind.EPISODE, season = 1, episode = 1)
        val second = first.copy(id = "s2e1", name = "Outra temporada", season = 2)
        compose.setContent { OttTheme { SeriesDialog(series, listOf(first, second), false, {}) } }
        compose.onNodeWithText(first.name).assertIsDisplayed()
        compose.onNodeWithText(second.name).assertDoesNotExist()
        capture("series-season-1")
        compose.onNodeWithText(compose.activity.getString(R.string.dialog_season, 2)).performSemanticsAction(SemanticsActions.OnClick) { check(it()) }
        compose.onNodeWithText(second.name).assertIsDisplayed()
        compose.onNodeWithText(first.name).assertDoesNotExist()
        capture("series-season-2")
    }
    @Test fun loginKeepsTwoFieldsAndNeonIdentity() {
        compose.setContent { OttTheme { CustomerLoginScreen(busy = false, onSubmit = {}) } }
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(2)
        compose.onNodeWithTag("customer-brand").assertIsDisplayed()
        capture("login")
    }
}
