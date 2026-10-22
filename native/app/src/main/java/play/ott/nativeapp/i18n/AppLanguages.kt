package play.ott.nativeapp.i18n

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.MainThread
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.os.ConfigurationCompat
import androidx.core.os.LocaleListCompat

/** An empty override follows Android's locale list; unqualified resources are English. */
object AppLanguages {
    data class AppLanguage(val tag: String, val nativeName: String)

    // Keep at least the languages offered by ottplay-foss's selectLang().
    val supported = listOf(
        AppLanguage("en", "English"),
        AppLanguage("hy", "Հայերեն"),
        AppLanguage("be", "Беларуская"),
        AppLanguage("bg", "Български"),
        AppLanguage("fr", "Français"),
        AppLanguage("de", "Deutsch"),
        AppLanguage("el", "Ελληνικά"),
        AppLanguage("he", "עברית"),
        AppLanguage("hu", "Magyar"),
        AppLanguage("it", "Italiano"),
        AppLanguage("lv", "Latviski"),
        AppLanguage("lt", "Lietuvių"),
        AppLanguage("pl", "Polski"),
        AppLanguage("pt", "Português"),
        AppLanguage("ro", "Română"),
        AppLanguage("ru", "Русский"),
        AppLanguage("es", "Español"),
        AppLanguage("tr", "Türkçe"),
        AppLanguage("uk", "Українська"),
        AppLanguage("uz", "Oʻzbekcha"),
    )

    private var activityLocalesRestored = false

    fun currentTag(): String = AppCompatDelegate.getApplicationLocales()[0]?.language.orEmpty()
        .let { if (it == "iw") "he" else it }

    /** Called after AppCompat has restored its persisted language at Activity creation. */
    @MainThread
    fun onActivityCreated() { activityLocalesRestored = true }

    @MainThread
    fun setLanguage(tag: String) {
        require(tag.isEmpty() || supported.any { it.tag == tag }) { "Unsupported app language: $tag" }
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
    }

    /** ViewModels and services also need the app language on Android 12 and earlier. */
    fun localizedContext(context: Context): Context {
        val locales = AppCompatDelegate.getApplicationLocales()
        if (locales.isEmpty) {
            // After restoration an empty list means "System", even while async disk storage
            // still contains the previous override. Before the first Activity, a service may
            // need ContextCompat to read a saved language on Android 12 and earlier.
            return if (activityLocalesRestored) context else ContextCompat.getContextForLanguage(context)
        }
        return context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            ConfigurationCompat.setLocales(this, locales)
        })
    }
}
