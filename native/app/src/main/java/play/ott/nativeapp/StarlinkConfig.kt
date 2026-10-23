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
    /** Client builds accept only operator-owned endpoints, never editable provider settings. */
    fun isManagedSource(source: SourceConfig): Boolean =
        (isStarlinkAccount(source) && source.headers.isEmpty() && source.epgUrl.isBlank() && source.mac.isBlank()) ||
        (source.id == PLAYLIST_SOURCE_ID && source.kind == SourceKind.M3U && source.url == PLAYLIST_URL &&
            source.username.isBlank() && source.password.isBlank() && source.headers.isEmpty() && source.epgUrl.isBlank())

    fun visibleSources(sources: List<SourceConfig>, customerMode: Boolean = BuildConfig.CUSTOMER_MODE): List<SourceConfig> =
        if (customerMode) sources.filter(::isManagedSource) else sources

}
