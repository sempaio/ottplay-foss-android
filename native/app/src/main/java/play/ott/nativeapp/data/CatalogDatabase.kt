package play.ott.nativeapp.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.sqlite.transaction
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import play.ott.nativeapp.core.CoreMessage
import play.ott.nativeapp.core.CoreMessageKey
import play.ott.nativeapp.core.Catalog
import play.ott.nativeapp.core.MediaEntry
import play.ott.nativeapp.core.Programme
import play.ott.nativeapp.core.needsHeaderOriginRefresh

@Serializable
private data class CatalogMetadata(val format: Int = 2, val epgUrls: List<String> = emptyList(), val notes: List<String> = emptyList(), val messages: List<CoreMessage> = emptyList())

/** Transactional EPG index and encrypted catalog blocks. All calls run on Dispatchers.IO. */
class CatalogDatabase(context: Context, private val vault: SourceVault) :
    SQLiteOpenHelper(context, "catalog.db", null, 1) {
    private val json = Json { ignoreUnknownKeys = true }
    init { setWriteAheadLoggingEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE entries(source_id TEXT NOT NULL,id TEXT NOT NULL,payload BLOB NOT NULL,ordinal INTEGER NOT NULL,PRIMARY KEY(source_id,id))")
        db.execSQL("CREATE TABLE epg(source_id TEXT NOT NULL,channel_id TEXT NOT NULL,title TEXT NOT NULL,start_ms INTEGER NOT NULL,end_ms INTEGER NOT NULL,description TEXT NOT NULL,PRIMARY KEY(source_id,channel_id,start_ms,end_ms))")
        db.execSQL("CREATE INDEX epg_channel_time ON epg(source_id,channel_id COLLATE NOCASE,start_ms,end_ms)")
        db.execSQL("CREATE TABLE catalogs(source_id TEXT PRIMARY KEY,payload BLOB NOT NULL,updated_ms INTEGER NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun replace(sourceId: String, catalog: Catalog) {
        val db = writableDatabase
        db.transaction {
            db.delete("entries", "source_id=?", arrayOf(sourceId))
            db.compileStatement("INSERT OR REPLACE INTO entries VALUES(?,?,?,?)").use { insert ->
                // One Keystore operation per bounded block, rather than one per channel.
                // Bound bytes too: a SQLite CursorWindow cannot hold an arbitrarily large row.
                var block = mutableListOf<String>()
                var bytes = 2
                var ordinal = 0L
                fun flush() {
                    if (block.isEmpty()) return
                    insert.clearBindings()
                    insert.bindString(1, sourceId); insert.bindString(2, "block:$ordinal")
                    insert.bindBlob(3, vault.encrypt(block.joinToString(",", "[", "]").encodeToByteArray()))
                    insert.bindLong(4, ordinal++); insert.executeInsert()
                    block = mutableListOf(); bytes = 2
                }
                catalog.entries.forEach { value ->
                    val entry = json.encodeToString(value)
                    val size = entry.encodeToByteArray().size
                    require(size <= 512 * 1024) { "Catalog entry exceeds supported size" }
                    if (block.size >= 128 || bytes + size + 1 > 512 * 1024) flush()
                    block.add(entry); bytes += size + 1
                }
                flush()
            }
            db.insertWithOnConflict("catalogs", null, ContentValues().apply {
                put("source_id", sourceId)
                put("payload", vault.encrypt(json.encodeToString(CatalogMetadata(epgUrls = catalog.epgUrls, notes = catalog.notes, messages = catalog.messages)).encodeToByteArray()))
                put("updated_ms", System.currentTimeMillis())
            }, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    fun read(sourceId: String): Catalog {
        val db = readableDatabase
        // Metadata and all blocks must belong to the same refresh transaction.
        db.beginTransactionNonExclusive()
        try {
        val metadata = readableDatabase.rawQuery("SELECT payload FROM catalogs WHERE source_id=?", arrayOf(sourceId)).use {
            if (it.moveToFirst()) vault.decrypt(it.getBlob(0)).decodeToString() else return Catalog(emptyList())
        }
        // Read the initial development format until its next successful, atomic refresh.
        val legacy = metadata.trimStart().startsWith("[")
        val info = if (legacy) CatalogMetadata(format = 1, epgUrls = json.decodeFromString(metadata))
            else json.decodeFromString<CatalogMetadata>(metadata)
        require(info.format in 1..2) { "Unsupported cached catalog" }
        val entries = readableDatabase.rawQuery(
            "SELECT payload FROM entries WHERE source_id=? ORDER BY ordinal", arrayOf(sourceId)
        ).use { cursor -> buildList {
            while (cursor.moveToNext()) {
                val text = vault.decrypt(cursor.getBlob(0)).decodeToString()
                if (info.format == 1) add(json.decodeFromString<MediaEntry>(text))
                else addAll(json.decodeFromString<List<MediaEntry>>(text))
            }
        }}
        // Refresh legacy snapshots before playback can reinterpret source credentials as CDN
        // credentials. Keep the encrypted rows intact until a successful atomic replacement.
        if (entries.any { it.needsHeaderOriginRefresh() }) return Catalog(emptyList(), notes =
            listOf(CoreMessage(CoreMessageKey.HEADER_ORIGIN_REFRESH).english()),
            messages = listOf(CoreMessage(CoreMessageKey.HEADER_ORIGIN_REFRESH)))
        return Catalog(entries, info.epgUrls, info.notes, info.messages)
        } finally { db.endTransaction() }
    }

    fun replaceEpg(sourceId: String, programmes: List<Programme>) {
        val db = writableDatabase
        db.transaction {
            db.delete("epg", "source_id=?", arrayOf(sourceId))
            db.compileStatement("INSERT OR REPLACE INTO epg VALUES(?,?,?,?,?,?)").use { insert ->
                programmes.forEach {
                    insert.clearBindings(); insert.bindString(1, sourceId); insert.bindString(2, it.channelId)
                    insert.bindString(3, it.title); insert.bindLong(4, it.startMillis)
                    insert.bindLong(5, it.endMillis); insert.bindString(6, it.description)
                    insert.executeInsert()
                }
            }
        }
    }

    fun programmes(entry: MediaEntry): List<Programme> {
        val since = System.currentTimeMillis() - 14L * 86400_000
        fun query(channel: String): List<Programme> = readableDatabase.rawQuery(
            "SELECT channel_id,title,start_ms,end_ms,description FROM epg WHERE source_id=? AND channel_id=? COLLATE NOCASE AND end_ms>? ORDER BY start_ms LIMIT 1000",
            arrayOf(entry.sourceId, channel, since.toString())
        ).use { cursor -> buildList {
            while(cursor.moveToNext()) add(Programme(cursor.getString(0),cursor.getString(1),cursor.getLong(2),cursor.getLong(3),cursor.getString(4)))
        }}
        val matched = if (entry.epgId.isNotBlank()) query(entry.epgId) else emptyList()
        return matched.ifEmpty { query(entry.name) }
    }

    fun delete(sourceId: String) {
        val db = writableDatabase
        db.transaction {
            listOf("entries", "epg", "catalogs").forEach { db.delete(it, "source_id=?", arrayOf(sourceId)) }
        }
    }
}
