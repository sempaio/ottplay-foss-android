package play.ott.nativeapp.core

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlin.test.*

class StarlinkXtreamTest {
    private fun config(s: MockWebServer) = SourceConfig("m81", "Fixture", SourceKind.XTREAM,
        s.url("/").toString(), "fixture user/+", "fixture?&password")

    @Test fun `account must be active and credential query is encoded`() = runBlocking {
        MockWebServer().use { s ->
            s.start()
            val repo = ProviderRepository()
            for (account in listOf("""{"auth":0,"status":"Disabled"}""", """{"auth":"1","status":"Expired"}""")) {
                s.enqueue(MockResponse().setBody("{\"user_info\":$account}"))
                assertFailsWith<ProviderException> { repo.authenticate(config(s)) }
            }
            s.enqueue(MockResponse().setBody("""{"user_info":{"auth":1,"status":"Active"}}"""))
            repo.authenticate(config(s))
            repeat(3) {
                val request = s.takeRequest()
                assertEquals(config(s).username, request.requestUrl?.queryParameter("username"))
                assertEquals(config(s).password, request.requestUrl?.queryParameter("password"))
                assertNull(request.requestUrl?.queryParameter("action"))
            }
        }
    }

    @Test fun `VOD uses container from details preserving credential path segments`() = runBlocking {
        MockWebServer().use { s ->
            s.start()
            val source = config(s)
            val url = s.url("/").newBuilder().addPathSegment("movie").addPathSegment(source.username)
                .addPathSegment(source.password).addPathSegment("42.mp4").build()
            val entry = MediaEntry("film", source.id, "Film", url.toString(), MediaKind.MOVIE, providerId = "42")
            s.enqueue(MockResponse().setBody("""{"info":{"description":"Fixture"},"movie_data":{"container_extension":"mkv"}}"""))
            val resolved = ProviderRepository().resolve(source, entry)
            assertEquals(listOf("movie", source.username, source.password, "42.mkv"), resolved.url.toHttpUrl().pathSegments)
            val request = s.takeRequest()
            assertEquals("get_vod_info", request.requestUrl?.queryParameter("action"))
            assertEquals("42", request.requestUrl?.queryParameter("vod_id"))
        }
    }

    @Test fun `short EPG accepts empty schedule and decodes programmes`() = runBlocking {
        MockWebServer().use { s ->
            s.start()
            val source = config(s)
            val entry = MediaEntry("live", source.id, "Live", kind = MediaKind.LIVE, providerId = "7")
            val repo = ProviderRepository()
            s.enqueue(MockResponse().setBody("""{"epg_listings":[]}"""))
            assertTrue(repo.loadShortEpg(source, entry).isEmpty())
            s.enqueue(MockResponse().setBody("""{"epg_listings":[{"title":"Tm90aWNpYXM=","description":"Rml4dHVyZQ==","start_timestamp":"100","stop_timestamp":"200"}]}"""))
            val programme = repo.loadShortEpg(source, entry).single()
            assertEquals("Noticias", programme.title)
            assertEquals(100000L, programme.startMillis)
            assertEquals(200000L, programme.endMillis)
            assertEquals("7", s.takeRequest().requestUrl?.queryParameter("stream_id"))
        }
    }

    @Test fun `provider follows 307 without reading redirect body`() = runBlocking {
        MockWebServer().use { s ->
            s.start()
            s.enqueue(MockResponse().setResponseCode(307).setHeader("Location", "/cdn/catalog").setBody("ignored"))
            s.enqueue(MockResponse().setBody("#EXTM3U\n#EXTINF:-1,Fixture\nhttps://example.invalid/live.m3u8\n"))
            val source = SourceConfig("redirect", "Fixture", SourceKind.M3U, s.url("/playlist").toString())
            assertEquals("Fixture", ProviderRepository().load(source).entries.single().name)
            assertEquals("/playlist", s.takeRequest().path)
            assertEquals("/cdn/catalog", s.takeRequest().path)
        }
    }
    @Test fun `M81 pending series never invent episodes or stream paths`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val source = config(server)
            val entry = MediaEntry("pending", source.id, "[24H] Fixture [S07]", kind = MediaKind.SERIES, providerId = "42")
            server.enqueue(MockResponse().setBody("""{"info":{"name":"Fixture"},"seasons":[],"episodes":{},"episode_metadata":"pending"}"""))
            assertTrue(ProviderRepository().loadEpisodes(source, entry).isEmpty())
            val request = server.takeRequest()
            assertEquals("get_series_info", request.requestUrl?.queryParameter("action"))
            assertEquals("42", request.requestUrl?.queryParameter("series_id"))
            assertEquals(1, server.requestCount)
        }
    }

}
