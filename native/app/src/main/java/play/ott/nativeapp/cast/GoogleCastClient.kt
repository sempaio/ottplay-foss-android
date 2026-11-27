package play.ott.nativeapp.cast

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import kotlinx.coroutines.delay
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * Pure-Kotlin LAN Google Cast v2 client (`_googlecast._tcp.` mDNS discovery + TLS port 8009
 * Default Media Receiver `CC1AD845`). Streams directly to Chromecast / Google TV / Android TV
 * while letting the user use their phone freely or lock the screen.
 */
internal class GoogleCastClient {
    private val connected = AtomicBoolean(false)
    private val requestSeq = AtomicInteger(1)
    private val writeLock = Any()
    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r, "StarlinkGoogleCast").apply { isDaemon = true }
    }

    @Volatile private var socket: SSLSocket? = null
    @Volatile private var output: OutputStream? = null
    @Volatile private var connectedHostPort: String? = null
    @Volatile private var transportId: String? = null
    @Volatile private var sessionId: String? = null
    @Volatile private var mediaSessionId: Int = -1
    @Volatile private var volume01: Double = 0.65
    @Volatile private var lastMediaStatus: CastMediaStatus? = null

    @Volatile private var pendingLaunch: CompletableFuture<CastReceiverAppStatus>? = null
    @Volatile private var pendingLoad: CompletableFuture<Result<CastMediaStatus>>? = null

    fun connectAndLoad(
        device: CastDevice,
        mediaUrl: String,
        title: String,
        contentType: String,
        isLive: Boolean,
        startPositionMs: Long,
    ): Result<Int> {
        require(DlnaProtocol.isPrivateLanHost(device.host)) { "Blocked non-LAN Cast target" }
        val targetKey = "${device.host}:${device.port}"

        // Fast path: reuse already-running CC1AD845 session on the SAME Chromecast
        // (avoids STOP->LAUNCH race condition so channel/movie switching works every single time!)
        val activeTid = transportId
        if (connected.get() && connectedHostPort == targetKey && !activeTid.isNullOrBlank()) {
            val reused = sendLoadWithRetry(activeTid, mediaUrl, title, contentType, isLive, startPositionMs)
            if (reused != null && reused.isSuccess) {
                return Result.success((volume01 * 100.0).toInt().coerceIn(0, 100))
            }
            // If receiver closed or errored, fall through to fresh connect below
        }

        disconnect(stopApp = false)

        return try {
            // Google Cast devices on private Wi-Fi LANs use device-issued Cast certificates on port 8009.
            // Connection is strictly gated to private RFC1918/link-local LAN IPv4 addresses above.
            val trustManager = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) = Unit
                override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) = Unit
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val sslCtx = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf(trustManager), SecureRandom())
            }
            val rawSock = sslCtx.socketFactory.createSocket() as SSLSocket
            rawSock.connect(InetSocketAddress(device.host, device.port), 5_000)
            rawSock.soTimeout = 0
            rawSock.tcpNoDelay = true
            rawSock.startHandshake()

            socket = rawSock
            output = rawSock.outputStream
            connectedHostPort = targetKey
            connected.set(true)

            startReaderLoop(rawSock)
            startHeartbeatLoop()

            // 1. Connect to Chromecast receiver control channel
            sendMessage(GoogleCastProtocol.RECEIVER_ID, GoogleCastProtocol.NS_CONNECTION, GoogleCastProtocol.buildConnectJson())

            // 2. Launch Default Media Receiver (CC1AD845)
            val launchFuture = CompletableFuture<CastReceiverAppStatus>()
            pendingLaunch = launchFuture
            sendMessage(
                GoogleCastProtocol.RECEIVER_ID,
                GoogleCastProtocol.NS_RECEIVER,
                GoogleCastProtocol.buildLaunchJson(requestSeq.getAndIncrement()),
            )

            val appStatus = runCatching { launchFuture.get(10, TimeUnit.SECONDS) }.getOrNull()
            pendingLaunch = null
            if (appStatus == null) {
                disconnect(stopApp = false)
                return Result.failure(IllegalStateException("O Chromecast não abriu o receptor de mídia."))
            }

            transportId = appStatus.transportId
            sessionId = appStatus.sessionId
            appStatus.volumeLevel?.let { volume01 = it.coerceIn(0.0, 1.0) }

            // 3. Connect to receiver media transport & wait for CC1AD845 JS MediaManager boot
            sendMessage(appStatus.transportId, GoogleCastProtocol.NS_CONNECTION, GoogleCastProtocol.buildConnectJson())
            Thread.sleep(900L)
            sendMessage(appStatus.transportId, GoogleCastProtocol.NS_MEDIA, GoogleCastProtocol.buildGetStatusJson(requestSeq.getAndIncrement()))
            Thread.sleep(250L)

            // 4. Load media stream (retry once if CC1AD845 was still cold-booting)
            val loadOutcome = sendLoadWithRetry(appStatus.transportId, mediaUrl, title, contentType, isLive, startPositionMs)
            if (loadOutcome == null) {
                disconnect(stopApp = true)
                return Result.failure(IllegalStateException("Chromecast abriu a tela mas não respondeu ao carregamento do vídeo."))
            }
            if (loadOutcome.isFailure) {
                disconnect(stopApp = true)
                return Result.failure(loadOutcome.exceptionOrNull() ?: IllegalStateException("Falha ao carregar no Chromecast."))
            }
            Result.success((volume01 * 100.0).toInt().coerceIn(0, 100))
        } catch (e: Exception) {
            disconnect(stopApp = false)
            Result.failure(e)
        }
    }

    private fun sendLoadWithRetry(
        tid: String,
        mediaUrl: String,
        title: String,
        contentType: String,
        isLive: Boolean,
        startPositionMs: Long,
    ): Result<CastMediaStatus>? {
        var outcome: Result<CastMediaStatus>? = null
        for (attempt in 1..2) {
            val loadFuture = CompletableFuture<Result<CastMediaStatus>>()
            pendingLoad = loadFuture
            val sent = sendMessage(
                tid,
                GoogleCastProtocol.NS_MEDIA,
                GoogleCastProtocol.buildLoadMediaJson(
                    requestId = requestSeq.getAndIncrement(),
                    mediaUrl = mediaUrl,
                    title = title,
                    contentType = contentType,
                    isLive = isLive,
                    startPositionSec = startPositionMs / 1000.0,
                ),
            )
            if (!sent) {
                pendingLoad = null
                return null
            }
            val waitSec = if (attempt == 1) 7L else 10L
            outcome = runCatching { loadFuture.get(waitSec, TimeUnit.SECONDS) }.getOrNull()
            pendingLoad = null
            if (outcome != null) return outcome
            runCatching { Thread.sleep(700L) }
        }
        return outcome
    }

    fun play(): Boolean = sendMediaSimple("PLAY")
    fun pause(): Boolean = sendMediaSimple("PAUSE")

    fun seek(positionMs: Long): Boolean {
        val tid = transportId ?: return false
        val msid = mediaSessionId.takeIf { it >= 0 } ?: return false
        return sendMessage(
            tid,
            GoogleCastProtocol.NS_MEDIA,
            GoogleCastProtocol.buildMediaCommandJson("SEEK", requestSeq.getAndIncrement(), msid, positionMs / 1000.0),
        )
    }

    fun setVolume(percent: Int): Boolean {
        val level = (percent.coerceIn(0, 100) / 100.0)
        volume01 = level
        return sendMessage(
            GoogleCastProtocol.RECEIVER_ID,
            GoogleCastProtocol.NS_RECEIVER,
            GoogleCastProtocol.buildSetVolumeJson(requestSeq.getAndIncrement(), level),
        )
    }

    fun requestStatus(): CastMediaStatus? {
        val tid = transportId ?: return lastMediaStatus
        sendMessage(tid, GoogleCastProtocol.NS_MEDIA, GoogleCastProtocol.buildGetStatusJson(requestSeq.getAndIncrement()))
        return lastMediaStatus
    }

    fun disconnect(stopApp: Boolean = true) {
        if (connected.get()) {
            if (stopApp) {
                val sid = sessionId
                if (!sid.isNullOrBlank()) {
                    runCatching {
                        sendMessage(
                            GoogleCastProtocol.RECEIVER_ID,
                            GoogleCastProtocol.NS_RECEIVER,
                            GoogleCastProtocol.buildStopSessionJson(requestSeq.getAndIncrement(), sid),
                        )
                    }
                }
            } else {
                // Clean Cast v2 sender detach: close virtual channels WITHOUT stopping CC1AD845
                // so Chromecast keeps playing autonomously even after the phone leaves Wi-Fi.
                val tid = transportId
                if (!tid.isNullOrBlank()) {
                    runCatching {
                        sendMessage(tid, GoogleCastProtocol.NS_CONNECTION, GoogleCastProtocol.buildCloseJson())
                    }
                }
                runCatching {
                    sendMessage(
                        GoogleCastProtocol.RECEIVER_ID,
                        GoogleCastProtocol.NS_CONNECTION,
                        GoogleCastProtocol.buildCloseJson(),
                    )
                }
            }
        }
        connected.set(false)
        connectedHostPort = null
        transportId = null
        sessionId = null
        mediaSessionId = -1
        lastMediaStatus = null
        pendingLaunch?.cancel(true)
        pendingLoad?.cancel(true)
        runCatching { socket?.close() }
        socket = null
        output = null
    }

    private fun sendMediaSimple(cmd: String): Boolean {
        val tid = transportId ?: return false
        val msid = mediaSessionId.takeIf { it >= 0 } ?: return false
        return sendMessage(
            tid,
            GoogleCastProtocol.NS_MEDIA,
            GoogleCastProtocol.buildMediaCommandJson(cmd, requestSeq.getAndIncrement(), msid),
        )
    }

    private fun sendMessage(destinationId: String, namespace: String, payloadJson: String): Boolean {
        if (!connected.get()) return false
        val out = output ?: return false
        val bytes = GoogleCastProtocol.encodeFramed(
            CastWireMessage(
                sourceId = GoogleCastProtocol.SENDER_ID,
                destinationId = destinationId,
                namespace = namespace,
                payloadUtf8 = payloadJson,
            ),
        )
        return try {
            synchronized(writeLock) {
                out.write(bytes)
                out.flush()
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun startReaderLoop(sslSock: SSLSocket) {
        executor.execute {
            try {
                val input = DataInputStream(sslSock.inputStream)
                while (connected.get() && !sslSock.isClosed) {
                    val length = input.readInt()
                    if (length <= 0 || length > 1_048_576) break
                    val frame = ByteArray(length)
                    input.readFully(frame)
                    val msg = GoogleCastProtocol.decodeProtobuf(frame) ?: continue
                    handleIncoming(msg)
                }
            } catch (_: Exception) {
                // Socket closed or disconnected
            } finally {
                connected.set(false)
            }
        }
    }

    private fun startHeartbeatLoop() {
        executor.execute {
            while (connected.get()) {
                try {
                    Thread.sleep(5_000L)
                    if (connected.get()) {
                        sendMessage(
                            GoogleCastProtocol.RECEIVER_ID,
                            GoogleCastProtocol.NS_HEARTBEAT,
                            GoogleCastProtocol.buildPingJson(),
                        )
                    }
                } catch (_: InterruptedException) {
                    break
                } catch (_: Exception) {
                    break
                }
            }
        }
    }

    private fun handleIncoming(msg: CastWireMessage) {
        when (msg.namespace) {
            GoogleCastProtocol.NS_HEARTBEAT -> {
                if (GoogleCastProtocol.parseMessageType(msg.payloadUtf8) == "PING") {
                    sendMessage(msg.sourceId.ifBlank { GoogleCastProtocol.RECEIVER_ID }, GoogleCastProtocol.NS_HEARTBEAT, GoogleCastProtocol.buildPongJson())
                }
            }
            GoogleCastProtocol.NS_RECEIVER -> {
                GoogleCastProtocol.parseReceiverStatus(msg.payloadUtf8)?.let { status ->
                    transportId = status.transportId
                    sessionId = status.sessionId
                    status.volumeLevel?.let { volume01 = it.coerceIn(0.0, 1.0) }
                    pendingLaunch?.complete(status)
                }
            }
            GoogleCastProtocol.NS_MEDIA -> {
                val type = GoogleCastProtocol.parseMessageType(msg.payloadUtf8)
                if (type == "LOAD_FAILED" || type == "INVALID_REQUEST") {
                    val detail = GoogleCastProtocol.parseLoadFailureDetail(msg.payloadUtf8)
                    pendingLoad?.complete(Result.failure(IllegalStateException("Chromecast recusou a mídia ($detail).")))
                    return
                }
                GoogleCastProtocol.parseMediaStatus(msg.payloadUtf8)?.let { media ->
                    mediaSessionId = media.mediaSessionId
                    lastMediaStatus = media
                    pendingLoad?.complete(Result.success(media))
                }
            }
        }
    }

    companion object {
        private const val SERVICE_TYPE = "_googlecast._tcp."

        /** Discovers Chromecast, Google TV and Android TV receivers on the local Wi-Fi network via mDNS. */
        suspend fun discoverDevices(context: Context, scanWindowMs: Long = 2_400L): List<CastDevice> {
            val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
                ?: return emptyList()
            val found = ConcurrentHashMap<String, CastDevice>()
            val resolveGate = Semaphore(1)

            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(regType: String?) = Unit
                override fun onDiscoveryStopped(serviceType: String?) = Unit
                override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                    runCatching { nsd.stopServiceDiscovery(this) }
                }
                override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) = Unit
                override fun onServiceLost(serviceInfo: NsdServiceInfo?) = Unit

                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    @Suppress("DEPRECATION")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        runCatching {
                            nsd.registerServiceInfoCallback(
                                serviceInfo,
                                { it.run() },
                                object : NsdManager.ServiceInfoCallback {
                                    override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) = Unit
                                    override fun onServiceLost() = Unit
                                    override fun onServiceInfoCallbackUnregistered() = Unit
                                    override fun onServiceUpdated(resolved: NsdServiceInfo) {
                                        recordResolved(resolved, found)
                                        runCatching { nsd.unregisterServiceInfoCallback(this) }
                                    }
                                },
                            )
                        }
                    } else {
                        Thread {
                            if (!resolveGate.tryAcquire(2, TimeUnit.SECONDS)) return@Thread
                            runCatching {
                                nsd.resolveService(
                                    serviceInfo,
                                    object : NsdManager.ResolveListener {
                                        override fun onResolveFailed(info: NsdServiceInfo?, errorCode: Int) {
                                            resolveGate.release()
                                        }
                                        override fun onServiceResolved(resolved: NsdServiceInfo) {
                                            recordResolved(resolved, found)
                                            resolveGate.release()
                                        }
                                    },
                                )
                            }.onFailure { resolveGate.release() }
                        }.start()
                    }
                }
            }

            return try {
                nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
                delay(scanWindowMs)
                found.values.toList()
            } catch (_: Exception) {
                found.values.toList()
            } finally {
                runCatching { nsd.stopServiceDiscovery(listener) }
            }
        }

        private fun recordResolved(resolved: NsdServiceInfo, target: ConcurrentHashMap<String, CastDevice>) {
            @Suppress("DEPRECATION")
            val host = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                resolved.hostAddresses.firstOrNull { addr ->
                    DlnaProtocol.isPrivateLanHost(addr.hostAddress.orEmpty())
                }?.hostAddress
            } else {
                resolved.host?.hostAddress
            } ?: return
            if (!DlnaProtocol.isPrivateLanHost(host)) return

            val attrs = resolved.attributes.orEmpty()
            val friendly = attrs["fn"]?.toString(Charsets.UTF_8)?.trim()?.takeIf { it.isNotBlank() }
                ?: resolved.serviceName?.trim()?.takeIf { it.isNotBlank() }
                ?: "Chromecast ($host)"
            val model = attrs["md"]?.toString(Charsets.UTF_8)?.trim().orEmpty().ifBlank { "Chromecast / Google TV" }
            val castId = attrs["id"]?.toString(Charsets.UTF_8)?.trim()?.takeIf { it.isNotBlank() } ?: "$host:${resolved.port}"
            val port = resolved.port.takeIf { it in 1..65535 } ?: 8009

            val device = CastDevice(
                id = "cast:$castId",
                friendlyName = friendly,
                manufacturer = "Google Cast",
                modelName = model,
                host = host,
                locationUrl = "cast://$host:$port",
                avTransportControlUrl = "cast://$host:$port/$castId",
                renderingControlUrl = "cast://$host:$port/volume",
                protocol = CastProtocol.CHROMECAST,
                port = port,
            )
            target[device.avTransportControlUrl] = device
        }
    }
}
