package play.ott.nativeapp.cast

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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

    private fun normalizeHost(raw: String): String =
        raw.trim().removePrefix("[").removeSuffix("]").trimEnd('.').lowercase(Locale.ROOT)

    fun isAllowedUpstreamUrl(
        rawUrl: String,
        allowLoopbackForTest: Boolean = false,
        resolveDns: Boolean = false,
    ): Boolean {
        val trimmed = rawUrl.trim()
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return false
        val httpUrl = trimmed.toHttpUrlOrNull() ?: return false
        val uriScheme = uri.scheme?.lowercase(Locale.ROOT) ?: return false
        val httpScheme = httpUrl.scheme.lowercase(Locale.ROOT)
        if ((uriScheme != "http" && uriScheme != "https") || uriScheme != httpScheme) return false

        val rawUriHost = uri.host?.trim()?.removePrefix("[")?.removeSuffix("]")
            ?.takeIf { it.isNotBlank() } ?: return false
        val rawHttpHost = httpUrl.host.trim().removePrefix("[").removeSuffix("]")
            .takeIf { it.isNotBlank() } ?: return false

        // Reject IPv6 zone IDs or percent-encoded host obfuscation
        if (rawUriHost.contains('%') || rawHttpHost.contains('%')) return false

        val uriHost = normalizeHost(rawUriHost)
        val httpHost = normalizeHost(rawHttpHost)
        if (uriHost.isBlank() || httpHost.isBlank()) return false

        // Validate BOTH raw URI host and OkHttp canonical host to eliminate parser differentials
        // and block obfuscated IP literals (e.g., 2130706433, 127.1, 0177.0.0.1, localhost.)
        for (candidateHost in listOf(uriHost, httpHost).distinct()) {
            if (isLoopbackHostLiteral(candidateHost)) {
                if (!allowLoopbackForTest) return false
            } else if (isBlockedHostLiteral(candidateHost, allowLoopbackForTest = allowLoopbackForTest)) {
                return false
            }
        }

        if (resolveDns) {
            val resolved = runCatching { InetAddress.getAllByName(httpHost) }.getOrNull()
            if (resolved.isNullOrEmpty() || resolved.any { isBlockedAddress(it, allowLoopbackForTest) }) {
                return false
            }
        }
        return true
    }

    internal fun isLoopbackHostLiteral(rawHost: String): Boolean {
        val normalizedHost = normalizeHost(rawHost)
        if (normalizedHost.isBlank()) return false
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
            if (parsed is Inet6Address && parsed.isLoopbackAddress) return true
        }
        return false
    }

    internal fun isBlockedHostLiteral(
        rawHost: String,
        allowLoopbackForTest: Boolean = false,
    ): Boolean {
        if (rawHost.contains('%')) return true
        val normalizedHost = normalizeHost(rawHost)
        if (normalizedHost.isBlank() ||
            normalizedHost == "0.0.0.0" ||
            normalizedHost == "::" ||
            normalizedHost.endsWith(".local") ||
            normalizedHost.endsWith(".internal")
        ) {
            return true
        }
        // Check IPv6 literal (including IPv4-mapped, IPv4-compatible, IPv4-translated, NAT64, 6to4, Teredo, ULA, link-local)
        if (normalizedHost.contains(':')) {
            if (!IPV6_LITERAL_CHARS.matches(normalizedHost)) return true
            val parsed = runCatching { InetAddress.getByName("[$normalizedHost]") }.getOrNull() ?: return true
            // Java unwraps ::ffff:a.b.c.d into Inet4Address; never allow IPv4-mapped loopback even in test mode
            val effectiveAllowLoopback = allowLoopbackForTest && parsed is Inet6Address
            return isBlockedAddress(parsed, effectiveAllowLoopback)
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
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            val b2 = bytes[2].toInt() and 0xFF
            val b3 = bytes[3].toInt() and 0xFF
            val b4 = bytes[4].toInt() and 0xFF
            val b5 = bytes[5].toInt() and 0xFF
            // Unique Local Address fc00::/7
            if ((b0 and 0xFE) == 0xFC) return true
            // Link-local fe80::/10
            if (b0 == 0xFE && (b1 and 0xC0) == 0x80) return true
            // NAT64 local-use prefix 64:ff9b:1::/48 (RFC 8215)
            if (b0 == 0x00 && b1 == 0x64 && b2 == 0xFF && b3 == 0x9B && b4 == 0x00 && b5 == 0x01) {
                return true
            }
            // Extract embedded IPv4 from transition/compatibility mechanisms (never allow wrapped loopback)
            val embeddedIpv4 = extractEmbeddedIpv4(bytes)
            if (embeddedIpv4 != null) {
                return isBlockedAddress(embeddedIpv4, allowLoopbackForTest = false)
            }
        }
        return false
    }

    private fun extractEmbeddedIpv4(bytes: ByteArray): InetAddress? {
        if (bytes.size != 16) return null
        val zero0to7 = (0 until 8).all { bytes[it] == 0.toByte() }
        val zero8to9 = bytes[8] == 0.toByte() && bytes[9] == 0.toByte()
        val zero10to11 = bytes[10] == 0.toByte() && bytes[11] == 0.toByte()
        // 1. IPv4-mapped ::ffff:a.b.c.d
        if (zero0to7 && zero8to9 && bytes[10] == 0xFF.toByte() && bytes[11] == 0xFF.toByte()) {
            return InetAddress.getByAddress(bytes.copyOfRange(12, 16))
        }
        // 2. Deprecated IPv4-compatible ::a.b.c.d (excluding :: and ::1 already checked above)
        if (zero0to7 && zero8to9 && zero10to11) {
            return InetAddress.getByAddress(bytes.copyOfRange(12, 16))
        }
        // 3. IPv4-translated ::ffff:0:a.b.c.d (RFC 2765)
        if (zero0to7 && bytes[8] == 0xFF.toByte() && bytes[9] == 0xFF.toByte() && zero10to11) {
            return InetAddress.getByAddress(bytes.copyOfRange(12, 16))
        }
        // 4. NAT64 Well-Known Prefix 64:ff9b::a.b.c.d (RFC 6052)
        if (bytes[0] == 0x00.toByte() && bytes[1] == 0x64.toByte() &&
            bytes[2] == 0xFF.toByte() && bytes[3] == 0x9B.toByte() &&
            (4 until 12).all { bytes[it] == 0.toByte() }
        ) {
            return InetAddress.getByAddress(bytes.copyOfRange(12, 16))
        }
        // 5. 6to4 2002:AABB:CCDD::/48 (RFC 3056)
        if (bytes[0] == 0x20.toByte() && bytes[1] == 0x02.toByte()) {
            return InetAddress.getByAddress(bytes.copyOfRange(2, 6))
        }
        // 6. Teredo 2001:0000::/32 (RFC 4380) — client IPv4 is bitwise-inverted in bytes 12..15
        if (bytes[0] == 0x20.toByte() && bytes[1] == 0x01.toByte() &&
            bytes[2] == 0x00.toByte() && bytes[3] == 0x00.toByte()
        ) {
            val inverted = ByteArray(4) { i -> (bytes[12 + i].toInt() xor 0xFF).toByte() }
            return InetAddress.getByAddress(inverted)
        }
        return null
    }

    /**
     * OkHttp [Dns] guard invoked BEFORE TCP socket connect on both initial requests and every
     * HTTP redirect hop, blocking private/loopback/link-local/ULA targets with zero packets sent.
     */
    fun createSafeUpstreamDns(
        allowLoopbackForTest: Boolean = false,
        onBlocked: (String) -> Unit = {},
    ): Dns = Dns { hostname ->
        val normalized = normalizeHost(hostname)
        if (normalized.isBlank() || hostname.contains('%')) {
            onBlocked("Redirect/URL inválida bloqueada por segurança (anti-SSRF)")
            throw UnknownHostException("Blocked unsafe upstream host: $hostname")
        }
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
        if (addresses.isEmpty() || addresses.any { isBlockedAddress(it, allowLoopbackForTest) }) {
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
            if (host == null) return
            if (host.contains('%')) {
                onBlocked("Redirect/host inválido bloqueado antes da conexão (anti-SSRF)")
                throw IOException("Blocked unsafe upstream host: $host")
            }
            val normalized = normalizeHost(host)
            if (normalized.isBlank()) {
                onBlocked("Redirect/host vazio bloqueado antes da conexão (anti-SSRF)")
                throw IOException("Blocked unsafe upstream host: $host")
            }
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

        private fun resolveAndVerifyAll(host: String): InetAddress {
            checkHost(host)
            val all = InetAddress.getAllByName(host)
            if (all.isEmpty()) throw IOException("Blocked unresolvable upstream host: $host")
            for (addr in all) {
                checkAddress(addr)
            }
            return all.first()
        }

        override fun createSocket(): Socket = object : Socket() {
            override fun connect(endpoint: SocketAddress?) {
                val safeEndpoint = validateEndpoint(endpoint)
                super.connect(safeEndpoint)
            }

            override fun connect(endpoint: SocketAddress?, timeout: Int) {
                val safeEndpoint = validateEndpoint(endpoint)
                super.connect(safeEndpoint, timeout)
            }

            private fun validateEndpoint(endpoint: SocketAddress?): InetSocketAddress {
                val isa = endpoint as? InetSocketAddress
                    ?: throw IOException("Blocked unsupported upstream SocketAddress")
                checkHost(isa.hostString)
                val resolvedAddr = isa.address
                if (isa.isUnresolved || resolvedAddr == null) {
                    val safeAddr = resolveAndVerifyAll(isa.hostString)
                    return InetSocketAddress(safeAddr, isa.port)
                }
                checkAddress(resolvedAddr)
                return isa
            }
        }

        override fun createSocket(host: String, port: Int): Socket {
            val addr = resolveAndVerifyAll(host)
            return Socket(addr, port)
        }

        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket {
            val addr = resolveAndVerifyAll(host)
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
