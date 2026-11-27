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
    val totalRequests: Int = 0,
    val lastHttpSummary: String = "",
    val bindAddress: String = "",
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
        @Volatile var initialMediaPlaylistUrl: String? = null,
        @Volatile var initialSegmentDurationSec: Int = 5,
        @Volatile var initialLiveSegments: List<Pair<String, ByteArray>> = emptyList(),
    )

    private val running = AtomicBoolean(false)
    private val activeStream = AtomicReference<ActiveStream?>(null)
    private var serverSocket: ServerSocket? = null
    private val clientSockets = CopyOnWriteArrayList<Socket>()
    private val hlsRelayLock = Any()
    private val hlsRelayUrls = LinkedHashMap<String, String>(256, 0.75f, true)
    private val hlsUrlToId = HashMap<String, String>(256)
    private val bytesSentCounter = AtomicLong(0L)
    private val chunksSentCounter = AtomicInteger(0)
    private val reconnectCounter = AtomicInteger(0)
    private val requestCounter = AtomicInteger(0)
    @Volatile private var lastHttpSummary: String = ""
    @Volatile private var lastBindAddress: String = ""

    private val executor = Executors.newCachedThreadPool { r ->
        Thread({
            // Elevate proxy worker thread priority so Android background cgroups never starve TV sockets
            runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO) }
            r.run()
        }, "StarlinkCastProxy").apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY
        }
    }
    private val random = SecureRandom()
    private val nullTsBurst = HlsRemuxCodec.createMpegTsNullBurst(packetCount = 4)

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
        totalRequests = requestCounter.get(),
        lastHttpSummary = lastHttpSummary,
        bindAddress = lastBindAddress,
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
        synchronized(hlsRelayLock) {
            hlsRelayUrls.clear()
            hlsUrlToId.clear()
        }
        bytesSentCounter.set(0L)
        chunksSentCounter.set(0)
        reconnectCounter.set(0)
        requestCounter.set(0)
        lastHttpSummary = ""
        lastBindAddress = "$localLanIp:$port"

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
        // Pre-fetch the very first live HLS segment BEFORE announcing to Samsung/LG TV
        // so byte 0 streams in < 5 ms (prevents Samsung "Problema de conexão de rede"!)
        if (isLive && probe.isHls) {
            prefetchInitialLiveSegment(config)
        }
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
        synchronized(hlsRelayLock) {
            hlsRelayUrls.clear()
            hlsUrlToId.clear()
        }
        closeActiveClients()
    }

    @Synchronized
    fun shutdown() {
        running.set(false)
        activeStream.set(null)
        synchronized(hlsRelayLock) {
            hlsRelayUrls.clear()
            hlsUrlToId.clear()
        }
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
        runCatching { socket.sendBufferSize = 512 * 1024 }
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
        requestCounter.incrementAndGet()
        lastHttpSummary = "$method $subPath"
        when {
            subPath == "live.m3u8" && stream.isLive && stream.isHls -> {
                handleChromecastHlsManifest(method, stream, stream.resolvedUpstreamUrl, out)
            }
            subPath.startsWith("res/") -> {
                val resKey = subPath.removePrefix("res/").substringBefore('.')
                val remoteUrl = synchronized(hlsRelayLock) { hlsRelayUrls[resKey] }
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
        val ext = guessHlsRelayExt(remoteUrl)
        synchronized(hlsRelayLock) {
            val existing = hlsUrlToId[remoteUrl]
            if (existing != null) {
                hlsRelayUrls[existing] // touch LRU
                return "${stream.localOrigin}/cast/${stream.token}/res/$existing$ext"
            }
            // Evict only the oldest entries (never wipe active segments mid-playback!)
            while (hlsRelayUrls.size >= 300) {
                val oldest = hlsRelayUrls.entries.iterator().next()
                hlsUrlToId.remove(oldest.value)
                hlsRelayUrls.remove(oldest.key)
            }
            val id = randomToken()
            hlsRelayUrls[id] = remoteUrl
            hlsUrlToId[remoteUrl] = id
            return "${stream.localOrigin}/cast/${stream.token}/res/$id$ext"
        }
    }

    private fun guessHlsRelayExt(url: String): String {
        val clean = url.substringBefore('?').lowercase(Locale.ROOT)
        return when {
            clean.endsWith(".m3u8") || clean.contains("/resource/") || clean.contains("/playlist") -> ".m3u8"
            clean.endsWith(".ts") -> ".ts"
            clean.endsWith(".vtt") -> ".vtt"
            clean.endsWith(".aac") -> ".aac"
            clean.endsWith(".mp4") || clean.endsWith(".m4s") -> ".m4s"
            else -> ".bin"
        }
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
        val fetched = fetchText(manifestUrl) ?: fetchText(manifestUrl)
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
        // Retry once on transient IPTV CDN hiccups so Chromecast Shaka player never aborts on a single blip
        var fetchedPayload: Triple<String, String, ByteArray>? = null
        var lastCode = 502
        for (attempt in 1..2) {
            val req = Request.Builder()
                .url(remoteUrl)
                .header("User-Agent", "OTT-play-Android/1.0")
                .get()
                .build()
            val hit = runCatching {
                controlClient.newCall(req).execute().use { resp ->
                    lastCode = resp.code
                    if (!resp.isSuccessful) return@use null
                    Triple(resp.header("Content-Type").orEmpty(), resp.request.url.toString(), resp.body.bytes())
                }
            }.getOrNull()
            if (hit != null) {
                fetchedPayload = hit
                break
            }
            if (attempt == 1) runCatching { Thread.sleep(250L) }
        }
        if (fetchedPayload == null) {
            writeSimpleStatus(out, "$lastCode Upstream Error")
            return
        }

        val (ct, finalUrl, bytes) = fetchedPayload
        val isSubPlaylist = ct.contains("mpegurl", ignoreCase = true) ||
            remoteUrl.substringBefore('?').lowercase(Locale.ROOT).endsWith(".m3u8") ||
            (bytes.size < 256_000 && HlsRemuxCodec.isM3u8Playlist(String(bytes, Charsets.UTF_8)))
        val finalBytes = if (isSubPlaylist) {
            HlsRemuxCodec.rewritePlaylistForRelay(
                String(bytes, Charsets.UTF_8),
                finalUrl,
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
     * Pre-fetches and decrypts the last 2 live HLS segments (~8–10s cushion) BEFORE sending
     * SetAVTransportURI to Samsung/LG TVs so byte 0 streams in < 5 ms AND AVPlay fills its
     * hardware decode buffer immediately (eliminates both connection errors and initial stutters).
     */
    private fun prefetchInitialLiveSegment(stream: ActiveStream) {
        runCatching {
            val playlistResp = fetchText(stream.originalUpstreamUrl) ?: return
            val (effectiveUrl, playlistText) = playlistResp
            val resolvedMedia = if (HlsRemuxCodec.isMasterPlaylist(playlistText)) {
                val picked = HlsRemuxCodec.pickBestVariantUrl(playlistText, effectiveUrl) ?: return
                stream.initialMediaPlaylistUrl = picked
                fetchText(picked) ?: return
            } else {
                effectiveUrl to playlistText
            }
            val parsed = HlsRemuxCodec.parseMediaPlaylist(resolvedMedia.second, resolvedMedia.first)
            if (parsed.segments.isEmpty()) return
            stream.initialSegmentDurationSec = parsed.targetDurationSec
            val queryDiff = parsed.segments.map { it.uri.substringBefore('?') }.distinct().size < parsed.segments.size
            val keyCache = HashMap<String, ByteArray>()
            val seedSegments = parsed.segments.takeLast(2)
            val preloaded = ArrayList<Pair<String, ByteArray>>(seedSegments.size)
            for (seg in seedSegments) {
                val rawBytes = fetchBytes(seg.uri) ?: continue
                val decrypted = if (seg.keyMethod == "AES-128" && !seg.keyUri.isNullOrBlank()) {
                    val keyBytes = keyCache[seg.keyUri] ?: fetchBytes(seg.keyUri)?.also { fetched ->
                        if (fetched.size == 16) keyCache[seg.keyUri] = fetched
                    }
                    if (keyBytes == null || keyBytes.size != 16) continue
                    HlsRemuxCodec.decryptAes128Cbc(rawBytes, keyBytes, HlsRemuxCodec.buildIvBytes(seg.ivHex, seg.sequence))
                } else rawBytes
                val aligned = HlsRemuxCodec.alignMpegTsPackets(decrypted)
                if (aligned.isNotEmpty()) {
                    preloaded.add(HlsRemuxCodec.segmentDedupKey(seg.uri, queryDiff) to aligned)
                }
            }
            if (preloaded.isNotEmpty()) {
                stream.initialLiveSegments = preloaded
            }
        }
    }

    /**
     * Sleep-free async producer-consumer HLS -> 188-byte aligned MPEG-TS live streamer:
     * - Emits the pre-fetched 2-segment cushion IMMEDIATELY (< 5 ms!) so Samsung AVPlay locks fast.
     * - Never uses `Thread.sleep()` chunk pacing on the writer thread (Android background timer-slack
     *   stretches `Thread.sleep()` and starves Samsung's buffer); instead relies on the 512 KB kernel
     *   TCP send buffer + TV TCP backpressure for jitter-free real-time delivery even when minimized!
     * - Emits a minimal 4-packet Null-TS heartbeat ONLY if upstream stalls > 2.5s.
     */
    private fun streamHlsAsMpegTs(stream: ActiveStream, out: OutputStream) {
        data class QueuedSegment(val bytes: ByteArray, val durationSec: Int, val immediate: Boolean)

        val queue = LinkedBlockingQueue<QueuedSegment>(12)
        val producerAlive = AtomicBoolean(true)
        val clientAlive = AtomicBoolean(true)

        val preloaded = stream.initialLiveSegments
        val initialSeen = LinkedHashSet<String>()
        for ((key, tsBytes) in preloaded) {
            initialSeen.add(key)
            queue.offer(QueuedSegment(tsBytes, stream.initialSegmentDurationSec, immediate = true))
        }

        val producer = executor.submit {
            val seenKeys = LinkedHashSet<String>(initialSeen)
            val keyCache = LinkedHashMap<String, ByteArray>(16, 0.75f, true)
            var mediaPlaylistUrl: String? = stream.initialMediaPlaylistUrl
            var firstFetch = preloaded.isEmpty()
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

                    val candidates = if (firstFetch && parsed.segments.size > 2) {
                        firstFetch = false
                        parsed.segments.takeLast(2)
                    } else if (!firstFetch && seenKeys.size == preloaded.size && preloaded.isNotEmpty() && parsed.segments.size > 2) {
                        // Start right after the last preloaded segment (no stale backlog dump)
                        val qd = parsed.segments.map { it.uri.substringBefore('?') }.distinct().size < parsed.segments.size
                        val preloadIdx = parsed.segments.indexOfLast { seg ->
                            HlsRemuxCodec.segmentDedupKey(seg.uri, qd) in seenKeys
                        }
                        if (preloadIdx >= 0) parsed.segments.drop(preloadIdx + 1) else parsed.segments.takeLast(2)
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

                        val isFirst = preloaded.isEmpty() && addedCount == 0 && seenKeys.size == 1
                        while (running.get() && clientAlive.get() && activeStream.get()?.token == stream.token) {
                            if (queue.offer(QueuedSegment(alignedTs, parsed.targetDurationSec, immediate = isFirst), 500L, TimeUnit.MILLISECONDS)) {
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
                // Wake consumer immediately on clean producer exit (no 1.5s poll tail wait)
                queue.offer(QueuedSegment(ByteArray(0), 0, immediate = false))
            }
        }

        try {
            var lastWriteMs = System.currentTimeMillis()
            while (running.get() && activeStream.get()?.token == stream.token) {
                val item = queue.poll(1_500L, TimeUnit.MILLISECONDS)
                if (item != null) {
                    if (item.bytes.isEmpty()) break
                    writeChunkedToTv(out, item.bytes)
                    lastWriteMs = System.currentTimeMillis()
                    bytesSentCounter.addAndGet(item.bytes.size.toLong())
                    chunksSentCounter.incrementAndGet()
                } else if (!producerAlive.get() && queue.isEmpty()) {
                    break
                } else if (System.currentTimeMillis() - lastWriteMs >= 2_500L) {
                    // Only send minimal 4-packet Null-TS keep-alive if upstream stalled > 2.5s
                    out.write(nullTsBurst)
                    out.flush()
                    lastWriteMs = System.currentTimeMillis()
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
        val step = 64 * 1024
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
                append("Access-Control-Expose-Headers: *\r\n")
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

            // True async double-buffered VOD streamer (6 MB RAM read-ahead + 4 MB Range slices):
            // - First 64 KB chunk reaches Samsung/LG/Chromecast immediately (< 80 ms) for instant MP4 moov probe & seek.
            // - Background producer prefetches the NEXT 4 MB Range slice BEFORE the TV finishes the current one,
            //   eliminating all stop-and-wait HTTP gaps between slices even when the phone is minimized!
            // - Automatically stitches across Cloudflare Worker ~90s drops & refreshes expired M81 redirects.
            val readAheadQueue = LinkedBlockingQueue<ByteArray>(VOD_READAHEAD_CHUNKS)
            val tvAlive = AtomicBoolean(true)
            val eofSentinel = ByteArray(0)

            val producer = executor.submit {
                var cursor = range.start
                val readBuf = ByteArray(VOD_CHUNK_BYTES)
                var sliceRetries = 0
                try {
                    while (cursor <= range.end && tvAlive.get() && running.get() && activeStream.get()?.token == stream.token) {
                        val sliceEnd = minOf(cursor + VOD_SLICE_BYTES - 1L, range.end)
                        val targetUrl = stream.resolvedUpstreamUrl
                        var sliceProgress = 0L

                        try {
                            val req = Request.Builder()
                                .url(targetUrl)
                                .header("User-Agent", "OTT-play-Android/1.0")
                                .header("Range", "bytes=$cursor-$sliceEnd")
                                .get()
                                .build()
                            streamingClient.newCall(req).execute().use { resp ->
                                if (resp.code == 206 || (resp.code == 200 && cursor == 0L)) {
                                    val input = BufferedInputStream(resp.body.byteStream(), VOD_CHUNK_BYTES)
                                    val expectedMax = (sliceEnd - cursor) + 1L
                                    while (sliceProgress < expectedMax && tvAlive.get() && running.get() && activeStream.get()?.token == stream.token) {
                                        val toRead = minOf(readBuf.size.toLong(), expectedMax - sliceProgress).toInt()
                                        val n = input.read(readBuf, 0, toRead)
                                        if (n <= 0) break
                                        val copy = readBuf.copyOf(n)
                                        var offered = false
                                        while (tvAlive.get() && running.get() && activeStream.get()?.token == stream.token) {
                                            if (readAheadQueue.offer(copy, 250L, TimeUnit.MILLISECONDS)) {
                                                offered = true
                                                break
                                            }
                                        }
                                        if (!offered) break
                                        cursor += n
                                        sliceProgress += n
                                    }
                                    if (sliceProgress > 0L) {
                                        chunksSentCounter.incrementAndGet()
                                        sliceRetries = 0
                                    }
                                }
                            }
                        } catch (_: Exception) {
                            // Upstream hiccup / Cloudflare drop -> resume cleanly from exact `cursor`
                        }

                        if (!tvAlive.get()) break
                        if (sliceProgress == 0L) {
                            sliceRetries++
                            reconnectCounter.incrementAndGet()
                            if (sliceRetries > 6) break
                            val refreshed = probeUpstream(stream.originalUpstreamUrl, isLive = false)
                            if (refreshed.finalUrl.isNotBlank()) {
                                stream.resolvedUpstreamUrl = refreshed.finalUrl
                            }
                            runCatching { Thread.sleep(250L * sliceRetries) }
                        }
                    }
                } finally {
                    // Signal clean EOF to writer thread
                    while (tvAlive.get() && !readAheadQueue.offer(eofSentinel, 200L, TimeUnit.MILLISECONDS)) {
                        if (!running.get()) break
                    }
                }
            }

            try {
                while (running.get() && activeStream.get()?.token == stream.token) {
                    val chunk = readAheadQueue.poll(1_000L, TimeUnit.MILLISECONDS) ?: continue
                    if (chunk.isEmpty()) break
                    out.write(chunk)
                    bytesSentCounter.addAndGet(chunk.size.toLong())
                    // Flush whenever queue is temporarily empty so TV gets instant first bytes & smooth tail
                    if (readAheadQueue.isEmpty()) {
                        out.flush()
                    }
                }
                runCatching { out.flush() }
            } catch (_: IOException) {
                // Normal when Samsung/LG closes socket on seek/stop
            } finally {
                tvAlive.set(false)
                producer.cancel(true)
                readAheadQueue.clear()
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
        private const val VOD_SLICE_BYTES = 4L * 1024L * 1024L
        private const val VOD_CHUNK_BYTES = 64 * 1024
        private const val VOD_READAHEAD_CHUNKS = 96 // 96 * 64 KB = 6 MB async read-ahead cushion
    }
}
