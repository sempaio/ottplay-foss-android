package play.ott.nativeapp.cast

import okhttp3.Dns
import okhttp3.Interceptor
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.net.URI
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import javax.net.SocketFactory

/**
 * Monotonic session generation guard (`Session Generation ID`).
 * Every new cast attempt, disconnect, or detach advances the generation so late/stale async
 * operations can NEVER overwrite the state of a newer or already-closed cast session.
 */
internal class CastSessionGeneration {
    private val counter = AtomicLong(0L)

    fun current(): Long = counter.get()

    fun next(): Long = counter.incrementAndGet()

    fun advance(): Long = counter.incrementAndGet()

    fun isCurrent(generation: Long): Boolean = generation > 0L && counter.get() == generation
}

internal enum class CastFailureCategory {
    ORIGIN_OR_TOKEN,
    CORS_OR_MIXED_CONTENT,
    NETWORK_OR_TIMEOUT,
    RECEIVER_UNSUPPORTED,
    UPNP_TRANSITION_701,
    UNKNOWN,
}

internal data class CastFailureClassification(
    val category: CastFailureCategory,
    val tag: String,
    val userHint: String,
)

/**
 * Classifies Cast / DLNA / upstream errors into actionable categories for diagnostics and UI messages.
 */
internal object CastFailureClassifier {
    private val UPNP_701_REGEX = Regex("\\b701\\b")
    private val UPNP_714_REGEX = Regex("\\b714\\b")
    private val ORIGIN_HTTP_CODE_REGEX = Regex("\\b(?:401|403|404|410)\\b")

    fun classify(rawMessage: String?, httpCode: Int? = null): CastFailureClassification {
        val msg = rawMessage.orEmpty().lowercase(Locale.ROOT)
        return when {
            msg.contains("upnp 701") || UPNP_701_REGEX.containsMatchIn(msg) ||
                msg.contains("transition not available") -> CastFailureClassification(
                category = CastFailureCategory.UPNP_TRANSITION_701,
                tag = "UPNP_701",
                userHint = "A TV estava trocando de estado (UPnP 701). Aguarde 1 segundo e tente novamente.",
            )
            httpCode in setOf(401, 403, 404, 410) ||
                ORIGIN_HTTP_CODE_REGEX.containsMatchIn(msg) ||
                msg.contains("token") || msg.contains("expirad") -> CastFailureClassification(
                category = CastFailureCategory.ORIGIN_OR_TOKEN,
                tag = "ORIGEM_TOKEN",
                userHint = "O link de origem expirou ou recusou acesso (HTTP ${httpCode ?: "401/403"}). Reabra o canal/filme.",
            )
            msg.contains("cors") || msg.contains("mixed") || msg.contains("sem https") -> CastFailureClassification(
                category = CastFailureCategory.CORS_OR_MIXED_CONTENT,
                tag = "CORS_HTTP",
                userHint = "Este canal exige a Ponte Wi-Fi ativa no celular (origem HTTP sem CORS).",
            )
            msg.contains("timeout") || msg.contains("timed out") || msg.contains("unreachable") ||
                msg.contains("connect") || msg.contains("refused") || msg.contains("sem resposta") ||
                msg.contains("reset") || msg.contains("eof") -> CastFailureClassification(
                category = CastFailureCategory.NETWORK_OR_TIMEOUT,
                tag = "REDE_TIMEOUT",
                userHint = "Falha de rede ou tempo esgotado ao falar com a TV/origem. Verifique se ambos estão no mesmo Wi-Fi.",
            )
            msg.contains("media_src_not_supported") || msg.contains("format") || msg.contains("codec") ||
                UPNP_714_REGEX.containsMatchIn(msg) || msg.contains("sample-aes") ||
                msg.contains("ext-x-map") || msg.contains("recusou") -> CastFailureClassification(
                category = CastFailureCategory.RECEIVER_UNSUPPORTED,
                tag = "CODEC_PERFIL",
                userHint = "A TV/Chromecast não suportou o formato ou codec deste vídeo.",
            )
            else -> CastFailureClassification(
                category = CastFailureCategory.UNKNOWN,
                tag = "ERRO_GERAL",
                userHint = "Não foi possível iniciar a reprodução na TV. Abra 📋 Diagnóstico Cast para detalhes.",
            )
        }
    }
}

