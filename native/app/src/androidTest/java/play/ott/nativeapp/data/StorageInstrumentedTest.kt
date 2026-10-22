package play.ott.nativeapp.data

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import play.ott.nativeapp.core.Catalog
import play.ott.nativeapp.core.MediaEntry
import play.ott.nativeapp.core.Programme
import play.ott.nativeapp.core.ProviderException
import play.ott.nativeapp.core.SourceConfig
import play.ott.nativeapp.core.SourceKind
import play.ott.nativeapp.core.XmltvParser

/** Exercises real Android Keystore, AtomicFile, SQLite/WAL and SAX without touching user sources. */
@RunWith(AndroidJUnit4::class)
class StorageInstrumentedTest {
    private lateinit var storage: IsolatedStorageContext
    private lateinit var vault: SourceVault
    private val json = Json { encodeDefaults = true }
    private val sourceA get() = SourceConfig("test-a", "Test A", SourceKind.M3U,
        "https://example.invalid/list.m3u?token=secret-source-token-9713",
        password = "secret-password-7926", headers = mapOf("Authorization" to "Bearer secret-header-2631"))
    private val sourceB get() = SourceConfig("test-b", "Test B", SourceKind.M3U, "https://example.invalid/other.m3u")

    @Before fun createStorage() {
        storage = IsolatedStorageContext(InstrumentationRegistry.getInstrumentation().targetContext)
        vault = SourceVault(storage)
    }

    @After fun removeOnlyTestStorage() { storage.cleanUp() }

    @Test fun androidKeystoreRoundTripUsesFreshNonceAndRejectsTamperingWithoutDataLoss() {
        val sources = listOf(sourceA, sourceB)
        vault.write(sources)
        assertEquals(sources, SourceVault(storage).read())
        val file = File(storage.noBackupFilesDir, "sources.enc")
        val original = file.readBytes()
        assertNoPlaintextCredentials()
        val plaintext = "synthetic secret message".encodeToByteArray()
        val first = vault.encrypt(plaintext)
        val second = vault.encrypt(plaintext)
        assertFalse("AES-GCM encryption must use a fresh IV", first.contentEquals(second))
        assertArrayEquals(plaintext, SourceVault(storage).decrypt(first))
        val tampered = original.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        file.writeBytes(tampered)
        val failure = runCatching { SourceVault(storage).read() }
        assertTrue("Modified ciphertext must not be accepted as an empty source list", failure.isFailure)
        assertArrayEquals("A failed read must preserve the damaged file for recovery", tampered, file.readBytes())
        file.writeBytes(original)
        assertEquals(sources, SourceVault(storage).read())
    }

    @Test fun catalogAndEpgAreIsolatedBySourceAndExplicitGuideIdTakesPrecedence() {
        val db = CatalogDatabase(storage, vault)
        val channelA = entry(sourceA.id).copy(name = "Display Name", epgId = "exact-id")
        val channelB = entry(sourceB.id).copy(name = "Display Name", epgId = "exact-id")
        val first = Catalog(listOf(channelA), listOf("https://example.invalid/guide?token=secret-epg-token-8462"), listOf("Unsupported DRM metadata"))
        val second = Catalog(listOf(channelB))
        val now = System.currentTimeMillis()
        val exactA = Programme("EXACT-ID", "Source A exact", now - 60_000, now + 60_000)
        val misleadingName = Programme("Display Name", "Other channel name match", now - 60_000, now + 60_000)
        val exactB = Programme("exact-id", "Source B exact", now - 60_000, now + 60_000)
        try {
            db.replace(sourceA.id, first)
            db.replace(sourceB.id, second)
            db.replaceEpg(sourceA.id, listOf(exactA, misleadingName))
            db.replaceEpg(sourceB.id, listOf(exactB))
            assertEquals(first, db.read(sourceA.id))
            assertEquals(second, db.read(sourceB.id))
            assertEquals(listOf(exactA), db.programmes(channelA))
            assertEquals(listOf(exactB), db.programmes(channelB))
            assertEquals(listOf(misleadingName), db.programmes(channelA.copy(epgId = "missing-id")))
            assertNoPlaintextCredentials()
            db.delete(sourceA.id)
            assertTrue(db.read(sourceA.id).entries.isEmpty())
            assertTrue(db.programmes(channelA).isEmpty())
            assertEquals(second, db.read(sourceB.id))
            assertEquals(listOf(exactB), db.programmes(channelB))
        } finally { db.close() }
    }

