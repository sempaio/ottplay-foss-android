package play.ott.nativeapp.core

import java.net.URI
import java.net.URLDecoder
import java.util.Collections
import java.util.Locale
import kotlinx.serialization.Serializable

@Serializable
enum class DrmScheme { WIDEVINE, PLAYREADY, CLEARKEY }

/** License credentials are private request data, separate from stream/manifest credentials. */
@Serializable
data class DrmConfig(
    val scheme: DrmScheme,
    val licenseUrl: String,
    val licenseHeaders: Map<String, String> = emptyMap(),
    val multiSession: Boolean = false,
) {
    override fun toString(): String = "DrmConfig(scheme=$scheme, multiSession=$multiSession)"
}

/** Shared import/IPC validation. Errors deliberately omit the input URL and header values. */
object DrmPolicy {
    fun validated(config: DrmConfig): DrmConfig = config.copy(
        licenseUrl = licenseUrl(config.licenseUrl),
        licenseHeaders = headers(config.licenseHeaders),
    )

    fun licenseUrl(value: String): String {
        require(value.length in 1..16_384 && value == value.trim()) { "Invalid DRM license address" }
        val uri = try { URI(value) } catch (_: Exception) { null }
        require(uri != null && uri.scheme?.lowercase(Locale.ROOT) in setOf("http", "https") &&
            !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawFragment == null &&
            uri.port in -1..65535 && value.all { it.code in 33..126 }) { "Invalid DRM license address" }
        val decoded = try { URLDecoder.decode(value, "UTF-8") } catch (_: Exception) { "{" }
        require(decoded.none { it == '{' || it == '}' || it.code < 32 || it.code == 127 }) {
            "DRM license request substitutions are unsupported"
        }
        return value
    }

    fun headers(input: Map<String, String>): Map<String, String> {
        require(input.size <= 64) { "Too many DRM license headers" }
        val names = HashSet<String>()
        val result = LinkedHashMap<String, String>()
        input.forEach { (name, value) ->
            val lower = name.lowercase(Locale.ROOT)
            require(name.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) && names.add(lower)) {
                "Invalid or duplicate DRM license header"
            }
            require(lower !in setOf("host", "content-length", "transfer-encoding", "connection") &&
                value.length <= 8192 && value.all { it == '\t' || it.code in 32..126 }) {
                "Invalid DRM license header"
            }
            val canonical = when (lower) { "content-type" -> "Content-Type"; "soapaction" -> "SOAPAction"; else -> name }
            result[canonical] = value
        }
        return Collections.unmodifiableMap(result)
    }
}
