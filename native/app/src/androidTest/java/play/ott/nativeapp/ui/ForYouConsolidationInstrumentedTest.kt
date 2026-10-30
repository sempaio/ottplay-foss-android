package play.ott.nativeapp.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import play.ott.nativeapp.core.MediaEntry
import play.ott.nativeapp.core.MediaKind
import play.ott.nativeapp.data.ForYouProfile

@RunWith(AndroidJUnit4::class)
class ForYouConsolidationInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Before fun foreground() { awaitActivityWindowReady(compose.activity, hideIme = true) }

    @Test fun choicesSupportMultipleTopicsSubtopicsBackAndConsentOff() {
        var topics = emptySet<String>(); var subtopics = emptySet<String>(); var consent = true
        compose.setContent { OttTheme { ForYouDialog(ForYouProfile(), { a, b, _, d -> topics = a; subtopics = b; consent = d }, {}) } }
        compose.onNodeWithTag("topic-futebol").performScrollTo().performClick()
        compose.onNodeWithTag("topic-desenhos").performScrollTo().performClick()
        compose.onNodeWithTag("for-you-next").performClick()
        compose.onNodeWithTag("subtopic-futebol:brasileiro").performScrollTo().performClick()
        compose.onNodeWithTag("subtopic-desenhos:classicos").performScrollTo().performClick()
        compose.onNodeWithText("Voltar").performClick()
        compose.onNodeWithTag("for-you-next").performClick()
        compose.onNodeWithTag("for-you-next").performClick()
        compose.onNodeWithTag("for-you-consent").performScrollTo().assertIsOff()
        compose.onNodeWithTag("for-you-next").performClick()
        compose.runOnIdle {
            assertEquals(setOf("futebol", "desenhos"), topics)
            assertEquals(setOf("futebol:brasileiro", "desenhos:classicos"), subtopics)
            assertFalse(consent)
        }
    }

    @Test fun skippingDoesNotInventPreferences() {
        var skipped = false; var saved = false
        compose.setContent { OttTheme { ForYouDialog(ForYouProfile(), { _, _, _, _ -> saved = true }, { skipped = true }) } }
        compose.onNodeWithText("Pular").performClick()
        compose.runOnIdle { assertTrue(skipped); assertFalse(saved) }
    }

    @Test fun aLoadedTransparentLogoReplacesRatherThanOverlaysPlaceholder() {
        val image = File(compose.activity.cacheDir, "qa-transparent-logo.png")
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        bitmap.setPixel(8, 8, android.graphics.Color.BLUE)
        image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val entry = MediaEntry("artwork-qa", "fixture", "Fixture", logo = image.toURI().toString(), kind = MediaKind.LIVE)
        compose.setContent { OttTheme { MediaCard(entry, false, false, {}) } }
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("artwork-placeholder-artwork-qa", useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithText("STARLINK").assertDoesNotExist()
        image.delete()
    }
}
