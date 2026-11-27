package play.ott.nativeapp.cast

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal data class HlsSegment(
    val sequence: Long,
    val uri: String,
    val keyMethod: String,
    val keyUri: String?,
    val ivHex: String?,
)

internal data class ParsedMediaPlaylist(
    val targetDurationSec: Int,
    val mediaSequence: Long,
    val segments: List<HlsSegment>,
    val isEnded: Boolean,
)

internal data class ByteRangeSpec(
    val start: Long,
    val end: Long,
    val isPartial: Boolean,
)

internal data class ProxyRuntimeStats(
    val bytesSent: Long = 0L,
    val chunksSent: Int = 0,
    val upstreamReconnects: Int = 0,
    val activeClients: Int = 0,
)

/**
 * Pure HLS manifest parser, CORS manifest rewriter, HTTP Range parser, MPEG-TS packet aligner,
 * MPEG-TS Null Packet generator, and RFC 8216 AES-128-CBC segment decryptor.
 */
internal object HlsRemuxCodec {
    private val BANDWIDTH_REGEX = Regex("BANDWIDTH=(\\d+)", RegexOption.IGNORE_CASE)
    private val METHOD_REGEX = Regex("METHOD=([^,\\s]+)", RegexOption.IGNORE_CASE)
    private val URI_REGEX = Regex("URI=\"([^\"]+)\"", RegexOption.IGNORE_CASE)
    private val IV_REGEX = Regex("IV=0x([0-9a-fA-F]+)", RegexOption.IGNORE_CASE)

    /**
     * Standard ISO/IEC 13818-1 MPEG-TS Null Packet (188 bytes, PID 0x1FFF).
     * Hardware Smart TV demuxers silently discard null packets while keeping the
     * TCP stream active across 5–10s live HLS segment intervals.
     */
    fun createMpegTsNullBurst(packetCount: Int = 8): ByteArray {
        val count = packetCount.coerceAtLeast(1)
        val out = ByteArray(188 * count)
        for (p in 0 until count) {
            val base = p * 188
            out[base] = 0x47
            out[base + 1] = 0x1F
            out[base + 2] = 0xFF.toByte()
            out[base + 3] = 0x10
            for (i in 4 until 188) {
                out[base + i] = 0xFF.toByte()
            }
        }
        return out
    }

    /**
     * Aligns an MPEG-TS segment strictly on 188-byte `0x47` packet boundaries, stripping any
     * leading ID3/garbage bytes and any trailing partial packet so hardware TV demuxers stay locked.
     */
    fun alignMpegTsPackets(data: ByteArray): ByteArray {
        if (data.size < 188) return ByteArray(0)
        var start = -1
        val limit = minOf(data.size - 188, 8192)
        for (i in 0..limit) {
            if (data[i] == 0x47.toByte() && (i + 188 >= data.size || data[i + 188] == 0x47.toByte())) {
                start = i
                break
            }
        }
        if (start < 0) return ByteArray(0)
        val alignedLen = ((data.size - start) / 188) * 188
        if (alignedLen <= 0) return ByteArray(0)
        return if (start == 0 && alignedLen == data.size) data else data.copyOfRange(start, start + alignedLen)
    }

    /**
     * Parses standard HTTP `Range: bytes=start-end` headers against `totalLength`.
     */
    fun parseByteRange(rangeHeader: String?, totalLength: Long): ByteRangeSpec? {
        if (totalLength <= 0L) return null
        if (rangeHeader.isNullOrBlank()) {
            return ByteRangeSpec(start = 0L, end = totalLength - 1L, isPartial = false)
        }
        val clean = rangeHeader.trim()
        if (!clean.startsWith("bytes=", ignoreCase = true)) {
            return ByteRangeSpec(start = 0L, end = totalLength - 1L, isPartial = false)
        }
        val firstRange = clean.substring(6).substringBefore(',').trim()
        val dash = firstRange.indexOf('-')
        if (dash < 0) return null
        val startPart = firstRange.substring(0, dash).trim()
        val endPart = firstRange.substring(dash + 1).trim()
        return if (startPart.isEmpty()) {
            val suffix = endPart.toLongOrNull()?.coerceAtLeast(1L) ?: return null
            val start = (totalLength - suffix).coerceAtLeast(0L)
            ByteRangeSpec(start = start, end = totalLength - 1L, isPartial = true)
        } else {
            val start = startPart.toLongOrNull() ?: return null
            if (start < 0L || start >= totalLength) return null
            val end = if (endPart.isEmpty()) {
                totalLength - 1L
            } else {
                (endPart.toLongOrNull() ?: (totalLength - 1L)).coerceAtMost(totalLength - 1L)
            }
            if (end < start) return null
            ByteRangeSpec(start = start, end = end, isPartial = true)
        }
    }

    fun isMasterPlaylist(text: String): Boolean =
        text.contains("#EXT-X-STREAM-INF", ignoreCase = true)

    fun isM3u8Playlist(text: String): Boolean =
        text.trimStart().startsWith("#EXTM3U", ignoreCase = true)

    /** Picks a balanced HD variant (preferring <= 3.6 Mbps for smooth Wi-Fi TV streaming). */
    fun pickBestVariantUrl(masterText: String, baseUrl: String): String? {
        val base = baseUrl.toHttpUrlOrNull() ?: return null
        val variants = ArrayList<Pair<Long, String>>()
        val lines = masterText.lineSequence().map { it.trim() }.toList()
        var pendingBandwidth = -1L
        for (line in lines) {
            if (line.isBlank()) continue
            if (line.startsWith("#EXT-X-STREAM-INF", ignoreCase = true)) {
                pendingBandwidth = BANDWIDTH_REGEX.find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            } else if (!line.startsWith("#") && pendingBandwidth >= 0L) {
                val resolved = base.resolve(line)?.toString()
                if (resolved != null) {
                    variants.add(pendingBandwidth to resolved)
                }
                pendingBandwidth = -1L
            }
        }
        if (variants.isEmpty()) return null
        val balanced = variants.filter { it.first in 900_000L..3_600_000L }.maxByOrNull { it.first }
        return (balanced ?: variants.maxByOrNull { it.first })?.second
    }