/**
 * Anti-SSRF validator and bounded-memory stream reader for external CDN upstreams fetched by the
 * local Wi-Fi bridge. Blocks redirects or manifest URIs pointing to loopback, link-local, or
 * private LAN addresses (while keeping TV LAN control endpoints cleanly separated via [DlnaProtocol]).
 */
internal object UpstreamUrlSecurity {
    const val MAX_PLAYLIST_BYTES = 1 * 1024 * 1024 // 1 MB max for .m3u8 text manifests
    const val MAX_SEGMENT_BYTES = 24 * 1024 * 1024 // 24 MB max per single HLS segment in RAM
    const val MAX_DEVICE_XML_BYTES = 512 * 1024 // 512 KB max for UPnP device description XML

    private val IPV6_LITERAL_CHARS = Regex("^[0-9a-f:.]+$")
    private val NUMERIC_OR_HEX_LABEL = Regex("^(?:\\d+|0x[0-9a-f]+)$")

    fun isAllowedUpstreamUrl(
        rawUrl: String,
        allowLoopbackForTest: Boolean = false,
        resolveDns: Boolean = false,
    ): Boolean {
        val uri = runCatching { URI(rawUrl.trim()) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return false
        if (scheme != "http" && scheme != "https") return false
        val host = uri.host?.trim()?.removePrefix("[")?.removeSuffix("]")?.lowercase(Locale.ROOT)
            ?.takeIf { it.isNotBlank() } ?: return false

        // Reject IPv6 zone IDs or percent-encoded host obfuscation
        if (host.contains('%')) return false

        if (isLoopbackHostLiteral(host)) {
            return allowLoopbackForTest
        }
        if (isBlockedHostLiteral(host, allowLoopbackForTest = allowLoopbackForTest)) return false

        if (resolveDns) {
            val resolved = runCatching { InetAddress.getAllByName(host) }.getOrNull()
            if (resolved != null && resolved.any { isBlockedAddress(it, allowLoopbackForTest) }) {
                return false
            }
        }
        return true
    }

    internal fun isLoopbackHostLiteral(normalizedHost: String): Boolean {
        if (normalizedHost == "localhost" ||
            normalizedHost.endsWith(".localhost") ||
            normalizedHost == "::1" ||
            normalizedHost == "0:0:0:0:0:0:0:1"
        ) {
            return true
        }
        val ipv4Parts = normalizedHost.split('.')
        if (ipv4Parts.size == 4 &&
            ipv4Parts.all { part -> part.isNotEmpty() && part.all(Char::isDigit) && (part == "0" || !part.startsWith("0")) } &&
            ipv4Parts.all { it.toIntOrNull() in 0..255 }
        ) {
            return ipv4Parts[0].toInt() == 127
        }
        if (normalizedHost.contains(':') && IPV6_LITERAL_CHARS.matches(normalizedHost)) {
            val parsed = runCatching { InetAddress.getByName("[$normalizedHost]") }.getOrNull()
            if (parsed != null && parsed.isLoopbackAddress) return true
        }
        return false
    }

    internal fun isBlockedHostLiteral(
        normalizedHost: String,
        allowLoopbackForTest: Boolean = false,
    ): Boolean {
        if (normalizedHost.contains('%') ||
            normalizedHost == "0.0.0.0" ||
            normalizedHost == "::" ||
            normalizedHost.endsWith(".local") ||
            normalizedHost.endsWith(".internal")
        ) {
            return true
        }
        // Check IPv6 literal (including IPv4-mapped ::ffff:172.16.0.1, ::ffff:7f00:1, ULA fc00::/7, link-local fe80::/10)
        if (normalizedHost.contains(':')) {
            if (!IPV6_LITERAL_CHARS.matches(normalizedHost)) return true
            val parsed = runCatching { InetAddress.getByName("[$normalizedHost]") }.getOrNull() ?: return true
            return isBlockedAddress(parsed, allowLoopbackForTest)
        }
        // Check dotted / integer / octal / hex IPv4 literals
        val parts = normalizedHost.split('.')
        if (parts.all { NUMERIC_OR_HEX_LABEL.matches(it) }) {
            // Canonical 4-octet decimal IPv4 without leading zeros (rejects 2130706433, 127.1, 0177.0.0.1, 0x7f000001)
            if (parts.size != 4) return true
            if (parts.any { it.startsWith("0x") || (it.length > 1 && it.startsWith("0")) }) return true
            val octets = parts.map { it.toIntOrNull() ?: return true }
            if (octets.any { it !in 0..255 }) return true
            val a = octets[0]
            val b = octets[1]
            return when {
                a == 0 -> true
                a == 10 -> true
                a == 127 -> !allowLoopbackForTest
                a == 169 && b == 254 -> true
                a == 172 && b in 16..31 -> true
                a == 192 && b == 168 -> true
                a >= 224 -> true // multicast / reserved
                else -> false
            }
        }
        return false
    }

    internal fun isBlockedAddress(addr: InetAddress, allowLoopbackForTest: Boolean): Boolean {
        if (addr.isLoopbackAddress) return !allowLoopbackForTest
        if (addr.isAnyLocalAddress || addr.isLinkLocalAddress || addr.isSiteLocalAddress || addr.isMulticastAddress) {
            return true
        }
        if (addr is Inet4Address) {
            val raw = addr.address
            val a = raw[0].toInt() and 0xFF
            val b = raw[1].toInt() and 0xFF
            if (a == 0 || a == 10 || a == 127 || (a == 169 && b == 254) ||
                (a == 172 && b in 16..31) || (a == 192 && b == 168) || a >= 224
            ) {
                return true
            }
        }
        if (addr is Inet6Address) {
            val bytes = addr.address
            val firstByte = bytes[0].toInt() and 0xFF
            // Unique Local Address fc00::/7
            if ((firstByte and 0xFE) == 0xFC) return true
            // Link-local fe80::/10
            val secondByte = bytes[1].toInt() and 0xFF
            if (firstByte == 0xFE && (secondByte and 0xC0) == 0x80) return true
            // IPv4-mapped ::ffff:0:0/96 (in case JVM kept Inet6Address wrapper)
            val isIpv4Mapped = (0 until 10).all { bytes[it] == 0.toByte() } &&
                bytes[10] == 0xFF.toByte() && bytes[11] == 0xFF.toByte()
            if (isIpv4Mapped) {
                val v4 = InetAddress.getByAddress(bytes.copyOfRange(12, 16))
                return isBlockedAddress(v4, allowLoopbackForTest)
            }
        }
        return false
    }

    /**
     * OkHttp [Dns] guard invoked BEFORE TCP socket connect on both initial requests and every
     * HTTP redirect hop, blocking private/loopback/link-local/ULA targets with zero packets sent.
     */
    fun createSafeUpstreamDns(
        allowLoopbackForTest: Boolean = false,
        onBlocked: (String) -> Unit = {},
    ): Dns = Dns { hostname ->
        val normalized = hostname.trim().removePrefix("[").removeSuffix("]").lowercase(Locale.ROOT)
        val isLoopbackLit = isLoopbackHostLiteral(normalized)
        if (isLoopbackLit && !allowLoopbackForTest) {
            onBlocked("Redirect/URL loopback bloqueada por segurança (anti-SSRF)")
            throw UnknownHostException("Blocked unsafe upstream host: $hostname")
        }
        if (!isLoopbackLit && isBlockedHostLiteral(normalized, allowLoopbackForTest = allowLoopbackForTest)) {
            onBlocked("Redirect/URL privada bloqueada por segurança (anti-SSRF)")
            throw UnknownHostException("Blocked unsafe upstream host: $hostname")
        }
        val addresses = Dns.SYSTEM.lookup(hostname)
        if (addresses.any { isBlockedAddress(it, allowLoopbackForTest) }) {
            onBlocked("DNS resolveu para IP privado/local bloqueado (anti-SSRF)")
            throw UnknownHostException("Blocked unsafe upstream IP for $hostname")
        }
        addresses
    }

    /**
     * OkHttp [SocketFactory] guard invoked RIGHT BEFORE TCP `connect()` — even for literal IP
     * redirect targets where OkHttp 5.x bypasses [Dns] completely! Guarantees ZERO TCP packets
     * ever leave the device toward loopback/RFC1918/link-local/ULA targets.
     */
    fun createSafeSocketFactory(
        allowLoopbackForTest: Boolean = false,
        onBlocked: (String) -> Unit = {},
    ): SocketFactory = object : SocketFactory() {
        private fun checkAddress(addr: InetAddress?) {
            if (addr != null && isBlockedAddress(addr, allowLoopbackForTest)) {
                onBlocked("Redirect/IP privado bloqueado antes da conexão (anti-SSRF)")
                throw IOException("Blocked unsafe upstream IP: ${addr.hostAddress}")
            }
        }

        private fun checkHost(host: String?) {
            val normalized = host?.trim()?.removePrefix("[")?.removeSuffix("]")?.lowercase(Locale.ROOT) ?: return
            val isLoopbackLit = isLoopbackHostLiteral(normalized)
            if (isLoopbackLit && !allowLoopbackForTest) {
                onBlocked("Redirect/host loopback bloqueado antes da conexão (anti-SSRF)")
                throw IOException("Blocked unsafe upstream host: $host")
            }
            if (!isLoopbackLit && isBlockedHostLiteral(normalized, allowLoopbackForTest = allowLoopbackForTest)) {
                onBlocked("Redirect/host privado bloqueado antes da conexão (anti-SSRF)")
                throw IOException("Blocked unsafe upstream host: $host")
            }
        }

        override fun createSocket(): Socket = object : Socket() {
            override fun connect(endpoint: SocketAddress?) {
                validateEndpoint(endpoint)
                super.connect(endpoint)
            }

            override fun connect(endpoint: SocketAddress?, timeout: Int) {
                validateEndpoint(endpoint)
                super.connect(endpoint, timeout)
            }

            private fun validateEndpoint(endpoint: SocketAddress?) {
                val isa = endpoint as? InetSocketAddress ?: return
                checkHost(isa.hostString)
                checkAddress(isa.address)
            }
        }

        override fun createSocket(host: String, port: Int): Socket {
            checkHost(host)
            val addr = InetAddress.getByName(host)
            checkAddress(addr)
            return Socket(addr, port)
        }

        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket {
            checkHost(host)
            val addr = InetAddress.getByName(host)
            checkAddress(addr)
            return Socket(addr, port, localHost, localPort)
        }

        override fun createSocket(host: InetAddress, port: Int): Socket {
            checkAddress(host)
            return Socket(host, port)
        }

        override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket {
            checkAddress(address)
            return Socket(address, port, localAddress, localPort)
        }
    }

    /**
     * OkHttp [Interceptor] used as both application interceptor (validates scheme/host before DNS)
     * and network interceptor (defense-in-depth on redirect URLs & actual connected socket IP).
     */
    fun createSafeUpstreamInterceptor(
        allowLoopbackForTest: Boolean = false,
        onBlocked: (String) -> Unit = {},
    ): Interceptor = Interceptor { chain ->
        val reqUrl = chain.request().url.toString()
        if (!isAllowedUpstreamUrl(reqUrl, allowLoopbackForTest = allowLoopbackForTest, resolveDns = false)) {
            onBlocked("Redirect/URL privada bloqueada por segurança (anti-SSRF)")
            throw IOException("Blocked unsafe upstream URL")
        }
        val remoteAddr = chain.connection()?.socket()?.inetAddress
        if (remoteAddr != null && isBlockedAddress(remoteAddr, allowLoopbackForTest)) {
            onBlocked("IP privado bloqueado por segurança (anti-SSRF)")
            throw IOException("Blocked unsafe upstream IP")
        }
        chain.proceed(chain.request())
    }

    /**
     * Reads an [InputStream] into memory with a hard upper bound [maxBytes] to prevent OOM
     * when a misbehaving upstream returns an unexpectedly huge body.
     * Throws [IOException] if the payload exceeds [maxBytes].
     */
    @Throws(IOException::class)
    fun readBoundedBytes(input: InputStream, maxBytes: Int): ByteArray {
        require(maxBytes > 0) { "maxBytes must be positive" }
        val out = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
        val buf = ByteArray(16 * 1024)
        var total = 0L
        val maxLong = maxBytes.toLong()
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (n == 0) continue
            total += n.toLong()
            if (total > maxLong) {
                val limitLabel = if (maxBytes >= 1024 * 1024) {
                    "${maxBytes / (1024 * 1024)} MB"
                } else {
                    "$maxBytes bytes"
                }
                throw IOException("Resposta upstream excedeu o limite seguro de $limitLabel")
            }
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
