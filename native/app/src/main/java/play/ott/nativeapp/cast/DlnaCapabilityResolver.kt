package play.ott.nativeapp.cast

import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

internal data class DlnaSinkCapabilities(
    val rawProtocols: List<String>,
    val supportsHls: Boolean,
    val supportsMpegTs: Boolean,
    val supportsMp4: Boolean,
    val supportsMkv: Boolean,
    val supportsWebm: Boolean,
) {
    fun summaryTag(): String =
        "Sink(${rawProtocols.size}): HLS=${yesNo(supportsHls)} TS=${yesNo(supportsMpegTs)} " +
            "MP4=${yesNo(supportsMp4)} MKV=${yesNo(supportsMkv)}"

    private fun yesNo(v: Boolean): String = if (v) "sim" else "não"
}

internal data class DlnaCandidateProfile(
    val label: String,
    val mediaUrl: String,
    val mimeType: String,
    val dlnaFeatures: String,
    val includeMetadata: Boolean,
    val isDirect: Boolean = false,
)

/**
 * Receiver Capability Resolver for DLNA / UPnP Smart TVs (Samsung Tizen, LG webOS, Roku, TV Box):
 * - Parses `ConnectionManager:1#GetProtocolInfo` (`Sink`) declarations.
 * - Caches per-device Sink capabilities and winning candidate profiles with TTL so subsequent
 *   channels/movies connect on the first attempt without repeating rejected profiles.
 * - Deduplicates identical negotiation candidates and detects `UPnP 701` state transition faults.
 */
