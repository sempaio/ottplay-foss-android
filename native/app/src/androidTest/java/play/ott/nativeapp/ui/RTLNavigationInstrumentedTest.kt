package play.ott.nativeapp.ui

import android.content.res.Configuration
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Locale
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import play.ott.nativeapp.core.MediaEntry
import play.ott.nativeapp.core.MediaKind
import play.ott.nativeapp.core.SourceConfig
import play.ott.nativeapp.core.SourceKind

/** Hardware D-pad navigation in a Hebrew TV layout, without touching persisted app settings. */
@RunWith(AndroidJUnit4::class)
class RTLNavigationInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun televisionEntersLeftwardCatalogAfterThePreviousGridWasScrolled() {
        val television = Configuration(compose.activity.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_TYPE_MASK.inv()) or Configuration.UI_MODE_TYPE_TELEVISION
            setLocale(Locale.forLanguageTag("he"))
        }
        val hebrewContext = compose.activity.createConfigurationContext(television)
        val source = SourceConfig("rtl-ui-test", "מקור בדיקה", SourceKind.M3U, "https://example.com/list.m3u")
        val entry = MediaEntry("rtl-item", source.id, "סרטון בדיקה", "https://example.com/video.mp4", kind = MediaKind.MOVIE)
        val entries = (0 until 80).flatMap { index -> listOf(
            entry.copy(id = "rtl-live-$index", kind = MediaKind.LIVE),
            entry.copy(id = "rtl-movie-$index", kind = MediaKind.MOVIE),
        ) }
        compose.setContent {
            CompositionLocalProvider(
                LocalContext provides hebrewContext,
                LocalConfiguration provides television,
                LocalLayoutDirection provides LayoutDirection.Rtl,
            ) {
                OttNativeApp(AppUiState(sources = listOf(source), selectedSourceId = source.id, entries = entries),
                    {}, null, {}, {})
            }
        }
        awaitFocused("library-tab-LIVE")
        compose.onNodeWithTag("library-grid").performScrollToIndex(60)
        compose.onNodeWithTag("library-tab-LIVE").assertIsFocused()
        awaitActivityWindowReady(compose.activity, hideIme = true)

        key(KeyEvent.KEYCODE_DPAD_DOWN)
        awaitFocused("library-tab-MOVIES")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithTag("library-tab-MOVIES").assertIsFocused()

        // In RTL the rail is at the right edge. Right must not teleport focus
        // leftward into the grid, as the previous LTR-only shortcut did.
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.onNodeWithTag("library-tab-MOVIES").assertIsFocused()
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        awaitFocused("catalog-item-rtl-movie-0")
        compose.onNodeWithTag("catalog-item-rtl-movie-0").assertIsDisplayed().assertIsFocused()
        val rail = compose.onNodeWithTag("library-tab-MOVIES").fetchSemanticsNode().boundsInRoot
        val card = compose.onNodeWithTag("catalog-item-rtl-movie-0").fetchSemanticsNode().boundsInRoot
        assertTrue("The focused RTL catalogue card must be physically left of the rail", card.center.x < rail.center.x)
    }

    private fun key(code: Int) {
        instrumentation.sendKeyDownUpSync(code)
        compose.waitForIdle()
    }

    private fun awaitFocused(tag: String) {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes()
                .any { it.config.getOrElse(SemanticsProperties.Focused) { false } }
        }
        compose.onNodeWithTag(tag).assertIsFocused()
    }
}
