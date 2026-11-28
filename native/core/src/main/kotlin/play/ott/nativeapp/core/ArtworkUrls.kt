package play.ott.nativeapp.core

import java.text.Normalizer
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Artwork URL normalization, dead TMDB mirror rescue, and edition-aware title normalization
 * used to prevent false cover-art stripping on duplicate release/quality variants.
 */
object ArtworkUrls {
    private val HTTPS_UPGRADE_HOSTS = setOf(
        "image.tmdb.org",
        "media.themoviedb.org",
        "panel.taila5c5c2.ts.net",
        "upload.wikimedia.org",
        "lh3.googleusercontent.com",
        "1.bp.blogspot.com",
        "2.bp.blogspot.com",
        "3.bp.blogspot.com",
        "4.bp.blogspot.com",
        "www.fontedecanais.dev",
        "fontedecanais.dev",
    )

    private val TRUSTED_TMDB_HOSTS = setOf(
        "image.tmdb.org",
        "media.themoviedb.org",
    )

    /** Matches standard TMDB image paths such as /t/p/w600_and_h900_bestv2/<hash>.jpg. */
    private val TMDB_POSTER_PATH_RE = Regex(
        """^/t/p/(?:original|w\d+(?:_and_h\d+_bestv2)?)/[A-Za-z0-9._-]+\.(?:jpg|jpeg|png|webp)$""",
        RegexOption.IGNORE_CASE,
    )

    private val COMBINING_MARKS_RE = Regex("""\p{Mn}+""")
    private val EDITION_TAGS_RE = Regex(
        """(\[[^\]]*]|\((?:19|20)\d{2}\)|\((?:dub|leg|dublado|legendado|nacional|dual|4k|fhd|hd|sd|cam|hdr|uhd|3d|h265|hevc|parte\s*\d+)[^)]*\)|\b(?:4k|uhd|fhd|hd|sd|hdr|cam|dub|leg|dublado|legendado|dual|nacional|lancamento|h265|hevc)\b|\.\d{1,3}(?=\s|$))""",
        RegexOption.IGNORE_CASE,
    )
    private val TRAILING_YEAR_RE = Regex("""(?:\s+|-)(?:19|20)\d{2}$""")
    private val NON_ALNUM_RE = Regex("""[^a-z0-9]+""")

    /**
     * Normalizes raw provider artwork URLs:
     * - trims whitespace and encodes raw spaces
     * - expands protocol-relative URLs (`//...`) to `https://...`
     * - rejects non-HTTP(S) schemes
     * - rescues dead TMDB mirrors serving standard `/t/p/<size>/<file>` paths directly from `https://image.tmdb.org`
     * - upgrades known HTTPS-capable image hosts from `http://` to `https://`
     */
    fun normalizeUrl(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        val withScheme = if (trimmed.startsWith("//")) "https:$trimmed" else trimmed
        if (!withScheme.startsWith("https://", ignoreCase = true) &&
            !withScheme.startsWith("http://", ignoreCase = true)
        ) {
            return ""
        }
        val encodedSpaces = if (' ' in withScheme) withScheme.replace(" ", "%20") else withScheme
        val parsed = encodedSpaces.toHttpUrlOrNull() ?: return ""
        val hostLower = parsed.host.lowercase()

        // Rescue dead third-party TMDB mirrors (e.g. http://timg.bdta.pro/t/p/w600_and_h900_bestv2/...)
        if (hostLower !in TRUSTED_TMDB_HOSTS && TMDB_POSTER_PATH_RE.matches(parsed.encodedPath)) {
            return parsed.newBuilder()
                .scheme("https")
                .host("image.tmdb.org")
                .port(443)
                .query(null)
                .fragment(null)
                .build()
                .toString()
        }

        if (!parsed.isHttps && hostLower in HTTPS_UPGRADE_HOSTS) {
            return parsed.newBuilder()
                .scheme("https")
                .port(443)
                .build()
                .toString()
        }

        return parsed.toString()
    }

    /**
     * Returns an `https://` fallback candidate when an `http://` artwork URL fails to load,
     * skipping URLs that already use HTTPS or explicit non-80 ports.
     */
    fun httpsUpgradeCandidate(url: String): String? {
        val parsed = url.trim().toHttpUrlOrNull() ?: return null
        if (parsed.isHttps || parsed.port != 80) return null
        return parsed.newBuilder()
            .scheme("https")
            .port(443)
            .build()
            .toString()
    }

    /**
     * Normalizes a VOD/series title for shared-artwork detection so release/quality/audio
     * variants of the same title (e.g. "Fora de Controle" vs "Fora de Controle [Lançamento]",
     * "Velhos Bandidos" vs "Velhos Bandidos 2026") keep their shared official poster while
     * genuinely unrelated titles sharing a generic placeholder remain filtered.
     */
    fun normalizeTitleForArtworkDedupe(name: String): String {
        val raw = name.trim()
        if (raw.isEmpty()) return ""
        val decomposed = Normalizer.normalize(raw, Normalizer.Form.NFKD)
        val ascii = COMBINING_MARKS_RE.replace(decomposed, "").lowercase()
        val withoutTags = EDITION_TAGS_RE.replace(ascii, " ")
        val collapsed = NON_ALNUM_RE.replace(withoutTags, " ").trim()
        val withoutTrailingYear = TRAILING_YEAR_RE.replace(collapsed, "").trim()
        // Never wipe numeric-only titles like "2012" or "1917".
        return withoutTrailingYear.ifEmpty { collapsed.ifEmpty { raw.lowercase() } }
    }

    /**
     * Accepts detail posters from verified TMDB metadata (handling both `poster_source=tmdb`
     * and M81's `metadata_source=tmdb` as well as direct `image.tmdb.org` URLs).
     */
    fun isTrustedDetailPoster(url: String, posterSource: String, metadataSource: String): Boolean {
        if (url.isBlank()) return false
        if (posterSource.equals("tmdb", ignoreCase = true) ||
            metadataSource.equals("tmdb", ignoreCase = true)
        ) {
            return true
        }
        val host = url.toHttpUrlOrNull()?.host?.lowercase() ?: return false
        return host in TRUSTED_TMDB_HOSTS
    }

    /** True when a cached VOD/series entry still carries legacy HTTP TMDB or dead mirror artwork. */
    fun needsArtworkRefresh(logo: String): Boolean {
        val trimmed = logo.trim()
        if (trimmed.isEmpty()) return true
        val parsed = trimmed.toHttpUrlOrNull() ?: return true
        val hostLower = parsed.host.lowercase()
        if (!parsed.isHttps && hostLower in HTTPS_UPGRADE_HOSTS) return true
        if (hostLower !in TRUSTED_TMDB_HOSTS && TMDB_POSTER_PATH_RE.matches(parsed.encodedPath)) return true
        return false
    }
}
