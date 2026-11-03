package play.ott.nativeapp.data

import android.content.ContentValues
import android.content.Context
import java.io.ByteArrayOutputStream
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
import play.ott.nativeapp.core.XtreamGroup
import play.ott.nativeapp.core.needsHeaderOriginRefresh

/** Age decides when stale-while-revalidate is needed. */
internal data class XtreamCached<T>(val value: T, val ageMillis: Long)

@Serializable
private data class CatalogMetadata(val format: Int = 2, val epgUrls: List<String> = emptyList(), val notes: List<String> = emptyList(), val messages: List<CoreMessage> = emptyList())

/** Transactional EPG index and encrypted catalog blocks. All calls run on Dispatchers.IO. */
class CatalogDatabase(context: Context, private val vault: SourceVault) :
    SQLiteOpenHelper(context, "catalog.db", null, 4) {
    private val json = Json { ignoreUnknownKeys = true }
    init { setWriteAheadLoggingEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE entries(source_id TEXT NOT NULL,id TEXT NOT NULL,payload BLOB NOT NULL,ordinal INTEGER NOT NULL,PRIMARY KEY(source_id,id))")
        db.execSQL("CREATE TABLE epg(source_id TEXT NOT NULL,channel_id TEXT NOT NULL,title TEXT NOT NULL,start_ms INTEGER NOT NULL,end_ms INTEGER NOT NULL,description TEXT NOT NULL,PRIMARY KEY(source_id,channel_id,start_ms,end_ms))")
        db.execSQL("CREATE INDEX epg_channel_time ON epg(source_id,channel_id COLLATE NOCASE,start_ms,end_ms)")
        createRecent(db)
        createProfileRecent(db)
        db.execSQL("CREATE TABLE catalogs(source_id TEXT PRIMARY KEY,payload BLOB NOT NULL,updated_ms INTEGER NOT NULL)")
        createXtreamCache(db)
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createRecent(db)
        if (oldVersion < 3) createProfileRecent(db)
        if (oldVersion < 4) createXtreamCache(db)
    }

    private fun createXtreamCache(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS xtream_cache_meta(source_id TEXT NOT NULL, cache_key TEXT NOT NULL, updated_ms INTEGER NOT NULL, PRIMARY KEY(source_id,cache_key))")
        db.execSQL("CREATE TABLE IF NOT EXISTS xtream_cache_chunks(source_id TEXT NOT NULL, cache_key TEXT NOT NULL, ordinal INTEGER NOT NULL, payload BLOB NOT NULL, PRIMARY KEY(source_id,cache_key,ordinal))")
    }

    private fun createRecent(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS recent(source_id TEXT NOT NULL,id TEXT NOT NULL,payload BLOB NOT NULL,updated_ms INTEGER NOT NULL,PRIMARY KEY(source_id,id))")
    }

    private fun createProfileRecent(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS profile_recent(profile_id TEXT NOT NULL,source_id TEXT NOT NULL,id TEXT NOT NULL,payload BLOB NOT NULL,updated_ms INTEGER NOT NULL,PRIMARY KEY(profile_id,source_id,id))")
    }

    fun saveRecent(value: RecentPlayback, profileId: String = "default") {
        require(profileId in setOf("default", "secondary"))
        val table = if (profileId == "default") "recent" else "profile_recent"
        writableDatabase.transaction {
            val where = if (profileId == "default") "source_id=? AND id=?" else "profile_id=? AND source_id=? AND id=?"
            val args = if (profileId == "default") arrayOf(value.entry.sourceId, value.entry.id)
                else arrayOf(profileId, value.entry.sourceId, value.entry.id)
            if (value.positionMs <= 0) delete(table, where, args)
            else insertWithOnConflict(table, null, ContentValues().apply {
                if (profileId != "default") put("profile_id", profileId)
                put("source_id", value.entry.sourceId); put("id", value.entry.id)
                put("payload", vault.encrypt(json.encodeToString(value).encodeToByteArray()))
                put("updated_ms", value.updatedMs)
            }, SQLiteDatabase.CONFLICT_REPLACE)
            if (profileId == "default") execSQL("DELETE FROM recent WHERE rowid NOT IN (SELECT rowid FROM recent ORDER BY updated_ms DESC LIMIT 100)")
            else execSQL("DELETE FROM profile_recent WHERE profile_id=? AND rowid NOT IN (SELECT rowid FROM profile_recent WHERE profile_id=? ORDER BY updated_ms DESC LIMIT 100)", arrayOf(profileId, profileId))
        }
    }

    fun recent(profileId: String = "default"): List<RecentPlayback> {
        require(profileId in setOf("default", "secondary"))
        val query = if (profileId == "default") "SELECT payload FROM recent ORDER BY updated_ms DESC LIMIT 100"
            else "SELECT payload FROM profile_recent WHERE profile_id=? ORDER BY updated_ms DESC LIMIT 100"
        return readableDatabase.rawQuery(query, if (profileId == "default") null else arrayOf(profileId)).use { cursor ->
            buildList { while (cursor.moveToNext()) add(json.decodeFromString<RecentPlayback>(vault.decrypt(cursor.getBlob(0)).decodeToString())) }
        }
    }


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

    /** Encrypted metadata and selected categories, not a second complete playlist. */
    private fun readXtreamCache(sourceId: String, key: String): XtreamCached<String>? {
        val db = readableDatabase
        db.beginTransactionNonExclusive()
        try {
            val updated = db.rawQuery(
                "SELECT updated_ms FROM xtream_cache_meta WHERE source_id=? AND cache_key=?",
                arrayOf(sourceId, key)
            ).use { if (it.moveToFirst()) it.getLong(0) else return null }
            val output = ByteArrayOutputStream()
            val blockCount = db.rawQuery(
                "SELECT payload FROM xtream_cache_chunks WHERE source_id=? AND cache_key=? ORDER BY ordinal",
                arrayOf(sourceId, key)
            ).use { cursor ->
                var count = 0
                while (cursor.moveToNext()) {
                    val block = vault.decrypt(cursor.getBlob(0))
                    if (output.size() + block.size > XtreamCacheCodec.MAX_PAYLOAD_BYTES) return null
                    output.write(block)
                    count++
                }
                count
            }
            if (blockCount == 0) return null
            return XtreamCached(output.toString(Charsets.UTF_8.name()),
                (System.currentTimeMillis() - updated).coerceAtLeast(0))
        } finally { db.endTransaction() }
    }

    private fun writeXtreamCache(sourceId: String, key: String, value: String) {
        val bytes = value.encodeToByteArray()
        if (bytes.size > XtreamCacheCodec.MAX_PAYLOAD_BYTES) return
        val chunks = XtreamCacheCodec.chunks(bytes)
        val db = writableDatabase
        db.transaction {
            delete("xtream_cache_meta", "source_id=? AND cache_key=?", arrayOf(sourceId, key))
            delete("xtream_cache_chunks", "source_id=? AND cache_key=?", arrayOf(sourceId, key))
            insertOrThrow("xtream_cache_meta", null, ContentValues().apply {
                put("source_id", sourceId)
                put("cache_key", key)
                put("updated_ms", System.currentTimeMillis())
            })
            compileStatement("INSERT INTO xtream_cache_chunks(source_id,cache_key,ordinal,payload) VALUES(?,?,?,?)").use { stmt ->
                chunks.forEachIndexed { index, chunk ->
                    stmt.clearBindings()
                    stmt.bindString(1, sourceId)
                    stmt.bindString(2, key)
                    stmt.bindLong(3, index.toLong())
                    stmt.bindBlob(4, vault.encrypt(chunk))
                    stmt.executeInsert()
                }
            }
            // Store at most eight selected groups per source, plus category metadata.
            val keys = rawQuery(
                "SELECT cache_key FROM xtream_cache_meta WHERE source_id=? AND cache_key!='categories' ORDER BY updated_ms DESC,rowid DESC",
                arrayOf(sourceId)
            ).use { cursor -> buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }}
            keys.drop(8).forEach { stale ->
                delete("xtream_cache_meta", "source_id=? AND cache_key=?", arrayOf(sourceId, stale))
                delete("xtream_cache_chunks", "source_id=? AND cache_key=?", arrayOf(sourceId, stale))
            }
        }
    }

    private fun keyForGroup(group: XtreamGroup): String =
        "group/" + group.kind.name + "/" + group.id

    internal fun cachedXtreamGroups(sourceId: String): XtreamCached<List<XtreamGroup>>? =
        readXtreamCache(sourceId, "categories")?.let { item ->
            XtreamCached(json.decodeFromString<List<XtreamGroup>>(item.value), item.ageMillis)
        }

    fun storeXtreamGroups(sourceId: String, groups: List<XtreamGroup>) {
        writeXtreamCache(sourceId, "categories", json.encodeToString(groups))
    }

    internal fun cachedXtreamGroup(sourceId: String, group: XtreamGroup): XtreamCached<Catalog>? =
        readXtreamCache(sourceId, keyForGroup(group))?.let { item ->
            val catalog = json.decodeFromString<Catalog>(item.value)
            if (catalog.entries.any { it.kind != group.kind || it.sourceId != sourceId }) return null
            XtreamCached(catalog, item.ageMillis)
        }

    fun storeXtreamGroup(sourceId: String, group: XtreamGroup, catalog: Catalog) {
        if (catalog.entries.any { it.kind != group.kind || it.sourceId != sourceId }) return
        writeXtreamCache(sourceId, keyForGroup(group), json.encodeToString(catalog))
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
            listOf("entries", "epg", "catalogs", "recent", "profile_recent", "xtream_cache_meta", "xtream_cache_chunks").forEach { db.delete(it, "source_id=?", arrayOf(sourceId)) }
        }
    }
}
