package play.ott.nativeapp.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import play.ott.nativeapp.core.*
import play.ott.nativeapp.playback.PlaybackTestApplication
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = PlaybackTestApplication::class)
class EpgRefreshMigrationTest {
    private lateinit var repo: NativeRepository
    private lateinit var vault: SourceVault
    private lateinit var db: CatalogDatabase
    private val source = SourceConfig("source", "Fixture", SourceKind.M3U, "https://provider.invalid/list.m3u")
    private val old = Programme("a", "Old", 10, 20, "Old description")

    @Before fun prepareEncryptedStorage() {
        val context: Context = ApplicationProvider.getApplicationContext()
        repo = NativeRepository(context, RemoteTransportPolicy.HTTP_COMPATIBLE)
        vault = ReflectionHelpers.getField(repo, "vault")
        db = ReflectionHelpers.getField(repo, "db")
        val key = KeyGenerator.getInstance("AES").apply { init(128) }.generateKey()
        ReflectionHelpers.setField(vault, "key\$delegate", lazy { key })
        // Substitute only the hardware AES key: real encrypted storage and SQLite still run.
        vault.write(listOf(source))
        db.replaceEpg(source.id, listOf(old))
    }

    @After fun closeStorage() { db.close() }

    private fun rows(id: String = source.id): List<List<String>> = db.readableDatabase.rawQuery(
            "SELECT channel_id,title,start_ms,end_ms,description FROM epg WHERE source_id=? ORDER BY channel_id,start_ms,end_ms,title", arrayOf(id)
        ).use { cursor -> buildList { while (cursor.moveToNext()) add((0..4).map { cursor.getString(it) }) } }
    private fun xml(title: String = "Fresh", channel: String = "a", start: String = "20260914110000", stop: String = "20260914120000") =
            "<programme channel='$channel' start='$start +0000' stop='$stop +0000'><title>$title</title><desc>Description $title</desc></programme>"

    private fun response(body: String) = MockResponse().setBody(body)

