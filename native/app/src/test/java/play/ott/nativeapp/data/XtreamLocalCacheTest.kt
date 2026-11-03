package play.ott.nativeapp.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import play.ott.nativeapp.core.Catalog
import play.ott.nativeapp.core.MediaEntry
import play.ott.nativeapp.core.MediaKind
import play.ott.nativeapp.core.XtreamGroup
import play.ott.nativeapp.playback.PlaybackTestApplication
import javax.crypto.KeyGenerator

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = PlaybackTestApplication::class)
class XtreamLocalCacheTest {
    private lateinit var db: CatalogDatabase
    private lateinit var vault: SourceVault
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun prepare() {
        vault = SourceVault(context)
        val key = KeyGenerator.getInstance("AES").apply { init(128) }.generateKey()
        ReflectionHelpers.setField(vault, "key\$delegate", lazy { key })
        db = CatalogDatabase(context, vault)
    }

    @After fun close() { db.close() }

    @Test fun categoriesAndVisitedContentAreEncryptedAndReusableAfterReopen() {
        val source = "fixture-xtream"
        val group = XtreamGroup("cat:001", "Futebol e Notícias", MediaKind.LIVE)
        val movie = XtreamGroup("movie", "Filmes", MediaKind.MOVIE)
        val categories = listOf(group, movie)
        db.storeXtreamGroups(source, categories)
        val contents = Catalog((0..750).map {
            MediaEntry(id = "channel-$it", sourceId = source,
                name = "Canal $it", kind = MediaKind.LIVE, group = group.name,
                url = "https://fixture.invalid/live/secret-value/$it.ts")
        })
        db.storeXtreamGroup(source, group, contents)
        assertEquals(categories, db.cachedXtreamGroups(source)?.value)
        assertEquals(contents, db.cachedXtreamGroup(source, group)?.value)
        assertNull(db.cachedXtreamGroup("other-source", group))
        db.readableDatabase.rawQuery(
            "SELECT payload FROM xtream_cache_chunks WHERE source_id=?",
            arrayOf(source)
        ).use { c ->
            while (c.moveToNext()) {
                assertFalse(c.getBlob(0).decodeToString().contains("secret-value"))
            }
        }
        db.close()
        db = CatalogDatabase(context, vault)
        assertEquals(categories, db.cachedXtreamGroups(source)?.value)
        assertEquals(contents, db.cachedXtreamGroup(source, group)?.value)
        db.delete(source)
        assertNull(db.cachedXtreamGroups(source))
        assertNull(db.cachedXtreamGroup(source, group))
    }

    @Test fun storesAtMostEightSelectedGroupsWithoutDroppingCategories() {
        val source = "fixture-xtream"
        db.storeXtreamGroups(source, listOf(XtreamGroup("index", "Categorias", MediaKind.LIVE)))
        repeat(10) { index ->
            val group = XtreamGroup("g" + index.toString().padStart(2, '0'),
                "Grupo $index", MediaKind.LIVE)
            db.storeXtreamGroup(source, group,
                Catalog(listOf(MediaEntry(id = "e-$index", sourceId = source,
                    name = "Canal $index", kind = MediaKind.LIVE, group = group.name))))
        }
        assertEquals(8, db.readableDatabase.rawQuery(
            "SELECT count(*) FROM xtream_cache_meta WHERE source_id=? AND cache_key!='categories'",
            arrayOf(source)
        ).use { c -> c.moveToFirst(); c.getInt(0) })
        assertNotNull(db.cachedXtreamGroups(source))
        assertNull(db.cachedXtreamGroup(source, XtreamGroup("g00", "Grupo 0", MediaKind.LIVE)))
        assertNotNull(db.cachedXtreamGroup(source, XtreamGroup("g09", "Grupo 9", MediaKind.LIVE)))
    }

    @Test fun migrationFromVersionThreePreservesPreviousCatalog() {
        val legacy = Catalog(listOf(MediaEntry(id="old", sourceId="legacy",
            name="Guardado", kind=MediaKind.SERIES)))
        db.replace("legacy", legacy)
        // Simulate an existing 0.3.2 database that did not have Xtream cache tables.
        db.writableDatabase.execSQL("DROP TABLE xtream_cache_chunks")
        db.writableDatabase.execSQL("DROP TABLE xtream_cache_meta")
        db.writableDatabase.version = 3
        db.close()
        db = CatalogDatabase(context, vault)
        assertEquals(4, db.writableDatabase.version)
        assertEquals(legacy, db.read("legacy"))
        val categories = listOf(XtreamGroup("a", "Séries", MediaKind.SERIES))
        db.storeXtreamGroups("legacy", categories)
        assertEquals(categories, db.cachedXtreamGroups("legacy")?.value)
    }
}
