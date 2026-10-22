package play.ott.nativeapp.data

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.LocaleList
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import play.ott.nativeapp.core.Catalog
import play.ott.nativeapp.core.CoreMessage
import play.ott.nativeapp.core.CoreMessageKey
import play.ott.nativeapp.core.MediaEntry
import play.ott.nativeapp.core.SourceConfig
import play.ott.nativeapp.core.SourceKind
import play.ott.nativeapp.i18n.AppLanguages
import play.ott.nativeapp.playback.PlaybackTestApplication
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = PlaybackTestApplication::class)
class CoreMessageLocalizationTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun useSystemLanguage() {
        AppLanguages.onActivityCreated()
        AppLanguages.setLanguage("")
    }

    private fun contextFor(tag: String): Context = context.createConfigurationContext(
        Configuration(context.resources.configuration).apply { setLocales(LocaleList.forLanguageTags(tag)) }
    )

    @Test fun `malformed imported metadata is rejected before accessing or changing existing storage`() {
        val directory = temporary.newFolder()
        val storage = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = directory
            override fun getFilesDir(): File = directory
        }
        // Deliberately unreadable vault bytes also prove validation precedes vault.read().
        val vault = File(directory, "sources.enc").apply { writeBytes(byteArrayOf(9, 4, 2, 7)) }
        val backup = File(directory, "sources.enc.bak").apply { writeBytes(byteArrayOf(5, 8, 1)) }
        val preferences = File(directory, "datastore/player.preferences_pb").apply {
            parentFile!!.mkdirs(); writeBytes(byteArrayOf(6, 3, 0))
        }
        val originalFiles = directory.walkTopDown().filter(File::isFile).map { it.relativeTo(directory).path }.toSet()
        val repository = NativeRepository(storage)
        val first = SourceConfig("first", "My source", SourceKind.M3U, "https://first.example/list.m3u")
        val invalid = listOf(
            CoreMessage(CoreMessageKey.IMPORTED_PLAYLIST),
            CoreMessage(CoreMessageKey.IMPORTED_PLAYLIST, listOf("0")),
            CoreMessage(CoreMessageKey.IMPORTED_PLAYLIST, listOf("not-an-index")),
            CoreMessage(CoreMessageKey.IMPORTED_PLAYLIST, listOf("1", "private-value")),
            CoreMessage(CoreMessageKey.DEMO_SOURCE, listOf("private-value")),
            CoreMessage(CoreMessageKey.XTREAM_SECTION_UNAVAILABLE, listOf("MOVIE", "404")),
        )
        for (metadata in invalid) {
            val second = first.copy(id = "second", name = "Keep my name", nameMessage = metadata)
            val document = Json.encodeToString(SettingsBackup(sources = listOf(first, second)))
            val failure = assertThrows(IllegalArgumentException::class.java) {
                runBlocking { repository.importSettings(document) }
            }
            assertEquals("Invalid generated source name metadata", failure.message)
            assertFalse(failure.message.orEmpty().contains("private-value"))
            assertArrayEquals(byteArrayOf(9, 4, 2, 7), vault.readBytes())
            assertArrayEquals(byteArrayOf(5, 8, 1), backup.readBytes())
            assertArrayEquals(byteArrayOf(6, 3, 0), preferences.readBytes())
            assertEquals(originalFiles, directory.walkTopDown().filter(File::isFile).map { it.relativeTo(directory).path }.toSet())
        }
    }

    @Test fun `malformed cached labels and notes render their original payload without mutation`() {
        val repository = NativeRepository(contextFor("ru"))
        val entry = Json.decodeFromString<MediaEntry>("""{
            "id":"entry","sourceId":"source","name":"Provider's own title","group":"My group",
            "nameMessage":{"key":"EPISODE"},
            "groupMessage":{"key":"OTHER_GROUP","args":["unexpected"]}
        }""")
        assertEquals(entry, repository.displayEntry(entry))
        val source = SourceConfig("source", "My exact name", SourceKind.M3U, "https://example.test/list.m3u",
            nameMessage = CoreMessage(CoreMessageKey.IMPORTED_PLAYLIST))
        assertEquals(source, repository.displaySource(source))
        val notes = listOf("Original provider note", "Another retained note")
        val malformed = CoreMessage(CoreMessageKey.XTREAM_GROUPS_UNAVAILABLE, listOf("MOVIE"))
        val catalog = Catalog(listOf(entry), notes = notes, messages = listOf(malformed))
        assertEquals(notes, repository.catalogNotes(catalog))
        assertEquals(notes, repository.importNotes(ImportResult(1, notes, listOf(malformed))))
        assertEquals(notes, catalog.notes)
        assertEquals(listOf(malformed), catalog.messages)
    }

    @Test fun `invalid formatter domains use the supplied text while valid generated text localizes`() {
        val russian = contextFor("ru")
        val invalid = listOf(
            CoreMessage(CoreMessageKey.UNTITLED, listOf("unrecognized-section")),
            CoreMessage(CoreMessageKey.XTREAM_SECTION_UNAVAILABLE, listOf("EPISODE", "404")),
            CoreMessage(CoreMessageKey.XTREAM_SECTION_UNAVAILABLE, listOf("LIVE", "not-a-status")),
            CoreMessage(CoreMessageKey.XTREAM_SECTION_UNAVAILABLE, listOf("LIVE", "999")),
            CoreMessage(CoreMessageKey.CHANNEL),
            CoreMessage(CoreMessageKey.EPISODE, listOf("")),
        )
        for (message in invalid) assertEquals("Original text", message.localized(russian, "Original text"))
        assertEquals("Imported playlist 2", CoreMessage(CoreMessageKey.IMPORTED_PLAYLIST, listOf("2")).localized(contextFor("en")))
        assertEquals("Other", CoreMessage(CoreMessageKey.OTHER_GROUP).localized(contextFor("en")))
        assertFalse(CoreMessage(CoreMessageKey.OTHER_GROUP).localized(russian) == "Other")
    }

    @Test fun `explicit demo names survive serialized backup and presentation in either language`() {
        for (name in listOf("Offline demo", "Тест без интернета")) {
            val edited = NativeRepository.demoSource().copy(name = name, nameMessage = null, nameIsUserDefined = true)
            val restored = Json.decodeFromString<SettingsBackup>(Json.encodeToString(SettingsBackup(sources = listOf(edited))))
                .sources.single()
            for (language in listOf("en", "ru")) assertEquals(edited, NativeRepository(contextFor(language)).displaySource(restored))
        }
        // Existing backups still decode without the new provenance field and retain their migration.
        val legacy = Json.decodeFromString<SourceConfig>("""{
            "id":"local-demo","name":"Тест без интернета","kind":"M3U","url":"demo://local"
        }""")
        assertFalse(legacy.nameIsUserDefined)
        assertEquals("Offline demo", NativeRepository(contextFor("en")).displaySource(legacy).name)
    }
}