    @Test fun capturedRepositoryAndDatabaseContractsRemainUnchanged() = runBlocking {
        // Captured on 0be1caded9963f3ee3ca8ed0f54674e895696716 before extracting refresh policy.
        val results = mutableListOf<JsonObject>()
        suspend fun run(name: String, action: suspend () -> Pair<Throwable?, List<String>>) {
            db.writableDatabase.execSQL("DROP TRIGGER IF EXISTS fail_epg_insert")
            db.delete(source.id); db.delete("other")
            vault.write(listOf(source)); db.replaceEpg(source.id, listOf(old))
            val (error, requests) = action()
            results.add(buildJsonObject {
                put("name", name); put("rows", JsonArray(rows().map { row -> JsonArray(row.map(::JsonPrimitive)) }))
                put("otherRows", JsonArray(rows("other").map { row -> JsonArray(row.map(::JsonPrimitive)) }))
                put("requests", JsonArray(requests.map(::JsonPrimitive)))
                put("sourcePresent", repo.sources().any { it.id == source.id })
                if (error != null) put("error", buildJsonObject { put("type", error.javaClass.name); put("message", error.message.orEmpty()) })
            })
        }
        suspend fun network(responses: List<MockResponse>): Pair<Throwable?, List<String>> =
            MockWebServer().use { server ->
                responses.forEach(server::enqueue); server.start()
                val paths = responses.indices.map { "/guide$it" }
                val error = try { repo.refreshEpg(source, Catalog(emptyList(), paths.map { server.url(it).toString() })); null } catch (error: Throwable) { error }
                error to (0 until server.requestCount).map { server.takeRequest().path.orEmpty() }
            }

        run("no URLs preserves old rows") { repo.refreshEpg(source, Catalog(emptyList())); null to emptyList() }
        run("single successful guide replaces old rows") { network(listOf(response("<tv>${xml()}</tv>"))) }
        run("successful empty guide clears old rows") { network(listOf(response("<tv/>"))) }
        run("first HTTP failure stops before later URL and retains old rows") { network(listOf(MockResponse().setResponseCode(503), response("<tv>${xml()}</tv>"))) }
        run("later HTTP failure discards prior successful guide") { network(listOf(response("<tv>${xml()}</tv>"), MockResponse().setResponseCode(503))) }
        run("later malformed XML discards prior successful guide") { network(listOf(response("<tv>${xml()}</tv>"), response("<tv><channel"))) }
        run("later source owns duplicate exact interval") { network(listOf(response("<tv>${xml("First")}</tv>"), response("<tv>${xml("Last")}</tv>"))) }
        run("first duplicate within one guide survives parser dedup") { network(listOf(response("<tv>${xml("First")}${xml("Last")}</tv>"))) }
        run("different intervals from sources are merged") { network(listOf(response("<tv>${xml("First")}</tv>"), response("<tv>${xml("Later", stop="20260914130000")}</tv>"))) }
        run("channel ID case remains distinct in SQLite primary key") { network(listOf(response("<tv>${xml("Lower", "a")}${xml("Upper", "A")}</tv>"))) }
        run("replacement does not affect other source") { db.replaceEpg("other", listOf(old.copy(title="Other"))); network(listOf(response("<tv>${xml()}</tv>"))) }
        suspend fun race(mode: String): Pair<Throwable?, List<String>> = MockWebServer().use { server ->
            val arrived = CountDownLatch(1); val release = CountDownLatch(1)
            server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest): MockResponse {
                arrived.countDown(); check(release.await(10, TimeUnit.SECONDS)); return response("<tv>${xml()}</tv>")
            }}
            server.start()
            val job = async(Dispatchers.IO) { try { repo.refreshEpg(source, Catalog(emptyList(), listOf(server.url("/delayed").toString()))); null } catch (error: Throwable) { error } }
            check(arrived.await(10, TimeUnit.SECONDS))
            try {
                when(mode) {
                    "edit" -> { repo.save(source.copy(name="Edited")); db.replaceEpg(source.id, listOf(old.copy(title="Edited state"))) }
                    "delete" -> repo.remove(source.id)
                    "equal" -> repo.save(source.copy())
                    "restore" -> { repo.save(source.copy(name="Temporary")); repo.save(source.copy()) }
                }
            } finally { release.countDown() }
            job.await() to listOf(server.takeRequest().path.orEmpty())
        }
        run("source edited before commit silently skips stale write") { race("edit") }
        run("source deleted before commit remains deleted") { race("delete") }
        run("equal source copy before commit permits replacement") { race("equal") }
        run("source changed and restored before commit permits replacement") { race("restore") }
        run("database insertion failure rolls back delete and earlier inserts") {
            db.writableDatabase.execSQL("CREATE TRIGGER fail_epg_insert BEFORE INSERT ON epg WHEN NEW.title='Reject' BEGIN SELECT RAISE(ABORT, 'fixture reject'); END")
            val error = try { db.replaceEpg(source.id, listOf(old.copy(title="Intermediate", startMillis=30), old.copy(title="Reject"))); null } catch(error:Throwable) { error }
            error to emptyList()
        }
        run("direct empty replacement clears only selected source") { db.replaceEpg("other", listOf(old)); db.replaceEpg(source.id, emptyList()); null to emptyList() }
        run("direct duplicate replacement gives last row ownership") { db.replaceEpg(source.id, listOf(old.copy(title="First"),old.copy(title="Last"))); null to emptyList() }
        check(results.size == 18)
        val expected = requireNotNull(javaClass.getResource("/epg-refresh-before-core.json")).readText()
        assertEquals(Json.parseToJsonElement(expected), JsonArray(results))
    }

    @Test fun cancellationDuringFetchRetainsRowsAndStopsBeforeTheNextUrl() = runBlocking {
        MockWebServer().use { server ->
            val arrived = CountDownLatch(1)
            val release = CountDownLatch(1)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    arrived.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                    return response("<tv>${xml()}</tv>")
                }
            }
            server.start()
            val failure = AtomicReference<Throwable?>()
            val job = launch(Dispatchers.IO) {
                try {
                    repo.refreshEpg(source, Catalog(emptyList(), listOf("/first", "/unrequested").map { server.url(it).toString() }))
                } catch (error: Throwable) {
                    failure.set(error)
                    throw error
                }
            }
            val cancellation = CancellationException("Refresh cancelled by fixture")
            try {
                assertTrue(arrived.await(10, TimeUnit.SECONDS))
                job.cancel(cancellation)
                withTimeout(10_000) { job.join() }
                assertTrue(job.isCancelled)
                assertTrue(failure.get() is CancellationException)
                assertEquals(cancellation.message, failure.get()?.message)
                assertEquals(listOf(listOf("a", "Old", "10", "20", "Old description")), rows())
                assertEquals(1, server.requestCount)
            } finally {
                release.countDown()
                job.cancelAndJoin()
            }
        }
    }

    @Test fun concurrentRefreshesFetchOutsideTheLockAndCommitInCompletionOrder() = runBlocking {
        MockWebServer().use { server ->
            val firstArrived = CountDownLatch(1)
            val secondArrived = CountDownLatch(1)
            val releaseFirst = CountDownLatch(1)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path == "/first") {
                        firstArrived.countDown()
                        check(releaseFirst.await(10, TimeUnit.SECONDS))
                        return response("<tv>${xml("First")}</tv>")
                    }
                    secondArrived.countDown()
                    return response("<tv>${xml("Second")}</tv>")
                }
            }
            server.start()
            val first = async(Dispatchers.IO) {
                repo.refreshEpg(source, Catalog(emptyList(), listOf(server.url("/first").toString())))
            }
            try {
                assertTrue(firstArrived.await(10, TimeUnit.SECONDS))
                val second = async(Dispatchers.IO) {
                    repo.refreshEpg(source, Catalog(emptyList(), listOf(server.url("/second").toString())))
                }
                assertTrue(secondArrived.await(10, TimeUnit.SECONDS))
                withTimeout(10_000) { second.await() }
                assertEquals("Second", rows().single()[1])
                releaseFirst.countDown()
                withTimeout(10_000) { first.await() }
                assertEquals("First", rows().single()[1])
                assertEquals(2, server.requestCount)
            } finally {
                releaseFirst.countDown()
                first.cancelAndJoin()
            }
        }
    }

    @Test fun databaseFailureDuringRefreshPropagatesAndRollsBackTheWholeReplacement() = runBlocking {
        db.writableDatabase.execSQL("CREATE TRIGGER fail_epg_insert BEFORE INSERT ON epg WHEN NEW.title='Reject' BEGIN SELECT RAISE(ABORT, 'fixture reject'); END")
        MockWebServer().use { server ->
            server.enqueue(response("<tv>${xml("Intermediate")}${xml("Reject", start = "20260914120000", stop = "20260914130000")}</tv>"))
            server.start()
            val error = try {
                repo.refreshEpg(source, Catalog(emptyList(), listOf(server.url("/guide").toString())))
                null
            } catch (error: Throwable) { error }
            assertTrue(error is android.database.sqlite.SQLiteConstraintException)
            assertEquals("fixture reject (code 1811 SQLITE_CONSTRAINT_TRIGGER)", error?.message)
            assertEquals(listOf(listOf("a", "Old", "10", "20", "Old description")), rows())
            assertEquals(1, server.requestCount)
        }
    }
}