    @Test fun largeCatalogUsesBoundedEncryptedBlocksAndAtomicReplacement() {
        val db = CatalogDatabase(storage, vault)
        val entries = (0 until 3000).map { entry(sourceA.id).copy(id = "channel-$it", name = "Channel $it") }
        val catalog = Catalog(entries, notes = listOf("Retain catalog notice"))
        try {
            val start = android.os.SystemClock.elapsedRealtime()
            db.replace(sourceA.id, catalog)
            val written = android.os.SystemClock.elapsedRealtime()
            assertEquals(catalog, db.read(sourceA.id))
            val read = android.os.SystemClock.elapsedRealtime()
            android.util.Log.i("OttplayStorageTest", "3000 catalog entries: write=${written-start}ms read=${read-written}ms")
            db.readableDatabase.rawQuery("SELECT count(*),max(length(payload)) FROM entries WHERE source_id=?", arrayOf(sourceA.id)).use {
                assertTrue(it.moveToFirst())
                assertEquals(24, it.getInt(0))
                assertTrue(it.getLong(1) < 513 * 1024)
            }
            val oversized = entry(sourceA.id).copy(description = "x".repeat(600 * 1024))
            assertTrue(runCatching { db.replace(sourceA.id, Catalog(entries.take(130) + oversized)) }.isFailure)
            assertEquals("Failed refresh must roll back already-written blocks", catalog, db.read(sourceA.id))
            assertNoPlaintextCredentials()
        } finally { db.close() }
    }

    @Test fun editingSourceInvalidatesItsCachedCredentialsAndKeepsOtherSource() = runBlocking {
        val repository = NativeRepository(storage)
        repository.save(sourceA)
        repository.save(sourceB)
        val db = CatalogDatabase(storage, vault)
        val a = Catalog(listOf(entry(sourceA.id)))
        val b = Catalog(listOf(entry(sourceB.id)))
        val now = System.currentTimeMillis()
        val programme = Programme("channel", "Guide", now - 10_000, now + 60_000)
        try {
            db.replace(sourceA.id, a); db.replace(sourceB.id, b)
            db.replaceEpg(sourceA.id, listOf(programme)); db.replaceEpg(sourceB.id, listOf(programme))
            repository.save(sourceA)
            assertEquals("An unchanged source must retain its cache", a, repository.cached(sourceA.id))
            repository.save(sourceA.copy(url = "https://other.invalid/new.m3u"))
            assertTrue(repository.cached(sourceA.id).entries.isEmpty())
            assertTrue(repository.programmes(entry(sourceA.id)).isEmpty())
            assertEquals(b, repository.cached(sourceB.id))
            assertEquals(listOf(programme), repository.programmes(entry(sourceB.id)))
        } finally { db.close() }
    }

    @Test fun rejectedImportValidatesEverySourceBeforeChangingAnyCredentialsOrCache() = runBlocking {
        val repository = NativeRepository(storage)
        repository.save(sourceA)
        repository.save(sourceB)
        val preferencesBefore = repository.preferences.data.first()
        val db = CatalogDatabase(storage, vault)
        val originalCatalog = Catalog(listOf(entry(sourceA.id)))
        val now = System.currentTimeMillis()
        val originalGuide = listOf(Programme("channel", "Retain this guide", now - 10_000, now + 60_000))
        val replacements = sourceA.copy(url = "https://replacement.invalid/catalog.m3u")
        val invalidSources = listOf(
            sourceB.copy(id = ""),
            sourceB.copy(name = " "),
            sourceB.copy(id = "x".repeat(161)),
            sourceB.copy(url = "https://"),
            sourceB.copy(kind = SourceKind.XTREAM, username = "", password = ""),
            sourceB.copy(headers = mapOf("Authorization" to "ok\r\nInjected: value")),
            sourceB.copy(headers = mapOf("Host" to "different.invalid")),
            sourceB.copy(url = "file:///private/no-grant.m3u"),
        )
        try {
            db.replace(sourceA.id, originalCatalog)
            db.replaceEpg(sourceA.id, originalGuide)
            val vaultBefore = File(storage.noBackupFilesDir, "sources.enc").readBytes()
            for (invalid in invalidSources) {
                val document = json.encodeToString(SettingsBackup(sources = listOf(replacements, invalid), preferences = preferencesBefore))
                assertTrue("The complete import must be rejected", runCatching { repository.importSettings(document) }.isFailure)
                assertEquals(listOf(sourceA, sourceB), repository.sources())
                assertArrayEquals("Validation failure must not even rewrite encrypted credentials", vaultBefore, File(storage.noBackupFilesDir, "sources.enc").readBytes())
                assertEquals(originalCatalog, repository.cached(sourceA.id))
                assertEquals(originalGuide, repository.programmes(entry(sourceA.id)))
                assertEquals(preferencesBefore, repository.preferences.data.first())
            }
        } finally { db.close() }
    }

