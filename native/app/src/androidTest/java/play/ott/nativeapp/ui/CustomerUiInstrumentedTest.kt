package play.ott.nativeapp.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.*
import org.junit.runner.RunWith
import play.ott.nativeapp.StarlinkConfig
import play.ott.nativeapp.core.SourceConfig

@RunWith(AndroidJUnit4::class)
class CustomerUiInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun loginOnlyAllowsCredentialsAndKeepsTheServerFixed() {
        var saved: SourceConfig? = null
        compose.setContent { OttTheme { CustomerLoginScreen(busy = false, onSubmit = { saved = it }) } }
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(2)
        compose.onNodeWithTag("customer-username").performTextInput("fixture-user")
        compose.onNodeWithTag("customer-password").performTextInput("fixture-password")
        compose.onNodeWithTag("customer-sign-in").performSemanticsAction(SemanticsActions.OnClick) { check(it()) }
        compose.runOnIdle {
            Assert.assertEquals(StarlinkConfig.XTREAM_BASE_URL, saved?.url)
            Assert.assertTrue(StarlinkConfig.isManagedSource(requireNotNull(saved)))
        }
        compose.onNodeWithText("M81", substring = true).assertDoesNotExist()
        compose.onNodeWithText("https://", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Xtream", substring = true).assertDoesNotExist()
    }
    @Test fun customerHomeDoesNotExposeProviderManagement() {
        compose.setContent { OttNativeApp(AppUiState(sources = listOf(StarlinkConfig.playlistSource()), selectedSourceId = StarlinkConfig.PLAYLIST_SOURCE_ID), {}, null, {}, {}, customerMode = true) }
        compose.onNodeWithText(compose.activity.getString(play.ott.nativeapp.R.string.library_sources)).assertDoesNotExist()
        compose.onNodeWithText(StarlinkConfig.PLAYLIST_URL).assertDoesNotExist()
    }
}
