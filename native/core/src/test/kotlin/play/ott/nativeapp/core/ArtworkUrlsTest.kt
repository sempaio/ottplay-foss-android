package play.ott.nativeapp.core

import kotlin.test.*

class ArtworkUrlsTest {
    @Test fun upgradesKnownHttpsHostsAndPreservesPath() {
        assertEquals(
            "https://image.tmdb.org/t/p/w300/6oCBiVDfEfMnbNA7s3JaqdDCm9w.jpg",
            ArtworkUrls.normalizeUrl("http://image.tmdb.org/t/p/w300/6oCBiVDfEfMnbNA7s3JaqdDCm9w.jpg"),
        )
        assertEquals(
            "https://media.themoviedb.org/t/p/w500/abc.jpg",
            ArtworkUrls.normalizeUrl("  HTTP://media.themoviedb.org/t/p/w500/abc.jpg  "),
        )
        assertEquals(
            "https://panel.taila5c5c2.ts.net/channel-logo/22b118cc173f893f08dc.png",
            ArtworkUrls.normalizeUrl("http://panel.taila5c5c2.ts.net/channel-logo/22b118cc173f893f08dc.png"),
        )
        assertEquals(
            "https://topxz.live/logos/Documentarios/HGTV.png",
            ArtworkUrls.normalizeUrl("http://topxz.live/logos/Documentarios/HGTV.png"),
        )
    }

    @Test fun rescuesDeadTmdbMirrorPathsToOfficialTmdb() {
        assertEquals(
            "https://image.tmdb.org/t/p/w600_and_h900_bestv2/9LNp4wA2DzZhdYUbTan3shbC8j8.jpg",
            ArtworkUrls.normalizeUrl("http://timg.bdta.pro/t/p/w600_and_h900_bestv2/9LNp4wA2DzZhdYUbTan3shbC8j8.jpg"),
        )
        assertEquals(
            "https://image.tmdb.org/t/p/w1066_and_h600_face/eXxKVF10yJErWut4A6LFLs3O5jx.jpg",
            ArtworkUrls.normalizeUrl("http://timg.bdta.pro/t/p/w1066_and_h600_face/eXxKVF10yJErWut4A6LFLs3O5jx.jpg"),
        )
    }

    @Test fun handlesProtocolRelativeSpacesAndRejectsUnsafeSchemes() {
        assertEquals(
            "https://image.tmdb.org/t/p/w500/poster.jpg",
            ArtworkUrls.normalizeUrl("//image.tmdb.org/t/p/w500/poster.jpg"),
        )
        assertEquals(
            "http://example.invalid/covers/My%20Movie.jpg",
            ArtworkUrls.normalizeUrl("http://example.invalid/covers/My Movie.jpg"),
        )
        assertEquals("", ArtworkUrls.normalizeUrl(""))
        assertEquals("", ArtworkUrls.normalizeUrl("   "))
        assertEquals("", ArtworkUrls.normalizeUrl("javascript:alert(1)"))
        assertEquals("", ArtworkUrls.normalizeUrl("file:///etc/passwd"))
        assertEquals("", ArtworkUrls.normalizeUrl("not a url"))
    }

    @Test fun httpsUpgradeCandidateOnlyUpgradesDefaultPortHttp() {
        assertEquals(
            "https://cdn.example.invalid/poster.jpg",
            ArtworkUrls.httpsUpgradeCandidate("http://cdn.example.invalid/poster.jpg"),
        )
        assertNull(ArtworkUrls.httpsUpgradeCandidate("https://cdn.example.invalid/poster.jpg"))
        assertNull(ArtworkUrls.httpsUpgradeCandidate("http://cdn.example.invalid:8080/poster.jpg"))
        assertNull(ArtworkUrls.httpsUpgradeCandidate(""))
    }

