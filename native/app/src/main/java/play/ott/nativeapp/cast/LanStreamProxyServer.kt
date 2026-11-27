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

/**
 * Pure HLS manifest parser, CORS manifest rewriter, MPEG-TS Null Packet generator,
 * and RFC 8216 AES-128-CBC segment decryptor.
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
 * Lightweight local Wi-Fi HTTP relay server supporting:
 * 1. DLNA Smart TVs (Samsung Tizen, LG webOS, Roku, Xbox, Kodi):
 *    - Live HLS (Pluto AES-128 + IPTV M81) -> continuous MPEG-TS (`video/mp2t`) with async segment prefetch
 *      + ISO MPEG-TS Null Packet keep-alives so TVs never freeze at the 10-second segment boundary.
 *    - VOD Movies/Series -> direct HTTP CDN detection first, or LAN HTTP `HEAD`/`Range` (`206`) with infinite readTimeout.
 * 2. Chromecast / Google TV (`CC1AD845`):
 *    - Live HLS with `Access-Control-Allow-Origin: *` via `/cast/<token>/live.m3u8` + session-whitelisted `/cast/<token>/res/<id>`.
 */
internal class LanStreamProxyServer(
    private val allowLoopbackForTest: Boolean = false,
) {
    private data class ActiveStream(
        val token: String,
        val localOrigin: String,
        val upstreamUrl: String,
        val isLive: Boolean,
        val isHls: Boolean,
        val mimeType: String,
        val contentLength: Long?,
        val dlnaFeatures: String,
    )

    private val running = AtomicBoolean(false)
    private val activeStream = AtomicReference<ActiveStream?>(null)
    private var serverSocket: ServerSocket? = null
    private val clientSockets = CopyOnWriteArrayList<Socket>()
    private val hlsRelayUrls = ConcurrentHashMap<String, String>()
    private val hlsUrlToId = ConcurrentHashMap<String, String>()
    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r, "StarlinkCastProxy").apply { isDaemon = true }
    }
    private val random = SecureRandom()
    private val nullTsBurst = HlsRemuxCodec.createMpegTsNullBurst(packetCount = 12)

    /** Short timeouts for playlist/probe/segment fetches so stalls recover rapidly. */
    private val controlClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /** Infinite read timeout for continuous VOD / raw TS streams while TV buffers ahead. */
    private val streamingClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(0, TimeUnit.MILLISECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

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
            upstreamUrl = probe.finalUrl,
            isLive = isLive,
            isHls = probe.isHls,
            mimeType = probe.mimeType,
            contentLength = if (isLive) null else probe.contentLength,
            dlnaFeatures = dlnaFeatures,
        )
        activeStream.set(config)

        val localUrl = "$localOrigin/cast/$token/$ext"
        val castHlsUrl = if (isLive && probe.isHls) "$localOrigin/cast/$token/live.m3u8" else null
        val directHttpVod = if (!isLive && probe.finalUrl.startsWith("http://", ignoreCase = true)) {
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
                    // Normal when TV closes connection or switches channel
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
                "Access-Control-Allow-Headers: Range, Content-Type\r\n" +
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
                handleChromecastHlsManifest(method, stream, stream.upstreamUrl, out)
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
                "Cache-Control: no-cache\r\n" +
                "Connection: close\r\n\r\n"
            out.write(header.toByteArray(Charsets.ISO_8859_1))
            if (method != "HEAD") out.write(finalBytes)
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
        val req = Request.Builder()
            .url(stream.upstreamUrl)
            .header("User-Agent", "OTT-play-Android/1.0")
            .get()
            .build()
        streamingClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return
            val input = BufferedInputStream(resp.body.byteStream(), 32 * 1024)
            val buffer = ByteArray(32 * 1024)
            while (running.get() && activeStream.get()?.token == stream.token) {
                val n = input.read(buffer)
                if (n <= 0) break
                out.write(buffer, 0, n)
                out.flush()
            }
        }
    }

    /**
     * Async producer-consumer HLS -> continuous MPEG-TS live streamer:
     * - Pre-buffers 4 initial segments (~20–30s cushion) so Samsung/LG TVs never starve.
     * - Producer polls & decrypts upcoming AES-128 / clear segments ahead of playback.
     * - Writer emits standard 188-byte MPEG-TS Null Packets (PID 0x1FFF) every 1.5s whenever
     *   waiting for the next 5–10s live segment so the TV's DLNA HTTP socket never times out!
     */
    private fun streamHlsAsMpegTs(stream: ActiveStream, out: OutputStream) {
        val queue = LinkedBlockingQueue<ByteArray>(10)
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
                    val targetUrl = mediaPlaylistUrl ?: stream.upstreamUrl
                    val playlistResp = fetchText(targetUrl)
                    if (playlistResp == null) {
                        consecutiveErrors++
                        mediaPlaylistUrl = null
                        if (consecutiveErrors >= 10) break
                        Thread.sleep(600L)
                        continue
                    }
                    val (effectiveUrl, playlistText) = playlistResp
                    val resolvedMedia = if (HlsRemuxCodec.isMasterPlaylist(playlistText)) {
                        val picked = HlsRemuxCodec.pickBestVariantUrl(playlistText, effectiveUrl) ?: break
                        mediaPlaylistUrl = picked
                        fetchText(picked) ?: continue
                    } else {
                        effectiveUrl to playlistText
                    }

                    val parsed = HlsRemuxCodec.parseMediaPlaylist(resolvedMedia.second, resolvedMedia.first)
                    if (parsed.segments.isEmpty()) {
                        consecutiveErrors++
                        mediaPlaylistUrl = null
                        if (consecutiveErrors >= 10) break
                        Thread.sleep(700L)
                        continue
                    }
                    consecutiveErrors = 0

                    // Send up to last 4 segments on initial connect for immediate ~20s TV buffer cushion
                    val candidates = if (firstFetch && parsed.segments.size > 4) {
                        firstFetch = false
                        parsed.segments.takeLast(4)
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
                        if (seenKeys.size > 120) {
                            seenKeys.iterator().let { it.next(); it.remove() }
                        }

                        val rawBytes = fetchBytes(seg.uri) ?: fetchBytes(seg.uri) ?: continue
                        val tsBytes = if (seg.keyMethod == "AES-128" && !seg.keyUri.isNullOrBlank()) {
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

                        while (running.get() && clientAlive.get() && activeStream.get()?.token == stream.token) {
                            if (queue.offer(tsBytes, 500L, TimeUnit.MILLISECONDS)) {
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
                val chunk = queue.poll(1_500L, TimeUnit.MILLISECONDS)
                if (chunk != null) {
                    writeChunkedToTv(out, chunk)
                    sentFirstRealSegment = true
                } else {
                    if (!producerAlive.get() && queue.isEmpty()) break
                    // Emit standard 188-byte MPEG-TS Null Packets so Samsung/LG TV never hits a 10s read timeout
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

    private fun handleVodRequest(method: String, rangeHeader: String?, stream: ActiveStream, out: OutputStream) {
        if (method == "HEAD") {
            val headText = buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("Content-Type: ${stream.mimeType}\r\n")
                append("Access-Control-Allow-Origin: *\r\n")
                if (stream.contentLength != null && stream.contentLength > 0L) {
                    append("Content-Length: ${stream.contentLength}\r\n")
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

        val reqBuilder = Request.Builder()
            .url(stream.upstreamUrl)
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
            }
            out.flush()
        }
    }

    private data class UpstreamProbe(
        val finalUrl: String,
        val isHls: Boolean,
        val mimeType: String,
        val contentLength: Long?,
    )

    private fun probeUpstream(url: String, isLive: Boolean): UpstreamProbe {
        if (isLive) {
            val lower = url.lowercase(Locale.ROOT)
            val looksHls = lower.contains(".m3u8") || lower.contains("/live-hls/") || lower.contains("/pluto/")
            if (looksHls) {
                return UpstreamProbe(url, isHls = true, mimeType = "video/mp2t", contentLength = null)
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
                    UpstreamProbe(finalUrl, isHls = hls, mimeType = "video/mp2t", contentLength = null)
                }
            }.getOrNull()
            return detected ?: UpstreamProbe(url, isHls = false, mimeType = "video/mp2t", contentLength = null)
        }

        val vodProbe = runCatching {
            val headReq = Request.Builder()
                .url(url)
                .header("User-Agent", "OTT-play-Android/1.0")
                .head()
                .build()
            controlClient.newCall(headReq).execute().use { resp ->
                if (resp.isSuccessful) {
                    val finalUrl = resp.request.url.toString()
                    val length = resp.header("Content-Length")?.toLongOrNull()
                    val mime = normalizeVodMime(resp.header("Content-Type"), finalUrl)
                    UpstreamProbe(finalUrl, isHls = false, mimeType = mime, contentLength = length)
                } else null
            }
        }.getOrNull() ?: runCatching {
            val rangeReq = Request.Builder()
                .url(url)
                .header("User-Agent", "OTT-play-Android/1.0")
                .header("Range", "bytes=0-0")
                .get()
                .build()
            controlClient.newCall(rangeReq).execute().use { resp ->
                val finalUrl = resp.request.url.toString()
                val totalFromRange = resp.header("Content-Range")?.substringAfter('/')?.toLongOrNull()
                val length = totalFromRange ?: resp.header("Content-Length")?.toLongOrNull()
                val mime = normalizeVodMime(resp.header("Content-Type"), finalUrl)
                UpstreamProbe(finalUrl, isHls = false, mimeType = mime, contentLength = length)
            }
        }.getOrNull()

        return vodProbe ?: UpstreamProbe(
            finalUrl = url,
            isHls = false,
            mimeType = normalizeVodMime(null, url),
            contentLength = null,
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
}
