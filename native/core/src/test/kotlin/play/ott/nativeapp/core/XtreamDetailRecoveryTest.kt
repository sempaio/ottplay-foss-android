package play.ott.nativeapp.core

import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlin.test.*

class XtreamDetailRecoveryTest {
    private val source = SourceConfig("fixture", "Fixture", SourceKind.XTREAM,
        "https://fixture.invalid", "synthetic-user", "synthetic-password",
        headers = mapOf("X-Test-Account" to "synthetic"))
    private val movie = MediaEntry("movie-7", source.id, "Fixture movie",
        "https://fixture.invalid/movie/synthetic-user/synthetic-password/7.mp4", MediaKind.MOVIE, providerId = "7")
    private val series = MediaEntry("series-8", source.id, "Fixture series", kind = MediaKind.SERIES, providerId = "8")
    private val live = MediaEntry("live-9", source.id, "Fixture live", kind = MediaKind.LIVE, providerId = "9")

    @Test fun `shared source artwork is hidden only for different titles`() {
        val same = "https://fixture.invalid/shared.jpg"
        val entries = listOf(movie.copy(logo = same), movie.copy(id = "movie-8", name = "Other", logo = same),
            movie.copy(id = "movie-9", logo = "https://fixture.invalid/unique.jpg"))
        val result = XtreamProvider(ProviderHttp(OkHttpClient(), RemoteTransportPolicy(false))).withoutSharedArtwork(entries)
        assertEquals("", result[0].logo)
        assertEquals("", result[1].logo)
        assertEquals("https://fixture.invalid/unique.jpg", result[2].logo)
    }

    private fun exercise(operation: String, failures: Int = 1, status: Int? = null,
        tls: Boolean = false, block: suspend (ProviderRepository, AtomicInteger) -> Unit) = runBlocking {
        val calls = AtomicInteger()
        val client = OkHttpClient.Builder().retryOnConnectionFailure(false).addInterceptor { chain ->
            val request = chain.request()
            assertEquals(operation, request.url.queryParameter("action").orEmpty())
            assertEquals(source.username, request.url.queryParameter("username"))
            assertEquals(source.password, request.url.queryParameter("password"))
            assertEquals("synthetic", request.header("X-Test-Account"))
            val attempt = calls.incrementAndGet()
            if (attempt <= failures && status == null) {
                if (tls) throw SSLHandshakeException("private-value")
                throw SocketTimeoutException("private-value")
            }
            val body = when (operation) {
                "get_vod_info" -> """{"movie_data":{"container_extension":"mp4"},"info":{"description":"Verified"}}"""
                "get_series_info" -> """{"episodes":{"1":[{"id":"81","episode_num":1,"title":"Episode","container_extension":"mp4"}]}}"""
                "get_short_epg" -> """{"epg_listings":[]}"""
                else -> """{"user_info":{"auth":1,"status":"Active"}}"""
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(if (attempt <= failures) status ?: 200 else 200).message("Fixture")
                .body(body.toResponseBody()).build()
        }.build()
        try { block(ProviderRepository(client), calls) }
        finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
    }

    @Test fun `movie detail recovers once without changing identity or playback url`() = exercise("get_vod_info") { repository, calls ->
        val result = repository.loadMovieInfo(source, movie)
        assertEquals("Verified", result.description)
        assertEquals(movie.id, result.id)
        assertEquals(movie.url, result.url)
        assertEquals(2, calls.get())
    }

    @Test fun `pending TMDB detail is fetched again and preserves playback`() = runBlocking {
        val calls = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val attempt = calls.incrementAndGet()
            val body = if (attempt == 1)
                """{"info":{"metadata_pending":true,"description":"","movie_image":""},"movie_data":{"container_extension":"mp4"}}"""
            else """{"info":{"metadata_pending":false,"description":"TMDB synopsis","movie_image":"https://image.tmdb.org/t/p/w500/poster.jpg","poster_source":"tmdb"},"movie_data":{"container_extension":"mp4"}}"""
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("Fixture").body(body.toResponseBody()).build()
        }.build()
        try {
            val result = ProviderRepository(client).loadMovieInfo(source, movie.copy(logo = ""))
            assertEquals(2, calls.get())
            assertEquals("TMDB synopsis", result.description)
            assertEquals("https://image.tmdb.org/t/p/w500/poster.jpg", result.logo)
            assertEquals(movie.url, result.url)
        } finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
    }

    @Test fun `series detail brings synopsis and TMDB cover to dialog`() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val body = """{"info":{"plot":"Series synopsis","cover":"https://image.tmdb.org/t/p/w500/series.jpg","poster_source":"tmdb"}}"""
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("Fixture").body(body.toResponseBody()).build()
        }.build()
        try {
            val result = ProviderRepository(client).loadSeriesInfo(source, series)
            assertEquals("Series synopsis", result.description)
            assertEquals("https://image.tmdb.org/t/p/w500/series.jpg", result.logo)
            assertEquals(series.id, result.id)
        } finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
    }

    @Test fun `movie playback resolution recovers without fetching the video`() = exercise("get_vod_info") { repository, calls ->
        val result = repository.resolve(source, movie)
        assertEquals(movie.url, result.url)
        assertEquals(2, calls.get())
    }

    @Test fun `series detail timeout recovers with real returned episode ids`() = exercise("get_series_info") { repository, calls ->
        val episodes = repository.loadEpisodes(source, series)
        assertEquals("81", episodes.single().providerId)
        assertEquals(1, episodes.single().season)
        assertEquals(1, episodes.single().episode)
        assertEquals(2, calls.get())
    }

    @Test fun `short epg and standalone authentication recover transient timeouts`() {
        exercise("get_short_epg") { repository, calls ->
            assertTrue(repository.loadShortEpg(source, live).isEmpty()); assertEquals(2, calls.get())
        }
        exercise("") { repository, calls -> repository.authenticate(source); assertEquals(2, calls.get()) }
    }

    @Test fun `persistent detail timeout is bounded and sanitized`() = exercise("get_vod_info", failures = 100) { repository, calls ->
        val error = assertFailsWith<ProviderException> { repository.loadMovieInfo(source, movie) }
        assertEquals(ProviderFailure.TIMEOUT, error.failure)
        assertEquals(2, calls.get())
        assertFalse(error.message.orEmpty().contains("private-value"))
    }

    @Test fun `HTTP rejection and TLS failure are never retried`() {
        for (status in listOf(401, 403, 429, 500)) exercise("get_vod_info", status = status) { repository, calls ->
            assertEquals(status, assertFailsWith<ProviderException> { repository.loadMovieInfo(source, movie) }.statusCode)
            assertEquals(1, calls.get())
        }
        exercise("get_vod_info", tls = true) { repository, calls ->
            assertEquals(ProviderFailure.TLS, assertFailsWith<ProviderException> { repository.loadMovieInfo(source, movie) }.failure)
            assertEquals(1, calls.get())
        }
    }

    @Test fun `cancellation during backoff prevents another request`() = exercise("get_vod_info", failures = 100) { repository, calls -> coroutineScope {
        val request = launch { repository.loadMovieInfo(source, movie) }
        withTimeout(3000) { while (calls.get() == 0) delay(5) }
        request.cancelAndJoin()
        assertEquals(1, calls.get())
    } }
}
