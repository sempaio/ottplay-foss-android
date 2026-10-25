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


    @Test fun `movie detail fetched for a dialog is reused on immediate play only once`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val source = config(server)
            val url = server.url("/").newBuilder().addPathSegment("movie")
                .addPathSegment(source.username).addPathSegment(source.password)
                .addPathSegment("42.mp4").build().toString()
            val movie = MediaEntry("movie-42", source.id, "Movie", url, MediaKind.MOVIE, providerId = "42")
            val repo = ProviderRepository()
            val payload = """{"info":{"description":"Detailed movie"},"movie_data":{"container_extension":"mkv"}}"""
            server.enqueue(MockResponse().setBody(payload))
            val detailed = repo.loadMovieInfo(source, movie)
            assertEquals("Detailed movie", detailed.description)
            assertEquals("42.mkv", detailed.url.toHttpUrl().pathSegments.last())
            assertEquals(detailed.url, repo.resolve(source, detailed).url)
            assertEquals(1, server.requestCount, "Open details then play must not repeat get_vod_info")

            // Consumption is one-shot, not a lasting replacement for fresh signed media URLs.
            server.enqueue(MockResponse().setBody(payload))
            assertEquals(detailed.url, repo.resolve(source, detailed).url)
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun `movie detail reuse is invalidated and never shared with another account`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val source = config(server)
            fun entry(config: SourceConfig) = MediaEntry(
                "same-item", config.id, "Movie",
                server.url("/").newBuilder().addPathSegment("movie")
                    .addPathSegment(config.username).addPathSegment(config.password)
                    .addPathSegment("42.mp4").build().toString(),
                MediaKind.MOVIE, providerId = "42"
            )
            val repo = ProviderRepository()
            server.enqueue(MockResponse().setBody("""{"movie_data":{"container_extension":"mkv"}}"""))
            repo.loadMovieInfo(source, entry(source))

            // Same source ID and movie ID must not bypass another account's credentials.
            val otherAccount = source.copy(password = "other-account-password")
            server.enqueue(MockResponse().setBody("""{"movie_data":{"container_extension":"avi"}}"""))
            val other = repo.resolve(otherAccount, entry(otherAccount))
            assertEquals(listOf("movie", otherAccount.username, otherAccount.password, "42.avi"),
                other.url.toHttpUrl().pathSegments)
            assertEquals(2, server.requestCount)
            assertEquals(source.password, server.takeRequest().requestUrl?.queryParameter("password"))
            assertEquals(otherAccount.password, server.takeRequest().requestUrl?.queryParameter("password"))

            // Source refresh/edit/removal invalidates the short-lived detail.
            server.enqueue(MockResponse().setBody("""{"movie_data":{"container_extension":"mkv"}}"""))
            repo.loadMovieInfo(source, entry(source))
            repo.invalidateMovieDetails(source.id)
            server.enqueue(MockResponse().setBody("""{"movie_data":{"container_extension":"mp4"}}"""))
            val refreshed = repo.resolve(source, entry(source))
            assertEquals("42.mp4", refreshed.url.toHttpUrl().pathSegments.last())
            assertEquals(4, server.requestCount)
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


    @Test fun xtreamSeasonArraysProduceSameEpisodesAsGroupedObjects() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val source = config(server)
            val entry = MediaEntry("series", source.id, "Fixture", kind = MediaKind.SERIES, providerId = "6017")
            val grouped = """{"episodes":{"1":[{"id":"801","episode_num":1,"title":"Pilot","container_extension":"mp4"}]}}"""
            val listOfSeasons = """{"episodes":[{"season_number":1,"episodes":[{"id":"801","episode_num":1,"title":"Pilot","container_extension":"mp4"}]}]}"""
            val flat = """{"episodes":[{"season":1,"id":"801","episode_num":1,"title":"Pilot","container_extension":"mp4"}]}"""
            val fromSeasons = """{"episodes":{},"seasons":[{"season_number":1,"episodes":[{"id":"801","episode_num":1,"title":"Pilot","container_extension":"mp4"}]}]}"""
            val repo = ProviderRepository()
            val signatures = listOf(grouped, listOfSeasons, flat, fromSeasons).map { response ->
                server.enqueue(MockResponse().setBody(response))
                repo.loadEpisodes(source, entry).map { Triple(it.providerId, it.season, it.episode) }
            }
            assertEquals(listOf(Triple("801", 1, 1)), signatures.first())
            signatures.forEach { assertEquals(signatures.first(), it) }
            repeat(4) {
                val request = server.takeRequest()
                assertEquals("get_series_info", request.requestUrl?.queryParameter("action"))
                assertEquals("6017", request.requestUrl?.queryParameter("series_id"))
            }
        }
    }

    @Test fun pendingSeriesDoNotInventEpisodesAndMalformedArraysFailVisibly() {
        val pending = """{"info":{"name":"Pending"},"episodes":{},"seasons":[{"season_number":1}]}"""
        assertEquals(pending, normalizeXtreamEpisodeRows(parseProviderJson(pending)).toString())
        assertFailsWith<ProviderException> {
            normalizeXtreamEpisodeRows(parseProviderJson(
                """{"episodes":[{"season_number":1,"episodes":[{"title":"Missing identifier","episode_num":1}]}]}"""
            ))
        }
        // A legacy flat list with no season still belongs to the shared core.
        val legacy = """{"episodes":[{"id":"801","episode_num":1,"title":"Season unspecified"}]}"""
        assertEquals(legacy, normalizeXtreamEpisodeRows(parseProviderJson(legacy)).toString())
    }

}
