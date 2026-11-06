package play.ott.nativeapp.ui

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * EPG dates follow the device's local day. Show the end date only when
 * the programme actually crosses local midnight (not merely UTC midnight).
 */
internal fun programmeTimeLabels(
    startMillis: Long,
    endMillis: Long,
    locale: Locale,
    timeZone: TimeZone,
    longPattern: String,
    shortPattern: String,
): Pair<String, String> {
    val start = Calendar.getInstance(timeZone, locale).apply { timeInMillis = startMillis }
    val end = Calendar.getInstance(timeZone, locale).apply { timeInMillis = endMillis }
    val sameLocalDay = start.get(Calendar.ERA) == end.get(Calendar.ERA) &&
        start.get(Calendar.YEAR) == end.get(Calendar.YEAR) &&
        start.get(Calendar.DAY_OF_YEAR) == end.get(Calendar.DAY_OF_YEAR)
    val startFormat = SimpleDateFormat(longPattern, locale).apply { this.timeZone = timeZone }
    val endFormat = SimpleDateFormat(if (sameLocalDay) shortPattern else longPattern, locale)
        .apply { this.timeZone = timeZone }
    return startFormat.format(Date(startMillis)) to endFormat.format(Date(endMillis))
}
