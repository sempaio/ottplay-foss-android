package play.ott.nativeapp.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import play.ott.nativeapp.core.*
import play.ott.nativeapp.StarlinkConfig

@RunWith(AndroidJUnit4::class)
class StarlinkUiInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun m81LoginNeedsOnlyUsernameAndPassword() {
        var saved: SourceConfig? = null
        compose.setContent { OttTheme { SourceEditor(null, {}, { saved = it }) } }
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(2)
        compose.onAllNodes(hasSetTextAction())[0].performTextInput("fixture-user")
        compose.onAllNodes(hasSetTextAction())[1].performTextInput("fixture-password")
        compose.onNodeWithText("Entrar").performSemanticsAction(SemanticsActions.OnClick) { check(it()) }
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
        compose.onNodeWithText("Entrar").performSemanticsAction(SemanticsActions.OnClick) { check(it()) }
        compose.runOnIdle { assertEquals(0, saves) }
    }
    @Test fun publishedPlaylistCanBeSelectedAlongsideXtreamWithoutTypingAUrl() {
        val account = SourceConfig("account", "Conta Xtream simulada", SourceKind.XTREAM, StarlinkConfig.XTREAM_BASE_URL)
        val playlist = StarlinkConfig.playlistSource()
        var selected: String? = null
        compose.setContent {
            OttNativeApp(AppUiState(sources = listOf(account, playlist), selectedSourceId = account.id),
                onAction = { if (it is AppAction.SelectSource) selected = it.sourceId },
                controller = null, onPictureInPicture = {}, onFullscreenChanged = {})
        }
        compose.onNodeWithText(account.name).performSemanticsAction(SemanticsActions.OnClick) { check(it()) }
        compose.onNodeWithText(playlist.name).performSemanticsAction(SemanticsActions.OnClick) { check(it()) }
        compose.runOnIdle { assertEquals(StarlinkConfig.PLAYLIST_SOURCE_ID, selected) }
    }

    @Test fun publishedPlaylistPresetUsesTheCompleteM3uAddress() {
        compose.setContent { OttTheme { SourceEditor(StarlinkConfig.playlistSource(), {}, {}) } }
        compose.onNodeWithText("https://panel.taila5c5c2.ts.net/app/starlink-iptv/playlist.m3u").assertExists()
    }

}
