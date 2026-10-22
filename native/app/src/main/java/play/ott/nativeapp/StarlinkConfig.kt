package play.ott.nativeapp

import play.ott.nativeapp.core.SourceConfig
import play.ott.nativeapp.core.SourceKind

/** Distinct contracts: Xtream account/catalog API and the published STARLINK M3U. */
object StarlinkConfig {
    const val XTREAM_BASE_URL = "https://panel.taila5c5c2.ts.net"
    const val PLAYLIST_URL = "$XTREAM_BASE_URL/app/starlink-iptv/playlist.m3u"
    const val PLAYLIST_SOURCE_ID = "starlink-published-playlist"

    fun playlistSource() = SourceConfig(PLAYLIST_SOURCE_ID, "Lista STARLINK IPTV", SourceKind.M3U, PLAYLIST_URL)

    fun isStarlinkAccount(source: SourceConfig) = source.kind == SourceKind.XTREAM &&
        source.url.trimEnd('/') == XTREAM_BASE_URL
}
