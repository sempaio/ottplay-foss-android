package play.ott.nativeapp.cast

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.widget.Toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal data class CastSessionState(
    val scanning: Boolean = false,
    val devices: List<CastDevice> = emptyList(),
    val scanStatus: String? = null,
    val connectedDevice: CastDevice? = null,
    val castingTitle: String = "",
    val castingUrl: String = "",
    val rawSourceUrl: String = "",
    val castingDirectMode: Boolean = false,
    val castingIsLive: Boolean = false,
    val isPlayingOnTv: Boolean = false,
    val tvTransportState: String = "",
    val tvPositionMs: Long = 0L,
    val tvDurationMs: Long = 0L,
    val tvVolume: Int = 50,
    val proxyStats: ProxyRuntimeStats = ProxyRuntimeStats(),
    val autoReconnectCount: Int = 0,
    val busyAction: String? = null,
    val errorMessage: String? = null,
    val diagnosticLog: List<String> = emptyList(),
)

/**
 * Process-scoped holder so an active TV cast session survives Activity backgrounding,
 * app switching (WhatsApp/Instagram), screen-off, and orientation changes.
 */
internal object CastSessionHost {
    val controller: UniversalCastController = UniversalCastController()
    val isCastingActive: Boolean
        get() = controller.state.value.connectedDevice != null
}

/**
 * Unified pure-Kotlin Smart TV & Chromecast caster:
 * - DLNA / UPnP (`AVTransport:1` + `RenderingControl:1`) for Samsung Tizen, LG webOS, Roku, TV Box, Xbox, Kodi.
 * - Google Cast v2 (`_googlecast._tcp.` + TLS 8009 Default Media Receiver `CC1AD845`) for Chromecast, Google TV, Android TV.
 * - Backed by `CastRelayService` foreground service so the user can lock the screen or use other apps freely.
 */
internal data class CastAttemptResult(
    val success: Boolean,
    val effectiveUrl: String,
    val initialVolume: Int? = null,
    val isDirectMode: Boolean = false,
    val error: String? = null,
)

