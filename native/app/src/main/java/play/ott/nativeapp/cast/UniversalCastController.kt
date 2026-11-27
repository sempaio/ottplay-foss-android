package play.ott.nativeapp.cast

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.provider.Settings
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
    val castingIsLive: Boolean = false,
    val isPlayingOnTv: Boolean = false,
    val tvPositionMs: Long = 0L,
    val tvDurationMs: Long = 0L,
    val tvVolume: Int = 50,
    val busyAction: String? = null,
    val errorMessage: String? = null,
)

/**
 * Pure-Kotlin universal Smart TV caster (UPnP/DLNA SSDP discovery + AVTransport/RenderingControl SOAP)
 * plus Android system screen-mirroring & external casting app launchers.
 *
 * Works across Samsung Tizen, LG webOS, Android TV / Google TV, Roku, Fire TV, TV Box, Xbox and Kodi
 * on the same Wi-Fi network with zero third-party SDK dependencies.
 */
internal class UniversalCastController {
    private val mutableState = MutableStateFlow(CastSessionState())
    val state: StateFlow<CastSessionState> = mutableState.asStateFlow()
    private val lanProxy = LanStreamProxyServer()

    private val lanHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
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
                scanStatus = "Procurando Smart TVs no Wi-Fi...",
                errorMessage = null,
            )
        }
        val found = try {
            withContext(Dispatchers.IO) {
                discoverDlnaDevices(context.applicationContext)
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
                .sortedBy { it.friendlyName.lowercase() }
            prev.copy(
                scanning = false,
                devices = merged,
                scanStatus = if (merged.isEmpty()) {
                    "Nenhuma Smart TV DLNA respondeu no Wi-Fi. Use o Espelhamento de Tela abaixo ou confira se TV e celular estão no mesmo Wi-Fi."
                } else {
                    "${merged.size} ${if (merged.size == 1) "TV encontrada" else "TVs encontradas"} na rede"
                },
            )
        }
    }

    suspend fun castMedia(
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
        return try {
            val result = withContext(Dispatchers.IO) {
                val rewrittenUrl = DlnaProtocol.rewriteMediaUrlForCast(rawUrl, isLive)
                val phoneWifiIp = DlnaProtocol.localLanIpv4ForTarget(device.host)
                val castTarget = if (phoneWifiIp != null) {
                    lanProxy.prepareStream(rewrittenUrl, isLive, phoneWifiIp)
                } else {
                    val directUrl = resolvePlayableCastUrl(rawUrl, isLive)
                    ProxiedCastTarget(
                        localUrl = directUrl,
                        mimeType = if (isLive) "video/mp2t" else "video/mp4",
                        dlnaFeatures = "*",
                        contentLength = null,
                        isLive = isLive,
                    )
                }

                // Best-effort Stop clears stale state on Samsung / LG renderers
                runCatching {
                    postSoap(
                        device.avTransportControlUrl,
                        DlnaProtocol.AV_TRANSPORT_SERVICE,
                        "Stop",
                        DlnaProtocol.buildStopSoap(),
                    )
                }

                // Try Samsung/LG-standard DLNA profile first, then generic MIME, then empty metadata
                val attempts = listOf(
                    DlnaProtocol.buildSetAvTransportUriSoap(
                        mediaUrl = castTarget.localUrl,
                        title = title,
                        isLive = isLive,
                        includeMetadata = true,
                        mimeOverride = castTarget.mimeType,
                        dlnaFeatures = castTarget.dlnaFeatures,
                        contentLength = castTarget.contentLength,
                    ),
                    DlnaProtocol.buildSetAvTransportUriSoap(
                        mediaUrl = castTarget.localUrl,
                        title = title,
                        isLive = isLive,
                        includeMetadata = true,
                        mimeOverride = if (isLive) "video/mpeg" else castTarget.mimeType,
                        dlnaFeatures = "*",
                        contentLength = castTarget.contentLength,
                    ),
                    DlnaProtocol.buildSetAvTransportUriSoap(
                        mediaUrl = castTarget.localUrl,
                        title = title,
                        isLive = isLive,
                        includeMetadata = false,
                    ),
                )

                var setUriOk = false
                var lastFault: String? = null
                for (soapBody in attempts) {
                    val resp = postSoap(
                        device.avTransportControlUrl,
                        DlnaProtocol.AV_TRANSPORT_SERVICE,
                        "SetAVTransportURI",
                        soapBody,
                    )
                    if (resp.isSuccessful) {
                        setUriOk = true
                        break
                    }
                    lastFault = DlnaProtocol.extractSoapFaultSummary(resp.body) ?: "HTTP ${resp.code}"
                }

                if (!setUriOk) {
                    lanProxy.clearStream()
                    val detail = lastFault?.let { " ($it)" }.orEmpty()
                    return@withContext Triple(
                        false,
                        "A TV recusou o vídeo$detail. Tente Espelhar Tela ou Web Video Caster.",
                        castTarget.localUrl,
                    )
                }

                val playRes = postSoap(
                    device.avTransportControlUrl,
                    DlnaProtocol.AV_TRANSPORT_SERVICE,
                    "Play",
                    DlnaProtocol.buildPlaySoap(),
                )
                if (!playRes.isSuccessful) {
                    lanProxy.clearStream()
                    val detail = DlnaProtocol.extractSoapFaultSummary(playRes.body) ?: "HTTP ${playRes.code}"
                    return@withContext Triple(
                        false,
                        "A TV não iniciou a reprodução ($detail).",
                        castTarget.localUrl,
                    )
                }

                if (!isLive && startPositionMs > 5_000L) {
                    delay(700L)
                    runCatching {
                        postSoap(
                            device.avTransportControlUrl,
                            DlnaProtocol.AV_TRANSPORT_SERVICE,
                            "Seek",
                            DlnaProtocol.buildSeekSoap(startPositionMs),
                        )
                    }
                }

                val initialVolume = device.renderingControlUrl?.let { rcUrl ->
                    runCatching {
                        val volResp = postSoap(
                            rcUrl,
                            DlnaProtocol.RENDERING_CONTROL_SERVICE,
                            "GetVolume",
                            DlnaProtocol.buildGetVolumeSoap(),
                        )
                        DlnaProtocol.extractTagText(volResp.body, "CurrentVolume")?.toIntOrNull()
                    }.getOrNull()
                }

                Triple(true, initialVolume?.toString().orEmpty(), castTarget.localUrl)
            }

            if (result.first) {
                val vol = result.second.toIntOrNull() ?: state.value.tvVolume
                mutableState.update {
                    it.copy(
                        connectedDevice = device,
                        castingTitle = title,
                        castingUrl = result.third,
                        castingIsLive = isLive,
                        isPlayingOnTv = true,
                        tvPositionMs = startPositionMs.coerceAtLeast(0L),
                        tvDurationMs = 0L,
                        tvVolume = vol.coerceIn(0, 100),
                        busyAction = null,
                        errorMessage = null,
                    )
                }
                true
            } else {
                mutableState.update {
                    it.copy(
                        busyAction = null,
                        errorMessage = result.second,
                    )
                }
                false
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            lanProxy.clearStream()
            mutableState.update {
                it.copy(
                    busyAction = null,
                    errorMessage = "Falha ao enviar para ${device.friendlyName}: ${e.message ?: "verifique o Wi-Fi"}",
                )
            }
            false
        }
    }

    suspend fun togglePlayPause() {
        val current = state.value
        val device = current.connectedDevice ?: return
        val nextPlaying = !current.isPlayingOnTv
        val action = if (nextPlaying) "Play" else "Pause"
        val soap = if (nextPlaying) DlnaProtocol.buildPlaySoap() else DlnaProtocol.buildPauseSoap()
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                postSoap(device.avTransportControlUrl, DlnaProtocol.AV_TRANSPORT_SERVICE, action, soap).isSuccessful
            }.getOrDefault(false)
        }
        if (ok) {
            mutableState.update { it.copy(isPlayingOnTv = nextPlaying, errorMessage = null) }
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
            runCatching {
                postSoap(
                    device.avTransportControlUrl,
                    DlnaProtocol.AV_TRANSPORT_SERVICE,
                    "Seek",
                    DlnaProtocol.buildSeekSoap(clamped),
                ).isSuccessful
            }.getOrDefault(false)
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
        val rcUrl = device.renderingControlUrl ?: return
        val nextVol = (current.tvVolume + delta).coerceIn(0, 100)
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                postSoap(
                    rcUrl,
                    DlnaProtocol.RENDERING_CONTROL_SERVICE,
                    "SetVolume",
                    DlnaProtocol.buildSetVolumeSoap(nextVol),
                ).isSuccessful
            }.getOrDefault(false)
        }
        if (ok) {
            mutableState.update { it.copy(tvVolume = nextVol, errorMessage = null) }
        }
    }

    suspend fun pollPosition() {
        val current = state.value
        val device = current.connectedDevice ?: return
        if (current.castingIsLive) return
        val resp = withContext(Dispatchers.IO) {
            runCatching {
                postSoap(
                    device.avTransportControlUrl,
                    DlnaProtocol.AV_TRANSPORT_SERVICE,
                    "GetPositionInfo",
                    DlnaProtocol.buildGetPositionInfoSoap(),
                )
            }.getOrNull()
        } ?: return
        if (!resp.isSuccessful) return
        val relMs = DlnaProtocol.parseUpnpTimeMs(DlnaProtocol.extractTagText(resp.body, "RelTime"))
        val durMs = DlnaProtocol.parseUpnpTimeMs(DlnaProtocol.extractTagText(resp.body, "TrackDuration"))
        mutableState.update { prev ->
            prev.copy(
                tvPositionMs = relMs ?: prev.tvPositionMs,
                tvDurationMs = durMs?.takeIf { it > 0L } ?: prev.tvDurationMs,
            )
        }
    }

    suspend fun disconnect() {
        val device = state.value.connectedDevice
        lanProxy.clearStream()
        mutableState.update {
            it.copy(
                connectedDevice = null,
                castingTitle = "",
                castingUrl = "",
                isPlayingOnTv = false,
                tvPositionMs = 0L,
                tvDurationMs = 0L,
                busyAction = null,
                errorMessage = null,
            )
        }
        if (device != null) {
            withContext(Dispatchers.IO) {
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

    fun clearError() {
        mutableState.update { it.copy(errorMessage = null) }
    }

    fun shutdown() {
        lanProxy.shutdown()
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

                // Send 2 discovery bursts spaced 350ms apart to overcome Wi-Fi UDP packet loss
                repeat(2) { burst ->
                    for (query in queries) {
                        val bytes = query.toByteArray(Charsets.UTF_8)
                        val packet = DatagramPacket(bytes, bytes.size, multicastGroup, DlnaProtocol.SSDP_PORT)
                        runCatching { socket.send(packet) }
                    }
                    val deadline = System.currentTimeMillis() + if (burst == 0) 900L else 1500L
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
                async(Dispatchers.IO) {
                    fetchDeviceDescription(loc)
                }
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

    /**
     * Prepares the stream URL for Smart TVs:
     * - LIVE: rewrites M81 `/live/...` to `/live-hls/...m3u8` so M81 renews short-lived tokens automatically.
     * - VOD: follows up to 3 HTTP 30x redirects so DLNA TVs that choke on 307/TLS receive the direct CDN file URL.
     */
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
        fun openSystemScreenMirroring(context: Context): Boolean {
            val intents = listOf(
                Intent(Settings.ACTION_CAST_SETTINGS),
                Intent("android.settings.WIFI_DISPLAY_SETTINGS"),
            )
            for (intent in intents) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (runCatching { intent.resolveActivity(context.packageManager) != null }.getOrDefault(false)) {
                    if (runCatching { context.startActivity(intent); true }.getOrDefault(false)) {
                        return true
                    }
                }
            }
            Toast.makeText(
                context,
                "Abra 'Transmitir' ou 'Smart View' na barra de atalhos do Android para espelhar a tela.",
                Toast.LENGTH_LONG,
            ).show()
            return false
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
                    "Instale Web Video Caster ou VLC para enviar por app externo, ou use as Smart TVs acima.",
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
