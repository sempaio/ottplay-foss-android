package play.ott.nativeapp.ui

import android.content.res.Configuration
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import play.ott.nativeapp.core.MediaEntry
import play.ott.nativeapp.core.MediaKind
import play.ott.nativeapp.core.CoreMessage
import play.ott.nativeapp.core.CoreMessageKey
import play.ott.nativeapp.R
import play.ott.nativeapp.core.SourceConfig
import play.ott.nativeapp.core.SourceKind

/** UI behavior is exercised without changing the installed application's real source vault. */
@RunWith(AndroidJUnit4::class)
class NativeUiInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun text(id: Int) = compose.activity.getString(id)
    private val source = SourceConfig("ui-test", "Демоисточник", SourceKind.M3U, "https://example.com/list.m3u")
    private val entry = MediaEntry("ui-test:movie", source.id, "Тестовый ролик", "https://example.com/video.mp4", kind = MediaKind.MOVIE)

    @Test fun onboardingDemoSelectionPlaybackBackAndSourceEditor() {
        var snapshot by mutableStateOf(AppUiState())
        val actions = mutableListOf<AppAction>()
        compose.setContent {
            OttNativeApp(
                state = snapshot,
                onAction = { action ->
                    actions += action
                    when (action) {
                        AppAction.AddDemo -> snapshot = snapshot.copy(sources = listOf(source), selectedSourceId = source.id, entries = listOf(entry))
                        is AppAction.Play -> snapshot = snapshot.copy(playingEntry = action.entry)
                        is AppAction.SaveSource -> snapshot = snapshot.copy(sources = snapshot.sources + action.source)
                        AppAction.StopPlayback -> snapshot = snapshot.copy(playingEntry = null)
                        else -> Unit
                    }
                },
                controller = null, onPictureInPicture = {}, onFullscreenChanged = {},
            )
        }
        compose.onNodeWithText(text(R.string.library_try_demo)).performScrollTo().activateForDevice()
        compose.runOnIdle {
            assertTrue("Demo activation must dispatch its action", actions.any { it == AppAction.AddDemo })
            assertEquals(listOf(source), snapshot.sources)
            assertEquals(source.id, snapshot.selectedSourceId)
            assertEquals(listOf(entry), snapshot.entries)
        }
        // A movie-only source must select the film tab; an empty live section must not hide it.
        compose.onNodeWithTag("catalog-item-${entry.id}").assertIsDisplayed().activateForDevice()
        compose.onNodeWithTag("player-container").assertIsDisplayed()
        // A previous editor's IME can outlive Compose idleness and consume Back.
        // Deliver one hardware key only after the activity owns a keyboard-free window.
        awaitActivityWindowReady(compose.activity, hideIme = true)
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("player-container").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithTag("player-container").assertDoesNotExist()
        compose.onNodeWithTag("now-playing-bar").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.library_sources)).activateForDevice()
        compose.onNodeWithText(text(R.string.library_add_by_url)).performScrollTo().activateForDevice()
        compose.onNodeWithText("Outras fontes / opções avançadas").performClick()
        compose.onNodeWithText(text(R.string.dialog_source_kind_m3u)).performClick()
        compose.onNodeWithText(text(R.string.dialog_source_name)).performTextReplacement("Новая библиотека")
        compose.onNodeWithText(text(R.string.dialog_source_playlist_url)).performTextReplacement("https://example.com/new.m3u")
        compose.onNodeWithText(text(R.string.dialog_source_archive_hours)).performScrollTo().performTextInput("48")
        compose.onNodeWithText(text(R.string.dialog_add), substring = false).activateForDevice()
        compose.runOnIdle {
            assertTrue(actions.any { it == AppAction.AddDemo })
            assertEquals(entry, (actions.first { it is AppAction.Play } as AppAction.Play).entry)
            assertTrue(actions.any { it is AppAction.SaveSource && it.source.name == "Новая библиотека" && it.source.url == "https://example.com/new.m3u" && it.source.catchupDaysFallback == 2.0 })
        }
    }

    @Test fun televisionRailSupportsDirectionalFocusAndSelection() {
        val television = Configuration(InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_TYPE_MASK.inv()) or Configuration.UI_MODE_TYPE_TELEVISION
        }
        compose.setContent {
            CompositionLocalProvider(LocalConfiguration provides television) {
                OttNativeApp(
                    state = AppUiState(sources = listOf(source), selectedSourceId = source.id, entries = listOf(entry)),
                    onAction = {}, controller = null, onPictureInPicture = {}, onFullscreenChanged = {},
                )
            }
        }
        compose.onNodeWithText(text(R.string.library_tab_movies)).assertIsFocused()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionUp) }
        compose.onNodeWithText(text(R.string.library_tab_live)).assertIsFocused()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText(text(R.string.library_tab_movies)).assertIsFocused()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.onNodeWithTag("catalog-item-${entry.id}").assertIsDisplayed()
    }

    @Test fun localizedGroupChangeRestoresCatalogAndPreservesProviderGroupFilter() {
        val generated = entry.copy(group = "Local test")
        val provider = entry.copy(id = "provider-movie", name = "Provider movie", group = "Provider group")
        var snapshot by mutableStateOf(AppUiState(
            sources = listOf(source), selectedSourceId = source.id, entries = listOf(generated, provider),
        ))
        compose.setContent {
            OttNativeApp(snapshot, {}, null, {}, {})
        }
        compose.onNodeWithTag("library-group-Local test").performSemanticsAction(SemanticsActions.OnClick) { check(it()) }
        compose.onNodeWithTag("catalog-item-${generated.id}").assertIsDisplayed()
        compose.onNodeWithTag("catalog-item-${provider.id}").assertDoesNotExist()

        // The retained ViewModel republishes translated presentation labels after locale recreation.
        compose.runOnIdle {
            snapshot = snapshot.copy(entries = listOf(generated.copy(group = "Локальный тест"), provider))
        }
        compose.onNodeWithTag("catalog-item-${generated.id}").assertIsDisplayed()
        compose.onNodeWithTag("catalog-item-${provider.id}").assertIsDisplayed()

        compose.onNodeWithTag("library-group-Provider group").performSemanticsAction(SemanticsActions.OnClick) { check(it()) }
        compose.runOnIdle { snapshot = snapshot.copy(entries = listOf(generated, provider)) }
        compose.onNodeWithTag("catalog-item-${provider.id}").assertIsDisplayed()
        compose.onNodeWithTag("catalog-item-${generated.id}").assertDoesNotExist()
    }

    @Test fun sourceEditorRelocalizesUntouchedGeneratedNameButPreservesExplicitRename() {
        val generatedName = CoreMessage(CoreMessageKey.DEMO_SOURCE)
        var original by mutableStateOf(source.copy(name = "Тест без интернета", nameMessage = generatedName))
        var editorOpen by mutableStateOf(true)
        val saved = mutableListOf<SourceConfig>()
        compose.setContent {
            if (editorOpen) SourceEditor(original, { editorOpen = false }) {
                saved += it
                editorOpen = false
            }
        }

        // A system language change republishes the same source with a new generated label.
        compose.runOnIdle { original = original.copy(name = "Offline demo") }
        compose.onNodeWithText("Offline demo").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.dialog_save)).performSemanticsAction(SemanticsActions.OnClick) { check(it()) }
        compose.runOnIdle {
            assertEquals("Offline demo", saved.single().name)
            assertEquals(generatedName, saved.single().nameMessage)
            assertTrue(!saved.single().nameIsUserDefined)
        }
        compose.onNodeWithText(text(R.string.dialog_source_edit_title)).assertDoesNotExist()
        awaitActivityWindowReady(compose.activity)

        // Even a rename equal to a legacy translation is the user's text, not a generated label.
        // Reopen the saved source as the app does; Save disposes the previous editor.
        compose.runOnIdle {
            original = saved.single()
            editorOpen = true
        }
        compose.onNodeWithText(text(R.string.dialog_source_name)).performTextReplacement("Тест без интернета")
        compose.runOnIdle { original = original.copy(name = "Démonstration hors ligne") }
        compose.onNodeWithText("Тест без интернета").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.dialog_save)).performSemanticsAction(SemanticsActions.OnClick) { check(it()) }
        compose.runOnIdle {
            assertEquals(2, saved.size)
            assertEquals("Тест без интернета", saved.last().name)
            assertEquals(null, saved.last().nameMessage)
            assertTrue(saved.last().nameIsUserDefined)
        }
        compose.onNodeWithText(text(R.string.dialog_source_edit_title)).assertDoesNotExist()
        awaitActivityWindowReady(compose.activity)
    }

    @Test fun televisionEntersANewTabAfterThePreviousGridWasScrolled() {
        val television = Configuration(InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_TYPE_MASK.inv()) or Configuration.UI_MODE_TYPE_TELEVISION
        }
        val entries = (0 until 80).flatMap { index -> listOf(
            entry.copy(id = "live-$index", kind = MediaKind.LIVE),
            entry.copy(id = "movie-$index", kind = MediaKind.MOVIE),
        ) }
        compose.setContent {
            CompositionLocalProvider(LocalConfiguration provides television) {
                OttNativeApp(AppUiState(sources = listOf(source), selectedSourceId = source.id, entries = entries),
                    {}, null, {}, {})
            }
        }
        compose.onNodeWithTag("library-grid").performScrollToIndex(60)
        compose.onNodeWithTag("library-tab-LIVE").assertIsFocused()
        // Deliver hardware events so a phone running the TV layout also leaves touch input mode.
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        awaitActivityWindowReady(compose.activity, hideIme = true)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
        awaitFocused("library-tab-MOVIES")
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitForIdle()
        compose.onNodeWithTag("library-tab-MOVIES").assertIsFocused()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
        awaitFocused("catalog-item-movie-0")
        compose.onNodeWithTag("catalog-item-movie-0").assertIsDisplayed().assertIsFocused()
    }

    private fun awaitFocused(tag: String) {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes()
                .any { it.config.getOrElse(SemanticsProperties.Focused) { false } }
        }
        compose.onNodeWithTag(tag).assertIsFocused()
    }

    private fun SemanticsNodeInteraction.activateForDevice() {
        assertIsDisplayed()
        val configuration = InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration
        if (configuration.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION) {
            // TvButton handles D-pad enter, not the touch events injected by performClick().
            performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            assertIsFocused()
            performKeyInput { pressKey(Key.DirectionCenter) }
        } else {
            performClick()
        }
    }
}
