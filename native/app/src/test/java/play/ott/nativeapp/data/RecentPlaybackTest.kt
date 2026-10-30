package play.ott.nativeapp.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import play.ott.nativeapp.core.*
import play.ott.nativeapp.playback.PlaybackTestApplication
import javax.crypto.KeyGenerator

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = PlaybackTestApplication::class)
class RecentPlaybackTest {
    private lateinit var db: CatalogDatabase
    private val episode = MediaEntry("episode", "synthetic-source", "Synthetic episode", "https://example.invalid/series/fixture-user/fixture-password/3.mp4", kind = MediaKind.EPISODE, season = 2, episode = 3)
    @Before fun prepare() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val vault = SourceVault(context)
        val key = KeyGenerator.getInstance("AES").apply { init(128) }.generateKey()
        ReflectionHelpers.setField(vault, "key\$delegate", lazy { key })
        db = CatalogDatabase(context, vault)
    }
    @After fun close() { db.close() }
    @Test fun encryptedEpisodeRoundTripAndCompletionRemoval() {
        db.saveRecent(RecentPlayback(episode, 1500, 60000, 1))
        assertEquals(episode, db.recent().single().entry)
        db.readableDatabase.rawQuery("SELECT payload FROM recent", null).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertFalse(cursor.getBlob(0).decodeToString().contains("fixture-password"))
        }
        db.saveRecent(RecentPlayback(episode, 0, 60000, 2))
        assertTrue(db.recent().isEmpty())
    }
    @Test fun sourceRemovalAndGlobalLimit() {
        repeat(105) { db.saveRecent(RecentPlayback(episode.copy(id = "episode-$it"), 1000, null, it.toLong())) }
        assertEquals(100, db.recent().size)
        assertEquals("episode-104", db.recent().first().entry.id)
        db.delete(episode.sourceId)
        assertTrue(db.recent().isEmpty())
    }
    @Test fun profileHistoryIsEncryptedIndependentAndRemovedWithItsSource() {
        db.saveRecent(RecentPlayback(episode, 1000, 60000, 1))
        db.saveRecent(RecentPlayback(episode, 9000, 60000, 2), "secondary")
        assertEquals(1000L, db.recent().single().positionMs)
        assertEquals(9000L, db.recent("secondary").single().positionMs)
        db.readableDatabase.rawQuery("SELECT payload FROM profile_recent", null).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertFalse(cursor.getBlob(0).decodeToString().contains("fixture-password"))
        }
        db.saveRecent(RecentPlayback(episode, 0, 60000, 3), "secondary")
        assertTrue(db.recent("secondary").isEmpty())
        assertEquals(1, db.recent().size)
        db.saveRecent(RecentPlayback(episode, 9000, 60000, 4), "secondary")
        db.delete(episode.sourceId)
        assertTrue(db.recent().isEmpty()); assertTrue(db.recent("secondary").isEmpty())
    }

    @Test fun versionTwoMigrationPreservesDefaultHistory() {
        db.saveRecent(RecentPlayback(episode, 1000, 60000, 1))
        db.writableDatabase.execSQL("DROP TABLE profile_recent")
        db.writableDatabase.version = 2
        db.close()
        val vault: SourceVault = ReflectionHelpers.getField(db, "vault")
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = CatalogDatabase(context, vault)
        assertEquals(3, db.writableDatabase.version)
        assertEquals(1000L, db.recent().single().positionMs)
        assertTrue(db.recent("secondary").isEmpty())
    }

    @Test fun versionOneMigrationPreservesEncryptedCatalog() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db.replace(episode.sourceId, Catalog(listOf(episode)))
        db.writableDatabase.execSQL("DROP TABLE recent")
        db.writableDatabase.version = 1
        db.close()
        // Retain the same vault instance/key, as AndroidKeyStore does across restarts.
        val vault: SourceVault = ReflectionHelpers.getField(db, "vault")
        db = CatalogDatabase(context, vault)
        assertEquals(3, db.writableDatabase.version)
        assertEquals(episode, db.read(episode.sourceId).entries.single())
        assertTrue(db.recent().isEmpty())
    }
}