    @Test fun titleDedupeNormalizationCollapsesEditionAndYearVariantsOnly() {
        val launchBase = ArtworkUrls.normalizeTitleForArtworkDedupe("Fora de Controle")
        assertEquals(launchBase, ArtworkUrls.normalizeTitleForArtworkDedupe("Fora de Controle [Lançamento]"))
        assertEquals(launchBase, ArtworkUrls.normalizeTitleForArtworkDedupe("Fora de Controle [L]"))
        assertEquals(launchBase, ArtworkUrls.normalizeTitleForArtworkDedupe("Fora de Controle (2026)"))
        assertEquals(launchBase, ArtworkUrls.normalizeTitleForArtworkDedupe("Fora de Controle 2026"))
        assertEquals(launchBase, ArtworkUrls.normalizeTitleForArtworkDedupe("Fora de Controle [Dublado] 4K"))

        assertEquals(
            ArtworkUrls.normalizeTitleForArtworkDedupe("O Preço de uma Vida"),
            ArtworkUrls.normalizeTitleForArtworkDedupe("O Preço de uma Vida [L]"),
        )

        // Numeric-only movie titles must remain distinct and non-empty.
        assertEquals("2012", ArtworkUrls.normalizeTitleForArtworkDedupe("2012"))
        assertEquals("1917", ArtworkUrls.normalizeTitleForArtworkDedupe("1917"))
        assertNotEquals(
            ArtworkUrls.normalizeTitleForArtworkDedupe("2012"),
            ArtworkUrls.normalizeTitleForArtworkDedupe("1917"),
        )

        // Unrelated titles remain distinct.
        assertNotEquals(
            ArtworkUrls.normalizeTitleForArtworkDedupe("Fixture movie"),
            ArtworkUrls.normalizeTitleForArtworkDedupe("Other"),
        )
    }

    @Test fun trustedDetailPosterAcceptsM81MetadataSourceAndTmdbHost() {
        assertTrue(ArtworkUrls.isTrustedDetailPoster("https://cdn.example/a.jpg", "tmdb", ""))
        assertTrue(ArtworkUrls.isTrustedDetailPoster("https://cdn.example/a.jpg", "", "tmdb"))
        assertTrue(ArtworkUrls.isTrustedDetailPoster("https://image.tmdb.org/t/p/w500/x.jpg", "", ""))
        assertFalse(ArtworkUrls.isTrustedDetailPoster("https://untrusted.example/shared.jpg", "", ""))
        assertFalse(ArtworkUrls.isTrustedDetailPoster("", "tmdb", "tmdb"))
    }

    @Test fun categoryArtworkRefreshTargetsLegacyUrlsAndHighBlankRatioWithoutThrashing() {
        assertFalse(ArtworkUrls.needsArtworkRefresh(""))
        assertFalse(ArtworkUrls.needsArtworkRefresh("https://image.tmdb.org/t/p/w500/ok.jpg"))
        assertTrue(ArtworkUrls.needsArtworkRefresh("http://image.tmdb.org/t/p/w500/old.jpg"))
        assertTrue(ArtworkUrls.needsArtworkRefresh("http://timg.bdta.pro/t/p/w500/dead.jpg"))
        assertTrue(ArtworkUrls.needsArtworkRefresh("//image.tmdb.org/t/p/w500/rel.jpg"))

        // 1 legitimately missing cover out of 10 must keep the normal 30-min cache TTL.
        val healthy = List(9) { "https://image.tmdb.org/t/p/w500/ok$it.jpg" } + ""
        assertFalse(ArtworkUrls.shouldRefreshCategoryArtwork(healthy))

        // >=25% blank across >=4 items indicates a legacy wiped cache and should refresh quickly.
        val wiped = listOf("https://image.tmdb.org/t/p/w500/a.jpg", "", "", "https://image.tmdb.org/t/p/w500/b.jpg")
        assertTrue(ArtworkUrls.shouldRefreshCategoryArtwork(wiped))

        // Any legacy HTTP TMDB URL triggers refresh even when no covers are blank.
        val legacyHttp = listOf("https://image.tmdb.org/t/p/w500/a.jpg", "http://image.tmdb.org/t/p/w500/b.jpg")
        assertTrue(ArtworkUrls.shouldRefreshCategoryArtwork(legacyHttp))
    }
}
