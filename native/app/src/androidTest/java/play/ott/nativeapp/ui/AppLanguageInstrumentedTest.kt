package play.ott.nativeapp.ui

import android.view.View
import android.content.res.Configuration
import android.os.LocaleList
import androidx.core.app.LocaleManagerCompat
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import play.ott.nativeapp.MainActivity
import play.ott.nativeapp.R
import play.ott.nativeapp.i18n.AppLanguages
import play.ott.nativeapp.playback.PlaybackTestLifecycle

/** Exercise the actual phone/TV Activity and persisted platform locale through the visible picker. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class AppLanguageInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var savedTag = ""

    @Before fun launch() {
        PlaybackTestLifecycle.finishPreviousPlayback()
        instrumentation.startActivitySync(PlaybackTestLifecycle.launchIntent())
        awaitActivity()
        instrumentation.runOnMainSync { savedTag = AppLanguages.currentTag() }
    }

    @After fun restoreLanguage() {
        instrumentation.runOnMainSync { AppLanguages.setLanguage(savedTag) }
        instrumentation.waitForIdleSync()
        PlaybackTestLifecycle.finishPreviousPlayback()
    }

    @Test fun pickerChangesLanguageAndLayoutThenReturnsToAndroidPreferences() {
        choose("en")
        assertLanguage("en", "Sources", View.LAYOUT_DIRECTION_LTR)
        choose("ru")
        assertLanguage("ru", "Источники", View.LAYOUT_DIRECTION_LTR)
        choose("he")
        val hebrew = awaitActivity()
        instrumentation.runOnMainSync {
            assertEquals("he", AppLanguages.currentTag())
            assertEquals(View.LAYOUT_DIRECTION_RTL, hebrew.resources.configuration.layoutDirection)
        }
        // Opening the picker after recreation must scroll to and expose the current language.
        compose.onNodeWithTag("settings-language").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { check(it()) }
        awaitTag("language-he")
        compose.onNodeWithTag("language-he").assertIsDisplayed()
        compose.onNodeWithTag("language-list").performScrollToNode(hasTestTag("language-system"))
        compose.onNodeWithTag("language-system").performSemanticsAction(SemanticsActions.OnClick) { check(it()) }
        val context = instrumentation.targetContext
        val systemLocales = LocaleManagerCompat.getSystemLocales(context)
        val expected = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            setLocales(LocaleList.forLanguageTags(systemLocales.toLanguageTags()))
        })
        val systemSources = expected.getString(R.string.library_sources)
        val systemDirection = expected.resources.configuration.layoutDirection
        compose.waitUntil(10_000) {
            var applied = false
            instrumentation.runOnMainSync {
                val activity = resumedActivity()
                applied = AppLanguages.currentTag().isEmpty() &&
                    activity?.getString(R.string.library_sources) == systemSources &&
                    activity.resources.configuration.layoutDirection == systemDirection
            }
            applied
        }
        assertLanguage("", systemSources, systemDirection)
    }

    private fun choose(tag: String) {
        if (compose.onAllNodesWithTag("settings-language").fetchSemanticsNodes().isEmpty()) {
            awaitTag("open-settings")
            compose.onNodeWithTag("open-settings").performSemanticsAction(SemanticsActions.OnClick) { check(it()) }
        }
        awaitTag("settings-language")
        compose.onNodeWithTag("settings-language").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { check(it()) }
        awaitTag("language-list")
        compose.onNodeWithTag("language-list").performScrollToNode(hasTestTag("language-$tag"))
        compose.onNodeWithTag("language-$tag").performSemanticsAction(SemanticsActions.OnClick) { check(it()) }
        compose.waitUntil(10_000) {
            var ready = false
            instrumentation.runOnMainSync {
                ready = resumedActivity()?.resources?.configuration?.locales?.get(0)?.language.let {
                    (if (it == "iw") "he" else it) == tag
                }
            }
            ready
        }
        awaitTag("settings-language")
    }

    private fun assertLanguage(tag: String, sources: String, direction: Int) {
        val activity = awaitActivity()
        instrumentation.runOnMainSync {
            assertEquals(tag, AppLanguages.currentTag())
            assertEquals(sources, activity.getString(R.string.library_sources))
            assertEquals(direction, activity.resources.configuration.layoutDirection)
        }
    }

    private fun resumedActivity(): MainActivity? = ActivityLifecycleMonitorRegistry.getInstance()
        .getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().firstOrNull()

    private fun awaitActivity(): MainActivity {
        var activity: MainActivity? = null
        compose.waitUntil(10_000) {
            instrumentation.runOnMainSync { activity = resumedActivity() }
            activity != null
        }
        return requireNotNull(activity)
    }

    private fun awaitTag(tag: String) = compose.waitUntil(10_000) {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }
}
