package play.ott.nativeapp.cast

import java.net.Inet4Address
import java.net.InetAddress
import java.net.URI
import java.util.Locale

internal enum class CastProtocol {
    DLNA,
    CHROMECAST,
}

internal enum class DlnaVendorProfile {
    SAMSUNG,
    LG,
    GENERIC,
}

internal data class CastDevice(
    val id: String,
    val friendlyName: String,
    val manufacturer: String,
    val modelName: String,
    val host: String,
    val locationUrl: String,
    val avTransportControlUrl: String,
    val renderingControlUrl: String?,
    val protocol: CastProtocol = CastProtocol.DLNA,
    val port: Int = 8009,
    val connectionManagerControlUrl: String? = null,
)

/**
 * Pure, dependency-free UPnP / DLNA protocol codec (SSDP M-SEARCH, device XML parsing,
 * DIDL-Lite metadata, AVTransport:1, RenderingControl:1 & ConnectionManager:1 SOAP 1.1 envelopes,
 * and M81 stream URL normalization for Smart TV renderers).
 */
internal object DlnaProtocol {
    const val SSDP_ADDRESS = "239.255.255.250"
    const val SSDP_PORT = 1900
    const val AV_TRANSPORT_SERVICE = "urn:schemas-upnp-org:service:AVTransport:1"
    const val RENDERING_CONTROL_SERVICE = "urn:schemas-upnp-org:service:RenderingControl:1"
    const val CONNECTION_MANAGER_SERVICE = "urn:schemas-upnp-org:service:ConnectionManager:1"
    const val MEDIA_RENDERER_DEVICE = "urn:schemas-upnp-org:device:MediaRenderer:1"

    private val LIVE_PATH_REGEX = Regex("^/live/([^/]+)/([^/]+)/(\\d+)(?:\\.[a-zA-Z0-9]+)?$")
    private val SERVICE_BLOCK_REGEX = Regex("<service(?:\\s[^>]*)?>([\\s\\S]*?)</service>", RegexOption.IGNORE_CASE)

    fun detectVendorProfile(device: CastDevice): DlnaVendorProfile {
        val sig = "${device.manufacturer} ${device.modelName} ${device.friendlyName} ${device.locationUrl}"
            .lowercase(Locale.ROOT)
        return when {
            sig.contains("webos") || sig.contains("lg electronics") || sig.contains("lge") ||
                Regex("\\blg\\b").containsMatchIn(sig) || sig.contains("netcast") -> DlnaVendorProfile.LG
            sig.contains("samsung") || sig.contains("tizen") -> DlnaVendorProfile.SAMSUNG
            else -> DlnaVendorProfile.GENERIC
        }
    }

    /** Removes credentials from URLs before logging into the user-visible diagnostic panel. */
    fun redactUrlForDiagnostics(url: String): String =
        url.replace(Regex("/(live|live-hls|movie|series)/[^/]+/[^/]+/"), "/$1/***/***/")
            .replace(Regex("([?&](?:token|password|pass|username|user|key)=)[^&]+", RegexOption.IGNORE_CASE), "$1***")

    fun buildMSearchPacket(searchTarget: String, mxSeconds: Int = 2): String {
        val mx = mxSeconds.coerceIn(1, 5)
        return buildString {
            append("M-SEARCH * HTTP/1.1\r\n")
            append("HOST: $SSDP_ADDRESS:$SSDP_PORT\r\n")
            append("MAN: \"ssdp:discover\"\r\n")
            append("MX: $mx\r\n")
            append("ST: $searchTarget\r\n")
            append("USER-AGENT: Android/UPnP/1.1 STARLINK/1.0\r\n")
            append("\r\n")
        }
    }

