package play.ott.nativeapp.core

import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import play.ott.core.Archive
import play.ott.core.ArchiveFormat
import play.ott.core.ArchiveRequest

object CatchupResolver {
    /** Platform adapter: zone database, URL parser and scoped transport headers. */
    fun resolve(entry: MediaEntry, programme: Programme, nowMillis: Long = System.currentTimeMillis()): PlaybackStream? {
        val catchup = entry.catchup ?: return null
        val zone = try { ZoneId.of(catchup.timeZone) } catch (_: Exception) { ZoneId.of("UTC") }
        val parsed = entry.url.toHttpUrlOrNull()
        val request = ArchiveRequest(
            url = parsed?.toString() ?: entry.url, mode = catchup.mode, source = catchup.source, days = catchup.days,
            start = programme.startMillis / 1000.0, end = programme.endMillis / 1000.0, now = nowMillis / 1000.0,
            live = entry.kind == MediaKind.LIVE, epgId = entry.epgId, channelId = programme.channelId,
            channelName = entry.name, resourceName = parsed?.pathSegments?.lastOrNull(),
        )
        val url = Archive.resolve(request, ArchiveFormat.ANDROID, { seconds ->
            val date = Instant.ofEpochSecond(seconds.toLong()).atZone(zone)
            listOf(date.year, date.monthValue, date.dayOfMonth, date.hour, date.minute, date.second)
        }, { value -> resolveHttp(entry.url, value).takeIf(String::isNotBlank) }) ?: return null
        return PlaybackStream(url, scopedHeaders(entry.headers, entry.headerOrigins, url), inferMimeType(url))
    }
}

internal fun inferMimeType(url: String): String? = when (httpUrl(url).encodedPath.substringAfterLast('.').lowercase(Locale.ROOT)) {
    "m3u8" -> "application/x-mpegURL"
    "mpd" -> "application/dash+xml"
    "mp4", "m4v" -> "video/mp4"
    "ts" -> "video/mp2t"
    else -> null
}