internal class DlnaCapabilityResolver(
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val ttlMs: Long = 30L * 60L * 1000L,
) {
    private data class TimedEntry<T>(val value: T, val expiresAtMs: Long)

    private val sinkCache = ConcurrentHashMap<String, TimedEntry<DlnaSinkCapabilities>>()
    private val workingProfileCache = ConcurrentHashMap<String, TimedEntry<String>>()

    fun getCachedSink(deviceId: String): DlnaSinkCapabilities? {
        val entry = sinkCache[deviceId] ?: return null
        if (clockMs() >= entry.expiresAtMs) {
            sinkCache.remove(deviceId, entry)
            return null
        }
        return entry.value
    }

    fun cacheSink(deviceId: String, caps: DlnaSinkCapabilities) {
        sinkCache[deviceId] = TimedEntry(caps, clockMs() + ttlMs)
    }

    fun getWorkingProfile(deviceId: String, isLive: Boolean, mimeType: String): String? {
        val key = profileCacheKey(deviceId, isLive, mimeType)
        val entry = workingProfileCache[key] ?: return null
        if (clockMs() >= entry.expiresAtMs) {
            workingProfileCache.remove(key, entry)
            return null
        }
        return entry.value
    }

    fun recordWorkingProfile(deviceId: String, isLive: Boolean, mimeType: String, label: String) {
        val key = profileCacheKey(deviceId, isLive, mimeType)
        workingProfileCache[key] = TimedEntry(label, clockMs() + ttlMs)
    }

    fun clear() {
        sinkCache.clear()
        workingProfileCache.clear()
    }

    /**
     * Orders and deduplicates DLNA negotiation candidates based on:
     * 1. Cached winning profile for this exact TV & media class (fastest path on zapping).
     * 2. Declared `ConnectionManager#GetProtocolInfo` Sink capabilities (e.g. preferring HLS when
     *    a webOS / Smart TV declares HLS without MPEG-TS).
     */
    fun orderCandidates(
        deviceId: String,
        vendor: DlnaVendorProfile,
        isLive: Boolean,
        mimeType: String,
        candidates: List<DlnaCandidateProfile>,
        sinkCaps: DlnaSinkCapabilities? = getCachedSink(deviceId),
    ): List<DlnaCandidateProfile> {
        // 1. Deduplicate identical wire profiles while preserving order
        val dedup = LinkedHashMap<String, DlnaCandidateProfile>()
        for (c in candidates) {
            val sig = "${c.mediaUrl}|${c.mimeType.lowercase(Locale.ROOT)}|${c.dlnaFeatures}|${c.includeMetadata}"
            dedup.putIfAbsent(sig, c)
        }
        val list = ArrayList(dedup.values)
        if (list.size <= 1) return list

        // 2. Apply Sink-guided preference for live streams when TV explicitly favors HLS over TS
        if (isLive && sinkCaps != null && sinkCaps.supportsHls && !sinkCaps.supportsMpegTs) {
            val hlsIdx = list.indexOfFirst { it.mimeType.contains("mpegurl", ignoreCase = true) }
            if (hlsIdx > 0) {
                val hlsCand = list.removeAt(hlsIdx)
                list.add(0, hlsCand)
            }
        }

        // 3. Promote previously proven winning profile for this exact TV (keep Direct VOD ahead when present)
        val winningLabel = getWorkingProfile(deviceId, isLive, mimeType)
        if (!winningLabel.isNullOrBlank()) {
            val winIdx = list.indexOfFirst { it.label == winningLabel }
            if (winIdx > 0) {
                val winner = list.removeAt(winIdx)
                val insertAt = if (!isLive && !winner.isDirect && list.firstOrNull()?.isDirect == true) 1 else 0
                list.add(insertAt, winner)
            }
        }

        // 4. Vendor safety guard: on Samsung live, never accidentally promote HLS ahead of TS unless previously proven
        if (vendor == DlnaVendorProfile.SAMSUNG && isLive && winningLabel.isNullOrBlank()) {
            val tsIdx = list.indexOfFirst { it.mimeType.equals("video/mp2t", ignoreCase = true) }
            if (tsIdx > 0) {
                val ts = list.removeAt(tsIdx)
                list.add(0, ts)
            }
        }

        return list
    }

    private fun profileCacheKey(deviceId: String, isLive: Boolean, mimeType: String): String =
        "${deviceId.trim()}|${if (isLive) "LIVE" else "VOD"}|${mimeType.lowercase(Locale.ROOT)}"

    companion object {
        private val UPNP_701_REGEX = Regex("\\b701\\b")

        fun parseSinkProtocolInfo(sinkCsv: String): DlnaSinkCapabilities {
            val entries = sinkCsv.split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            var supportsHls = false
            var supportsMpegTs = false
            var supportsMp4 = false
            var supportsMkv = false
            var supportsWebm = false

            for (entry in entries) {
                val lower = entry.lowercase(Locale.ROOT)
                val fields = lower.split(':', limit = 4)
                val contentFormat = fields.getOrNull(2)?.substringBefore(';')?.trim().orEmpty()
                val additionalInfo = fields.getOrNull(3).orEmpty()

                if (contentFormat.contains("mpegurl") || lower.contains("m3u8")) {
                    supportsHls = true
                }
                if (contentFormat == "video/mp2t" ||
                    contentFormat == "video/vnd.dlna.mpeg-tts" ||
                    contentFormat == "video/mpeg" ||
                    additionalInfo.contains("mpeg_ts")
                ) {
                    supportsMpegTs = true
                }
                if (contentFormat == "video/mp4" ||
                    contentFormat == "video/mpeg4" ||
                    additionalInfo.contains("avc_mp4")
                ) {
                    supportsMp4 = true
                }
                if (contentFormat.contains("matroska") ||
                    contentFormat == "video/mkv" ||
                    contentFormat == "video/x-mkv"
                ) {
                    supportsMkv = true
                }
                if (contentFormat == "video/webm") {
                    supportsWebm = true
                }
            }

            return DlnaSinkCapabilities(
                rawProtocols = entries,
                supportsHls = supportsHls,
                supportsMpegTs = supportsMpegTs,
                supportsMp4 = supportsMp4,
                supportsMkv = supportsMkv,
                supportsWebm = supportsWebm,
            )
        }

        fun isUpnp701Fault(faultSummary: String?): Boolean {
            if (faultSummary.isNullOrBlank()) return false
            val lower = faultSummary.lowercase(Locale.ROOT)
            return UPNP_701_REGEX.containsMatchIn(lower) || lower.contains("transition not available")
        }
    }
}