    fun parseMediaPlaylist(playlistText: String, baseUrl: String): ParsedMediaPlaylist {
        val base = baseUrl.toHttpUrlOrNull()
        var targetDuration = 5
        var mediaSequence = 0L
        var keyMethod = "NONE"
        var keyUri: String? = null
        var ivHex: String? = null
        var isEnded = false
        var segmentOffset = 0L
        val segments = ArrayList<HlsSegment>()

        for (rawLine in playlistText.lineSequence()) {
            val line = rawLine.trim()
            if (line.isBlank()) continue
            when {
                line.startsWith("#EXT-X-TARGETDURATION:", ignoreCase = true) -> {
                    targetDuration = line.substringAfter(':').trim().toDoubleOrNull()?.toInt()?.coerceIn(1, 30) ?: 5
                }
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:", ignoreCase = true) -> {
                    mediaSequence = line.substringAfter(':').trim().toLongOrNull()?.coerceAtLeast(0L) ?: 0L
                }
                line.startsWith("#EXT-X-KEY:", ignoreCase = true) -> {
                    keyMethod = METHOD_REGEX.find(line)?.groupValues?.get(1)?.uppercase(Locale.ROOT) ?: "NONE"
                    val rawKeyUri = URI_REGEX.find(line)?.groupValues?.get(1)
                    keyUri = if (rawKeyUri != null && keyMethod != "NONE") {
                        base?.resolve(rawKeyUri)?.toString() ?: rawKeyUri
                    } else null
                    ivHex = IV_REGEX.find(line)?.groupValues?.get(1)
                }
                line.startsWith("#EXT-X-ENDLIST", ignoreCase = true) -> {
                    isEnded = true
                }
                !line.startsWith("#") -> {
                    val resolved = base?.resolve(line)?.toString() ?: line
                    segments.add(
                        HlsSegment(
                            sequence = mediaSequence + segmentOffset,
                            uri = resolved,
                            keyMethod = keyMethod,
                            keyUri = keyUri,
                            ivHex = ivHex,
                        ),
                    )
                    segmentOffset++
                }
            }
        }
        return ParsedMediaPlaylist(
            targetDurationSec = targetDuration,
            mediaSequence = mediaSequence,
            segments = segments,
            isEnded = isEnded,
        )
    }

    /**
     * Computes a stable segment identity even when IPTV load balancers switch CDN hostnames
     * or rotate `?token=` query params between playlist polls.
     */
    fun segmentDedupKey(segUri: String, queryDifferentiated: Boolean): String {
        if (queryDifferentiated) return segUri
        val pathOnly = segUri.substringBefore('?')
        val fileName = pathOnly.substringAfterLast('/')
        return if (fileName.length >= 6 && fileName.any(Char::isDigit)) fileName else pathOnly
    }

    /**
     * Rewrites every URI inside an HLS master/media manifest (variants, segments, AES keys, maps)
     * through a session-scoped local relay function so Chromecast's Shaka player receives full CORS headers.
     */
    fun rewritePlaylistForRelay(
        playlistText: String,
        baseUrl: String,
        registerRemoteUrl: (String) -> String,
    ): String {
        val base = baseUrl.toHttpUrlOrNull()
        val out = StringBuilder(playlistText.length + 256)
        for (rawLine in playlistText.lineSequence()) {
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed.startsWith("#")) {
                val rewrittenTag = URI_REGEX.replace(trimmed) { match ->
                    val rawRef = match.groupValues[1]
                    val abs = base?.resolve(rawRef)?.toString() ?: rawRef
                    "URI=\"${registerRemoteUrl(abs)}\""
                }
                out.append(rewrittenTag).append('\n')
            } else {
                val abs = base?.resolve(trimmed)?.toString() ?: trimmed
                out.append(registerRemoteUrl(abs)).append('\n')
            }
        }
        return out.toString()
    }

    fun buildIvBytes(ivHex: String?, sequenceNumber: Long): ByteArray {
        if (!ivHex.isNullOrBlank()) {
            val clean = ivHex.trim().removePrefix("0x").removePrefix("0X")
            val padded = clean.padStart(32, '0').takeLast(32)
            return ByteArray(16) { idx ->
                padded.substring(idx * 2, idx * 2 + 2).toInt(16).toByte()
            }
        }
        return ByteBuffer.allocate(16).putLong(0L).putLong(sequenceNumber).array()
    }

    fun decryptAes128Cbc(encrypted: ByteArray, keyBytes: ByteArray, ivBytes: ByteArray): ByteArray {
        require(keyBytes.size == 16) { "AES-128 key must be 16 bytes" }
        require(ivBytes.size == 16) { "AES-128 IV must be 16 bytes" }
        val keySpec = SecretKeySpec(keyBytes, "AES")
        val ivSpec = IvParameterSpec(ivBytes)
        return runCatching {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
            cipher.doFinal(encrypted)
        }.getOrElse {
            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
            cipher.doFinal(encrypted)
        }
    }
}

internal data class ProxiedCastTarget(
    val localUrl: String,
    val chromecastHlsUrl: String?,
    val directHttpVodUrl: String?,
    val mimeType: String,
    val dlnaFeatures: String,
    val contentLength: Long?,
    val isLive: Boolean,
)

