package play.ott.nativeapp.cast

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.widget.Toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
internal class UniversalCastController {
    private val mutableState = MutableStateFlow(CastSessionState())
    val state: StateFlow<CastSessionState> = mutableState.asStateFlow()
    private val lanProxy = LanStreamProxyServer()
    private val googleCastClient = GoogleCastClient()
    @Volatile private var castStartedAtMs: Long = 0L
    @Volatile private var consecutiveStoppedTicks: Int = 0
    @Volatile private var reconnectingInFlight: Boolean = false

    private val lanHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(3, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    private val redirectResolverClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
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
        context: Context,
        device: CastDevice,
        rawUrl: String,
        title: String,
        isLive: Boolean,
        startPositionMs: Long = 0L,
    ): Boolean {
        mutableState.update {
            it.copy(
                busyAction = "Conectando em ${device.friendlyName}...",
                errorMessage = null,
            )
        }
        val appContext = context.applicationContext
        return try {
            // Stop prior Cast v2 session ONLY when switching to a DIFFERENT TV/Chromecast
            // (reusing the active CC1AD845 session on the same Chromecast avoids STOP->LAUNCH races!)
            val prevDevice = state.value.connectedDevice
            if (prevDevice?.protocol == CastProtocol.CHROMECAST &&
                prevDevice.avTransportControlUrl != device.avTransportControlUrl
            ) {
                withContext(Dispatchers.IO) { googleCastClient.disconnect(stopApp = true) }
            }

            val outcome = withContext(Dispatchers.IO) {
                when (device.protocol) {
                    CastProtocol.CHROMECAST -> castToChromecast(device, rawUrl, title, isLive, startPositionMs)
                    CastProtocol.DLNA -> castToDlna(device, rawUrl, title, isLive, startPositionMs)
                }
            }

            if (outcome.success) {
                val vol = outcome.initialVolume ?: state.value.tvVolume
                castStartedAtMs = System.currentTimeMillis()
                consecutiveStoppedTicks = 0
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
                        proxyStats = lanProxy.currentStats(),
                        autoReconnectCount = if (reconnectingInFlight) it.autoReconnectCount else 0,
                        busyAction = null,
                        errorMessage = null,
                    )
                }
                // Keep foreground service + WakeLock + WifiLock alive so user can freely use phone or lock screen!
                CastRelayService.startOrUpdate(appContext, device.friendlyName, title, isPlaying = true)
                true
            } else {
                CastRelayService.stop(appContext)
                mutableState.update {
                    it.copy(
                        busyAction = null,
                        errorMessage = outcome.error ?: "Não foi possível transmitir para ${device.friendlyName}.",
                    )
                }
                false
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            lanProxy.clearStream()
            CastRelayService.stop(appContext)
            mutableState.update {
                it.copy(
                    busyAction = null,
                    errorMessage = "Falha ao enviar para ${device.friendlyName}: ${e.message ?: "verifique o Wi-Fi"}",
                )
            }
            false
        }
    }

    private data class CastAttemptResult(
        val success: Boolean,
        val effectiveUrl: String,
        val initialVolume: Int? = null,
        val isDirectMode: Boolean = false,
        val error: String? = null,
    )

    private fun castToChromecast(
        device: CastDevice,
        rawUrl: String,
        title: String,
        isLive: Boolean,
        startPositionMs: Long,
    ): CastAttemptResult {
        val rewritten = DlnaProtocol.rewriteMediaUrlForCast(rawUrl, isLive)

        // 1. Direct cloud mode (ZERO phone proxy / works even with phone screen off or app closed!):
        //    - Live Pluto TV / HTTPS+CORS HLS on M81 -> direct https://.../live-hls/...m3u8
        //    - VOD Movies/Series -> resolved direct CDN MP4/MKV (Chromecast fetches Range chunks natively!)
        val directCandidate = resolveDirectChromecastCandidate(rewritten, isLive)
        if (directCandidate != null) {
            val (directUrl, directMime) = directCandidate
            val directRes = googleCastClient.connectAndLoad(
                device = device,
                mediaUrl = directUrl,
                title = title,
                contentType = directMime,
                isLive = isLive,
                startPositionMs = startPositionMs,
            )
            if (directRes.isSuccess) {
                lanProxy.clearStream()
                return CastAttemptResult(
                    success = true,
                    effectiveUrl = directUrl,
                    initialVolume = directRes.getOrNull(),
                    isDirectMode = true,
                )
            }
        }

        // 2. Fallback to local Wi-Fi CORS proxy (needed for IPTV Brasil HTTP segments without upstream CORS)
        val phoneWifiIp = DlnaProtocol.localLanIpv4ForTarget(device.host)
        val target = if (phoneWifiIp != null) {
            lanProxy.prepareStream(rewritten, isLive, phoneWifiIp)
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
        if (res.isSuccess) {
            return CastAttemptResult(true, mediaUrl, initialVolume = res.getOrNull(), isDirectMode = false)
        }
        lanProxy.clearStream()
        return CastAttemptResult(
            success = false,
            effectiveUrl = mediaUrl,
            error = res.exceptionOrNull()?.message ?: "Chromecast recusou a reprodução deste vídeo.",
        )
    }

    /**
     * Checks whether Chromecast can pull the media directly from M81/CDN without phone proxying:
     * - LIVE: M81 `/live-hls/` redirecting to `/iptv/pluto/` (all HTTPS + `ACAO: *`!), or HTTPS HLS with HTTPS segments.
     * - VOD: follows M81 redirects (GET Range 0-0) to the final MP4/MKV CDN URL.
     */
    private fun resolveDirectChromecastCandidate(rewrittenUrl: String, isLive: Boolean): Pair<String, String>? {
        if (!rewrittenUrl.startsWith("http://", ignoreCase = true) &&
            !rewrittenUrl.startsWith("https://", ignoreCase = true)
        ) return null

        if (isLive) {
            if (!rewrittenUrl.startsWith("https://", ignoreCase = true)) return null
            return runCatching {
                val req = Request.Builder()
                    .url(rewrittenUrl)
                    .header("Origin", "https://www.gstatic.com")
                    .header("User-Agent", "OTT-play-Android/1.0")
                    .get()
                    .build()
                redirectResolverClient.newCall(req).execute().use { resp ->
                    val acao = resp.header("Access-Control-Allow-Origin")
                    if (acao != "*") return@use null
                    val loc = resp.header("Location").orEmpty()
                    if (resp.code in 301..308 && loc.contains("/iptv/pluto/", ignoreCase = true)) {
                        // Pluto TV via M81: every hop & CDN segment is HTTPS + ACAO=*!
                        return@use rewrittenUrl to "application/vnd.apple.mpegurl"
                    }
                    if (resp.isSuccessful) {
                        val body = resp.body.string()
                        val mediaLines = body.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList()
                        // Only direct-cast HLS if all segment URLs are HTTPS (never HTTP mixed content)
                        if (mediaLines.isNotEmpty() && mediaLines.all { it.startsWith("https://", ignoreCase = true) || !it.contains("://") }) {
                            return@use rewrittenUrl to "application/vnd.apple.mpegurl"
                        }
                    }
                    null
                }
            }.getOrNull()
        }

        // VOD: resolve 307/302 redirect chain via Range 0-0 to final direct CDN URL
        return runCatching {
            val vodClient = OkHttpClient.Builder()
                .connectTimeout(6, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
            val req = Request.Builder()
                .url(rewrittenUrl)
                .header("Origin", "https://www.gstatic.com")
                .header("User-Agent", "OTT-play-Android/1.0")
                .header("Range", "bytes=0-0")
                .get()
                .build()
            vodClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful && resp.code != 206) return@use null
                val finalUrl = resp.request.url.toString()
                val lower = finalUrl.substringBefore('?').lowercase()
                val ct = resp.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase().orEmpty()
                val mime = when {
                    lower.endsWith(".mkv") || ct.contains("matroska") -> "video/x-matroska"
                    lower.endsWith(".webm") || ct.contains("webm") -> "video/webm"
                    else -> "video/mp4"
                }
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
    ): CastAttemptResult {
        val rewrittenUrl = DlnaProtocol.rewriteMediaUrlForCast(rawUrl, isLive)
        val phoneWifiIp = DlnaProtocol.localLanIpv4ForTarget(device.host)
        val castTarget = if (phoneWifiIp != null) {
            lanProxy.prepareStream(rewrittenUrl, isLive, phoneWifiIp)
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

        // Best-effort Stop clears stale state on Samsung / LG renderers
        runCatching {
            postSoap(device.avTransportControlUrl, DlnaProtocol.AV_TRANSPORT_SERVICE, "Stop", DlnaProtocol.buildStopSoap())
            Thread.sleep(250L)
        }

        // Always use the local Wi-Fi proxy for DLNA:
        // - Live: continuous 188-byte aligned MPEG-TS with Null Packet keep-alive + AES-128 decryption.
        // - VOD: 4 MB Range-stitched stream defeating Cloudflare Worker ~90s single-connection drops!
        val proxyAttempt = tryDlnaSetUriAndPlay(
            device = device,
            mediaUrl = castTarget.localUrl,
            title = title,
            isLive = isLive,
            mimeType = castTarget.mimeType,
            dlnaFeatures = castTarget.dlnaFeatures,
            contentLength = castTarget.contentLength,
            startPositionMs = startPositionMs,
        )
        if (!proxyAttempt.first) {
            lanProxy.clearStream()
            val detail = proxyAttempt.second?.let { " ($it)" }.orEmpty()
            return CastAttemptResult(
                success = false,
                effectiveUrl = castTarget.localUrl,
                error = "A TV recusou o vídeo$detail. Tente novamente ou use Web Video Caster.",
            )
        }
        val vol = queryDlnaVolume(device)
        return CastAttemptResult(true, castTarget.localUrl, initialVolume = vol)
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
        var playRes = postSoap(device.avTransportControlUrl, DlnaProtocol.AV_TRANSPORT_SERVICE, "Play", DlnaProtocol.buildPlaySoap())
        if (!playRes.isSuccessful) {
            runCatching { Thread.sleep(750L) }
            playRes = postSoap(device.avTransportControlUrl, DlnaProtocol.AV_TRANSPORT_SERVICE, "Play", DlnaProtocol.buildPlaySoap())
        }
        if (!playRes.isSuccessful) {
            val detail = DlnaProtocol.extractSoapFaultSummary(playRes.body) ?: "HTTP ${playRes.code}"
            return false to detail
        }

        if (!isLive && startPositionMs > 5_000L) {
            runCatching { Thread.sleep(700L) }
            runCatching {
                postSoap(
                    device.avTransportControlUrl,
                    DlnaProtocol.AV_TRANSPORT_SERVICE,
                    "Seek",
                    DlnaProtocol.buildSeekSoap(startPositionMs),
                )
            }
        }
        return true to null
    }

    private fun queryDlnaVolume(device: CastDevice): Int? = device.renderingControlUrl?.let { rcUrl ->
        runCatching {
            val volResp = postSoap(rcUrl, DlnaProtocol.RENDERING_CONTROL_SERVICE, "GetVolume", DlnaProtocol.buildGetVolumeSoap())
            DlnaProtocol.extractTagText(volResp.body, "CurrentVolume")?.toIntOrNull()
        }.getOrNull()
    }

    suspend fun togglePlayPause(context: Context? = null) {
        val current = state.value
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
            mutableState.update { it.copy(isPlayingOnTv = nextPlaying, errorMessage = null) }
            if (context != null) {
                CastRelayService.startOrUpdate(context, device.friendlyName, current.castingTitle, nextPlaying)
            }
        }
    }

    suspend fun seekTo(targetMs: Long) {
        val current = state.value
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
            mutableState.update { it.copy(tvPositionMs = clamped, errorMessage = null) }
        }
    }

    suspend fun seekRelative(deltaMs: Long) {
        seekTo(state.value.tvPositionMs + deltaMs)
    }

    suspend fun adjustVolume(delta: Int) {
        val current = state.value
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
            mutableState.update { it.copy(tvVolume = nextVol, errorMessage = null) }
        }
    }

    suspend fun pollPosition() {
        val current = state.value
        val device = current.connectedDevice ?: return
        val stats = lanProxy.currentStats()
        when (device.protocol) {
            CastProtocol.CHROMECAST -> {
                val st = withContext(Dispatchers.IO) { googleCastClient.requestStatus() }
                val stateLabel = st?.let {
                    if (!it.idleReason.isNullOrBlank()) "${it.playerState} (${it.idleReason})" else it.playerState
                }
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

    /**
     * Background DLNA watchdog (invoked every 5s by CastRelayService even with screen locked):
     * refreshes telemetry/position and automatically re-attaches the stream if the Smart TV
     * unexpectedly dropped to STOPPED/NO_MEDIA_PRESENT during a live ad discontinuity or Wi-Fi hiccup.
     */
    suspend fun watchdogTick(context: Context) {
        val before = state.value
        val device = before.connectedDevice ?: return
        if (reconnectingInFlight || before.busyAction != null) return
        pollPosition()
        if (device.protocol != CastProtocol.DLNA) return

        val after = state.value
        if (!after.isPlayingOnTv) {
            consecutiveStoppedTicks = 0
            return
        }
        val elapsedSinceStart = System.currentTimeMillis() - castStartedAtMs
        val dropped = after.tvTransportState.equals("STOPPED", ignoreCase = true) ||
            after.tvTransportState.equals("NO_MEDIA_PRESENT", ignoreCase = true)

        // Only attempt at most ONE quiet auto-recovery if playback had ALREADY stabilized for > 45s
        // with >= 2 MB transferred. Never auto-loop if the TV failed on initial connect!
        val hadStablePlayback = elapsedSinceStart > 45_000L && after.proxyStats.bytesSent >= 2_000_000L
        if (dropped && hadStablePlayback && after.proxyStats.activeClients == 0) {
            consecutiveStoppedTicks++
            // Avoid auto-reconnecting if a VOD movie genuinely finished near the end
            val nearVodEnd = !after.castingIsLive && after.tvDurationMs > 30_000L &&
                after.tvPositionMs >= (after.tvDurationMs - 15_000L)
            if (consecutiveStoppedTicks >= 2 && !nearVodEnd && after.autoReconnectCount < 1) {
                consecutiveStoppedTicks = 0
                reconnectCurrent(context, isAuto = true)
            }
        } else {
            consecutiveStoppedTicks = 0
        }
    }

    suspend fun reconnectCurrent(context: Context, isAuto: Boolean = false): Boolean {
        val current = state.value
        val device = current.connectedDevice ?: return false
        val sourceUrl = current.rawSourceUrl.ifBlank { return false }
        if (reconnectingInFlight) return false
        reconnectingInFlight = true
        return try {
            if (isAuto) {
                mutableState.update { it.copy(autoReconnectCount = it.autoReconnectCount + 1) }
            }
            castMedia(
                context = context,
                device = device,
                rawUrl = sourceUrl,
                title = current.castingTitle,
                isLive = current.castingIsLive,
                startPositionMs = if (current.castingIsLive) 0L else current.tvPositionMs,
            )
        } finally {
            reconnectingInFlight = false
        }
    }

    suspend fun disconnect(context: Context? = null) {
        val device = state.value.connectedDevice
        lanProxy.clearStream()
        consecutiveStoppedTicks = 0
        reconnectingInFlight = false
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
        if (context != null) {
            CastRelayService.stop(context)
        }
        if (device != null) {
            withContext(Dispatchers.IO) {
                when (device.protocol) {
                    CastProtocol.CHROMECAST -> googleCastClient.disconnect(stopApp = true)
                    CastProtocol.DLNA -> runCatching {
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
                val xml = resp.body.string()
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
