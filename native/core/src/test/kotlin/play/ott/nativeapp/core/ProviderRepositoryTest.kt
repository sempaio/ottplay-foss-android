package play.ott.nativeapp.core

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.HttpUrl.Companion.toHttpUrl

class ProviderRepositoryTest {
    @Test fun `local playlist has explicit provenance and scopes entry override case insensitively`() = runBlocking {
        val source = SourceConfig("local", "Local", SourceKind.M3U, "content://documents/list",
            headers = mapOf("Authorization" to "unbound-source-secret", "Cookie" to "unbound-cookie"))
        val entry = M3uParser.parse("""
            #EXTM3U
            #EXTINF:-1,Channel
            #EXTHTTP:{"authorization":"explicit-entry-secret"}
            https://cdn.invalid/live.m3u8
        """.trimIndent(), source).entries.single()
        assertFalse(entry.needsHeaderOriginRefresh())
        assertEquals(mapOf("authorization" to "https://cdn.invalid/"), entry.headerOrigins)
        assertEquals(mapOf("authorization" to "explicit-entry-secret"), ProviderRepository().resolve(source, entry).headers)
        val unbound = M3uParser.parse("#EXTM3U\n#EXTINF:-1,Channel\nhttps://cdn.invalid/live.m3u8", source).entries.single()
        assertFalse(unbound.needsHeaderOriginRefresh())
        assertEquals(emptyMap(), unbound.headerOrigins)
        assertEquals(emptyMap(), ProviderRepository().resolve(source, unbound).headers)
    }

    @Test fun `locally imported playlist resolves its absolute stream without treating content URI as HTTP`() = runBlocking {
        val source = SourceConfig("local", "File", SourceKind.M3U, "content://documents/my-list")
        val entry = M3uParser.parse("#EXTM3U\n#EXTINF:-1,One\nhttps://media.example.test/one.m3u8", source).entries.single()
        assertEquals(entry.url, ProviderRepository().resolve(source, entry).url)
    }

