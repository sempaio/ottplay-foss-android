package play.ott.nativeapp.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
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
        compose.waitUntil(20000) { compose.onAllNodesWithTag("customer-login").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(2)
        compose.onNodeWithTag("customer-brand").assertIsDisplayed()
        compose.onNodeWithText("https://panel.taila5c5c2.ts.net").assertDoesNotExist()
    }
}
