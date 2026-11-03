package play.ott.nativeapp.core

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.*

/** A Starlink Xtream launch must not request a full stream catalog. */
class StarlinkOnDemandTest {
    @Test fun initialCategoriesFetchNeverDownloadsStreams() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val config = SourceConfig("managed", "STARLINK", SourceKind.XTREAM,
                server.url("/").toString(), "user fixture", "password fixture")
            server.enqueue(MockResponse().setBody("""{"user_info":{"auth":1,"status":"Active"}}"""))
            server.enqueue(MockResponse().setBody("""[{"category_id":"live-a","category_name":"Noticias"}]"""))
            server.enqueue(MockResponse().setBody("""[{"category_id":"vod-a","category_name":"Filmes"}]"""))
            server.enqueue(MockResponse().setBody("""[{"category_id":"series-a","category_name":"Series"}]"""))
            val repo = ProviderRepository()
            val groups = repo.xtreamCategories(config)
            assertEquals(listOf(MediaKind.LIVE, MediaKind.MOVIE, MediaKind.SERIES), groups.map { it.kind })
            assertEquals(4, server.requestCount)
            val requests = (1..4).map { server.takeRequest().requestUrl }
            assertNull(requests[0]?.queryParameter("action"))
            assertEquals(
                listOf("get_live_categories", "get_vod_categories", "get_series_categories"),
                requests.drop(1).map { it?.queryParameter("action") })
            assertTrue(requests.all { it?.queryParameter("category_id") == null })

            server.enqueue(MockResponse().setBody(
                """[{"stream_id":41,"name":"Canal Noticias","category_id":"live-a",
                    "container_extension":"m3u8","stream_icon":"https://images.example/logo.png",
                    "epg_channel_id":"channel-41"}]"""))
            val loaded = repo.xtreamCategory(config, groups[0])
            assertEquals(1, loaded.entries.size)
            assertEquals("Canal Noticias", loaded.entries.single().name)
            assertEquals("Noticias", loaded.entries.single().group)
            assertEquals("41", loaded.entries.single().providerId)
            assertEquals("channel-41", loaded.entries.single().epgId)
            assertEquals(5, server.requestCount)
            val request = server.takeRequest()
            assertEquals("get_live_streams", request.requestUrl?.queryParameter("action"))
            assertEquals("live-a", request.requestUrl?.queryParameter("category_id"))
            assertEquals(listOf("live",config.username,config.password,"41.m3u8"),
                loaded.entries.single().url.toHttpUrl().pathSegments)
        }
    }

    @Test fun vodAndSeriesOnlyFetchSelectedCategory() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val config = SourceConfig("managed", "STARLINK", SourceKind.XTREAM,
                server.url("/").toString(), "user", "password")
            val repo = ProviderRepository()
            server.enqueue(MockResponse().setBody(
                """[{"stream_id":9,"name":"Filme","category_id":"vod","container_extension":"mkv"}]"""))
            val movie = repo.xtreamCategory(config, XtreamGroup("vod", "Filmes", MediaKind.MOVIE)).entries.single()
            assertEquals(MediaKind.MOVIE, movie.kind)
            assertEquals("9", movie.providerId)
            assertTrue(movie.url.endsWith("/9.mkv"))
            assertEquals("get_vod_streams", server.takeRequest().requestUrl?.queryParameter("action"))

            server.enqueue(MockResponse().setBody(
                """[{"series_id":7,"name":"Serie","category_id":"s1","cover":"https://images.example/cover.png"}]"""))
            val series = repo.xtreamCategory(config, XtreamGroup("s1", "Series", MediaKind.SERIES)).entries.single()
            assertEquals(MediaKind.SERIES, series.kind)
            assertEquals("7", series.providerId)
            assertTrue(series.url.isBlank())
            val req = server.takeRequest()
            assertEquals("get_series", req.requestUrl?.queryParameter("action"))
            assertEquals("s1", req.requestUrl?.queryParameter("category_id"))
            assertEquals(2, server.requestCount)
        }
    }
}