    @Test fun validImportMergesSourcesAndInvalidatesOnlyTheChangedCatalog() = runBlocking {
        val repository = NativeRepository(storage)
        repository.save(sourceA)
        repository.save(sourceB)
        // PreferencesDataStore has app-wide identity. Use exactly its current values, never test secrets.
        val preferencesBefore = repository.preferences.data.first()
        val db = CatalogDatabase(storage, vault)
        val secondCatalog = Catalog(listOf(entry(sourceB.id)))
        val replacement = sourceA.copy(name = "Updated source", url = "https://replacement.invalid/list.m3u")
        try {
            db.replace(sourceA.id, Catalog(listOf(entry(sourceA.id))))
            db.replace(sourceB.id, secondCatalog)
            val result = repository.importSettings(json.encodeToString(SettingsBackup(sources = listOf(replacement), preferences = preferencesBefore)))
            assertEquals(1, result.count)
            assertEquals(setOf(replacement, sourceB), repository.sources().toSet())
            assertTrue(repository.cached(sourceA.id).entries.isEmpty())
            assertEquals(secondCatalog, repository.cached(sourceB.id))
            assertEquals(preferencesBefore, repository.preferences.data.first())
            assertNoPlaintextCredentials()
        } finally { db.close() }
    }

    @Test fun mixedBackupRestoresNetworkSourcesPreferencesAndResumeWhileReportingMissingFileGrant() = runBlocking {
        val repository = NativeRepository(storage)
        repository.save(sourceA)
        val previous = repository.preferences.data.first()
        val file = sourceB.copy(id = "local-file", url = "content://documents/tree/playlist")
        val imported = UserPreferences(selectedSourceId = sourceB.id, favorites = setOf("backup-favorite"),
            backgroundPlayback = false, resumePositions = mapOf("backup-movie" to 42_000L))
        try {
            val result = repository.importSettings(json.encodeToString(SettingsBackup(sources = listOf(sourceB, file), preferences = imported)))
            assertEquals(1, result.count)
            assertEquals(setOf(sourceA, sourceB), repository.sources().toSet())
            assertTrue(result.notes.single().contains(file.name))
            val restored = repository.preferences.data.first()
            assertEquals(sourceB.id, restored.selectedSourceId)
            assertTrue("backup-favorite" in restored.favorites)
            assertEquals(42_000L, restored.resumePositions["backup-movie"])
            assertFalse(restored.backgroundPlayback)
            // A legacy sources-only dump has no native preferences; importing it must not reset them.
            repository.importSettings("""{"M3Us":[{"www":"https://legacy.invalid/list.m3u"}]}""")
            assertEquals(restored, repository.preferences.data.first())
        } finally { repository.preferences.update { previous } }
    }

    @Test fun invalidResumeOffsetCannotReplaceSourceOrInvalidateCatalog() = runBlocking {
        val repository = NativeRepository(storage)
        repository.save(sourceA)
        val previous = repository.preferences.data.first()
        val db = CatalogDatabase(storage, vault)
        val catalog = Catalog(listOf(entry(sourceA.id)))
        try {
            db.replace(sourceA.id, catalog)
            val backup = SettingsBackup(sources = listOf(sourceA.copy(url = sourceB.url)),
                preferences = UserPreferences(resumePositions = mapOf("movie" to -1)))
            assertTrue(runCatching { repository.importSettings(json.encodeToString(backup)) }.isFailure)
            assertEquals(listOf(sourceA), repository.sources())
            assertEquals(catalog, repository.cached(sourceA.id))
            assertEquals(previous, repository.preferences.data.first())
        } finally { db.close() }
    }

