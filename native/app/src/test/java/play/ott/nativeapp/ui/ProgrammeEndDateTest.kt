package play.ott.nativeapp.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

class ProgrammeEndDateTest {
    private val zone = TimeZone.getTimeZone("America/Sao_Paulo")
    private val dateAndTime = SimpleDateFormat("EEE, d MMM · HH:mm", Locale("pt", "BR")).apply { timeZone = zone }
    private val timeOnly = SimpleDateFormat("HH:mm", Locale("pt", "BR")).apply { timeZone = zone }
    private fun time(value: String): Long = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).apply {
        timeZone = zone
        isLenient = false
    }.parse(value)!!.time

    @Test fun sameDayEndKeepsTimeOnly() {
        assertEquals("22:50", programmeEndLabel(time("2026-11-05 21:30"), time("2026-11-05 22:50"), dateAndTime, timeOnly))
    }

    @Test fun crossingMidnightIncludesWeekdayAndEndDate() {
        val end = time("2026-11-06 00:15")
        assertEquals(dateAndTime.format(Date(end)), programmeEndLabel(time("2026-11-05 23:50"), end, dateAndTime, timeOnly))
    }

    @Test fun crossingNewYearIncludesEndDate() {
        val end = time("2027-01-01 00:10")
        assertEquals(dateAndTime.format(Date(end)), programmeEndLabel(time("2026-12-31 23:50"), end, dateAndTime, timeOnly))
    }

    @Test fun sameCalendarDayUsesTheConfiguredTimeZone() {
        val utcDate = SimpleDateFormat("EEE, d MMM · HH:mm", Locale("pt", "BR")).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val utcTime = SimpleDateFormat("HH:mm", Locale("pt", "BR")).apply { timeZone = TimeZone.getTimeZone("UTC") }
        // In UTC both times occur on Nov 6; in Sao Paulo they cross midnight.
        assertEquals("03:30", programmeEndLabel(time("2026-11-05 22:30"), time("2026-11-06 00:30"), utcDate, utcTime))
    }
}
