package play.ott.nativeapp.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import play.ott.nativeapp.MainActivity

@RunWith(AndroidJUnit4::class)
class StarlinkStartupInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun installedApplicationStartsAndOpensM81Login() {
        compose.waitUntil(20000) { compose.onAllNodesWithTag("welcome-add-source").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("welcome-add-source").performScrollTo().performClick()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(2)
        compose.onNodeWithText("Entrar • STARLINK IPTV").assertIsDisplayed()
    }
}
