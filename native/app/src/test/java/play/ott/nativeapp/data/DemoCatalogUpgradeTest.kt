package play.ott.nativeapp.data

import android.content.Context
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import play.ott.nativeapp.R
import play.ott.nativeapp.core.Catalog
import play.ott.nativeapp.core.MediaEntry
import play.ott.nativeapp.core.MediaKind
import play.ott.nativeapp.core.SourceConfig
import play.ott.nativeapp.core.SourceKind
import play.ott.nativeapp.i18n.AppLanguages
import play.ott.nativeapp.playback.PlaybackTestApplication
import java.io.File
import javax.crypto.KeyGenerator

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = PlaybackTestApplication::class)
class DemoCatalogUpgradeTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var repository: NativeRepository
    private lateinit var vault: SourceVault
    private lateinit var db: CatalogDatabase

    @Before fun prepareEncryptedStorage() {
        AppLanguages.onActivityCreated()
        AppLanguages.setLanguage("en")
        repository = NativeRepository(context)
        vault = ReflectionHelpers.getField(repository, "vault")
        db = ReflectionHelpers.getField(repository, "db")
        // Robolectric has no hardware AndroidKeyStore. Substitute only its backing key;
        // actual vault encryption, SQLite blocks and repository cache reads still run.
        val key = KeyGenerator.getInstance("AES").apply { init(128) }.generateKey()
        ReflectionHelpers.setField(vault, "key\$delegate", lazy { key })
    }

    @After fun closeStorage() {
        db.close()
        AppLanguages.setLanguage("")
    }

    @Test fun `encrypted legacy demo cache repairs labels and playable URI without refreshing or changing settings`() = runBlocking {
        // These are the old signed ff4eaca payload values, with no localization metadata.
        val source = Json.decodeFromString<SourceConfig>("""{
            "id":"local-demo","name":"Тест без интернета","kind":"M3U","url":"demo://local"
        }""")
        val legacy = oldEntry(source.id)
        assertNotEquals("Fixture must represent a resource ID from the previous APK", OLD_DEMO_RESOURCE_ID, R.raw.demo)
        assertNull(legacy.nameMessage)
        assertNull(legacy.groupMessage)
        vault.write(listOf(source))
        db.replace(source.id, Catalog(listOf(legacy)))
        val sourceBytes = sourceFile().readBytes()
        repository.preferences.update { it.copy(selectedSourceId = source.id,
            favorites = setOf(legacy.id), resumePositions = mapOf(legacy.id to 2450L)) }
        val preferences = repository.preferences.data.first()

        val repaired = repository.cached(source.id).entries.single()
        assertEquals(legacy.id, repaired.id)
        assertEquals(legacy.sourceId, repaired.sourceId)
        assertEquals("android.resource://${context.packageName}/raw/demo", repaired.url)
        val english = repository.displayEntry(repaired)
        assertEquals("Test pattern · 8 seconds", english.name)
        assertEquals("Local test", english.group)
        assertEquals("A synthetic test video. Works offline; the audio track is silent.", english.description)
        assertEquals("Offline demo", repository.displaySource(source).name)
        // Resolve and read the URI through Android, not just a string-pattern assertion.
        assertArrayEquals(context.resources.openRawResource(R.raw.demo).use { it.readBytes() },
            requireNotNull(context.contentResolver.openInputStream(repaired.url.toUri())).use { it.readBytes() })
        assertEquals(repaired.url, repository.stream(source, legacy).url)
        assertEquals(repaired, db.read(source.id).entries.single())
        assertEquals(listOf(source), repository.sources())
        assertArrayEquals(sourceBytes, sourceFile().readBytes())
        assertEquals(preferences, repository.preferences.data.first())

        // Rendering from another locale context must not rewrite the canonical cache.
        AppLanguages.setLanguage("")
        val russianRepository = NativeRepository(contextForRussian())
        val russian = russianRepository.displayEntry(repository.cached(source.id).entries.single())
        assertEquals(contextForRussian().getString(R.string.message_demo_name), russian.name)
        assertEquals(contextForRussian().getString(R.string.message_demo_group), russian.group)
        assertEquals(contextForRussian().getString(R.string.message_demo_description), russian.description)
        assertEquals(repaired.url, russian.url)
        assertArrayEquals(sourceBytes, sourceFile().readBytes())
    }

    @Test fun `provider and wrong-kind sources retain matching Russian names and numeric resource URLs`() = runBlocking {
        val candidates = listOf(
            SourceConfig("provider", "Тест без интернета", SourceKind.M3U, "https://provider.invalid/list.m3u"),
            SourceConfig("wrong-kind", "Тест без интернета", SourceKind.XTREAM, NativeRepository.DEMO_URL),
        )
        vault.write(candidates)
        val sourcesBefore = sourceFile().readBytes()
        for (source in candidates) {
            val cached = Catalog(listOf(oldEntry(source.id)), notes = listOf("Локальный тест"))
            db.replace(source.id, cached)
            val blockBefore = encryptedEntries(source.id)
            assertEquals(cached, repository.cached(source.id))
            assertEquals(cached.entries.single(), repository.displayEntry(cached.entries.single()))
            assertArrayEquals(blockBefore, encryptedEntries(source.id))
        }
        assertArrayEquals(sourcesBefore, sourceFile().readBytes())
        assertEquals(candidates, repository.sources())
    }

    @Test fun `repair uses demo IDs kind and own resource authority while preserving other catalogue text`() = runBlocking {
        val source = NativeRepository.demoSource().copy(name = "Тест без интернета", nameMessage = null, nameIsUserDefined = true)
        val legacy = oldEntry(source.id)
        val unrelated = listOf(
            legacy.copy(id = "user-entry"),
            legacy.copy(sourceId = "another-source"),
            legacy.copy(kind = MediaKind.LIVE),
            legacy.copy(url = "android.resource://another.application/$OLD_DEMO_RESOURCE_ID"),
            legacy.copy(url = "https://provider.invalid/video.m3u8"),
            legacy.copy(url = legacy.url + "?user=1"),
            legacy.copy(url = legacy.url + "#user"),
            legacy.copy(url = "android.resource://${context.packageName}/raw/user_video"),
        )
        vault.write(listOf(source))
        val cached = Catalog(listOf(legacy) + unrelated, epgUrls = listOf("https://provider.invalid/guide.xml"),
            notes = listOf("Тест изображения · 8 секунд"))
        db.replace(source.id, cached)
        val sourceBytes = sourceFile().readBytes()
        val restored = repository.cached(source.id)
        assertEquals(unrelated, restored.entries.drop(1))
        assertEquals(cached.epgUrls, restored.epgUrls)
        assertEquals(cached.notes, restored.notes)
        assertEquals("Test pattern · 8 seconds", repository.displayEntry(restored.entries.first()).name)
        assertEquals("Тест без интернета", repository.displaySource(repository.sources().single()).name)
        assertArrayEquals(sourceBytes, sourceFile().readBytes())
        assertEquals(restored, repository.cached(source.id))
    }

    @Test fun `fresh demo refresh writes a stable resource-name URI`() = runBlocking {
        val source = NativeRepository.demoSource()
        vault.write(listOf(source))
        val entry = repository.refresh(source).entries.single()
        assertEquals("android.resource://${context.packageName}/raw/demo", entry.url)
        assertEquals(entry, repository.cached(source.id).entries.single())
        assertEquals(entry.url, repository.stream(source, entry).url)
    }

    private fun oldEntry(sourceId: String): MediaEntry = Json.decodeFromString("""{
        "id":"$sourceId:pattern","sourceId":"$sourceId","name":"Тест изображения · 8 секунд",
        "url":"android.resource://${context.packageName}/$OLD_DEMO_RESOURCE_ID","kind":"MOVIE",
        "group":"Локальный тест","description":"Локальное тестовое видео без интернета."
    }""")

    private fun sourceFile() = File(context.noBackupFilesDir, "sources.enc")

    private fun encryptedEntries(sourceId: String): ByteArray = db.readableDatabase.rawQuery(
        "SELECT payload FROM entries WHERE source_id=? ORDER BY ordinal", arrayOf(sourceId)
    ).use { check(it.moveToFirst()); it.getBlob(0) }

    private fun contextForRussian() = context.createConfigurationContext(
        android.content.res.Configuration(context.resources.configuration).apply {
            setLocales(android.os.LocaleList.forLanguageTags("ru"))
        }
    )

    private companion object {
        // Verified with aapt2 on the actual signed ff4eaca APK, before AppCompat resources.
        const val OLD_DEMO_RESOURCE_ID = 0x7f0c0000
    }
}
