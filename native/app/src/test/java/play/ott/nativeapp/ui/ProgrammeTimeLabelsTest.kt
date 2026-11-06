package play.ott.nativeapp.ui

import java.time.Instant
import java.util.Locale
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

class ProgrammeTimeLabelsTest {
    private val zone = TimeZone.getTimeZone("America/Sao_Paulo")
    private val longPattern = "EEE, d MMM · HH:mm"
    private val shortPattern = "HH:mm"

    @Test fun `same-day programme shows one date and short ending time`() {
        val start = Instant.parse("2026-11-06T00:00:00Z").toEpochMilli()
        val end = Instant.parse("2026-11-06T01:30:00Z").toEpochMilli()
        val labels = programmeTimeLabels(start, end, Locale.US, zone, longPattern, shortPattern)
        assertEquals("Thu, 5 Nov · 21:00", labels.first)
        assertEquals("22:30", labels.second)
    }

    @Test fun `programme crossing local midnight shows date on both ends`() {
        val start = Instant.parse("2026-11-06T01:30:00Z").toEpochMilli()
        val end = Instant.parse("2026-11-06T03:30:00Z").toEpochMilli()
        val labels = programmeTimeLabels(start, end, Locale.US, zone, longPattern, shortPattern)
        assertEquals("Thu, 5 Nov · 22:30", labels.first)
        assertEquals("Fri, 6 Nov · 00:30", labels.second)
    }

    @Test fun `UTC midnight does not imply local day change`() {
        val start = Instant.parse("2026-11-05T23:45:00Z").toEpochMilli()
        val end = Instant.parse("2026-11-06T00:15:00Z").toEpochMilli()
        val labels = programmeTimeLabels(start, end, Locale.US, zone, longPattern, shortPattern)
        assertEquals("Thu, 5 Nov · 20:45", labels.first)
        assertEquals("21:15", labels.second)
    }
}
