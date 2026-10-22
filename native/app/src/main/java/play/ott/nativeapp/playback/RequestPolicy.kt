package play.ott.nativeapp.playback

import java.net.URI
import java.util.Collections
import java.util.Locale
import play.ott.nativeapp.AppTransportPolicy
import play.ott.nativeapp.core.RemoteTransportPolicy

/** Transport validation without Android dependencies or credential-bearing diagnostics. */
internal object RequestPolicy {
    private val headerName = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")

    fun requireStreamUrl(value: String, transportPolicy: RemoteTransportPolicy = AppTransportPolicy.current): String {
        val uri = try { URI(value) } catch (_: Exception) { null }
        val scheme = uri?.scheme?.lowercase(Locale.ROOT)
        val network = scheme in setOf("http", "https") && !uri?.host.isNullOrBlank() && uri.rawUserInfo == null
        val local = scheme in setOf("android.resource", "rawresource", "content") && !uri?.path.isNullOrBlank()
        require(uri != null && (network || local)) {
            "Use an HTTP(S), Android resource or content URL without embedded user information"
        }
        if (network) transportPolicy.requireHttpUrl(value)
        return value
    }

    /** A detached immutable snapshot, including case-insensitive duplicate rejection. */
    fun headers(input: Map<String, String>): Map<String, String> {
        require(input.size <= 64) { "Too many request headers" }
        val names = HashSet<String>()
        val copy = LinkedHashMap<String, String>()
        input.forEach { (name, value) ->
            require(headerName.matches(name) && names.add(name.lowercase(Locale.ROOT))) {
                "Invalid or duplicate request header"
            }
            require(value.length <= 8192 && value.all { it == '\t' || it.code in 32..126 }) {
                "Invalid request header value"
            }
            // The HTTP stack owns message framing and target routing.
            require(name.lowercase(Locale.ROOT) !in setOf("host", "content-length", "transfer-encoding", "connection")) {
                "Unsupported transport header"
            }
            copy[name] = value
        }
        return Collections.unmodifiableMap(copy)
    }
}

internal enum class ControllerAccess { OWNER, TRANSPORT, REJECT }

internal fun controllerAccess(sameUid: Boolean, notificationController: Boolean, trusted: Boolean): ControllerAccess =
    when {
        sameUid -> ControllerAccess.OWNER
        notificationController || trusted -> ControllerAccess.TRANSPORT
        else -> ControllerAccess.REJECT
    }