    fun parseSsdpLocation(rawPacket: String): String? {
        for (line in rawPacket.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("location:", ignoreCase = true)) {
                val url = trimmed.substringAfter(':').trim()
                if (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)) {
                    return url
                }
            }
        }
        return null
    }

    /** Restrict UPnP/DLNA HTTP requests strictly to private RFC1918 / link-local LAN IPv4 addresses. */
    fun isPrivateLanHost(host: String): Boolean {
        val clean = host.trim().removePrefix("[").removeSuffix("]")
        if (clean.isBlank()) return false
        val parts = clean.split('.')
        if (parts.size != 4) return false
        val octets = parts.map { it.toIntOrNull() ?: return false }
        if (octets.any { it !in 0..255 }) return false
        val (a, b, _, _) = octets
        return when {
            a == 10 -> true
            a == 172 && b in 16..31 -> true
            a == 192 && b == 168 -> true
            a == 169 && b == 254 -> true
            else -> false
        }
    }

    fun isSafeLanUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return false
        if (scheme != "http" && scheme != "https") return false
        val host = uri.host ?: return false
        return isPrivateLanHost(host)
    }

    fun parseDeviceDescriptionXml(xml: String, locationUrl: String): CastDevice? {
        val locationUri = runCatching { URI(locationUrl) }.getOrNull() ?: return null
        val host = locationUri.host ?: return null
        if (!isPrivateLanHost(host)) return null

        val urlBase = extractTagText(xml, "URLBase")?.takeIf { it.isNotBlank() } ?: locationUrl
        var avTransportUrl: String? = null
        var renderingControlUrl: String? = null
        var connectionManagerUrl: String? = null

        for (match in SERVICE_BLOCK_REGEX.findAll(xml)) {
            val block = match.groupValues[1]
            val serviceType = extractTagText(block, "serviceType").orEmpty()
            val controlUrl = extractTagText(block, "controlURL").orEmpty()
            if (controlUrl.isBlank()) continue
            val resolved = resolveUrl(urlBase, controlUrl) ?: continue
            if (!isSafeLanUrl(resolved)) continue
            when {
                serviceType.contains("AVTransport", ignoreCase = true) && avTransportUrl == null -> {
                    avTransportUrl = resolved
                }
                serviceType.contains("RenderingControl", ignoreCase = true) && renderingControlUrl == null -> {
                    renderingControlUrl = resolved
                }
                serviceType.contains("ConnectionManager", ignoreCase = true) && connectionManagerUrl == null -> {
                    connectionManagerUrl = resolved
                }
            }
        }
        val finalAvUrl = avTransportUrl ?: return null

        val friendlyName = extractTagText(xml, "friendlyName")
            ?.let(::unescapeXml)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: "Smart TV ($host)"
        val manufacturer = extractTagText(xml, "manufacturer")?.let(::unescapeXml)?.trim().orEmpty()
        val modelName = extractTagText(xml, "modelName")?.let(::unescapeXml)?.trim().orEmpty()
        val udn = extractTagText(xml, "UDN")?.trim().takeUnless { it.isNullOrBlank() } ?: locationUrl

        return CastDevice(
            id = udn,
            friendlyName = friendlyName,
            manufacturer = manufacturer,
            modelName = modelName,
            host = host,
            locationUrl = locationUrl,
            avTransportControlUrl = finalAvUrl,
            renderingControlUrl = renderingControlUrl,
            connectionManagerControlUrl = connectionManagerUrl,
        )
    }

    internal fun resolveUrl(baseUrl: String, relativeOrAbsolute: String): String? {
        val trimmed = relativeOrAbsolute.trim()
        if (trimmed.isBlank()) return null
        if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            return trimmed
        }
        val base = runCatching { URI(baseUrl) }.getOrNull() ?: return null
        val scheme = base.scheme ?: return null
        val host = base.host ?: return null
        val portSuffix = if (base.port > 0) ":${base.port}" else ""
        val path = if (trimmed.startsWith("/")) trimmed else "/$trimmed"
        return "$scheme://$host$portSuffix$path"
    }

    /**
     * Rewrites M81 live URLs (`/live/{u}/{p}/{id}.m3u8` or `.ts`) to `/live-hls/{u}/{p}/{id}.m3u8`
     * so M81 renews short-lived upstream tokens automatically for Smart TV DLNA renderers.
     */
    fun rewriteMediaUrlForCast(rawUrl: String, isLive: Boolean): String {
        if (!isLive) return rawUrl
        val uri = runCatching { URI(rawUrl) }.getOrNull() ?: return rawUrl
        val path = uri.rawPath ?: return rawUrl
        val match = LIVE_PATH_REGEX.matchEntire(path) ?: return rawUrl
        val user = match.groupValues[1]
        val pass = match.groupValues[2]
        val streamId = match.groupValues[3]
        val scheme = uri.scheme ?: return rawUrl
        val host = uri.host ?: return rawUrl
        val portSuffix = if (uri.port > 0) ":${uri.port}" else ""
        val querySuffix = uri.rawQuery?.takeIf { it.isNotBlank() }?.let { "?$it" }.orEmpty()
        return "$scheme://$host$portSuffix/live-hls/$user/$pass/$streamId.m3u8$querySuffix"
    }

    fun inferProtocolInfo(
        mediaUrl: String,
        isLive: Boolean,
        mimeOverride: String? = null,
        dlnaFeatures: String? = null,
    ): String {
        val path = runCatching { URI(mediaUrl).rawPath }.getOrNull().orEmpty().lowercase(Locale.ROOT)
        val mime = mimeOverride?.takeIf { it.isNotBlank() } ?: when {
            path.endsWith(".m3u8") -> "application/vnd.apple.mpegurl"
            path.endsWith(".ts") -> "video/mp2t"
            path.endsWith(".mkv") -> "video/x-matroska"
            path.endsWith(".webm") -> "video/webm"
            path.endsWith(".mp4") -> "video/mp4"
            isLive -> "video/mp2t"
            else -> "video/mp4"
        }
        val features = dlnaFeatures?.takeIf { it.isNotBlank() } ?: "*"
        return "http-get:*:$mime:$features"
    }

    fun buildDidlLiteMetadata(
        title: String,
        mediaUrl: String,
        isLive: Boolean,
        mimeOverride: String? = null,
        dlnaFeatures: String? = null,
        contentLength: Long? = null,
    ): String {
        val safeTitle = escapeXml(title.ifBlank { "STARLINK TV" })
        val safeUrl = escapeXml(mediaUrl)
        val protocolInfo = escapeXml(inferProtocolInfo(mediaUrl, isLive, mimeOverride, dlnaFeatures))
        val sizeAttr = if (!isLive && contentLength != null && contentLength > 0L) " size=\"$contentLength\"" else ""
        // Always use object.item.videoItem: Samsung Tizen / LG webOS DMRs reject videoBroadcast with UPnP 714.
        return "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
            "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\" " +
            "xmlns:dlna=\"urn:schemas-dlna-org:metadata-1-0/\">" +
            "<item id=\"0\" parentID=\"-1\" restricted=\"1\">" +
            "<dc:title>$safeTitle</dc:title>" +
            "<upnp:class>object.item.videoItem</upnp:class>" +
            "<res protocolInfo=\"$protocolInfo\"$sizeAttr>$safeUrl</res>" +
            "</item></DIDL-Lite>"
    }

    fun buildSoapEnvelope(serviceType: String, action: String, bodyXml: String): String =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body>" +
            "<u:$action xmlns:u=\"$serviceType\">$bodyXml</u:$action>" +
            "</s:Body></s:Envelope>"

    fun buildSetAvTransportUriSoap(
        mediaUrl: String,
        title: String,
        isLive: Boolean,
        includeMetadata: Boolean = true,
        mimeOverride: String? = null,
        dlnaFeatures: String? = null,
        contentLength: Long? = null,
    ): String {
        val metadata = if (includeMetadata) {
            escapeXml(buildDidlLiteMetadata(title, mediaUrl, isLive, mimeOverride, dlnaFeatures, contentLength))
        } else ""
        val body = "<InstanceID>0</InstanceID>" +
            "<CurrentURI>${escapeXml(mediaUrl)}</CurrentURI>" +
            "<CurrentURIMetaData>$metadata</CurrentURIMetaData>"
        return buildSoapEnvelope(AV_TRANSPORT_SERVICE, "SetAVTransportURI", body)
    }

    fun extractSoapFaultSummary(xml: String): String? {
        val code = extractTagText(xml, "errorCode")?.takeIf { it.isNotBlank() }
        val desc = extractTagText(xml, "errorDescription")?.takeIf { it.isNotBlank() }
        return when {
            code != null && desc != null -> "UPnP $code ($desc)"
            code != null -> "UPnP $code"
            desc != null -> desc
            else -> null
        }
    }

    fun buildPlaySoap(): String = buildSoapEnvelope(
        AV_TRANSPORT_SERVICE,
        "Play",
        "<InstanceID>0</InstanceID><Speed>1</Speed>",
    )

    fun buildPauseSoap(): String = buildSoapEnvelope(
        AV_TRANSPORT_SERVICE,
        "Pause",
        "<InstanceID>0</InstanceID>",
    )

    fun buildStopSoap(): String = buildSoapEnvelope(
        AV_TRANSPORT_SERVICE,
        "Stop",
        "<InstanceID>0</InstanceID>",
    )

    fun buildSeekSoap(positionMs: Long): String = buildSoapEnvelope(
        AV_TRANSPORT_SERVICE,
        "Seek",
        "<InstanceID>0</InstanceID><Unit>REL_TIME</Unit><Target>${formatUpnpTime(positionMs)}</Target>",
    )

    fun buildGetPositionInfoSoap(): String = buildSoapEnvelope(
        AV_TRANSPORT_SERVICE,
        "GetPositionInfo",
        "<InstanceID>0</InstanceID>",
    )

    fun buildGetTransportInfoSoap(): String = buildSoapEnvelope(
        AV_TRANSPORT_SERVICE,
        "GetTransportInfo",
        "<InstanceID>0</InstanceID>",
    )

    fun buildSetVolumeSoap(volumePercent: Int): String = buildSoapEnvelope(
        RENDERING_CONTROL_SERVICE,
        "SetVolume",
        "<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredVolume>${volumePercent.coerceIn(0, 100)}</DesiredVolume>",
    )

    fun buildGetVolumeSoap(): String = buildSoapEnvelope(
        RENDERING_CONTROL_SERVICE,
        "GetVolume",
        "<InstanceID>0</InstanceID><Channel>Master</Channel>",
    )

    fun buildGetProtocolInfoSoap(): String = buildSoapEnvelope(
        CONNECTION_MANAGER_SERVICE,
        "GetProtocolInfo",
        "",
    )

    fun formatUpnpTime(ms: Long): String {
        val totalSeconds = (ms.coerceAtLeast(0L) / 1000L)
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return "%02d:%02d:%02d".format(Locale.US, hours, minutes, seconds)
    }

    fun parseUpnpTimeMs(raw: String?): Long? {
        val text = raw?.trim()?.substringBefore('.') ?: return null
        val parts = text.split(':')
        if (parts.size != 3) return null
        val h = parts[0].toLongOrNull() ?: return null
        val m = parts[1].toLongOrNull() ?: return null
        val s = parts[2].toLongOrNull() ?: return null
        if (h < 0L || m !in 0L..59L || s !in 0L..59L) return null
        return ((h * 3600L) + (m * 60L) + s) * 1000L
    }

    fun extractTagText(xml: String, tagName: String): String? {
        val escaped = Regex.escape(tagName)
        val regex = Regex("<(?:[a-zA-Z0-9_.-]+:)?$escaped(?:\\s[^>]*)?>([\\s\\S]*?)</(?:[a-zA-Z0-9_.-]+:)?$escaped>", RegexOption.IGNORE_CASE)
        return regex.find(xml)?.groupValues?.get(1)?.trim()
    }

    fun escapeXml(input: String): String = buildString(input.length + 16) {
        for (ch in input) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(ch)
            }
        }
    }

    fun unescapeXml(input: String): String = input
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")

    /**
     * Finds the phone's Wi-Fi LAN IPv4 address that routes directly to `targetHost` (the Smart TV),
     * avoiding 5G/cellular or VPN interfaces on multi-homed Android devices.
     */
    internal fun localLanIpv4ForTarget(targetHost: String): String? {
        val targetPrefix24 = targetHost.substringBeforeLast('.', "")
        val targetParts = targetHost.split('.')
        val targetPrefix16 = if (targetParts.size == 4) "${targetParts[0]}.${targetParts[1]}." else ""

        val interfaces = runCatching {
            java.net.NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback }
        }.getOrDefault(emptyList())

        val candidates = interfaces.flatMap { iface ->
            val name = iface.name.orEmpty().lowercase(Locale.ROOT)
            val isWifi = name.startsWith("wlan") || name.startsWith("wifi") || name.startsWith("eth")
            iface.inetAddresses.toList()
                .filterIsInstance<Inet4Address>()
                .mapNotNull { addr -> addr.hostAddress?.takeIf(::isPrivateLanHost)?.let { it to isWifi } }
        }

        // 1. Exact /24 subnet match with the TV (immune to 5G cellular / Tailscale VPN!)
        if (targetPrefix24.isNotEmpty()) {
            candidates.firstOrNull { (ip, isWifi) -> isWifi && ip.startsWith("$targetPrefix24.") }?.first?.let { return it }
            candidates.firstOrNull { (ip, _) -> ip.startsWith("$targetPrefix24.") }?.first?.let { return it }
        }

        // 2. Kernel routing table probe via UDP connect (no packets sent)
        val routed = runCatching {
            java.net.DatagramSocket().use { probe ->
                probe.connect(InetAddress.getByName(targetHost), SSDP_PORT)
                probe.localAddress?.hostAddress
            }
        }.getOrNull()
        if (routed != null && isPrivateLanHost(routed)) return routed

        // 3. Same /16 subnet, then any Wi-Fi LAN interface, then fallback
        if (targetPrefix16.isNotEmpty()) {
            candidates.firstOrNull { (ip, _) -> ip.startsWith(targetPrefix16) }?.first?.let { return it }
        }
        return candidates.firstOrNull { (_, isWifi) -> isWifi }?.first
            ?: candidates.firstOrNull()?.first
    }

    internal fun localLanIpv4Address(): InetAddress? = runCatching {
        val interfaces = java.net.NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
        val wifiFirst = interfaces.sortedByDescending { iface ->
            val n = iface.name.orEmpty().lowercase(Locale.ROOT)
            when {
                n.startsWith("wlan") || n.startsWith("wifi") -> 2
                n.startsWith("eth") -> 1
                else -> 0
            }
        }
        wifiFirst.flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && isPrivateLanHost(it.hostAddress.orEmpty()) }
    }.getOrNull()
}
