package play.ott.nativeapp

import kotlin.test.Test
import kotlin.test.assertEquals
import play.ott.nativeapp.core.MediaKind

class ArtworkRequestPolicyTest {
    @Test fun movieCardsRequestSmallerPosterWithoutMutatingTheItem() {
        for (size in listOf("original", "w780", "w600_and_h900_bestv2")) {
            for (surface in listOf("grade", "inicio")) {
                assertEquals("https://image.tmdb.org/t/p/w500/movie.jpg",
                    ArtworkRequestPolicy.url("https://image.tmdb.org/t/p/$size/movie.jpg", MediaKind.MOVIE, surface))
            }
        }
    }

    @Test fun neverUpscalesSmallPostersOrRewritesOtherKindsAndSurfaces() {
        for (size in listOf("w92", "w185", "w342", "w500")) {
            val original = "https://image.tmdb.org/t/p/$size/movie.jpg"
            assertEquals(original, ArtworkRequestPolicy.url(original, MediaKind.MOVIE, "grade"))
        }
        val original = "https://image.tmdb.org/t/p/original/cover.jpg"
        for (kind in listOf(MediaKind.SERIES, MediaKind.EPISODE, MediaKind.LIVE)) {
            assertEquals(original, ArtworkRequestPolicy.url(original, kind, "grade"))
        }
        for (surface in listOf("detalhe", "destaque", "episodio")) {
            assertEquals(original, ArtworkRequestPolicy.url(original, MediaKind.MOVIE, surface))
        }
    }

    @Test fun preservesSignedUrlsUnknownHostsVectorsAndNonstandardPaths() {
        val urls = listOf(
            "https://image.tmdb.org/t/p/original/a.jpg?token=private",
            "https://image.tmdb.org/t/p/original/a.jpg#fragment",
            "https://image.tmdb.org:8443/t/p/original/a.jpg",
            "https://user:password@image.tmdb.org/t/p/original/a.jpg",
            "http://image.tmdb.org/t/p/original/a.jpg",
            "https://image.tmdb.org.attacker.test/t/p/original/a.jpg",
            "https://panel.example/channel-logo/01234567890123456789.png",
            "https://image.tmdb.org/t/p/original/logo.svg",
            "https://image.tmdb.org/t/p/custom/a.jpg",
        )
        urls.forEach { assertEquals(it, ArtworkRequestPolicy.url(it, MediaKind.MOVIE, "grade")) }
    }
}
