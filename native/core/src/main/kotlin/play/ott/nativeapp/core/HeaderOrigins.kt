package play.ott.nativeapp.core

import java.util.Locale
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

private val publicHeaderNames = setOf("user-agent", "accept", "accept-language")

/** The authority only: never persist credential-bearing paths, queries or user information here. */
private fun origin(value: String): String? = value.toHttpUrlOrNull()?.let {
    HttpUrl.Builder().scheme(it.scheme).host(it.host).port(it.port).build().toString()
}

internal fun headerOrigins(headers: Map<String, String>, url: String): Map<String, String> {
    val authority = origin(url) ?: return emptyMap()
    return headers.keys.associate { it.lowercase(Locale.ROOT) to authority }
}

internal fun scopedHeaders(headers: Map<String, String>, origins: Map<String, String>?, targetUrl: String): Map<String, String> {
    val target = origin(targetUrl)
    return headers.filterKeys { name ->
        val lower = name.lowercase(Locale.ROOT)
        lower in publicHeaderNames || (target != null && origins?.get(lower) == target)
    }
}

/** Old catalogs cannot distinguish inherited credentials from explicit stream credentials. */
fun MediaEntry.needsHeaderOriginRefresh(): Boolean = headerOrigins == null && headers.keys.any {
    val name = it.lowercase(Locale.ROOT)
    name !in publicHeaderNames
}

internal fun playbackHeaders(config: SourceConfig, entry: MediaEntry, targetUrl: String): Map<String, String> = mergedHeaders(
    scopedHeaders(config.headers, headerOrigins(config.headers, config.url), targetUrl),
    scopedHeaders(entry.headers, entry.headerOrigins, targetUrl),
)