internal class UniversalCastController(
    private val chromecastDisconnectHook: ((stopApp: Boolean) -> Unit)? = null,
    private val dlnaStopHook: ((CastDevice) -> Unit)? = null,
    private val castAttemptExecutor: (suspend (CastDevice, String, String, Boolean, Long) -> CastAttemptResult)? = null,
    private val chromecastStatusHook: (() -> CastMediaStatus?)? = null,
    private val proxyStatsProvider: (() -> ProxyRuntimeStats)? = null,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    private val mutableState = MutableStateFlow(CastSessionState())
    val state: StateFlow<CastSessionState> = mutableState.asStateFlow()
    private val lanProxy = LanStreamProxyServer()
    private val googleCastClient = GoogleCastClient()
    private val sessionGeneration = CastSessionGeneration()
    private val dlnaCapabilities = DlnaCapabilityResolver(clockMs = clockMs)
    private val stateCommitLock = Any()
    @Volatile private var castStartedAtMs: Long = 0L
    @Volatile private var consecutiveStoppedTicks: Int = 0
    private val reconnectingInFlight = AtomicBoolean(false)
    @Volatile private var lastWatchdogBytesSent: Long = -1L
    @Volatile private var userPausedLocally: Boolean = false
    private val diagLock = Any()
    private val diagBuffer = ArrayDeque<String>(24)

    internal fun setSessionStateForTest(
        newState: CastSessionState,
        startedAtMs: Long = clockMs(),
    ) {
        synchronized(stateCommitLock) {
            val nextGen = sessionGeneration.next()
            lanProxy.advanceMinimumGeneration(nextGen)
            castStartedAtMs = startedAtMs
            consecutiveStoppedTicks = 0
            lastWatchdogBytesSent = -1L
            userPausedLocally = !newState.isPlayingOnTv &&
                newState.tvTransportState.startsWith("PAUSED", ignoreCase = true)
            mutableState.value = newState
        }
    }

    internal fun currentGenerationForTest(): Long = sessionGeneration.current()

    private fun readProxyStats(): ProxyRuntimeStats = proxyStatsProvider?.invoke() ?: lanProxy.currentStats()

    private fun clearDiag() {
        synchronized(diagLock) { diagBuffer.clear() }
    }

    private fun logDiag(entry: String) {
        val safe = DlnaProtocol.redactUrlForDiagnostics(entry).take(220)
        val snap = synchronized(diagLock) {
            if (diagBuffer.size >= 18) diagBuffer.removeFirst()
            diagBuffer.addLast(safe)
            diagBuffer.toList()
        }
        mutableState.update { it.copy(diagnosticLog = snap) }
    }

    private fun logDiagIfCurrent(sessionGen: Long, entry: String) {
        if (sessionGeneration.isCurrent(sessionGen)) {
            logDiag(entry)
        }
    }

    private val lanHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(3, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    private val upstreamSafeDns = UpstreamUrlSecurity.createSafeUpstreamDns(allowLoopbackForTest = false)
    private val upstreamSafeSocketFactory = UpstreamUrlSecurity.createSafeSocketFactory(allowLoopbackForTest = false)
    private val upstreamSafeInterceptor = UpstreamUrlSecurity.createSafeUpstreamInterceptor(allowLoopbackForTest = false)

    private val redirectResolverClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .dns(upstreamSafeDns)
        .socketFactory(upstreamSafeSocketFactory)
        .addInterceptor(upstreamSafeInterceptor)
        .addNetworkInterceptor(upstreamSafeInterceptor)
        .build()

    private val vodRedirectClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .dns(upstreamSafeDns)
        .socketFactory(upstreamSafeSocketFactory)
        .addInterceptor(upstreamSafeInterceptor)
        .addNetworkInterceptor(upstreamSafeInterceptor)
        .build()

    suspend fun scanDevices(context: Context) {
        mutableState.update {
            it.copy(
                scanning = true,
                scanStatus = "Procurando Smart TVs e Chromecasts no Wi-Fi...",
                errorMessage = null,
            )
        }
        val appContext = context.applicationContext
        val found = try {
            coroutineScope {
                val dlnaDeferred = async(Dispatchers.IO) { runCatching { discoverDlnaDevices(appContext) }.getOrDefault(emptyList()) }
                val castDeferred = async(Dispatchers.IO) { runCatching { GoogleCastClient.discoverDevices(appContext, 2_400L) }.getOrDefault(emptyList()) }
                dlnaDeferred.await() + castDeferred.await()
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            mutableState.update {
                it.copy(
                    scanning = false,
                    scanStatus = "Não foi possível buscar TVs no Wi-Fi (${e.message ?: "erro de rede"}).",
                )
            }
            return
        }

        mutableState.update { prev ->
            val merged = (found + listOfNotNull(prev.connectedDevice))
                .distinctBy { it.avTransportControlUrl }
                .sortedWith(compareBy<CastDevice> { it.protocol.ordinal }.thenBy { it.friendlyName.lowercase() })
            prev.copy(
                scanning = false,
                devices = merged,
                scanStatus = if (merged.isEmpty()) {
                    "Nenhuma TV ou Chromecast respondeu agora. Confira se TV e celular estão no mesmo Wi-Fi e toque em Buscar."
                } else {
                    "${merged.size} ${if (merged.size == 1) "dispositivo encontrado" else "dispositivos encontrados"} no Wi-Fi"
                },
            )
        }
    }

    suspend fun castMedia(
        context: Context?,
        device: CastDevice,
        rawUrl: String,
        title: String,
        isLive: Boolean,
        startPositionMs: Long = 0L,
    ): Boolean = castMediaInternal(
        context = context,
        device = device,
        rawUrl = rawUrl,
        title = title,
        isLive = isLive,
        startPositionMs = startPositionMs,
        isAutoReconnect = false,
        requiredCurrentGen = null,
    )

    private suspend fun castMediaInternal(
        context: Context?,
        device: CastDevice,
        rawUrl: String,
        title: String,
        isLive: Boolean,
        startPositionMs: Long,
        isAutoReconnect: Boolean,
        requiredCurrentGen: Long?,
    ): Boolean {
        val claimed = synchronized(stateCommitLock) {
            if (requiredCurrentGen != null && !sessionGeneration.isCurrent(requiredCurrentGen)) {
                return@synchronized null
            }
            val nextGen = sessionGeneration.next()
            lanProxy.advanceMinimumGeneration(nextGen)
            val prev = mutableState.value.connectedDevice
            clearDiag()
            mutableState.update {
                it.copy(
                    autoReconnectCount = if (isAutoReconnect) it.autoReconnectCount + 1 else it.autoReconnectCount,
                    busyAction = "Conectando em ${device.friendlyName}...",
                    errorMessage = null,
                )
            }
            nextGen to prev
        } ?: return false
        val (gen, prevDevice) = claimed
        val vendorTag = if (device.protocol == CastProtocol.CHROMECAST) {
            "CHROMECAST"
        } else {
            DlnaProtocol.detectVendorProfile(device).name
        }
        logDiagIfCurrent(gen, "Sessão #$gen • Alvo: ${device.friendlyName} [$vendorTag] (${device.manufacturer}/${device.modelName}) @ ${device.host}")
        logDiagIfCurrent(gen, "Mídia: ${if (isLive) "AO VIVO" else "VOD"} • início=${startPositionMs / 1000}s")
        val appContext = context?.applicationContext
        return try {
            // Stop prior Cast v2 session ONLY when switching to a DIFFERENT TV/Chromecast
            // (reusing the active CC1AD845 session on the same Chromecast avoids STOP->LAUNCH races!)
            if (prevDevice?.protocol == CastProtocol.CHROMECAST &&
                prevDevice.avTransportControlUrl != device.avTransportControlUrl
            ) {
                withContext(Dispatchers.IO) {
                    val hook = chromecastDisconnectHook
                    if (hook != null) hook(true) else googleCastClient.disconnect(stopApp = true)
                }
            }

            if (!sessionGeneration.isCurrent(gen)) return false

            val outcome = if (castAttemptExecutor != null) {
                castAttemptExecutor.invoke(device, rawUrl, title, isLive, startPositionMs)
            } else {
                withContext(Dispatchers.IO) {
                    when (device.protocol) {
                        CastProtocol.CHROMECAST -> castToChromecast(device, rawUrl, title, isLive, startPositionMs, gen)
                        CastProtocol.DLNA -> castToDlna(device, rawUrl, title, isLive, startPositionMs, gen)
                    }
                }
            }

            // Atomic Session Generation ID commit: if a newer zap, disconnect, or detach happened
            // while I/O ran, discard this stale outcome completely so it NEVER corrupts active state!
            if (outcome.success) {
                val committed = synchronized(stateCommitLock) {
                    if (!sessionGeneration.isCurrent(gen)) return@synchronized false
                    val vol = outcome.initialVolume ?: mutableState.value.tvVolume
                    castStartedAtMs = clockMs()
                    consecutiveStoppedTicks = 0
                    lastWatchdogBytesSent = -1L
                    userPausedLocally = false
                    mutableState.update {
                        it.copy(
                            connectedDevice = device,
                            castingTitle = title,
                            castingUrl = outcome.effectiveUrl,
                            rawSourceUrl = rawUrl,
                            castingDirectMode = outcome.isDirectMode,
                            castingIsLive = isLive,
                            isPlayingOnTv = true,
                            tvTransportState = "PLAYING",
                            tvPositionMs = startPositionMs.coerceAtLeast(0L),
                            tvDurationMs = 0L,
                            tvVolume = vol.coerceIn(0, 100),
                            proxyStats = readProxyStats(),
                            autoReconnectCount = if (isAutoReconnect) it.autoReconnectCount else 0,
                            busyAction = null,
                            errorMessage = null,
                        )
                    }
                    true
                }
                if (!committed) return false
                if (appContext != null) {
                    // WakeLock + WifiLock are held ONLY in Bridge mode (released in Direct Cloud/CDN mode!)
                    CastRelayService.startOrUpdate(
                        appContext,
                        device.friendlyName,
                        title,
                        isPlaying = true,
                        isDirectMode = outcome.isDirectMode,
                    )
                }
                true
            } else {
                val classified = CastFailureClassifier.classify(outcome.error)
                val committed = synchronized(stateCommitLock) {
                    if (!sessionGeneration.isCurrent(gen)) return@synchronized false
                    mutableState.update {
                        it.copy(
                            busyAction = null,
                            errorMessage = outcome.error ?: classified.userHint,
                        )
                    }
                    true
                }
                if (!committed) return false
                logDiagIfCurrent(gen, "[Falha/${classified.tag}] ${outcome.error ?: classified.userHint}")
                if (appContext != null) CastRelayService.stop(appContext)
                false
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            val classified = CastFailureClassifier.classify(e.message)
            val committed = synchronized(stateCommitLock) {
                if (!sessionGeneration.isCurrent(gen)) return@synchronized false
                mutableState.update {
                    it.copy(
                        busyAction = null,
                        errorMessage = "Falha ao enviar para ${device.friendlyName}: ${e.message ?: classified.userHint}",
                    )
                }
                true
            }
            if (!committed) return false
            lanProxy.clearStream(expectedGeneration = gen)
            if (appContext != null) CastRelayService.stop(appContext)
            logDiagIfCurrent(gen, "[Exceção/${classified.tag}] ${e.message ?: "erro de rede"}")
            false
        }
    }

    private fun castToChromecast(
        device: CastDevice,
        rawUrl: String,
        title: String,
        isLive: Boolean,
        startPositionMs: Long,
        sessionGen: Long,
    ): CastAttemptResult {
        val rewritten = DlnaProtocol.rewriteMediaUrlForCast(rawUrl, isLive)

        // 1. Direct Cloud/CDN mode (ZERO phone proxy & ZERO video bytes through the panel!):
        //    Chromecast pulls video straight from the origin CDN so the user can disconnect the phone from Wi-Fi!
        //    - Live Pluto TV / HTTPS+CORS HLS -> direct HTTPS manifest with HTTPS CDN segments
        //    - VOD Movies/Series -> tries resolved external CDN URL first, then HTTPS 307 redirect URL if needed
        val directCandidate = resolveDirectChromecastCandidate(rewritten, isLive)
        if (!sessionGeneration.isCurrent(sessionGen)) {
            return CastAttemptResult(false, "", error = "Sessão substituída")
        }
        val directAttempts = if (isLive) {
            listOfNotNull(directCandidate)
        } else {
            buildDirectChromecastVodCandidates(rewritten, directCandidate)
        }
        if (directAttempts.isNotEmpty()) {
            for ((idx, attempt) in directAttempts.withIndex()) {
                if (!sessionGeneration.isCurrent(sessionGen)) {
                    return CastAttemptResult(false, "", error = "Sessão substituída")
                }
                val (directUrl, directMime) = attempt
                val label = if (idx == 0) "CDN direto" else "Redirect 307 direto"
                logDiagIfCurrent(sessionGen, "[Cast] Tentando modo direto ($label • $directMime)...")
                val directRes = googleCastClient.connectAndLoad(
                    device = device,
                    mediaUrl = directUrl,
                    title = title,
                    contentType = directMime,
                    isLive = isLive,
                    startPositionMs = startPositionMs,
                )
                if (!sessionGeneration.isCurrent(sessionGen)) {
                    return CastAttemptResult(false, directUrl, error = "Sessão substituída")
                }
                if (directRes.isSuccess) {
                    logDiagIfCurrent(sessionGen, "[Cast] Modo direto OK ($label) — TV busca vídeo direto no CDN, celular liberável!")
                    lanProxy.clearStream(expectedGeneration = sessionGen)
                    return CastAttemptResult(
                        success = true,
                        effectiveUrl = directUrl,
                        initialVolume = directRes.getOrNull(),
                        isDirectMode = true,
                    )
                }
                logDiagIfCurrent(sessionGen, "[Cast] Direto ($label) falhou (${directRes.exceptionOrNull()?.message ?: "erro"})")
            }
            logDiagIfCurrent(sessionGen, "[Cast] Chromecast recusou URLs diretas -> ativando ponte Wi-Fi local")
        } else {
            logDiagIfCurrent(sessionGen, "[Cast] Origem HTTP sem CORS direto -> usando ponte Wi-Fi local com prefetch 2x .ts")
        }

        if (!sessionGeneration.isCurrent(sessionGen)) {
            return CastAttemptResult(false, "", error = "Sessão substituída")
        }

        // 2. Fallback to local Wi-Fi CORS proxy (with async 2-segment prefetch + M81 /live-hls/ renewal!)
        val phoneWifiIp = DlnaProtocol.localLanIpv4ForTarget(device.host)
        logDiagIfCurrent(sessionGen, "[Cast] IP LAN local=$phoneWifiIp")
        val target = if (phoneWifiIp != null) {
            lanProxy.prepareStream(rewritten, isLive, phoneWifiIp, sessionGeneration = sessionGen)
        } else {
            val fallbackUrl = resolvePlayableCastUrl(rawUrl, isLive)
            ProxiedCastTarget(
                localUrl = fallbackUrl,
                chromecastHlsUrl = if (isLive) fallbackUrl else null,
                directHttpVodUrl = if (!isLive) fallbackUrl else null,
                mimeType = if (isLive) "application/vnd.apple.mpegurl" else "video/mp4",
                dlnaFeatures = "*",
                contentLength = null,
                isLive = isLive,
            )
        }

        if (target.wasSuperseded || !sessionGeneration.isCurrent(sessionGen)) {
            return CastAttemptResult(false, target.localUrl, error = "Sessão substituída")
        }

        val mediaUrl = if (isLive) {
            target.chromecastHlsUrl ?: target.localUrl
        } else {
            target.localUrl
        }
        val contentType = when {
            isLive && mediaUrl.endsWith(".m3u8", ignoreCase = true) -> "application/vnd.apple.mpegurl"
            isLive -> "video/mp2t"
            else -> target.mimeType
        }

        val res = googleCastClient.connectAndLoad(
            device = device,
            mediaUrl = mediaUrl,
            title = title,
            contentType = contentType,
            isLive = isLive,
            startPositionMs = startPositionMs,
        )
        if (!sessionGeneration.isCurrent(sessionGen)) {
            return CastAttemptResult(false, mediaUrl, error = "Sessão substituída")
        }
        if (res.isSuccess) {
            return CastAttemptResult(true, mediaUrl, initialVolume = res.getOrNull(), isDirectMode = false)
        }
        lanProxy.clearStream(expectedGeneration = sessionGen)
        return CastAttemptResult(
            success = false,
            effectiveUrl = mediaUrl,
            error = res.exceptionOrNull()?.message ?: "Chromecast recusou a reprodução deste vídeo.",
        )
    }

    /**
     * Checks whether Chromecast can pull the media directly from the origin CDN without phone proxying
     * (and with ZERO video bytes flowing through the authentication panel):
     * - LIVE: Pluto TV / HTTPS+CORS HLS with HTTPS segments (follows up to 3 HTTPS 307/302 redirects).
     * - VOD: follows auth redirects (`GET Range: bytes=0-0`) to the final MP4/MKV CDN URL.
     */
    private fun resolveDirectChromecastCandidate(rewrittenUrl: String, isLive: Boolean): Pair<String, String>? {
        if (!rewrittenUrl.startsWith("http://", ignoreCase = true) &&
            !rewrittenUrl.startsWith("https://", ignoreCase = true)
        ) return null

        if (isLive) {
            if (!rewrittenUrl.startsWith("https://", ignoreCase = true)) return null
            return runCatching {
                var currentUrl = rewrittenUrl
                repeat(4) { hop ->
                    if (!currentUrl.startsWith("https://", ignoreCase = true)) return@runCatching null
                    val req = Request.Builder()
                        .url(currentUrl)
                        .header("Origin", "https://www.gstatic.com")
                        .header("User-Agent", "OTT-play-Android/1.0")
                        .get()
                        .build()
                    val stepResult = redirectResolverClient.newCall(req).execute().use { resp ->
                        val acao = resp.header("Access-Control-Allow-Origin")
                        val loc = resp.header("Location").orEmpty()
                        if (resp.code in 301..308 && loc.isNotBlank()) {
                            val nextUrl = resp.request.url.resolve(loc)?.toString().orEmpty()
                            if (loc.contains("/iptv/pluto/", ignoreCase = true) && isGstaticCorsAllowed(acao)) {
                                // Pluto TV: panel only handles session/manifest text; video segments go 100% direct from Pluto CDN.
                                return@use Pair("FOUND", rewrittenUrl)
                            }
                            if (nextUrl.startsWith("https://", ignoreCase = true) && nextUrl != currentUrl) {
                                return@use Pair("NEXT", nextUrl)
                            }
                            return@use Pair("STOP", "")
                        }
                        if (resp.isSuccessful) {
                            val bodyBytes = UpstreamUrlSecurity.readBoundedBytes(
                                resp.body.byteStream(),
                                UpstreamUrlSecurity.MAX_PLAYLIST_BYTES,
                            )
                            val body = String(bodyBytes, Charsets.UTF_8)
                            if (isDirectHttpsHlsManifest(body, acao)) {
                                // Prefer direct HTTPS target when redirected off the auth panel, or rewrittenUrl on hop 0
                                val target = if (hop == 0) rewrittenUrl else currentUrl
                                return@use Pair("FOUND", target)
                            }
                        }
                        Pair("STOP", "")
                    }
                    when (stepResult.first) {
                        "FOUND" -> return@runCatching stepResult.second to "application/vnd.apple.mpegurl"
                        "NEXT" -> currentUrl = stepResult.second
                        else -> return@runCatching null
                    }
                }
                null
            }.getOrNull()
        }

        // VOD: resolve 307/302 redirect chain via Range 0-0 to final direct CDN URL
        return runCatching {
            val req = Request.Builder()
                .url(rewrittenUrl)
                .header("Origin", "https://www.gstatic.com")
                .header("User-Agent", "OTT-play-Android/1.0")
                .header("Range", "bytes=0-0")
                .get()
                .build()
            vodRedirectClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful && resp.code != 206) return@use null
                val finalUrl = resp.request.url.toString()
                val ct = resp.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase().orEmpty()
                val mime = guessVodMime(finalUrl, ct)
                finalUrl to mime
            }
        }.getOrNull()
    }

    private fun castToDlna(
        device: CastDevice,
        rawUrl: String,
        title: String,
        isLive: Boolean,
        startPositionMs: Long,
        sessionGen: Long,
    ): CastAttemptResult {
        val vendor = DlnaProtocol.detectVendorProfile(device)
        val sinkCaps = queryDlnaSinkCapabilities(device)
        if (!sessionGeneration.isCurrent(sessionGen)) {
            return CastAttemptResult(false, "", error = "Sessão substituída")
        }
        if (sinkCaps != null) {
            logDiagIfCurrent(sessionGen, "[DLNA/$vendor] ${sinkCaps.summaryTag()}")
        }
        val rewrittenUrl = DlnaProtocol.rewriteMediaUrlForCast(rawUrl, isLive)
        val phoneWifiIp = DlnaProtocol.localLanIpv4ForTarget(device.host)
        logDiagIfCurrent(sessionGen, "[DLNA/$vendor] IP LAN=$phoneWifiIp")
        val castTarget = if (phoneWifiIp != null) {
            lanProxy.prepareStream(rewrittenUrl, isLive, phoneWifiIp, sessionGeneration = sessionGen)
        } else {
            val directUrl = resolvePlayableCastUrl(rawUrl, isLive)
            ProxiedCastTarget(
                localUrl = directUrl,
                chromecastHlsUrl = null,
                directHttpVodUrl = if (!isLive && directUrl.startsWith("http://", ignoreCase = true)) directUrl else null,
                mimeType = if (isLive) "video/mp2t" else "video/mp4",
                dlnaFeatures = "*",
                contentLength = null,
                isLive = isLive,
            )
        }
        if (castTarget.wasSuperseded || !sessionGeneration.isCurrent(sessionGen)) {
            return CastAttemptResult(false, castTarget.localUrl, error = "Sessão substituída")
        }
        logDiagIfCurrent(sessionGen, "[DLNA/$vendor] Proxy mime=${castTarget.mimeType} len=${castTarget.contentLength ?: -1} hls=${castTarget.chromecastHlsUrl != null}")
        if (!isLive && sinkCaps != null && castTarget.mimeType.contains("matroska") && !sinkCaps.supportsMkv) {
            logDiagIfCurrent(sessionGen, "[DLNA/$vendor] AVISO: TV não declarou suporte a MKV no Sink — tentando mesmo assim")
        }

        // LG webOS gets its own dedicated multi-profile negotiation cascade with post-Play verification
        // so Samsung's proven flow remains 100% untouched!
        if (vendor == DlnaVendorProfile.LG) {
            return castToLgDlna(device, castTarget, title, isLive, startPositionMs, sessionGen, sinkCaps)
        }

        // Best-effort Stop clears stale state on Samsung / generic renderers
        runCatching {
            postSoap(device.avTransportControlUrl, DlnaProtocol.AV_TRANSPORT_SERVICE, "Stop", DlnaProtocol.buildStopSoap())
            Thread.sleep(250L)
        }
        if (!sessionGeneration.isCurrent(sessionGen)) {
            return CastAttemptResult(false, castTarget.localUrl, error = "Sessão substituída")
        }

        // 1. Try direct HTTP CDN mode first for non-Cloudflare-Worker VOD:
        //    Samsung/generic TVs pull directly from the CDN with ZERO phone relay — works even with phone turned off!
        val directVod = castTarget.directHttpVodUrl
        if (!isLive && !directVod.isNullOrBlank()) {
            logDiagIfCurrent(sessionGen, "[DLNA/$vendor] Tentando VOD direto HTTP...")
            val directAttempt = tryDlnaSetUriAndPlay(
                device = device,
                mediaUrl = directVod,
                title = title,
                isLive = false,
                mimeType = castTarget.mimeType,
                dlnaFeatures = castTarget.dlnaFeatures,
                contentLength = castTarget.contentLength,
                startPositionMs = startPositionMs,
                sessionGen = sessionGen,
            )
            if (!sessionGeneration.isCurrent(sessionGen)) {
                return CastAttemptResult(false, directVod, error = "Sessão substituída")
            }
            if (directAttempt.first) {
                logDiagIfCurrent(sessionGen, "[DLNA/$vendor] VOD direto HTTP aceito!")
                dlnaCapabilities.recordWorkingProfile(device.id, false, castTarget.mimeType, "DLNA-VOD-direto")
                lanProxy.clearStream(expectedGeneration = sessionGen)
                val vol = queryDlnaVolume(device)
                return CastAttemptResult(true, directVod, initialVolume = vol, isDirectMode = true)
            }
            logDiagIfCurrent(sessionGen, "[DLNA/$vendor] VOD direto recusado (${directAttempt.second ?: "erro"}), usando proxy 6MB...")
        }

        // 2. Resilient local Wi-Fi proxy for Live TV (2-segment pre-fetch, sleep-free MPEG-TS + AES-128)
        //    and HTTPS / Cloudflare Worker VOD (6 MB async read-ahead + 4 MB Range stitching):
        val proxyAttempt = tryDlnaSetUriAndPlay(
            device = device,
            mediaUrl = castTarget.localUrl,
            title = title,
            isLive = isLive,
            mimeType = castTarget.mimeType,
            dlnaFeatures = castTarget.dlnaFeatures,
            contentLength = castTarget.contentLength,
            startPositionMs = startPositionMs,
            sessionGen = sessionGen,
        )
        if (!sessionGeneration.isCurrent(sessionGen)) {
            return CastAttemptResult(false, castTarget.localUrl, error = "Sessão substituída")
        }
        if (!proxyAttempt.first) {
            logDiagIfCurrent(sessionGen, "[DLNA/$vendor] Proxy falhou: ${proxyAttempt.second ?: "erro"}")
            lanProxy.clearStream(expectedGeneration = sessionGen)
            val detail = proxyAttempt.second?.let { " ($it)" }.orEmpty()
            return CastAttemptResult(
                success = false,
                effectiveUrl = castTarget.localUrl,
                error = "A TV recusou o vídeo$detail. Tente novamente ou abra Diagnóstico.",
            )
        }
        dlnaCapabilities.recordWorkingProfile(device.id, isLive, castTarget.mimeType, "DLNA-proxy")
        logDiagIfCurrent(sessionGen, "[DLNA/$vendor] Reprodução iniciada via proxy!")
        val vol = queryDlnaVolume(device)
        return CastAttemptResult(true, castTarget.localUrl, initialVolume = vol, isDirectMode = false)
    }

    /**
     * Dedicated LG webOS MediaRenderer negotiation guided by [DlnaCapabilityResolver]:
     * - Orders candidates using cached winning profile for this TV + `ConnectionManager#GetProtocolInfo` Sink.
     * - Longer Stop settle (350ms) and SetURI->Play settle (650ms) + real UPnP 701 transport state check.
     * - Actively verifies `GetTransportInfo` ~1.1s after Play because webOS frequently returns
     *   HTTP 200 on Play and silently drops to STOPPED when rejecting a profile.
     */
    private fun castToLgDlna(
        device: CastDevice,
        castTarget: ProxiedCastTarget,
        title: String,
        isLive: Boolean,
        startPositionMs: Long,
        sessionGen: Long,
        sinkCaps: DlnaSinkCapabilities?,
    ): CastAttemptResult {
        val rawCandidates = ArrayList<DlnaCandidateProfile>()
        if (isLive) {
            rawCandidates.add(
                DlnaCandidateProfile(
                    label = "LG-TS+PN",
                    mediaUrl = castTarget.localUrl,
                    mimeType = "video/mp2t",
                    dlnaFeatures = "DLNA.ORG_PN=MPEG_TS_SD_NA_ISO;DLNA.ORG_OP=00;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000",
                    includeMetadata = true,
                ),
            )
            if (!castTarget.chromecastHlsUrl.isNullOrBlank()) {
                rawCandidates.add(
                    DlnaCandidateProfile(
                        label = "LG-HLS-m3u8",
                        mediaUrl = castTarget.chromecastHlsUrl,
                        mimeType = "application/vnd.apple.mpegurl",
                        dlnaFeatures = "*",
                        includeMetadata = true,
                    ),
                )
            }
            rawCandidates.add(
                DlnaCandidateProfile(
                    label = "LG-TS-wild",
                    mediaUrl = castTarget.localUrl,
                    mimeType = "video/mp2t",
                    dlnaFeatures = "*",
                    includeMetadata = true,
                ),
            )
            rawCandidates.add(
                DlnaCandidateProfile(
                    label = "LG-MPEG-wild",
                    mediaUrl = castTarget.localUrl,
                    mimeType = "video/mpeg",
                    dlnaFeatures = "*",
                    includeMetadata = true,
                ),
            )
            rawCandidates.add(
                DlnaCandidateProfile(
                    label = "LG-TS-nometa",
                    mediaUrl = castTarget.localUrl,
                    mimeType = "video/mp2t",
                    dlnaFeatures = "*",
                    includeMetadata = false,
                ),
            )
        } else {
            val directVod = castTarget.directHttpVodUrl
            if (!directVod.isNullOrBlank()) {
                rawCandidates.add(
                    DlnaCandidateProfile(
                        label = "LG-VOD-direto",
                        mediaUrl = directVod,
                        mimeType = castTarget.mimeType,
                        dlnaFeatures = "*",
                        includeMetadata = true,
                        isDirect = true,
                    ),
                )
            }
            rawCandidates.add(
                DlnaCandidateProfile(
                    label = "LG-VOD-DLNA",
                    mediaUrl = castTarget.localUrl,
                    mimeType = castTarget.mimeType,
                    dlnaFeatures = castTarget.dlnaFeatures,
                    includeMetadata = true,
                ),
            )
            rawCandidates.add(
                DlnaCandidateProfile(
                    label = "LG-VOD-wild",
                    mediaUrl = castTarget.localUrl,
                    mimeType = castTarget.mimeType,
                    dlnaFeatures = "*",
                    includeMetadata = true,
                ),
            )
            rawCandidates.add(
                DlnaCandidateProfile(
                    label = "LG-VOD-nometa",
                    mediaUrl = castTarget.localUrl,
                    mimeType = castTarget.mimeType,
                    dlnaFeatures = "*",
                    includeMetadata = false,
                ),
            )
        }
        val candidates = dlnaCapabilities.orderCandidates(
            deviceId = device.id,
            vendor = DlnaVendorProfile.LG,
            isLive = isLive,
            mimeType = castTarget.mimeType,
            candidates = rawCandidates,
            sinkCaps = sinkCaps,
        )

        var lastError: String? = null
        for ((idx, cand) in candidates.withIndex()) {
            if (!sessionGeneration.isCurrent(sessionGen)) {
                return CastAttemptResult(false, castTarget.localUrl, error = "Sessão substituída")
            }
            val stopResp = runCatching {
                postSoap(device.avTransportControlUrl, DlnaProtocol.AV_TRANSPORT_SERVICE, "Stop", DlnaProtocol.buildStopSoap())
            }.getOrNull()
            if (idx == 0) logDiagIfCurrent(sessionGen, "[LG] Stop -> HTTP ${stopResp?.code ?: 0}")
            runCatching { Thread.sleep(350L) }

            val soapBody = DlnaProtocol.buildSetAvTransportUriSoap(
                mediaUrl = cand.mediaUrl,
                title = title,
                isLive = isLive,
                includeMetadata = cand.includeMetadata,
                mimeOverride = cand.mimeType,
                dlnaFeatures = cand.dlnaFeatures,
                contentLength = if (isLive) null else castTarget.contentLength,
            )
            val setResp = runCatching {
                postSoap(device.avTransportControlUrl, DlnaProtocol.AV_TRANSPORT_SERVICE, "SetAVTransportURI", soapBody)
            }.getOrNull()
            if (setResp == null || !setResp.isSuccessful) {
                val fault = setResp?.let { DlnaProtocol.extractSoapFaultSummary(it.body) ?: "HTTP ${it.code}" } ?: "sem resposta"
                lastError = "${cand.label}: SetURI $fault"
                logDiagIfCurrent(sessionGen, "[LG] $lastError")
                continue
            }

            // webOS MediaRenderer needs ~650ms to initialize pipeline before accepting Play
            runCatching { Thread.sleep(650L) }
            var playAccepted = false
            var lastPlayFault: String? = null
            for (attempt in 1..3) {
                if (!sessionGeneration.isCurrent(sessionGen)) {
                    return CastAttemptResult(false, castTarget.localUrl, error = "Sessão substituída")
                }
                val playResp = runCatching {
                    postSoap(device.avTransportControlUrl, DlnaProtocol.AV_TRANSPORT_SERVICE, "Play", DlnaProtocol.buildPlaySoap())
                }.getOrNull()
                if (playResp?.isSuccessful == true) {
                    playAccepted = true
                    break
                }
                val fault = playResp?.let { DlnaProtocol.extractSoapFaultSummary(it.body) ?: "HTTP ${it.code}" } ?: "erro"
                lastPlayFault = fault
                logDiagIfCurrent(sessionGen, "[LG] ${cand.label} Play #$attempt -> $fault")
                // UPnP 701 = Transition not available: inspect real TV state before hammering Play again!
                if (DlnaCapabilityResolver.isUpnp701Fault(fault)) {
                    val liveState = queryDlnaTransportState(device)
                    if (liveState.equals("PLAYING", ignoreCase = true) || liveState.equals("TRANSITIONING", ignoreCase = true)) {
                        logDiagIfCurrent(sessionGen, "[LG] UPnP 701 mas TV já está em $liveState — prosseguindo")
                        playAccepted = true
                        break
                    }
                }
                runCatching { Thread.sleep(650L) }
            }
            if (!playAccepted) {
                lastError = "${cand.label}: Play ${lastPlayFault ?: "sem resposta"}"
                continue
            }

            // Post-Play verification: wait 1.1s and verify webOS actually entered PLAYING/TRANSITIONING or pulled bytes
            runCatching { Thread.sleep(1_100L) }
            if (!sessionGeneration.isCurrent(sessionGen)) {
                return CastAttemptResult(false, castTarget.localUrl, error = "Sessão substituída")
            }
            val postState = queryDlnaTransportState(device)
            val stats = lanProxy.currentStats()
            logDiagIfCurrent(sessionGen, "[LG] ${cand.label} pós-Play: TV=${postState.ifBlank { "?" }} reqs=${stats.totalRequests} bytes=${stats.bytesSent}")

            val tvActive = postState.equals("PLAYING", ignoreCase = true) ||
                postState.equals("TRANSITIONING", ignoreCase = true) ||
                postState.isBlank() ||
                (!cand.isDirect && (stats.bytesSent > 0L || stats.activeClients > 0))
            if (tvActive) {
                if (!sessionGeneration.isCurrent(sessionGen)) {
                    return CastAttemptResult(false, cand.mediaUrl, error = "Sessão substituída")
                }
                dlnaCapabilities.recordWorkingProfile(device.id, isLive, castTarget.mimeType, cand.label)
                if (cand.isDirect) {
                    lanProxy.clearStream(expectedGeneration = sessionGen)
                } else if (!isLive && startPositionMs > 5_000L) {
                    runCatching {
                        postSoap(
                            device.avTransportControlUrl,
                            DlnaProtocol.AV_TRANSPORT_SERVICE,
                            "Seek",
                            DlnaProtocol.buildSeekSoap(startPositionMs),
                        )
                    }
                }
                val vol = queryDlnaVolume(device)
                logDiagIfCurrent(sessionGen, "[LG] Conectado com sucesso via ${cand.label}!")
                return CastAttemptResult(true, cand.mediaUrl, initialVolume = vol, isDirectMode = cand.isDirect)
            }
            lastError = "${cand.label}: TV entrou em $postState"
        }

        if (sessionGeneration.isCurrent(sessionGen)) {
            lanProxy.clearStream(expectedGeneration = sessionGen)
        }
        return CastAttemptResult(
            success = false,
            effectiveUrl = castTarget.localUrl,
            error = "LG TV não iniciou o vídeo (${lastError ?: "erro desconhecido"}). Abra 📋 Diagnóstico e copie para análise.",
        )
    }

    private fun tryDlnaSetUriAndPlay(
        device: CastDevice,
        mediaUrl: String,
        title: String,
        isLive: Boolean,
        mimeType: String,
        dlnaFeatures: String,
        contentLength: Long?,
        startPositionMs: Long,
        sessionGen: Long,
    ): Pair<Boolean, String?> {
        val attempts = listOf(
            DlnaProtocol.buildSetAvTransportUriSoap(
                mediaUrl = mediaUrl,
                title = title,
                isLive = isLive,
                includeMetadata = true,
                mimeOverride = mimeType,
                dlnaFeatures = dlnaFeatures,
                contentLength = contentLength,
            ),
            DlnaProtocol.buildSetAvTransportUriSoap(
                mediaUrl = mediaUrl,
                title = title,
                isLive = isLive,
                includeMetadata = true,
                mimeOverride = if (isLive) "video/mpeg" else mimeType,
                dlnaFeatures = "*",
                contentLength = contentLength,
            ),
            DlnaProtocol.buildSetAvTransportUriSoap(
                mediaUrl = mediaUrl,
                title = title,
                isLive = isLive,
                includeMetadata = false,
            ),
        )

        var setUriOk = false
        var lastFault: String? = null
        for (soapBody in attempts) {
            if (!sessionGeneration.isCurrent(sessionGen)) return false to "Sessão substituída"
            val resp = postSoap(device.avTransportControlUrl, DlnaProtocol.AV_TRANSPORT_SERVICE, "SetAVTransportURI", soapBody)
            if (resp.isSuccessful) {
                setUriOk = true
                break
            }
            lastFault = DlnaProtocol.extractSoapFaultSummary(resp.body) ?: "HTTP ${resp.code}"
        }
        if (!setUriOk) return false to lastFault

        // Give LG webOS / Samsung Tizen time to finish async HEAD probe before Play (avoids UPnP 701!)
        runCatching { Thread.sleep(400L) }
        if (!sessionGeneration.isCurrent(sessionGen)) return false to "Sessão substituída"
        var playRes = postSoap(device.avTransportControlUrl, DlnaProtocol.AV_TRANSPORT_SERVICE, "Play", DlnaProtocol.buildPlaySoap())
        var playAccepted = playRes.isSuccessful
        if (!playAccepted) {
            val fault1 = DlnaProtocol.extractSoapFaultSummary(playRes.body) ?: "HTTP ${playRes.code}"
            if (DlnaCapabilityResolver.isUpnp701Fault(fault1)) {
                val tvState = queryDlnaTransportState(device)
                if (tvState.equals("PLAYING", ignoreCase = true) || tvState.equals("TRANSITIONING", ignoreCase = true)) {
                    playAccepted = true
                }
            }
            if (!playAccepted) {
                runCatching { Thread.sleep(750L) }
                if (!sessionGeneration.isCurrent(sessionGen)) return false to "Sessão substituída"
                playRes = postSoap(device.avTransportControlUrl, DlnaProtocol.AV_TRANSPORT_SERVICE, "Play", DlnaProtocol.buildPlaySoap())
                playAccepted = playRes.isSuccessful
            }
        }
        if (!playAccepted) {
            val detail = DlnaProtocol.extractSoapFaultSummary(playRes.body) ?: "HTTP ${playRes.code}"
            return false to detail
        }

        if (!isLive && startPositionMs > 5_000L && sessionGeneration.isCurrent(sessionGen)) {
            runCatching { Thread.sleep(700L) }
            if (sessionGeneration.isCurrent(sessionGen)) {
                runCatching {
                    postSoap(
                        device.avTransportControlUrl,
                        DlnaProtocol.AV_TRANSPORT_SERVICE,
                        "Seek",
                        DlnaProtocol.buildSeekSoap(startPositionMs),
                    )
                }
            }
        }
        return true to null
    }

    private fun queryDlnaTransportState(device: CastDevice): String = runCatching {
        val tr = postSoap(
            device.avTransportControlUrl,
            DlnaProtocol.AV_TRANSPORT_SERVICE,
            "GetTransportInfo",
            DlnaProtocol.buildGetTransportInfoSoap(),
        )
        if (tr.isSuccessful) DlnaProtocol.extractTagText(tr.body, "CurrentTransportState") else null
    }.getOrNull().orEmpty()

    private fun queryDlnaSinkCapabilities(device: CastDevice): DlnaSinkCapabilities? {
        dlnaCapabilities.getCachedSink(device.id)?.let { return it }
        val cmUrl = device.connectionManagerControlUrl ?: return null
        return runCatching {
            val resp = postSoap(
                cmUrl,
                DlnaProtocol.CONNECTION_MANAGER_SERVICE,
                "GetProtocolInfo",
                DlnaProtocol.buildGetProtocolInfoSoap(),
            )
            if (!resp.isSuccessful) return@runCatching null
            val sink = DlnaProtocol.extractTagText(resp.body, "Sink")?.let(DlnaProtocol::unescapeXml).orEmpty()
            if (sink.isBlank()) return@runCatching null
            DlnaCapabilityResolver.parseSinkProtocolInfo(sink).also { caps ->
                dlnaCapabilities.cacheSink(device.id, caps)
            }
        }.getOrNull()
    }

    private fun queryDlnaVolume(device: CastDevice): Int? = device.renderingControlUrl?.let { rcUrl ->
        runCatching {
            val volResp = postSoap(rcUrl, DlnaProtocol.RENDERING_CONTROL_SERVICE, "GetVolume", DlnaProtocol.buildGetVolumeSoap())
            DlnaProtocol.extractTagText(volResp.body, "CurrentVolume")?.toIntOrNull()
        }.getOrNull()
    }

    suspend fun togglePlayPause(context: Context? = null) {
        val (gen, current) = synchronized(stateCommitLock) { sessionGeneration.current() to mutableState.value }
        val device = current.connectedDevice ?: return
        val nextPlaying = !current.isPlayingOnTv
        val ok = withContext(Dispatchers.IO) {
            when (device.protocol) {
                CastProtocol.CHROMECAST -> if (nextPlaying) googleCastClient.play() else googleCastClient.pause()
                CastProtocol.DLNA -> {
                    val action = if (nextPlaying) "Play" else "Pause"
                    val soap = if (nextPlaying) DlnaProtocol.buildPlaySoap() else DlnaProtocol.buildPauseSoap()
                    runCatching {
                        postSoap(device.avTransportControlUrl, DlnaProtocol.AV_TRANSPORT_SERVICE, action, soap).isSuccessful
                    }.getOrDefault(false)
                }
            }
        }
        if (ok) {
            val committed = synchronized(stateCommitLock) {
                if (!sessionGeneration.isCurrent(gen)) return@synchronized false
                userPausedLocally = !nextPlaying
                mutableState.update {
                    it.copy(
                        isPlayingOnTv = nextPlaying,
                        tvTransportState = if (nextPlaying) "PLAYING" else "PAUSED",
                        errorMessage = null,
                    )
                }
                true
            }
            if (committed && context != null) {
                CastRelayService.startOrUpdate(
                    context,
                    device.friendlyName,
                    current.castingTitle,
                    nextPlaying,
                    isDirectMode = current.castingDirectMode,
                )
            }
        }
    }

    suspend fun seekTo(targetMs: Long) {
        val (gen, current) = synchronized(stateCommitLock) { sessionGeneration.current() to mutableState.value }
        val device = current.connectedDevice ?: return
        if (current.castingIsLive) return
        val clamped = targetMs.coerceAtLeast(0L).let {
            if (current.tvDurationMs > 0L) it.coerceAtMost(current.tvDurationMs) else it
        }
        val ok = withContext(Dispatchers.IO) {
            when (device.protocol) {
                CastProtocol.CHROMECAST -> googleCastClient.seek(clamped)
                CastProtocol.DLNA -> runCatching {
                    postSoap(
                        device.avTransportControlUrl,
                        DlnaProtocol.AV_TRANSPORT_SERVICE,
                        "Seek",
                        DlnaProtocol.buildSeekSoap(clamped),
                    ).isSuccessful
                }.getOrDefault(false)
            }
        }
        if (ok) {
            synchronized(stateCommitLock) {
                if (sessionGeneration.isCurrent(gen)) {
                    mutableState.update { it.copy(tvPositionMs = clamped, errorMessage = null) }
                }
            }
        }
    }

    suspend fun seekRelative(deltaMs: Long) {
        seekTo(state.value.tvPositionMs + deltaMs)
    }

    suspend fun adjustVolume(delta: Int) {
        val (gen, current) = synchronized(stateCommitLock) { sessionGeneration.current() to mutableState.value }
        val device = current.connectedDevice ?: return
        val nextVol = (current.tvVolume + delta).coerceIn(0, 100)
        val ok = withContext(Dispatchers.IO) {
            when (device.protocol) {
                CastProtocol.CHROMECAST -> googleCastClient.setVolume(nextVol)
                CastProtocol.DLNA -> {
                    val rcUrl = device.renderingControlUrl ?: return@withContext false
                    runCatching {
                        postSoap(
                            rcUrl,
                            DlnaProtocol.RENDERING_CONTROL_SERVICE,
                            "SetVolume",
                            DlnaProtocol.buildSetVolumeSoap(nextVol),
                        ).isSuccessful
                    }.getOrDefault(false)
                }
            }
        }
        if (ok) {
            synchronized(stateCommitLock) {
                if (sessionGeneration.isCurrent(gen)) {
                    mutableState.update { it.copy(tvVolume = nextVol, errorMessage = null) }
                }
            }
        }
    }

    suspend fun pollPosition() {
        val (gen, current) = synchronized(stateCommitLock) { sessionGeneration.current() to mutableState.value }
        val device = current.connectedDevice ?: return
        val stats = readProxyStats()
        when (device.protocol) {
            CastProtocol.CHROMECAST -> {
                val st = if (chromecastStatusHook != null) {
                    chromecastStatusHook.invoke()
                } else {
                    withContext(Dispatchers.IO) { googleCastClient.requestStatus() }
                }
                val stateLabel = st?.let {
                    if (!it.idleReason.isNullOrBlank()) "${it.playerState} (${it.idleReason})" else it.playerState
                }
                synchronized(stateCommitLock) {
                    if (!sessionGeneration.isCurrent(gen)) return
                    mutableState.update { prev ->
                        prev.copy(
                            proxyStats = stats,
                            tvTransportState = stateLabel ?: prev.tvTransportState,
                            isPlayingOnTv = st?.let { it.playerState == "PLAYING" || it.playerState == "BUFFERING" } ?: prev.isPlayingOnTv,
                            tvPositionMs = st?.let { (it.currentTimeSec * 1000.0).toLong().coerceAtLeast(0L) } ?: prev.tvPositionMs,
                            tvDurationMs = st?.takeIf { it.durationSec > 0.0 }?.let { (it.durationSec * 1000.0).toLong() } ?: prev.tvDurationMs,
                        )
                    }
                }
            }
            CastProtocol.DLNA -> {
                val (transportState, relMs, durMs) = withContext(Dispatchers.IO) {
                    val tState = runCatching {
                        val tr = postSoap(
                            device.avTransportControlUrl,
                            DlnaProtocol.AV_TRANSPORT_SERVICE,
                            "GetTransportInfo",
                            DlnaProtocol.buildGetTransportInfoSoap(),
                        )
                        if (tr.isSuccessful) DlnaProtocol.extractTagText(tr.body, "CurrentTransportState") else null
                    }.getOrNull()

                    var rel: Long? = null
                    var dur: Long? = null
                    if (!current.castingIsLive) {
                        runCatching {
                            val pr = postSoap(
                                device.avTransportControlUrl,
                                DlnaProtocol.AV_TRANSPORT_SERVICE,
                                "GetPositionInfo",
                                DlnaProtocol.buildGetPositionInfoSoap(),
                            )
                            if (pr.isSuccessful) {
                                rel = DlnaProtocol.parseUpnpTimeMs(DlnaProtocol.extractTagText(pr.body, "RelTime"))
                                dur = DlnaProtocol.parseUpnpTimeMs(DlnaProtocol.extractTagText(pr.body, "TrackDuration"))
                            }
                        }
                    }
                    Triple(tState, rel, dur)
                }

                synchronized(stateCommitLock) {
                    if (!sessionGeneration.isCurrent(gen)) return
                    mutableState.update { prev ->
                        prev.copy(
                            proxyStats = stats,
                            tvTransportState = transportState ?: prev.tvTransportState,
                            tvPositionMs = relMs ?: prev.tvPositionMs,
                            tvDurationMs = durMs?.takeIf { it > 0L } ?: prev.tvDurationMs,
                        )
                    }
                }
            }
        }
    }

    /**
     * Background Cast/DLNA watchdog (invoked every 12s by CastRelayService even with screen locked):
     * refreshes bridge health/position and performs at most ONE quiet auto-recovery if a stable
     * Bridge session unexpectedly dropped (DLNA STOPPED/NO_MEDIA_PRESENT or Chromecast IDLE ERROR / stall).
     */
    suspend fun watchdogTick(context: Context?) {
        val (gen, before) = synchronized(stateCommitLock) { sessionGeneration.current() to mutableState.value }
        val device = before.connectedDevice ?: return
        if (reconnectingInFlight.get() || before.busyAction != null) return

        // Silent fast-path: if TV/Chromecast is actively pulling bytes from our local bridge right now,
        // skip SOAP XML polling completely so we never add CPU/Wi-Fi contention on the Smart TV!
        if (!before.castingDirectMode) {
            val liveStats = readProxyStats()
            if (liveStats.activeClients > 0 && liveStats.bytesSent > lastWatchdogBytesSent) {
                lastWatchdogBytesSent = liveStats.bytesSent
                consecutiveStoppedTicks = 0
                synchronized(stateCommitLock) {
                    if (sessionGeneration.isCurrent(gen)) {
                        mutableState.update { prev ->
                            prev.copy(proxyStats = liveStats, tvTransportState = "PLAYING")
                        }
                    }
                }
                return
            }
            lastWatchdogBytesSent = liveStats.bytesSent
        }

        pollPosition()
        val after = synchronized(stateCommitLock) {
            if (!sessionGeneration.isCurrent(gen)) return
            mutableState.value
        }

        // Respect explicit user pause (local UI button or remote PAUSED / PAUSED_PLAYBACK) without
        // mistakenly treating Chromecast IDLE drops as a user pause on tick 2!
        val userPaused = userPausedLocally ||
            before.tvTransportState.startsWith("PAUSED", ignoreCase = true) ||
            after.tvTransportState.startsWith("PAUSED", ignoreCase = true)
        if (userPaused) {
            consecutiveStoppedTicks = 0
            return
        }
        val nowMs = clockMs()
        val elapsedSinceStart = nowMs - castStartedAtMs
        val dropped = when (device.protocol) {
            CastProtocol.DLNA -> after.tvTransportState.equals("STOPPED", ignoreCase = true) ||
                after.tvTransportState.equals("NO_MEDIA_PRESENT", ignoreCase = true)
            CastProtocol.CHROMECAST -> {
                val normalEnd = after.tvTransportState.contains("FINISHED", ignoreCase = true) ||
                    after.tvTransportState.contains("CANCELLED", ignoreCase = true)
                !after.castingDirectMode && !normalEnd && (
                    after.tvTransportState.contains("ERROR", ignoreCase = true) ||
                        after.tvTransportState.contains("INTERRUPTED", ignoreCase = true) ||
                        (after.proxyStats.lastActivityMs > 0L &&
                            (nowMs - after.proxyStats.lastActivityMs) > 25_000L &&
                            after.proxyStats.activeClients == 0)
                    )
            }
        }

        // Only attempt at most ONE quiet auto-recovery if playback had ALREADY stabilized for > 45s
        // with >= 2 MB transferred. Never auto-loop if the TV failed on initial connect!
        val hadStablePlayback = elapsedSinceStart > 45_000L && after.proxyStats.bytesSent >= 2_000_000L
        if (dropped && hadStablePlayback && after.proxyStats.activeClients == 0) {
            consecutiveStoppedTicks++
            // Avoid auto-reconnecting if a VOD movie genuinely finished near the end
            val nearVodEnd = !after.castingIsLive && after.tvDurationMs > 30_000L &&
                after.tvPositionMs >= (after.tvDurationMs - 15_000L)
            if (consecutiveStoppedTicks >= 2 && !nearVodEnd && after.autoReconnectCount < 1 && sessionGeneration.isCurrent(gen)) {
                consecutiveStoppedTicks = 0
                logDiagIfCurrent(gen, "[Watchdog] Queda detectada em ${device.friendlyName} (${after.tvTransportState}) — tentando 1 reconexão silenciosa")
                reconnectCurrent(context, isAuto = true, expectedGen = gen)
            }
        } else {
            consecutiveStoppedTicks = 0
        }
    }

    suspend fun reconnectCurrent(
        context: Context?,
        isAuto: Boolean = false,
        expectedGen: Long? = null,
    ): Boolean {
        val (snapGen, current) = synchronized(stateCommitLock) {
            val g = sessionGeneration.current()
            if (expectedGen != null && !sessionGeneration.isCurrent(expectedGen)) {
                return false
            }
            g to mutableState.value
        }
        val device = current.connectedDevice ?: return false
        val sourceUrl = current.rawSourceUrl.ifBlank { return false }
        if (!reconnectingInFlight.compareAndSet(false, true)) return false
        return try {
            castMediaInternal(
                context = context,
                device = device,
                rawUrl = sourceUrl,
                title = current.castingTitle,
                isLive = current.castingIsLive,
                startPositionMs = if (current.castingIsLive) 0L else current.tvPositionMs,
                isAutoReconnect = isAuto,
                requiredCurrentGen = expectedGen ?: snapGen,
            )
        } finally {
            reconnectingInFlight.set(false)
        }
    }

    /**
     * Releases the phone cleanly while keeping Direct-mode playback running autonomously on the TV / Chromecast!
     * Strictly idempotent and advances [sessionGeneration] first so any in-flight connect attempt is invalidated.
     *
     * Returns `true` when detached in Direct Mode, or `false` if the active stream requires the phone's Wi-Fi proxy.
     */
    suspend fun detachKeepPlaying(context: Context? = null): Boolean {
        val device = synchronized(stateCommitLock) {
            val current = mutableState.value
            val dev = current.connectedDevice ?: return@synchronized null
            if (!current.castingDirectMode) return@synchronized null
            val closedGen = sessionGeneration.advance()
            lanProxy.invalidateAndClearUpTo(closedGen)
            consecutiveStoppedTicks = 0
            userPausedLocally = false
            mutableState.update {
                it.copy(
                    connectedDevice = null,
                    castingTitle = "",
                    castingUrl = "",
                    rawSourceUrl = "",
                    castingDirectMode = false,
                    isPlayingOnTv = false,
                    tvTransportState = "",
                    tvPositionMs = 0L,
                    tvDurationMs = 0L,
                    proxyStats = ProxyRuntimeStats(),
                    autoReconnectCount = 0,
                    busyAction = null,
                    errorMessage = null,
                )
            }
            dev
        } ?: return false
        logDiag("[Cast] Celular liberado — ${device.friendlyName} continua reproduzindo sozinho!")
        if (context != null) {
            CastRelayService.stop(context)
        }
        withContext(NonCancellable + Dispatchers.IO) {
            when (device.protocol) {
                CastProtocol.CHROMECAST -> {
                    val hook = chromecastDisconnectHook
                    if (hook != null) hook(false) else googleCastClient.disconnect(stopApp = false)
                }
                CastProtocol.DLNA -> Unit // Direct CDN VOD: NEVER send UPnP Stop!
            }
        }
        return true
    }

    /**
     * Strictly idempotent disconnect: advances [sessionGeneration] immediately so any slow/in-flight
     * connect attempt can never resurrect this session after disconnect returns.
     */
    suspend fun disconnect(context: Context? = null) {
        val device = synchronized(stateCommitLock) {
            val closedGen = sessionGeneration.advance()
            lanProxy.invalidateAndClearUpTo(closedGen)
            val dev = mutableState.value.connectedDevice
            consecutiveStoppedTicks = 0
            userPausedLocally = false
            mutableState.update {
                it.copy(
                    connectedDevice = null,
                    castingTitle = "",
                    castingUrl = "",
                    rawSourceUrl = "",
                    castingDirectMode = false,
                    isPlayingOnTv = false,
                    tvTransportState = "",
                    tvPositionMs = 0L,
                    tvDurationMs = 0L,
                    proxyStats = ProxyRuntimeStats(),
                    autoReconnectCount = 0,
                    busyAction = null,
                    errorMessage = null,
                )
            }
            dev
        }
        if (context != null) {
            CastRelayService.stop(context)
        }
        if (device != null) {
            withContext(NonCancellable + Dispatchers.IO) {
                when (device.protocol) {
                    CastProtocol.CHROMECAST -> {
                        val hook = chromecastDisconnectHook
                        if (hook != null) hook(true) else googleCastClient.disconnect(stopApp = true)
                    }
                    CastProtocol.DLNA -> {
                        val hook = dlnaStopHook
                        if (hook != null) {
                            hook(device)
                        } else {
                            runCatching {
                                postSoap(
                                    device.avTransportControlUrl,
                                    DlnaProtocol.AV_TRANSPORT_SERVICE,
                                    "Stop",
                                    DlnaProtocol.buildStopSoap(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    fun clearError() {
        mutableState.update { it.copy(errorMessage = null) }
    }

    private suspend fun discoverDlnaDevices(appContext: Context): List<CastDevice> {
        val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val multicastLock = runCatching {
            wifi?.createMulticastLock("StarlinkDlnaDiscovery")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }.getOrNull()

        val locations = LinkedHashSet<String>()
        try {
            DatagramSocket(null).use { socket ->
                socket.reuseAddress = true
                socket.broadcast = true
                val bindAddr = DlnaProtocol.localLanIpv4Address()
                if (bindAddr != null) {
                    socket.bind(InetSocketAddress(bindAddr, 0))
                } else {
                    socket.bind(InetSocketAddress(0))
                }
                socket.soTimeout = 350

                val multicastGroup = InetAddress.getByName(DlnaProtocol.SSDP_ADDRESS)
                val queries = listOf(
                    DlnaProtocol.buildMSearchPacket(DlnaProtocol.AV_TRANSPORT_SERVICE, mxSeconds = 2),
                    DlnaProtocol.buildMSearchPacket(DlnaProtocol.MEDIA_RENDERER_DEVICE, mxSeconds = 2),
                )

                repeat(2) { burst ->
                    for (query in queries) {
                        val bytes = query.toByteArray(Charsets.UTF_8)
                        val packet = DatagramPacket(bytes, bytes.size, multicastGroup, DlnaProtocol.SSDP_PORT)
                        runCatching { socket.send(packet) }
                    }
                    val deadline = System.currentTimeMillis() + if (burst == 0) 900L else 1_400L
                    val buf = ByteArray(4096)
                    while (System.currentTimeMillis() < deadline) {
                        val recv = DatagramPacket(buf, buf.size)
                        try {
                            socket.receive(recv)
                            val text = String(recv.data, 0, recv.length, Charsets.UTF_8)
                            val loc = DlnaProtocol.parseSsdpLocation(text)
                            if (loc != null && DlnaProtocol.isSafeLanUrl(loc)) {
                                locations.add(loc)
                            }
                        } catch (_: SocketTimeoutException) {
                            // keep listening until burst deadline
                        }
                    }
                }
            }
        } finally {
            runCatching {
                if (multicastLock?.isHeld == true) multicastLock.release()
            }
        }

        if (locations.isEmpty()) return emptyList()

        return coroutineScope {
            locations.map { loc ->
                async(Dispatchers.IO) { fetchDeviceDescription(loc) }
            }.awaitAll().filterNotNull().distinctBy { it.avTransportControlUrl }
        }
    }

    private fun fetchDeviceDescription(locationUrl: String): CastDevice? {
        if (!DlnaProtocol.isSafeLanUrl(locationUrl)) return null
        return runCatching {
            val req = Request.Builder().url(locationUrl).get().build()
            lanHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val xmlBytes = UpstreamUrlSecurity.readBoundedBytes(
                    resp.body.byteStream(),
                    UpstreamUrlSecurity.MAX_DEVICE_XML_BYTES,
                )
                val xml = String(xmlBytes, Charsets.UTF_8)
                if (xml.isBlank()) return null
                DlnaProtocol.parseDeviceDescriptionXml(xml, locationUrl)
            }
        }.getOrNull()
    }

    internal fun resolvePlayableCastUrl(rawUrl: String, isLive: Boolean): String {
        val rewritten = DlnaProtocol.rewriteMediaUrlForCast(rawUrl, isLive)
        if (isLive) return rewritten
        var current = rewritten
        repeat(3) {
            val next = runCatching {
                val headReq = Request.Builder()
                    .url(current)
                    .head()
                    .header("User-Agent", "OTT-play-Android/1.0")
                    .build()
                redirectResolverClient.newCall(headReq).execute().use { resp ->
                    if (resp.code in 301..308) {
                        val loc = resp.header("Location")
                        if (!loc.isNullOrBlank()) resp.request.url.resolve(loc)?.toString() else null
                    } else null
                }
            }.getOrNull() ?: return current
            if (next == current) return current
            current = next
        }
        return current
    }

    private data class SoapResult(val isSuccessful: Boolean, val code: Int, val body: String)

    private fun postSoap(controlUrl: String, serviceType: String, action: String, envelopeXml: String): SoapResult {
        require(DlnaProtocol.isSafeLanUrl(controlUrl)) { "Blocked non-LAN UPnP target" }
        val reqBody = envelopeXml.toRequestBody("text/xml; charset=\"utf-8\"".toMediaType())
        val req = Request.Builder()
            .url(controlUrl)
            .post(reqBody)
            .header("SOAPACTION", "\"$serviceType#$action\"")
            .header("Connection", "close")
            .build()
        lanHttpClient.newCall(req).execute().use { resp ->
            return SoapResult(resp.isSuccessful, resp.code, resp.body.string())
        }
    }

    companion object {
        internal fun guessVodMime(url: String, contentType: String = ""): String {
            val lower = url.substringBefore('?').lowercase()
            val ct = contentType.substringBefore(';').trim().lowercase()
            return when {
                lower.endsWith(".mkv") || ct.contains("matroska") -> "video/x-matroska"
                lower.endsWith(".webm") || ct.contains("webm") -> "video/webm"
                else -> "video/mp4"
            }
        }

        internal fun isGstaticCorsAllowed(acaoHeader: String?): Boolean {
            val normalized = acaoHeader?.trim().orEmpty()
            return normalized == "*" || normalized.equals("https://www.gstatic.com", ignoreCase = true)
        }

        internal fun isDirectHttpsHlsManifest(body: String, acaoHeader: String?): Boolean {
            if (!isGstaticCorsAllowed(acaoHeader)) return false
            if (!body.trimStart().startsWith("#EXTM3U")) return false
            val mediaLines = body.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .toList()
            return mediaLines.isNotEmpty() &&
                mediaLines.all { it.startsWith("https://", ignoreCase = true) || !it.contains("://") }
        }

        /**
         * Builds ordered Direct Cloud/CDN VOD attempts for Chromecast:
         * 1. Already-resolved final external CDN URL (panel is not even touched by Chromecast).
         * 2. Original HTTPS auth redirect URL (`307` -> CDN, ZERO video bytes through the panel) in case
         *    the CDN token from attempt #1 was single-use or bound to the client User-Agent.
         */
        internal fun buildDirectChromecastVodCandidates(
            rewrittenUrl: String,
            resolvedCandidate: Pair<String, String>?,
        ): List<Pair<String, String>> {
            val out = ArrayList<Pair<String, String>>(2)
            if (resolvedCandidate != null && resolvedCandidate.first.isNotBlank()) {
                out.add(resolvedCandidate)
            }
            if (rewrittenUrl.startsWith("https://", ignoreCase = true) &&
                out.none { it.first == rewrittenUrl }
            ) {
                val mime = resolvedCandidate?.second ?: guessVodMime(rewrittenUrl)
                out.add(rewrittenUrl to mime)
            }
            return out
        }

        fun openExternalCastApp(context: Context, mediaUrl: String, title: String, isLive: Boolean): Boolean {
            val preparedUrl = DlnaProtocol.rewriteMediaUrlForCast(mediaUrl, isLive)
            if (!preparedUrl.startsWith("http://", ignoreCase = true) &&
                !preparedUrl.startsWith("https://", ignoreCase = true)
            ) {
                Toast.makeText(context, "Aguarde o vídeo carregar para abrir em app externo.", Toast.LENGTH_SHORT).show()
                return false
            }
            val mime = if (isLive || preparedUrl.lowercase().contains(".m3u8")) "application/x-mpegURL" else "video/*"
            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(preparedUrl), mime)
                putExtra("title", title)
                putExtra("android.intent.extra.TITLE", title)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val fallbackIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(preparedUrl), "video/*")
                putExtra("title", title)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val chosen = if (hasExternalHandler(context, viewIntent)) viewIntent else fallbackIntent
            if (!hasExternalHandler(context, chosen)) {
                Toast.makeText(
                    context,
                    "Instale Web Video Caster ou VLC para enviar por app externo, ou selecione sua TV acima.",
                    Toast.LENGTH_LONG,
                ).show()
                return false
            }
            return runCatching {
                context.startActivity(Intent.createChooser(chosen, "Transmitir: $title"))
                true
            }.getOrDefault(false)
        }

        private fun hasExternalHandler(context: Context, intent: Intent): Boolean = runCatching {
            context.packageManager.queryIntentActivities(intent, 0)
                .any { it.activityInfo.packageName != context.packageName }
        }.getOrDefault(false)
    }
}
