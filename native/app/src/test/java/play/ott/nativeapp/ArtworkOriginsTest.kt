package play.ott.nativeapp

import kotlin.test.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import play.ott.nativeapp.core.MediaEntry
import play.ott.nativeapp.core.MediaKind

class ArtworkOriginsTest {
    @Test fun classifiesArbitraryOriginsAndDoesNotExposePrivateParts() {
        assertEquals("https | unknown-cdn.example | imagem", artworkOrigin("https://user:password@unknown-cdn.example/private-title.png?token=secret"))
        assertEquals("https | panel.example | cache-painel", artworkOrigin("https://panel.example/channel-logo/private-id.png"))
        assertEquals("recurso do APK", artworkOrigin("android.resource://private.package/drawable/private"))
        assertEquals("arquivo local", artworkOrigin("file:///private/account/image.png"))
        assertEquals("provedor local", artworkOrigin("content://private-provider/private-title"))
        assertEquals("dados embutidos", artworkOrigin("data:image/png;base64,private-bytes"))
        assertEquals("não identificada", artworkOrigin("not a URL with private text"))
        assertEquals("sem imagem", artworkOrigin(""))
        assertEquals("https | 2001:db8::1 | imagem", artworkOrigin("https://[2001:db8::1]/private"))
    }

    @Test fun comparesAllKindsAndBoundsResultsWithoutKeepingStaleMeasurements() {
        var now = 0L
        val session = ArtworkOrigins(2) { now }
        val old = session.start("LIVE", "grade", "https://old.example/private")
        session.clear()
        session.image(old, "NETWORK")
        for ((kind, origin) in listOf("LIVE" to "NETWORK", "MOVIE" to "DISK", "SERIES" to "MEMORY_CACHE")) {
            val ticket = session.start(kind, "grade", "https://user:password@images.example/private-title?token=secret")
            now += 25_000_000
            session.image(ticket, origin, 100, 150)
        }
        val report = session.report()
        assertTrue(report.contains("MOVIE | grade | https | images.example | imagem | DISK"))
        assertTrue(report.contains("SERIES | grade | https | images.example | imagem | MEMORY_CACHE"))
        assertTrue(report.contains("100x150"))
        for (private in listOf("old.example", "password", "private-title", "token", "secret")) assertFalse(report.contains(private), private)
        assertFalse(report.contains("LIVE | grade"))
    }

    @Test fun inventoryUsesSelectedSourceAndCountsRepeatedImageUrls() {
        val entries = listOf(
            MediaEntry("private-id", "active", "private-title", kind = MediaKind.SERIES, logo = "https://old-cdn.example/image?token=secret"),
            MediaEntry("two", "active", "another-title", kind = MediaKind.SERIES, logo = "https://old-cdn.example/image?token=secret"),
            MediaEntry("three", "active", "Movie", kind = MediaKind.MOVIE, logo = "https://other.example/movie"),
            MediaEntry("four", "active", "Channel", kind = MediaKind.LIVE),
            MediaEntry("hidden", "different-account", "Other", logo = "https://unrelated.example/private"))
        val report = artworkInventory("active", entries)
        assertTrue(report.contains("SERIES | https | old-cdn.example | imagem: 2 itens; 1 URLs distintas"))
        assertTrue(report.contains("MOVIE | https | other.example"))
        assertTrue(report.contains("LIVE | sem imagem: 1 itens; 0 URLs distintas"))
        for (private in listOf("private-id", "private-title", "token", "secret", "unrelated.example")) assertFalse(report.contains(private), private)
    }

    @Test fun followsRedirectsUnchangedAndRecordsEachActualDestinationWithoutSendingTags() {
        MockWebServer().use { first -> MockWebServer().use { last ->
            first.start(); last.start()
            val finalUrl = last.url("/private-poster?password=secret").newBuilder().host("127.0.0.1").build()
            first.enqueue(MockResponse().setResponseCode(302).setHeader("Location", finalUrl))
            last.enqueue(MockResponse().setBody("image-bytes"))
            val session = ArtworkOrigins()
            val client = OkHttpClient.Builder().eventListenerFactory(session.httpFactory()).build()
            val request = Request.Builder().url(first.url("/private-title?token=secret"))
                .tag(ArtworkTag::class.java, ArtworkTag("SERIES", "grade")).build()
            client.newCall(request).execute().use { assertEquals("image-bytes", it.body.string()) }
            val report = session.report()
            assertTrue(report.contains("| 302 |"))
            assertTrue(report.contains("| 200 | 11 bytes |"))
            assertTrue(report.contains("127.0.0.1"))
            assertEquals(1, first.requestCount); assertEquals(1, last.requestCount)
            assertNull(first.takeRequest().getHeader("ArtworkTag"))
            for (private in listOf("private-title", "private-poster", "password", "token", "secret", "image-bytes")) assertFalse(report.contains(private), private)
        }}
    }

    @Test fun reports304AsRevalidationRatherThanAFullImageDownload() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(304)); server.start()
            val session = ArtworkOrigins()
            val request = Request.Builder().url(server.url("/private"))
                .tag(ArtworkTag::class.java, ArtworkTag("MOVIE", "grade")).build()
            OkHttpClient.Builder().eventListenerFactory(session.httpFactory()).build()
                .newCall(request).execute().use { assertEquals(304, it.code); it.body.bytes() }
            assertTrue(session.report().contains("| 304 | 0 bytes |"))
        }
    }

    @Test fun clearingReportDoesNotAdmitAnOldHttpResponseOrModifyItsBody() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("unchanged-image-bytes")); server.start()
            val session = ArtworkOrigins()
            val request = Request.Builder().url(server.url("/private"))
                .tag(ArtworkTag::class.java, ArtworkTag("LIVE", "grade")).build()
            OkHttpClient.Builder().eventListenerFactory(session.httpFactory()).build()
                .newCall(request).execute().use {
                    session.clear()
                    assertEquals("unchanged-image-bytes", it.body.string())
                }
            assertFalse(session.report().contains("HTTP | R"))
        }
    }
}
