package play.ott.nativeapp

import play.ott.nativeapp.core.MediaKind

/** Reduce known public TMDB movie posters only on thumbnail surfaces.
 * Keep series, details, signed URLs and every other image origin unchanged.
 * Coil continues to own memory/disk caching using the actual requested URL.
 */
internal object ArtworkRequestPolicy {
    private val poster = Regex("^https://image\\.tmdb\\.org/t/p/(original|w([0-9]+)(?:_and_h[0-9]+_bestv2)?)/([^/?#]+\\.(?:jpg|jpeg|png|webp))$")

    fun url(original: String, kind: MediaKind, surface: String): String {
        if (kind != MediaKind.MOVIE || surface !in setOf("grade", "inicio")) return original
        val match = poster.matchEntire(original) ?: return original
        val width = match.groupValues[2].toIntOrNull()
        if (width != null && width <= 500) return original
        return "https://image.tmdb.org/t/p/w500/${match.groupValues[3]}"
    }
}
