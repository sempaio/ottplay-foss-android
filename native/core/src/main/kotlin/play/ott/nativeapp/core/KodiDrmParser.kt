package play.ott.nativeapp.core

import java.net.URLDecoder
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** A deliberately bounded Kodi contract: native license POST/response, URL and headers only. */
internal class KodiDrmParser {
    private val properties = linkedMapOf<String, String>()
    private var duplicate = false

    fun property(name: String, value: String) {
        val key = name.trim().lowercase(Locale.ROOT)
        if (properties.put(key, value.trim()) != null) duplicate = true
    }

    fun result(): Result {
        val relevant = properties.filterKeys { key -> key.startsWith("inputstream.adaptive.") &&
            (key.substringAfterLast('.').let { it == "drm" || it.startsWith("drm_") || it.startsWith("license_") ||
                it == "server_certificate" || it == "pre_init_data" }) }
        val type = properties["mimetype"] ?: when (properties["inputstream.adaptive.manifest_type"]?.lowercase(Locale.ROOT)) {
            "mpd" -> "application/dash+xml"
            "hls" -> "application/x-mpegURL"
            "ism" -> "application/vnd.ms-sstr+xml"
            else -> null
        }
        if (relevant.isEmpty()) return Result(mimeType = type)
        return try {
            require(!duplicate) { "Duplicate DRM settings in playlist entry" }
            val allowed = setOf("license_type", "license_key", "license_url", "license_url_append", "drm_legacy", "drm")
            require(relevant.keys.all { it.substringAfterLast('.') in allowed }) {
                "Additional Kodi DRM parameters are unsupported"
            }
            val modern = properties["inputstream.adaptive.drm"]
            val legacy = properties["inputstream.adaptive.drm_legacy"]
            val old = properties["inputstream.adaptive.license_type"]
            require(listOfNotNull(modern, legacy, old).size == 1) { "Ambiguous or incomplete DRM configuration" }
            val config = when {
                modern != null -> {
                    require(relevant.size == 1) { "Mixed Kodi DRM formats are unsupported" }
                    modern(modern)
                }
                legacy != null -> {
                    require(relevant.size == 1) { "Mixed Kodi DRM formats are unsupported" }
                    val fields = legacy.split('|')
                    require(fields.size in 2..3) { "DRM requires a license server address" }
                    DrmConfig(scheme(fields[0]), fields[1], queryHeaders(fields.getOrElse(2) { "" }), multiSession = true)
                }
                else -> old(requireNotNull(old))
            }
            Result(DrmPolicy.validated(config), type)
        } catch (error: IllegalArgumentException) {
            Result(mimeType = type, unsupportedReason = error.message ?: "Kodi DRM configuration is unsupported")
        } catch (_: Exception) {
            // JSON exceptions may contain the license token; never publish their message.
            Result(mimeType = type, unsupportedReason = "Invalid Kodi DRM configuration")
        }
    }

    private fun old(type: String): DrmConfig {
        val key = properties["inputstream.adaptive.license_key"].orEmpty()
        val fields = key.split('|')
        require(fields.size == 1 || fields.size == 4) { "Only a standard DRM license POST is supported" }
        if (fields.size == 4) {
            val post = decode(fields[2])
            require(post == "R{SSM}" && fields[3] in setOf("", "R")) {
                "DRM license request or response transformations are unsupported"
            }
        }
        val splitUrl = properties["inputstream.adaptive.license_url"]
        require(splitUrl == null || fields[0].isEmpty()) { "Ambiguous DRM license address" }
        require(properties["inputstream.adaptive.license_url_append"] == null || splitUrl != null) {
            "Incomplete DRM license address"
        }
        val url = splitUrl?.plus(properties["inputstream.adaptive.license_url_append"].orEmpty()) ?: fields[0]
        return DrmConfig(scheme(type), url, queryHeaders(fields.getOrElse(1) { "" }))
    }

    private fun modern(value: String): DrmConfig {
        val root = try { Json.parseToJsonElement(value) as? JsonObject } catch (_: Exception) { null }
        require(root?.size == 1) { "Exactly one explicit DRM system is required" }
        val entry = requireNotNull(root).entries.single()
        val config = entry.value as? JsonObject ?: throw IllegalArgumentException("Invalid Kodi DRM configuration")
        require(config.keys.all { it in setOf("license", "force_single_session") }) {
            "Additional Kodi DRM parameters are unsupported"
        }
        val license = config["license"] as? JsonObject ?: throw IllegalArgumentException("DRM requires a license server address")
        require(license.keys.all { it in setOf("server_url", "req_headers") }) {
            "DRM license request or response transformations are unsupported"
        }
        val forceSingle = config["force_single_session"]?.let {
            (it as? JsonPrimitive)?.booleanOrNull ?: throw IllegalArgumentException("Invalid DRM session mode")
        } ?: false
        return DrmConfig(scheme(entry.key), string(license, "server_url"),
            queryHeaders(string(license, "req_headers", required = false)), multiSession = !forceSingle)
    }

    private fun string(root: JsonObject, key: String, required: Boolean = true): String {
        val value = root[key]
        if (!required && value == null) return ""
        require(value is JsonPrimitive && value.isString) { "Invalid Kodi DRM configuration" }
        return value.content
    }

    private fun scheme(value: String): DrmScheme = when (value.trim().lowercase(Locale.ROOT)) {
        "com.widevine.alpha" -> DrmScheme.WIDEVINE
        "com.microsoft.playready" -> DrmScheme.PLAYREADY
        "org.w3.clearkey" -> DrmScheme.CLEARKEY
        else -> throw IllegalArgumentException("The selected DRM system is unsupported")
    }

    private fun queryHeaders(value: String): Map<String, String> {
        if (value.isBlank()) return emptyMap()
        val result = linkedMapOf<String, String>()
        val names = HashSet<String>()
        value.split('&').forEach { pair ->
            require('=' in pair) { "Invalid DRM license header" }
            val name = decode(pair.substringBefore('='))
            require(names.add(name.lowercase(Locale.ROOT))) { "Duplicate DRM license header" }
            result[name] = decode(pair.substringAfter('='))
        }
        return DrmPolicy.headers(result)
    }

    private fun decode(value: String): String = try { URLDecoder.decode(value, "UTF-8") }
        catch (_: IllegalArgumentException) { throw IllegalArgumentException("Invalid DRM parameter encoding") }

    data class Result(val drm: DrmConfig? = null, val mimeType: String? = null, val unsupportedReason: String? = null)
}