/**
 * Resilient local Wi-Fi HTTP relay server built for long-running Smart TV & Chromecast playback:
 * - VOD (Movies/Series): Stitches 4 MB upstream `Range` slices into one seamless TV HTTP stream so
 *   Cloudflare Workers (`*.workers.dev`) NEVER disconnect at ~90s, with automatic M81 redirect
 *   re-resolution if upstream tokens expire mid-movie.
 * - Live TV (Pluto AES-128 & IPTV M81 `/live-hls/`): Async HLS -> 188-byte aligned MPEG-TS streamer
 *   with ISO Null Packet (`PID 0x1FFF`) keep-alives and 40-retry upstream resilience.
 * - Chromecast (`CC1AD845`): CORS-enabled `/cast/<token>/live.m3u8` + session-whitelisted `/res/`.
 */
internal class LanStreamProxyServer(
    private val allowLoopbackForTest: Boolean = false,
) {
    private class ActiveStream(
        val token: String,
        val localOrigin: String,
        val originalUpstreamUrl: String,
        @Volatile var resolvedUpstreamUrl: String,
        val isLive: Boolean,
        val isHls: Boolean,
        val mimeType: String,
        val contentLength: Long?,
        val supportsRanges: Boolean,
        val dlnaFeatures: String,
    )

    private val running = AtomicBoolean(false)
    private val activeStream = AtomicReference<ActiveStream?>(null)
    private var serverSocket: ServerSocket? = null
    private val clientSockets = CopyOnWriteArrayList<Socket>()
    private val hlsRelayUrls = ConcurrentHashMap<String, String>()
    private val hlsUrlToId = ConcurrentHashMap<String, String>()
    private val bytesSentCounter = AtomicLong(0L)
    private val chunksSentCounter = AtomicInteger(0)
    private val reconnectCounter = AtomicInteger(0)

    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r, "StarlinkCastProxy").apply { isDaemon = true }
    }
    private val random = SecureRandom()
    private val nullTsBurst = HlsRemuxCodec.createMpegTsNullBurst(packetCount = 10)

    /** Short timeouts for playlist/probe/segment/4MB-slice fetches so stalls recover rapidly. */
    private val controlClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /** Infinite read timeout for continuous raw streams. */
    private val streamingClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(0, TimeUnit.MILLISECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun currentStats(): ProxyRuntimeStats = ProxyRuntimeStats(
        bytesSent = bytesSentCounter.get(),
        chunksSent = chunksSentCounter.get(),
        upstreamReconnects = reconnectCounter.get(),
        activeClients = clientSockets.size,
    )

    @Synchronized
    fun ensureStarted(): Int {
        val existing = serverSocket
        if (running.get() && existing != null && !existing.isClosed) {
            return existing.localPort
        }
        val socket = ServerSocket(0, 24, InetAddress.getByName("0.0.0.0"))
        serverSocket = socket
        running.set(true)
        executor.execute { acceptLoop(socket) }
        return socket.localPort
    }

    fun prepareStream(
        upstreamUrl: String,
        isLive: Boolean,
        localLanIp: String,
    ): ProxiedCastTarget {
        val port = ensureStarted()
        closeActiveClients()
        hlsRelayUrls.clear()
        hlsUrlToId.clear()
        bytesSentCounter.set(0L)
        chunksSentCounter.set(0)
        reconnectCounter.set(0)

        val probe = probeUpstream(upstreamUrl, isLive)
        val token = randomToken()
        val ext = when {
            isLive -> "live.ts"
            probe.mimeType.contains("matroska") -> "video.mkv"
            probe.mimeType.contains("webm") -> "video.webm"
            probe.mimeType.contains("mp2t") -> "video.ts"
            else -> "video.mp4"
        }
        val dlnaFeatures = if (isLive) {
            "DLNA.ORG_OP=00;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"
        } else {
            "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"
        }
        val localOrigin = "http://$localLanIp:$port"
        val config = ActiveStream(
            token = token,
            localOrigin = localOrigin,
            originalUpstreamUrl = upstreamUrl,
            resolvedUpstreamUrl = probe.finalUrl,
            isLive = isLive,
            isHls = probe.isHls,
            mimeType = probe.mimeType,
            contentLength = if (isLive) null else probe.contentLength,
            supportsRanges = !isLive && probe.supportsRanges && (probe.contentLength ?: 0L) > 0L,
            dlnaFeatures = dlnaFeatures,
        )
        activeStream.set(config)

        val localUrl = "$localOrigin/cast/$token/$ext"
        val castHlsUrl = if (isLive && probe.isHls) "$localOrigin/cast/$token/live.m3u8" else null
        val directHttpVod = if (!isLive && probe.finalUrl.startsWith("http://", ignoreCase = true) &&
            !probe.finalUrl.contains(".workers.dev", ignoreCase = true)
        ) {
            probe.finalUrl
        } else null

        return ProxiedCastTarget(
            localUrl = localUrl,
            chromecastHlsUrl = castHlsUrl,
            directHttpVodUrl = directHttpVod,
            mimeType = config.mimeType,
            dlnaFeatures = dlnaFeatures,
            contentLength = config.contentLength,
            isLive = isLive,
        )
    }

    fun clearStream() {
        activeStream.set(null)
        hlsRelayUrls.clear()
        hlsUrlToId.clear()
        closeActiveClients()
    }

    @Synchronized
    fun shutdown() {
        running.set(false)
        activeStream.set(null)
        hlsRelayUrls.clear()
        hlsUrlToId.clear()
        closeActiveClients()
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private fun closeActiveClients() {
        val snapshot = ArrayList(clientSockets)
        clientSockets.clear()
        for (sock in snapshot) {
            runCatching { sock.close() }
        }
    }

    private fun acceptLoop(server: ServerSocket) {
        while (running.get() && !server.isClosed) {
            val client = try {
                server.accept()
            } catch (_: Exception) {
                if (!running.get()) break
                continue
            }
            val remoteHost = client.inetAddress?.hostAddress.orEmpty()
            val allowed = DlnaProtocol.isPrivateLanHost(remoteHost) ||
                (allowLoopbackForTest && (remoteHost == "127.0.0.1" || remoteHost == "::1"))
            if (!allowed) {
                runCatching { client.close() }
                continue
            }
            clientSockets.add(client)
            executor.execute {
                try {
                    handleClient(client)
                } catch (_: Exception) {
                    // Normal when TV closes connection or seeks
                } finally {
                    clientSockets.remove(client)
                    runCatching { client.close() }
                }
            }
        }
    }

    private fun handleClient(socket: Socket) {
        socket.soTimeout = 10_000
        socket.tcpNoDelay = true
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.ISO_8859_1))
        val requestLine = reader.readLine() ?: return
        val parts = requestLine.trim().split(' ')
        if (parts.size < 2) return
        val method = parts[0].uppercase(Locale.ROOT)
        val rawPath = parts[1].substringBefore('?')

        val headers = HashMap<String, String>()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isBlank()) break
            val colon = line.indexOf(':')
            if (colon > 0) {
                headers[line.substring(0, colon).trim().lowercase(Locale.ROOT)] = line.substring(colon + 1).trim()
            }
        }

        // Disable socket read timeout after HTTP headers are parsed so paused/buffering TVs never drop!
        socket.soTimeout = 0

        val out = socket.getOutputStream()
        if (method == "OPTIONS") {
            val cors = "HTTP/1.1 204 No Content\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n" +
                "Access-Control-Allow-Headers: *\r\n" +
                "Access-Control-Expose-Headers: *\r\n" +
                "Access-Control-Max-Age: 86400\r\n" +
                "Connection: close\r\n\r\n"
            out.write(cors.toByteArray(Charsets.ISO_8859_1))
            out.flush()
            return
        }

        val stream = activeStream.get()
        val prefix = stream?.let { "/cast/${it.token}/" }
        if (stream == null || prefix == null || !rawPath.startsWith(prefix)) {
            writeSimpleStatus(out, "404 Not Found")
            return
        }
        if (method != "GET" && method != "HEAD") {
            writeSimpleStatus(out, "405 Method Not Allowed")
            return
        }

        val subPath = rawPath.removePrefix(prefix)
        when {
            subPath == "live.m3u8" && stream.isLive && stream.isHls -> {
                handleChromecastHlsManifest(method, stream, stream.resolvedUpstreamUrl, out)
            }
            subPath.startsWith("res/") -> {
                val resId = subPath.removePrefix("res/")
                val remoteUrl = hlsRelayUrls[resId]
                if (remoteUrl == null) {
                    writeSimpleStatus(out, "404 Not Found")
                } else {
                    handleChromecastHlsResource(method, stream, remoteUrl, out)
                }
            }
            stream.isLive -> handleLiveRequest(method, stream, out)
            else -> handleVodRequest(method, headers["range"], stream, out)
        }
    }

    private fun registerHlsRelayUrl(stream: ActiveStream, remoteUrl: String): String {
        val existing = hlsUrlToId[remoteUrl]
        if (existing != null) return "${stream.localOrigin}/cast/${stream.token}/res/$existing"
        if (hlsRelayUrls.size > 400) {
            hlsRelayUrls.clear()
            hlsUrlToId.clear()
        }
        val id = randomToken()
        hlsRelayUrls[id] = remoteUrl
        hlsUrlToId[remoteUrl] = id
        return "${stream.localOrigin}/cast/${stream.token}/res/$id"
    }

    private fun handleChromecastHlsManifest(
        method: String,
        stream: ActiveStream,
        manifestUrl: String,
        out: OutputStream,
    ) {
        if (method == "HEAD") {
            val head = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/vnd.apple.mpegurl\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Cache-Control: no-cache\r\n" +
                "Connection: close\r\n\r\n"
            out.write(head.toByteArray(Charsets.ISO_8859_1))
            out.flush()
            return
        }
        val fetched = fetchText(manifestUrl)
        if (fetched == null) {
            writeSimpleStatus(out, "502 Bad Gateway")
            return
        }
        val rewritten = HlsRemuxCodec.rewritePlaylistForRelay(fetched.second, fetched.first) { remote ->
            registerHlsRelayUrl(stream, remote)
        }.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: application/vnd.apple.mpegurl\r\n" +
            "Content-Length: ${rewritten.size}\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Access-Control-Expose-Headers: *\r\n" +
            "Cache-Control: no-cache, no-store\r\n" +
            "Connection: close\r\n\r\n"
        out.write(header.toByteArray(Charsets.ISO_8859_1))
        out.write(rewritten)
        out.flush()
    }

    private fun handleChromecastHlsResource(
        method: String,
        stream: ActiveStream,
        remoteUrl: String,
        out: OutputStream,
    ) {
        val req = Request.Builder()
            .url(remoteUrl)
            .header("User-Agent", "OTT-play-Android/1.0")
            .get()
            .build()
        controlClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                writeSimpleStatus(out, "${resp.code} Upstream Error")
                return
            }
            val ct = resp.header("Content-Type").orEmpty()
            val bytes = resp.body.bytes()
            val isSubPlaylist = ct.contains("mpegurl", ignoreCase = true) ||
                remoteUrl.substringBefore('?').lowercase(Locale.ROOT).endsWith(".m3u8") ||
                (bytes.size < 256_000 && HlsRemuxCodec.isM3u8Playlist(String(bytes, Charsets.UTF_8)))
            val finalBytes = if (isSubPlaylist) {
                HlsRemuxCodec.rewritePlaylistForRelay(
                    String(bytes, Charsets.UTF_8),
                    resp.request.url.toString(),
                ) { remote -> registerHlsRelayUrl(stream, remote) }.toByteArray(Charsets.UTF_8)
            } else {
                bytes
            }
            val finalCt = if (isSubPlaylist) "application/vnd.apple.mpegurl" else ct.ifBlank { "video/mp2t" }
            val header = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: $finalCt\r\n" +
                "Content-Length: ${finalBytes.size}\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Access-Control-Expose-Headers: *\r\n" +
                "Cache-Control: no-cache\r\n" +
                "Connection: close\r\n\r\n"
            out.write(header.toByteArray(Charsets.ISO_8859_1))
            if (method != "HEAD") {
                out.write(finalBytes)
                bytesSentCounter.addAndGet(finalBytes.size.toLong())
                chunksSentCounter.incrementAndGet()
            }
            out.flush()
        }
    }

    private fun handleLiveRequest(method: String, stream: ActiveStream, out: OutputStream) {
        val respHeaders = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: ${stream.mimeType}\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("transferMode.dlna.org: Streaming\r\n")
            append("contentFeatures.dlna.org: ${stream.dlnaFeatures}\r\n")
            append("Accept-Ranges: none\r\n")
            append("Cache-Control: no-cache, no-store\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        out.write(respHeaders.toByteArray(Charsets.ISO_8859_1))
        out.flush()
        if (method == "HEAD") return

        if (stream.isHls) {
            streamHlsAsMpegTs(stream, out)
        } else {
            streamRawLive(stream, out)
        }
    }

    private fun streamRawLive(stream: ActiveStream, out: OutputStream) {
        var attempts = 0
        while (running.get() && activeStream.get()?.token == stream.token && attempts < 10) {
            attempts++
            try {
                val req = Request.Builder()
                    .url(stream.resolvedUpstreamUrl)
                    .header("User-Agent", "OTT-play-Android/1.0")
                    .get()
                    .build()
                streamingClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Thread.sleep(700L)
                        return@use
                    }
                    attempts = 0
                    val input = BufferedInputStream(resp.body.byteStream(), 32 * 1024)
                    val buffer = ByteArray(32 * 1024)
                    while (running.get() && activeStream.get()?.token == stream.token) {
                        val n = input.read(buffer)
                        if (n <= 0) break
                        out.write(buffer, 0, n)
                        out.flush()
                        bytesSentCounter.addAndGet(n.toLong())
                    }
                }
                reconnectCounter.incrementAndGet()
            } catch (io: IOException) {
                // Distinguish TV socket drop from upstream error by probing a null packet
                runCatching { out.write(nullTsBurst); out.flush() }.onFailure { return }
                reconnectCounter.incrementAndGet()
                runCatching { Thread.sleep(600L) }
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    /**
     * Async producer-consumer HLS -> 188-byte aligned MPEG-TS live streamer:
     * - Starts with up to 3 buffered segments for instant playback + buffer cushion.
     * - Strictly aligns every segment on 188-byte `0x47` TS boundaries before writing.
     * - Tolerates up to 40 consecutive upstream hiccups (~60s) with automatic master/token refresh
     *   while emitting ISO MPEG-TS Null Packets (`PID 0x1FFF`) every 1.2s so Samsung TV never times out!
     */
    private fun streamHlsAsMpegTs(stream: ActiveStream, out: OutputStream) {
        val queue = LinkedBlockingQueue<ByteArray>(8)
        val producerAlive = AtomicBoolean(true)
        val clientAlive = AtomicBoolean(true)

        val producer = executor.submit {
            val seenKeys = LinkedHashSet<String>()
            val keyCache = LinkedHashMap<String, ByteArray>(16, 0.75f, true)
            var mediaPlaylistUrl: String? = null
            var firstFetch = true
            var consecutiveErrors = 0

            try {
                while (running.get() && clientAlive.get() && activeStream.get()?.token == stream.token) {
                    val targetUrl = mediaPlaylistUrl ?: stream.originalUpstreamUrl
                    val playlistResp = fetchText(targetUrl)
                    if (playlistResp == null) {
                        consecutiveErrors++
                        mediaPlaylistUrl = null
                        reconnectCounter.incrementAndGet()
                        if (consecutiveErrors >= 40) break
                        Thread.sleep(750L)
                        continue
                    }
                    val (effectiveUrl, playlistText) = playlistResp
                    val resolvedMedia = if (HlsRemuxCodec.isMasterPlaylist(playlistText)) {
                        val picked = HlsRemuxCodec.pickBestVariantUrl(playlistText, effectiveUrl)
                        if (picked == null) {
                            consecutiveErrors++
                            mediaPlaylistUrl = null
                            if (consecutiveErrors >= 40) break
                            Thread.sleep(750L)
                            continue
                        }
                        mediaPlaylistUrl = picked
                        fetchText(picked) ?: run {
                            consecutiveErrors++
                            mediaPlaylistUrl = null
                            Thread.sleep(750L)
                            continue
                        }
                    } else {
                        effectiveUrl to playlistText
                    }

                    val parsed = HlsRemuxCodec.parseMediaPlaylist(resolvedMedia.second, resolvedMedia.first)
                    if (parsed.segments.isEmpty()) {
                        consecutiveErrors++
                        mediaPlaylistUrl = null
                        if (consecutiveErrors >= 40) break
                        Thread.sleep(750L)
                        continue
                    }
                    consecutiveErrors = 0

                    // Start with last 3 segments (balanced ~15–25s buffer without flooding low-RAM TVs)
                    val candidates = if (firstFetch && parsed.segments.size > 3) {
                        firstFetch = false
                        parsed.segments.takeLast(3)
                    } else {
                        firstFetch = false
                        parsed.segments
                    }

                    val queryDiff = parsed.segments.map { it.uri.substringBefore('?') }.distinct().size < parsed.segments.size
                    var addedCount = 0

                    for (seg in candidates) {
                        if (!running.get() || !clientAlive.get() || activeStream.get()?.token != stream.token) return@submit
                        val dedupKey = HlsRemuxCodec.segmentDedupKey(seg.uri, queryDiff)
                        if (!seenKeys.add(dedupKey)) continue
                        if (seenKeys.size > 160) {
                            seenKeys.iterator().let { it.next(); it.remove() }
                        }

                        val rawBytes = fetchBytes(seg.uri) ?: fetchBytes(seg.uri) ?: continue
                        val decrypted = if (seg.keyMethod == "AES-128" && !seg.keyUri.isNullOrBlank()) {
                            val keyBytes = keyCache[seg.keyUri] ?: fetchBytes(seg.keyUri)?.also { fetched ->
                                if (fetched.size == 16) {
                                    keyCache[seg.keyUri] = fetched
                                    if (keyCache.size > 24) {
                                        keyCache.keys.firstOrNull()?.let(keyCache::remove)
                                    }
                                }
                            }
                            if (keyBytes != null && keyBytes.size == 16) {
                                val iv = HlsRemuxCodec.buildIvBytes(seg.ivHex, seg.sequence)
                                HlsRemuxCodec.decryptAes128Cbc(rawBytes, keyBytes, iv)
                            } else {
                                continue
                            }
                        } else {
                            rawBytes
                        }

                        val alignedTs = HlsRemuxCodec.alignMpegTsPackets(decrypted)
                        if (alignedTs.isEmpty()) continue

                        while (running.get() && clientAlive.get() && activeStream.get()?.token == stream.token) {
                            if (queue.offer(alignedTs, 500L, TimeUnit.MILLISECONDS)) {
                                addedCount++
                                break
                            }
                        }
                    }

                    if (parsed.isEnded) break
                    val waitMs = if (addedCount > 0) {
                        (parsed.targetDurationSec * 250L).coerceIn(700L, 1_800L)
                    } else {
                        (parsed.targetDurationSec * 200L).coerceIn(600L, 1_400L)
                    }
                    Thread.sleep(waitMs)
                }
            } catch (_: InterruptedException) {
                // Clean exit
            } catch (_: Exception) {
                // Exit producer cleanly
            } finally {
                producerAlive.set(false)
            }
        }

        try {
            var sentFirstRealSegment = false
            while (running.get() && activeStream.get()?.token == stream.token) {
                val chunk = queue.poll(1_200L, TimeUnit.MILLISECONDS)
                if (chunk != null) {
                    writeChunkedToTv(out, chunk)
                    bytesSentCounter.addAndGet(chunk.size.toLong())
                    chunksSentCounter.incrementAndGet()
                    sentFirstRealSegment = true
                } else {
                    if (!producerAlive.get() && queue.isEmpty()) break
                    if (sentFirstRealSegment) {
                        out.write(nullTsBurst)
                        out.flush()
                    }
                }
            }
        } catch (_: IOException) {
            // Normal when TV closes connection
        } finally {
            clientAlive.set(false)
            producer.cancel(true)
        }
    }

    private fun writeChunkedToTv(out: OutputStream, data: ByteArray) {
        var offset = 0
        val step = 32 * 1024
        while (offset < data.size) {
            val len = minOf(step, data.size - offset)
            out.write(data, offset, len)
            offset += len
        }
        out.flush()
    }

    /**
     * VOD (Movies & Series) handler with **4 MB Chunked-Range Stitching + Auto-Token-Refresh**:
     * Defeats Cloudflare Workers (`*.workers.dev`) ~90s single-connection caps completely!
     * Each 4 MB upstream slice completes in < 1s; if any slice fails or expires mid-movie,
     * the proxy automatically re-resolves the original M81 `/movie/...` redirect and continues
     * seamlessly from the exact byte offset on the same TV TCP socket.
     */
    private fun handleVodRequest(method: String, rangeHeader: String?, stream: ActiveStream, out: OutputStream) {
        val totalLen = stream.contentLength
        if (method == "HEAD") {
            val headText = buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("Content-Type: ${stream.mimeType}\r\n")
                append("Access-Control-Allow-Origin: *\r\n")
                if (totalLen != null && totalLen > 0L) {
                    append("Content-Length: $totalLen\r\n")
                }
                append("Accept-Ranges: bytes\r\n")
                append("transferMode.dlna.org: Streaming\r\n")
                append("contentFeatures.dlna.org: ${stream.dlnaFeatures}\r\n")
                append("Connection: close\r\n")
                append("\r\n")
            }
            out.write(headText.toByteArray(Charsets.ISO_8859_1))
            out.flush()
            return
        }

        if (stream.supportsRanges && totalLen != null && totalLen > 0L) {
            val range = HlsRemuxCodec.parseByteRange(rangeHeader, totalLen)
            if (range == null) {
                val err = "HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$totalLen\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                out.write(err.toByteArray(Charsets.ISO_8859_1))
                out.flush()
                return
            }

            val responseLen = (range.end - range.start) + 1L
            val statusLine = if (range.isPartial) "206 Partial Content" else "200 OK"
            val headerBlock = buildString {
                append("HTTP/1.1 $statusLine\r\n")
                append("Content-Type: ${stream.mimeType}\r\n")
                append("Access-Control-Allow-Origin: *\r\n")
                append("Access-Control-Expose-Headers: *\r\n")
                append("Content-Length: $responseLen\r\n")
                if (range.isPartial) {
                    append("Content-Range: bytes ${range.start}-${range.end}/$totalLen\r\n")
                }
                append("Accept-Ranges: bytes\r\n")
                append("transferMode.dlna.org: Streaming\r\n")
                append("contentFeatures.dlna.org: ${stream.dlnaFeatures}\r\n")
                append("Connection: close\r\n")
                append("\r\n")
            }
            out.write(headerBlock.toByteArray(Charsets.ISO_8859_1))
            out.flush()

            // Producer-consumer 2 MB RAM-buffered slice prefetch:
            // Upstream HTTP socket finishes & closes in ~0.3s, so even if the TV pauses for
            // 30+ minutes (blocking out.write), ZERO upstream socket can ever time out!
            val sliceQueue = LinkedBlockingQueue<ByteArray>(2)
            val producerAlive = AtomicBoolean(true)
            val clientAlive = AtomicBoolean(true)

            val sliceProducer = executor.submit {
                var cursor = range.start
                var sliceRetries = 0
                try {
                    while (cursor <= range.end && running.get() && clientAlive.get() && activeStream.get()?.token == stream.token) {
                        val sliceEnd = minOf(cursor + VOD_SLICE_BYTES - 1L, range.end)
                        val expectedLen = ((sliceEnd - cursor) + 1L).toInt()
                        val fetched = fetchVodSliceBytes(stream.resolvedUpstreamUrl, cursor, sliceEnd, expectedLen)
                        if (fetched != null && fetched.isNotEmpty()) {
                            cursor += fetched.size.toLong()
                            sliceRetries = 0
                            while (running.get() && clientAlive.get() && activeStream.get()?.token == stream.token) {
                                if (sliceQueue.offer(fetched, 500L, TimeUnit.MILLISECONDS)) break
                            }
                        } else {
                            sliceRetries++
                            reconnectCounter.incrementAndGet()
                            if (sliceRetries > 5) break
                            val refreshed = probeUpstream(stream.originalUpstreamUrl, isLive = false)
                            if (refreshed.finalUrl.isNotBlank()) {
                                stream.resolvedUpstreamUrl = refreshed.finalUrl
                            }
                            Thread.sleep(350L * sliceRetries)
                        }
                    }
                } catch (_: InterruptedException) {
                    // Clean exit
                } catch (_: Exception) {
                    // Exit producer cleanly
                } finally {
                    producerAlive.set(false)
                }
            }

            try {
                while (running.get() && activeStream.get()?.token == stream.token) {
                    val data = sliceQueue.poll(1_000L, TimeUnit.MILLISECONDS)
                    if (data != null) {
                        writeChunkedToTv(out, data)
                        bytesSentCounter.addAndGet(data.size.toLong())
                        chunksSentCounter.incrementAndGet()
                    } else if (!producerAlive.get() && sliceQueue.isEmpty()) {
                        break
                    }
                }
            } catch (_: IOException) {
                // Normal when TV closes connection or seeks
            } finally {
                clientAlive.set(false)
                sliceProducer.cancel(true)
            }
            return
        }

        // Fallback for non-rangeable VOD servers
        val reqBuilder = Request.Builder()
            .url(stream.resolvedUpstreamUrl)
            .header("User-Agent", "OTT-play-Android/1.0")
            .get()
        if (!rangeHeader.isNullOrBlank()) {
            reqBuilder.header("Range", rangeHeader)
        }
        streamingClient.newCall(reqBuilder.build()).execute().use { resp ->
            if (!resp.isSuccessful && resp.code != 206) {
                writeSimpleStatus(out, "${resp.code} Upstream Error")
                return
            }
            val statusLine = if (resp.code == 206) "206 Partial Content" else "200 OK"
            val contentLength = resp.header("Content-Length") ?: resp.body.contentLength().takeIf { it > 0L }?.toString()
            val contentRange = resp.header("Content-Range")
            val headerBlock = buildString {
                append("HTTP/1.1 $statusLine\r\n")
                append("Content-Type: ${stream.mimeType}\r\n")
                append("Access-Control-Allow-Origin: *\r\n")
                if (contentLength != null) append("Content-Length: $contentLength\r\n")
                if (contentRange != null) append("Content-Range: $contentRange\r\n")
                append("Accept-Ranges: bytes\r\n")
                append("transferMode.dlna.org: Streaming\r\n")
                append("contentFeatures.dlna.org: ${stream.dlnaFeatures}\r\n")
                append("Connection: close\r\n")
                append("\r\n")
            }
            out.write(headerBlock.toByteArray(Charsets.ISO_8859_1))
            out.flush()

            val input = BufferedInputStream(resp.body.byteStream(), 64 * 1024)
            val buffer = ByteArray(64 * 1024)
            while (running.get() && activeStream.get()?.token == stream.token) {
                val n = input.read(buffer)
                if (n <= 0) break
                out.write(buffer, 0, n)
                bytesSentCounter.addAndGet(n.toLong())
            }
            out.flush()
        }
    }

    private data class UpstreamProbe(
        val finalUrl: String,
        val isHls: Boolean,
        val mimeType: String,
        val contentLength: Long?,
        val supportsRanges: Boolean,
    )

    private fun probeUpstream(url: String, isLive: Boolean): UpstreamProbe {
        if (isLive) {
            val lower = url.lowercase(Locale.ROOT)
            val looksHls = lower.contains(".m3u8") || lower.contains("/live-hls/") || lower.contains("/pluto/")
            if (looksHls) {
                return UpstreamProbe(url, isHls = true, mimeType = "video/mp2t", contentLength = null, supportsRanges = false)
            }
            val detected = runCatching {
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", "OTT-play-Android/1.0")
                    .header("Range", "bytes=0-1023")
                    .get()
                    .build()
                controlClient.newCall(req).execute().use { resp ->
                    val ct = resp.header("Content-Type").orEmpty().lowercase(Locale.ROOT)
                    val finalUrl = resp.request.url.toString()
                    val headBytes = ByteArray(16)
                    val read = resp.body.byteStream().read(headBytes)
                    val headStr = if (read > 0) String(headBytes, 0, read, Charsets.ISO_8859_1) else ""
                    val hls = ct.contains("mpegurl") ||
                        finalUrl.lowercase(Locale.ROOT).contains(".m3u8") ||
                        HlsRemuxCodec.isM3u8Playlist(headStr)
                    UpstreamProbe(finalUrl, isHls = hls, mimeType = "video/mp2t", contentLength = null, supportsRanges = false)
                }
            }.getOrNull()
            return detected ?: UpstreamProbe(url, isHls = false, mimeType = "video/mp2t", contentLength = null, supportsRanges = false)
        }

        // VOD: use Range GET 0-0 first (M81 /movie/ returns 405 on HEAD, while GET 0-0 resolves 307->302->206 in 1 shot!)
        val rangeProbe = runCatching {
            val rangeReq = Request.Builder()
                .url(url)
                .header("User-Agent", "OTT-play-Android/1.0")
                .header("Range", "bytes=0-0")
                .get()
                .build()
            controlClient.newCall(rangeReq).execute().use { resp ->
                if (resp.code == 206 || resp.isSuccessful) {
                    val finalUrl = resp.request.url.toString()
                    val totalFromRange = resp.header("Content-Range")?.substringAfter('/')?.toLongOrNull()
                    val length = totalFromRange ?: resp.header("Content-Length")?.toLongOrNull()
                    val rangesOk = resp.code == 206 || resp.header("Accept-Ranges").equals("bytes", ignoreCase = true)
                    val mime = normalizeVodMime(resp.header("Content-Type"), finalUrl)
                    UpstreamProbe(finalUrl, isHls = false, mimeType = mime, contentLength = length, supportsRanges = rangesOk)
                } else null
            }
        }.getOrNull() ?: runCatching {
            val headReq = Request.Builder()
                .url(url)
                .header("User-Agent", "OTT-play-Android/1.0")
                .head()
                .build()
            controlClient.newCall(headReq).execute().use { resp ->
                if (resp.isSuccessful) {
                    val finalUrl = resp.request.url.toString()
                    val length = resp.header("Content-Length")?.toLongOrNull()
                    val rangesOk = resp.header("Accept-Ranges").equals("bytes", ignoreCase = true)
                    val mime = normalizeVodMime(resp.header("Content-Type"), finalUrl)
                    UpstreamProbe(finalUrl, isHls = false, mimeType = mime, contentLength = length, supportsRanges = rangesOk)
                } else null
            }
        }.getOrNull()

        return rangeProbe ?: UpstreamProbe(
            finalUrl = url,
            isHls = false,
            mimeType = normalizeVodMime(null, url),
            contentLength = null,
            supportsRanges = false,
        )
    }

    private fun normalizeVodMime(headerCt: String?, url: String): String {
        val cleanCt = headerCt?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT).orEmpty()
        val lowerUrl = url.substringBefore('?').lowercase(Locale.ROOT)
        return when {
            lowerUrl.endsWith(".mkv") || cleanCt.contains("matroska") -> "video/x-matroska"
            lowerUrl.endsWith(".webm") || cleanCt.contains("webm") -> "video/webm"
            lowerUrl.endsWith(".ts") || cleanCt.contains("mp2t") -> "video/mp2t"
            lowerUrl.endsWith(".mp4") || cleanCt.contains("mp4") -> "video/mp4"
            cleanCt.startsWith("video/") -> cleanCt
            else -> "video/mp4"
        }
    }

    private fun fetchText(url: String): Pair<String, String>? = runCatching {
        val req = Request.Builder().url(url).header("User-Agent", "OTT-play-Android/1.0").get().build()
        controlClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            resp.request.url.toString() to resp.body.string()
        }
    }.getOrNull()

    private fun fetchVodSliceBytes(url: String, start: Long, end: Long, expectedLen: Int): ByteArray? = runCatching {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "OTT-play-Android/1.0")
            .header("Range", "bytes=$start-$end")
            .get()
            .build()
        controlClient.newCall(req).execute().use { resp ->
            if (resp.code != 206 && !(resp.code == 200 && start == 0L)) return null
            val input = BufferedInputStream(resp.body.byteStream(), 64 * 1024)
            val baos = java.io.ByteArrayOutputStream(expectedLen.coerceAtMost(VOD_SLICE_BYTES.toInt()))
            val buf = ByteArray(64 * 1024)
            var readTotal = 0
            while (readTotal < expectedLen) {
                val n = input.read(buf, 0, minOf(buf.size, expectedLen - readTotal))
                if (n <= 0) break
                baos.write(buf, 0, n)
                readTotal += n
            }
            if (readTotal > 0) baos.toByteArray() else null
        }
    }.getOrNull()

    private fun fetchBytes(url: String): ByteArray? = runCatching {
        val req = Request.Builder().url(url).header("User-Agent", "OTT-play-Android/1.0").get().build()
        controlClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            resp.body.bytes()
        }
    }.getOrNull()

    private fun writeSimpleStatus(out: OutputStream, status: String) {
        val resp = "HTTP/1.1 $status\r\nAccess-Control-Allow-Origin: *\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        out.write(resp.toByteArray(Charsets.ISO_8859_1))
        out.flush()
    }

    private fun randomToken(): String {
        val bytes = ByteArray(10)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val VOD_SLICE_BYTES = 2L * 1024L * 1024L
    }
}
