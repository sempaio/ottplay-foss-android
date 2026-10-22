package play.ott.nativeapp.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import play.ott.nativeapp.core.*

@RunWith(AndroidJUnit4::class)
class StarlinkUiInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun m81LoginNeedsOnlyUsernameAndPassword() {
        var saved: SourceConfig? = null
        compose.setContent { OttTheme { SourceEditor(null, {}, { saved = it }) } }
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(2)
        compose.onAllNodes(hasSetTextAction())[0].performTextInput("fixture-user")
        compose.onAllNodes(hasSetTextAction())[1].performTextInput("fixture-password")
        compose.onNodeWithText("Entrar", useUnmergedTree = true).performClick()
        compose.runOnIdle {
            assertEquals("https://panel.taila5c5c2.ts.net", saved?.url)
            assertEquals(SourceKind.XTREAM, saved?.kind)
            assertEquals("fixture-user", saved?.username)
            assertEquals("fixture-password", saved?.password)
        }
    }

    @Test fun emptyCredentialsCannotSubmit() {
        var saves = 0
        compose.setContent { OttTheme { SourceEditor(null, {}, { saves++ }) } }
        compose.onNodeWithText("Entrar", useUnmergedTree = true).performClick()
        compose.runOnIdle { assertEquals(0, saves) }
    }
}
