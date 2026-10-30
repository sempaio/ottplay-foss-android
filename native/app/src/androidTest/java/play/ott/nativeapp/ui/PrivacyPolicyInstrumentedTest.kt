package play.ott.nativeapp.ui

import android.content.res.Configuration
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import play.ott.nativeapp.R
import play.ott.nativeapp.core.SourceConfig
import play.ott.nativeapp.core.SourceKind

/** Policy reading must work before provider setup and with a TV remote, without network work. */
@RunWith(AndroidJUnit4::class)
class PrivacyPolicyInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun text(id: Int) = compose.activity.getString(id)

    @Test fun welcomePolicyIsAvailableBeforeSetupAndBackReturnsWithoutActions() {
        val actions = mutableListOf<AppAction>()
        compose.setContent { OttNativeApp(AppUiState(), { actions += it }, null, {}, {}) }
        compose.onNodeWithTag("welcome-privacy").performScrollTo().activate()
        compose.onNodeWithTag("privacy-policy").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.dialog_privacy_publisher_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.dialog_privacy_delete_title)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.dialog_privacy_android_title)).performScrollTo().assertIsDisplayed()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
        compose.onNodeWithTag("privacy-policy").assertDoesNotExist()
        compose.onNodeWithTag("welcome-privacy").assertIsDisplayed()
        compose.runOnIdle { assertTrue("Reading the policy must not create a source or start playback", actions.isEmpty()) }
    }

    @Test fun televisionCanReadWithDpadAndReturnToSettings() {
        val television = configurationFor(Configuration.UI_MODE_TYPE_TELEVISION)
        val source = SourceConfig("privacy-test", "Test", SourceKind.M3U, "https://example.com/list.m3u")
        val actions = mutableListOf<AppAction>()
        compose.setContent {
            CompositionLocalProvider(LocalConfiguration provides television) {
                OttNativeApp(AppUiState(sources = listOf(source), selectedSourceId = source.id), { actions += it }, null, {}, {})
            }
        }
        compose.onNodeWithText(text(R.string.library_settings)).activate()
        assertEquals(0, compose.onAllNodesWithText(text(R.string.library_background_playback)).fetchSemanticsNodes().size)
        compose.onNodeWithTag("settings-privacy").performScrollTo().activate()
        val reader = compose.onNodeWithTag("privacy-policy-text")
        reader.assertIsFocused()
        reader.performKeyInput { pressKey(Key.DirectionDown) }
        compose.waitForIdle()
        val offset = reader.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertTrue("D-pad Down must reveal policy text beyond the first screen", offset > 0f)
        reader.performKeyInput { pressKey(Key.DirectionUp) }
        compose.waitForIdle()
        assertEquals(0f, reader.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value(), 1f)
        reader.performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithTag("privacy-policy-close").assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.onNodeWithTag("privacy-policy").assertDoesNotExist()
        compose.onNodeWithTag("settings-privacy").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertTrue("Opening and reading privacy must not alter saved settings", actions.isEmpty()) }
    }

    @Test fun phoneKeepsItsBackgroundPlaybackSetting() {
        val phone = configurationFor(Configuration.UI_MODE_TYPE_NORMAL)
        compose.setContent {
            CompositionLocalProvider(LocalConfiguration provides phone) {
                OttNativeApp(AppUiState(), {}, null, {}, {})
            }
        }
        val settings = compose.onAllNodesWithText(text(R.string.dialog_more)).fetchSemanticsNodes()
        compose.onNodeWithText(if (settings.isNotEmpty()) text(R.string.dialog_more) else text(R.string.library_settings)).activate()
        compose.onNodeWithText(text(R.string.library_background_playback)).assertIsDisplayed()
    }

    private fun configurationFor(type: Int): Configuration =
        Configuration(InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_TYPE_MASK.inv()) or type
        }

    private fun SemanticsNodeInteraction.activate() {
        assertIsDisplayed()
        // Activate the exposed action on both Material and TV buttons, without relying on touch mode.
        performSemanticsAction(SemanticsActions.OnClick) { check(it()) }
    }
}
