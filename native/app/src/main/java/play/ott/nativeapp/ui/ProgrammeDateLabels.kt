package play.ott.nativeapp.ui

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date

/** Show the end date when an EPG programme crosses midnight in the guide time zone. */
internal fun programmeEndLabel(
    startMillis: Long,
    endMillis: Long,
    dateAndTimeFormat: SimpleDateFormat,
    timeOnlyFormat: SimpleDateFormat,
): String {
    val zone = dateAndTimeFormat.timeZone
    val start = Calendar.getInstance(zone).apply { timeInMillis = startMillis }
    val end = Calendar.getInstance(zone).apply { timeInMillis = endMillis }
    val sameDay = start.get(Calendar.ERA) == end.get(Calendar.ERA) &&
        start.get(Calendar.YEAR) == end.get(Calendar.YEAR) &&
        start.get(Calendar.DAY_OF_YEAR) == end.get(Calendar.DAY_OF_YEAR)
    return if (sameDay) timeOnlyFormat.format(Date(endMillis))
    else dateAndTimeFormat.format(Date(endMillis))
}