package play.ott.nativeapp.i18n

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import android.view.View
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import play.ott.nativeapp.R
import play.ott.nativeapp.playback.PlaybackTestApplication

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = PlaybackTestApplication::class)
class AppLanguagesTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @After fun clearOverride() { AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList()) }

    private fun contextFor(tags: String): Context = context.createConfigurationContext(
        Configuration(context.resources.configuration).apply { setLocales(LocaleList.forLanguageTags(tags)) }
    )

    @Test fun `system Russian is selected without an application override`() {
        assertEquals("", AppLanguages.currentTag())
        assertEquals("Источники", contextFor("ru-RU").getString(R.string.library_sources))
    }

    @Test fun `unsupported system language falls back to English`() {
        assertEquals("Sources", contextFor("ja-JP").getString(R.string.library_sources))
        assertEquals("Sources", contextFor("").getString(R.string.library_sources))
    }

    @Test fun `Android chooses a supported secondary system language`() {
        assertEquals("Источники", contextFor("ja-JP,ru-RU,en-US").getString(R.string.library_sources))
    }

    @Test fun `all FOSS languages have localized Android resources`() {
        assertEquals(20, AppLanguages.supported.size)
        val fallback = contextFor("en").getString(R.string.library_settings)
        for (language in AppLanguages.supported.filter { it.tag != "en" }) {
            val translated = contextFor(language.tag).getString(R.string.library_settings)
            assertTrue("Missing settings translation for ${language.tag}", translated != fallback)
        }
    }

    @Test fun `Hebrew config selects translations and right to left layout`() {
        val hebrew = contextFor("he-IL")
        assertEquals(View.LAYOUT_DIRECTION_RTL, hebrew.resources.configuration.layoutDirection)
        assertTrue(hebrew.getString(R.string.library_settings) != "Settings")
    }

    @Test @Config(sdk = [28]) fun `application override and clearing it update non activity messages immediately`() {
        AppLanguages.onActivityCreated()
        val englishSystem = contextFor("en-US")
        AppLanguages.setLanguage("ru")
        assertEquals("ru", AppLanguages.currentTag())
        assertEquals("Локальный плейлист", AppLanguages.localizedContext(englishSystem).getString(R.string.local_playlist))
        AppLanguages.setLanguage("")
        assertEquals("", AppLanguages.currentTag())
        assertEquals("Local playlist", AppLanguages.localizedContext(englishSystem).getString(R.string.local_playlist))
    }
}
