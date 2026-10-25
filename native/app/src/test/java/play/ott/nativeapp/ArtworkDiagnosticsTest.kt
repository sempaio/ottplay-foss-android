package play.ott.nativeapp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArtworkDiagnosticsTest {
    @Test fun comparesSourcesAndTimesForAllKinds() {
        var nanos = 0L
        val diagnostics = ArtworkDiagnostics(clock = { nanos })
        for ((kind, origin) in listOf("SERIES" to "MEMORY_CACHE", "MOVIE" to "DISK", "LIVE" to "NETWORK")) {
            val ticket = diagnostics.begin(kind, "grade", "https://image.tmdb.org/t/p/w300/a.jpg")
            nanos += 25_000_000
            diagnostics.finish(ticket, origin)
        }
        val report = diagnostics.report()
        assertTrue(report.contains("SERIES | grade | image.tmdb.org | TMDB | MEMORY_CACHE: 1 imagens; mediana 25ms"))
        assertTrue(report.contains("MOVIE | grade | image.tmdb.org | TMDB | DISK"))
        assertTrue(report.contains("LIVE | grade | image.tmdb.org | TMDB | NETWORK"))
    }

    @Test fun excludesSecretsTitlesPathsQueriesAndErrors() {
        val diagnostics = ArtworkDiagnostics()
        val ticket = diagnostics.begin("SERIES", "grade", "https://user:password@panel.example/channel-logo/private-id.png?token=private-token#private-fragment")
        diagnostics.finish(ticket, "ERROR", "https://private-url?password=secret")
        val report = diagnostics.report()
        assertTrue(report.contains("panel.example | channel-logo | ERROR"))
        for (secret in listOf("user:", "password", "private-id", "private-token", "private-fragment", "private-url", "secret", "https://")) {
            assertFalse(report.contains(secret), secret)
        }
        val malformed = diagnostics.begin("password=secret", "private-title", "invalid sensitive text")
        assertEquals("OUTRO", malformed.kind)
        assertEquals("OUTRO", malformed.surface)
        assertEquals("sem-host", malformed.host)
    }

    @Test fun boundsHistoryAndRejectsOldRequestsAfterReset() {
        val diagnostics = ArtworkDiagnostics(capacity = 2)
        val old = diagnostics.begin("LIVE", "grade", "https://old.example/a")
        diagnostics.clear()
        assertNull(diagnostics.finish(old, "NETWORK"))
        for (host in listOf("first.example", "second.example", "third.example")) {
            diagnostics.finish(diagnostics.begin("SERIES", "grade", "https://$host/a"), "DISK")
        }
        val report = diagnostics.report()
        assertFalse(report.contains("old.example"))
        assertFalse(report.contains("first.example"))
        assertTrue(report.contains("second.example"))
        assertTrue(report.contains("third.example"))
        assertTrue(report.contains("Últimos 2/2 resultados"))
    }

    @Test fun acceptsCancellationWithoutRecordingItAsASuccessfulCacheHit() {
        val diagnostics = ArtworkDiagnostics()
        diagnostics.finish(diagnostics.begin("MOVIE", "inicio", "https://images.example/a"), "CANCELLED")
        val report = diagnostics.report()
        assertTrue(report.contains("CANCELLED: 1 imagens"))
        assertFalse(report.contains("NETWORK:"))
    }

    @Test fun identifiesSelectedCatalogueWithoutLeakingAccountOrOtherSources() {
        val source = play.ott.nativeapp.core.SourceConfig("selected", "private-account-title",
            play.ott.nativeapp.core.SourceKind.XTREAM, "https://operator:password@panel.example/private?token=secret",
            username = "private-username", password = "private-password")
        val entries = listOf(
            play.ott.nativeapp.core.MediaEntry("private-id", source.id, "private-title", kind = play.ott.nativeapp.core.MediaKind.SERIES),
            play.ott.nativeapp.core.MediaEntry("other-id", "other-source", "other-title", kind = play.ott.nativeapp.core.MediaKind.MOVIE))
        val report = artworkContextReport("0.3.2-artwork-test", "a".repeat(40), source, entries, false)
        assertTrue(report.contains("Fonte selecionada: XTREAM"))
        assertTrue(report.contains("Servidor configurado: panel.example"))
        assertTrue(report.contains("canais=0, filmes=0, séries=1, episódios=0"))
        assertTrue(report.contains("Código: " + "a".repeat(40)))
        for (secret in listOf("private", "password", "operator", "token", "secret", "other-title", "https://"))
            assertFalse(report.contains(secret), secret)
        val managed = artworkContextReport("test", "invalid", StarlinkConfig.playlistSource(), emptyList(), true)
        assertTrue(managed.contains("Lista STARLINK IPTV (M3U)"))
        assertTrue(managed.contains("Código: local"))
    }
}