    @Test fun `Xtream requests live VOD and series APIs with encoded credentials and resolves episodes`() = runBlocking {
        MockWebServer().use { server ->
            val requests = mutableListOf<RecordedRequest>()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request
                    val body = when (request.requestUrl?.queryParameter("action")) {
                        null -> """{"user_info":{"auth":1,"status":"Active"},"server_info":{"timezone":"Europe/Berlin"}}"""
                        "get_live_categories" -> """[{"category_id":"10","category_name":"News"}]"""
                        "get_live_streams" -> """[{"stream_id":42,"name":"World","category_id":"10","epg_channel_id":"world.xmltv","stream_icon":"/world.png","tv_archive":1,"tv_archive_duration":7}]"""
                        "get_vod_categories" -> """[{"category_id":"20","category_name":"Movies"}]"""
                        "get_vod_streams" -> """[{"stream_id":"24","name":"Film","category_id":"20","container_extension":"mkv"}]"""
                        "get_series_categories" -> """[{"category_id":"30","category_name":"Series"}]"""
                        "get_series" -> """[{"series_id":7,"name":"A show","category_id":"30","cover":"/cover.jpg"}]"""
                        "get_series_info" -> """{"episodes":{"2":[{"id":"902","title":"Second season","episode_num":1,"container_extension":"mp4"}],"1":[{"id":"901","title":"Pilot","episode_num":1,"container_extension":"mkv","info":{"plot":"A beginning"}}]}}"""
                        else -> return MockResponse().setResponseCode(404)
                    }
                    return MockResponse().setBody(body)
                }
            }
            server.start()
            val config = SourceConfig("x", "Xtream", SourceKind.XTREAM, server.url("/nested/player_api.php?old=removed").toString(),
                username = "a/b &+", password = "p&?/# +")
            val repository = ProviderRepository()
            val catalog = repository.load(config)
            assertEquals(listOf(MediaKind.LIVE, MediaKind.MOVIE, MediaKind.SERIES), catalog.entries.map { it.kind })
            val live = catalog.entries[0]
            assertEquals("world.xmltv", live.epgId)
            assertEquals("News", live.group)
            assertEquals("Europe/Berlin", live.catchup?.timeZone)
            assertEquals(listOf("nested", "live", config.username, config.password, "42.m3u8"), live.url.toHttpUrl().pathSegments)
            assertEquals("24.mkv", repository.resolve(config, catalog.entries[1]).url.toHttpUrl().pathSegments.last())
            val episodes = repository.loadEpisodes(config, catalog.entries[2])
            assertEquals(listOf("Pilot", "Second season"), episodes.map { it.name })
            assertEquals(listOf(1, 2), episodes.map { it.season })
            assertEquals("A beginning", episodes.first().description)
            assertEquals(listOf("nested", "series", config.username, config.password, "901.mkv"), episodes.first().url.toHttpUrl().pathSegments)
            assertEquals("7", requests.last().requestUrl?.queryParameter("series_id"))
            assertTrue(requests.all { it.requestUrl?.queryParameter("username") == config.username && it.requestUrl?.queryParameter("password") == config.password })
            assertTrue(requests.all { it.requestUrl?.encodedPath == "/nested/player_api.php" })
            assertEquals(config.password, catalog.epgUrls.single().toHttpUrl().queryParameter("password"))
            assertFalse(config.toString().contains(config.password))
            assertFalse(live.toString().contains(config.password))
        }
    }

    @Test fun `Xtream authentication failure does not masquerade as an empty catalogue`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"user_info":{"auth":0}}""")); server.start()
            val config = SourceConfig("x", "X", SourceKind.XTREAM, server.url("/").toString(), "secret-name", "secret-password")
            val result = runCatching { ProviderRepository().load(config) }
            assertTrue(result.exceptionOrNull() is ProviderException)
            assertFalse(result.exceptionOrNull()?.message.orEmpty().contains("secret-password"))
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun `legacy combined Xtream response loads live and reports explicitly unsupported optional sections`() = runBlocking {
        MockWebServer().use { server ->
            val actions = mutableListOf<String?>()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val action = request.requestUrl?.queryParameter("action")
                    actions += action
                    return when (action) {
                        null -> MockResponse().setBody("""{"categories":[{"category_id":9,"category_name":"Legacy category"}],"live_streams":[{"stream_id":17,"name":"Legacy live","category_id":9}]}""")
                        "get_vod_streams" -> MockResponse().setResponseCode(404)
                        "get_series" -> MockResponse().setResponseCode(501)
                        else -> MockResponse().setResponseCode(500)
                    }
                }
            }
            server.start()
            val config = SourceConfig("legacy", "Legacy", SourceKind.XTREAM, server.url("/").toString(), "u", "p")
            val catalog = ProviderRepository().load(config)
            assertEquals("Legacy live", catalog.entries.single().name)
            assertEquals("Legacy category", catalog.entries.single().group)
            assertEquals(2, catalog.notes.size)
            assertTrue(catalog.messages.any { it.key == CoreMessageKey.XTREAM_SECTION_UNAVAILABLE && it.args == listOf("MOVIE", "404") })
            assertTrue(catalog.messages.any { it.key == CoreMessageKey.XTREAM_SECTION_UNAVAILABLE && it.args == listOf("SERIES", "501") })
            assertEquals(listOf(null, "get_vod_streams", "get_series"), actions)
        }
    }

    @Test fun `missing category endpoint keeps streams with a note while optional auth and server failures remain fatal`() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl?.queryParameter("action")) {
                    null -> MockResponse().setBody("""{"user_info":{"auth":1}}""")
                    "get_live_streams" -> MockResponse().setBody("""[{"stream_id":1,"name":"One"}]""")
                    "get_live_categories" -> MockResponse().setResponseCode(405)
                    "get_vod_streams", "get_series" -> MockResponse().setResponseCode(404)
                    else -> MockResponse().setResponseCode(500)
                }
            }
            server.start()
            val config = SourceConfig("missing", "Missing", SourceKind.XTREAM, server.url("/").toString(), "u", "p")
            val catalog = ProviderRepository().load(config)
            assertEquals("One", catalog.entries.single().name)
            assertTrue(catalog.messages.any { it.key == CoreMessageKey.XTREAM_GROUPS_UNAVAILABLE && it.args.last() == "405" })
        }
        for (code in listOf(401, 403, 500)) MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"live_streams":[{"stream_id":1,"name":"One"}],"categories":[]}"""))
            server.enqueue(MockResponse().setResponseCode(code)); server.start()
            val config = SourceConfig("fatal", "Fatal", SourceKind.XTREAM, server.url("/").toString(), "u", "p")
            val error = runCatching { ProviderRepository().load(config) }.exceptionOrNull()
            assertTrue(error is ProviderException)
            assertEquals(code, error.statusCode)
        }
    }

    @Test fun `Stalker paginates channels uses bearer MAC and resolves the original command`() = runBlocking {
        MockWebServer().use { server ->
            val requests = mutableListOf<RecordedRequest>()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request
                    val body = when (request.requestUrl?.queryParameter("action")) {
                        "handshake" -> """{"js":{"token":"session-token"}}"""
                        "get_profile" -> """{"js":{"id":1}}"""
                        "get_genres" -> """{"js":[{"id":"10","title":"News"}]}"""
                        "get_ordered_list" -> if (request.requestUrl?.queryParameter("p") == "1")
                            """{"js":{"total_items":"2","max_page_items":"1","data":[{"id":100,"name":"One","cmd":"ffmpeg http://localhost/ch/100_?x=1&y=2","tv_genre_id":"10","xmltv_id":"one","logo":"/logos/one.png"}]}}"""
                        else """{"js":{"total_items":2,"max_page_items":1,"data":[{"id":200,"name":"Two","cmd":"ffmpeg http://localhost/ch/200_","tv_genre_id":"10"}]}}"""
                        "create_link" -> """{"js":{"cmd":"ffmpeg https://media.example.test/live/one.m3u8?token=play-token"}}"""
                        else -> return MockResponse().setResponseCode(404)
                    }
                    return MockResponse().setBody(body)
                }
            }
            server.start()
            val config = SourceConfig("s", "Portal", SourceKind.STALKER, server.url("/stalker_portal/c/").toString(), mac = "00:1a:79:01:02:03")
            val repo = ProviderRepository()
            val entries = repo.load(config).entries
            assertEquals(listOf("One", "Two"), entries.map { it.name })
            assertEquals("News", entries.first().group)
            assertEquals("one", entries.first().epgId)
            val stream = repo.resolve(config, entries.first())
            assertEquals("https://media.example.test/live/one.m3u8?token=play-token", stream.url)
            assertNull(stream.headers["Authorization"])
            assertEquals(entries.first().url, requests.last().requestUrl?.queryParameter("cmd"))
            assertTrue(requests.all { it.requestUrl?.encodedPath == "/stalker_portal/server/load.php" })
            assertTrue(requests.all { it.getHeader("Cookie")?.contains("mac=00:1A:79:01:02:03") == true })
            assertNull(requests.first().getHeader("Authorization"))
            assertTrue(requests.drop(1).all { it.getHeader("Authorization") == "Bearer session-token" })
            assertEquals(1, requests.count { it.requestUrl?.queryParameter("action") == "handshake" })
        }
    }

    @Test fun `Stalker retries expired bearer once and rejects repeating pagination`() = runBlocking {
        MockWebServer().use { server ->
            val handshakes = AtomicInteger()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl?.queryParameter("action")) {
                    "handshake" -> MockResponse().setBody("""{"js":{"token":"token-${handshakes.incrementAndGet()}"}}""")
                    "get_profile" -> if (request.getHeader("Authorization") == "Bearer token-1") MockResponse().setResponseCode(401)
                        else MockResponse().setBody("""{"js":{}}""")
                    "get_genres" -> MockResponse().setBody("""{"js":[]}""")
                    "get_ordered_list" -> MockResponse().setBody("""{"js":{"total_items":2,"data":[{"id":1,"name":"Repeated","cmd":"http://localhost/ch/1"}]}}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            val config = SourceConfig("s", "Portal", SourceKind.STALKER, server.url("/portal.php").toString(), mac = "00:1a:79:01:02:03")
            val result = runCatching { ProviderRepository().load(config) }
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("repeated"))
            assertEquals(2, handshakes.get())
        }
    }

    @Test fun `FOSS JSON RPC endpoint preserves that dialect and synthesizes its documented stream path`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"result":{"token":"ok"}}"""))
            server.enqueue(MockResponse().setBody("""{"result":{"channels":[{"id":7,"name":"Seven","categories":["News","Second"],"tv_icon":"/tv-icon.png"}]}}"""))
            server.start()
            val config = SourceConfig("rpc", "RPC", SourceKind.STALKER, server.url("/stalker_portal/api/").toString(), mac = "00:1a:79:01:02:03")
            val repo = ProviderRepository()
            val channel = repo.load(config).entries.single()
            assertEquals("/stalker_portal/stream/7.m3u8", channel.url.toHttpUrl().encodedPath)
            assertEquals(config.mac, channel.url.toHttpUrl().queryParameter("mac"))
            assertEquals("News", channel.group)
            assertEquals(server.url("/tv-icon.png").toString(), channel.logo)
            assertEquals(channel.url, repo.resolve(config, channel).url)
            assertEquals("POST", server.takeRequest().method)
            assertTrue(server.takeRequest().body.readUtf8().contains("get_channels"))
        }
    }

    @Test fun `false FOSS RPC handshake cannot be reported as successful authentication`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"result":false}""")); server.start()
            val config = SourceConfig("rpc", "RPC", SourceKind.STALKER, server.url("/stalker_portal/api/").toString(), mac = "00:1a:79:01:02:03")
            val error = runCatching { ProviderRepository().load(config) }.exceptionOrNull()
            assertTrue(error is ProviderException)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun `source redirects resolve relative media from final location and strip custom secrets cross origin`() = runBlocking {
        MockWebServer().use { original -> MockWebServer().use { destination ->
            destination.enqueue(MockResponse().setBody("#EXTM3U\n#EXTINF:-1,One\nmedia/one.m3u8")); destination.start()
            original.enqueue(MockResponse().setResponseCode(302).setHeader("Location", destination.url("/nested/list.m3u"))); original.start()
            val config = SourceConfig("redirect", "Redirect", SourceKind.M3U, original.url("/list").toString(),
                headers = mapOf("Authorization" to "Bearer secret", "Cookie" to "id=secret", "X-Token" to "secret", "User-Agent" to "My agent"))
            val catalog = ProviderRepository().load(config)
            assertEquals(destination.url("/nested/media/one.m3u8").toString(), catalog.entries.single().url)
            val request = destination.takeRequest()
            assertNull(request.getHeader("Authorization")); assertNull(request.getHeader("Cookie")); assertNull(request.getHeader("X-Token"))
            assertEquals("My agent", request.getHeader("User-Agent"))
            val stream = ProviderRepository().resolve(config, catalog.entries.single())
            assertNull(stream.headers["Authorization"]); assertNull(stream.headers["Cookie"]); assertNull(stream.headers["X-Token"])
            assertEquals("My agent", stream.headers["User-Agent"])
        } }
    }

    @Test fun `cancelling catalogue load cancels and releases the underlying HTTP call`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)); server.start()
            val client = OkHttpClient.Builder().build()
            val config = SourceConfig("cancel", "Cancel", SourceKind.M3U, server.url("/wait").toString())
            val job = launch { ProviderRepository(client).load(config) }
            // takeRequest blocks, so let the launched coroutine dispatch to IO first.
            delay(50)
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            job.cancelAndJoin()
            withTimeout(5000) { while (client.dispatcher.runningCallsCount() != 0) delay(10) }
            assertTrue(job.isCancelled)
        }
    }

    @Test fun `oversized response is rejected before reading its body`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("x").setHeader("Content-Length", MAX_CATALOG_BYTES + 1)); server.start()
            val result = runCatching { ProviderRepository().load(SourceConfig("large", "Large", SourceKind.M3U, server.url("/big").toString())) }
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("size limit"))
        }
    }
}