    @Test fun androidSaxParsesPlainAndGzipXmltvButRejectsEveryDoctype() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tv><programme channel="channel" start="20260912183000 +0200" stop="20260912190000 +0200">
                <title>Новости &amp; погода</title><desc>Сводка дня</desc>
            </programme></tv>
        """.trimIndent().encodeToByteArray()
        val expected = listOf(Programme("channel", "Новости & погода",
            Instant.parse("2026-09-12T16:30:00Z").toEpochMilli(),
            Instant.parse("2026-09-12T17:00:00Z").toEpochMilli(), "Сводка дня"))
        assertEquals(expected, XmltvParser.parse(xml))
        val compressed = ByteArrayOutputStream().also { output -> GZIPOutputStream(output).use { it.write(xml) } }.toByteArray()
        assertEquals(expected, XmltvParser.parse(compressed))
        val marker = File(storage.root, "entity-marker.txt").apply { writeText("ENTITY-MUST-NOT-BE-READ") }
        val declarations = listOf(
            "<!DOCTYPE tv>",
            "<!DOCTYPE tv [<!ENTITY text 'expanded'>]>",
            "<!DOCTYPE tv SYSTEM '${marker.toURI()}'>",
            "<!DOCTYPE tv [<!ENTITY text SYSTEM '${marker.toURI()}'>]>",
        )
        for (declaration in declarations) {
            val bad = "$declaration<tv><programme channel='c' start='20260912000000 Z' stop='20260912010000 Z'><title>Title</title></programme></tv>"
            val failure = runCatching { XmltvParser.parse(bad.encodeToByteArray()) }.exceptionOrNull()
            assertTrue("Android SAX must fail closed on a DOCTYPE", failure is ProviderException)
            assertFalse(failure?.message.orEmpty().contains("ENTITY-MUST-NOT-BE-READ"))
        }
    }

    @Test fun legacyCredentialCatalogRefreshPreservesStoredRowsUntilReplacement() {
        val db = CatalogDatabase(storage, vault)
        try {
            val legacy = entry(sourceA.id).copy(headerOrigins = null)
            db.replace(sourceA.id, Catalog(listOf(legacy)))
            assertTrue(db.read(sourceA.id).entries.isEmpty())
            db.readableDatabase.rawQuery("SELECT count(*) FROM entries WHERE source_id=?", arrayOf(sourceA.id)).use {
                assertTrue(it.moveToFirst())
                assertEquals(1, it.getInt(0))
            }
            val refreshed = Catalog(listOf(entry(sourceA.id)))
            db.replace(sourceA.id, refreshed)
            assertEquals(refreshed, db.read(sourceA.id))
        } finally { db.close() }
    }

    private fun entry(sourceId: String) = MediaEntry(
        id = "same-id-in-both-sources", sourceId = sourceId, name = "channel", epgId = "channel",
        url = "https://stream.invalid/live?password=secret-stream-token-5829",
        headers = mapOf("Authorization" to "Bearer secret-header-2631"),
        headerOrigins = mapOf("authorization" to "https://stream.invalid/"),
    )

    private fun assertNoPlaintextCredentials() {
        val secrets = listOf("secret-source-token-9713", "secret-password-7926", "secret-header-2631", "secret-stream-token-5829", "secret-epg-token-8462")
        storage.root.walkTopDown().filter { it.isFile }.forEach { file ->
            val raw = file.readBytes().toString(StandardCharsets.ISO_8859_1)
            secrets.forEach { secret -> assertFalse("Credentials found unencrypted in ${file.name}", raw.contains(secret)) }
        }
    }
}

/** Redirect only credentials/cache. Do not redirect or delete the global DataStore or key alias. */
private class IsolatedStorageContext(base: Context) : ContextWrapper(base) {
    val root = File(base.cacheDir, "instrumentation-storage-${UUID.randomUUID()}").apply { mkdirs() }
    private val opened = mutableListOf<SQLiteDatabase>()
    override fun getNoBackupFilesDir(): File = File(root, "no-backup").apply { mkdirs() }
    override fun getDatabasePath(name: String): File = File(root, "databases/$name").apply { parentFile?.mkdirs() }
    override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
        openOrCreateDatabase(name, mode, factory, null)
    override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?): SQLiteDatabase =
        SQLiteDatabase.openDatabase(getDatabasePath(name).path, factory, SQLiteDatabase.CREATE_IF_NECESSARY or SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING, errorHandler).also { opened += it }
    override fun deleteDatabase(name: String): Boolean = SQLiteDatabase.deleteDatabase(getDatabasePath(name))
    fun cleanUp() {
        opened.forEach { if (it.isOpen) it.close() }
        root.deleteRecursively()
    }
}
