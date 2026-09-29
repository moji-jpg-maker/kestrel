package dev.narumi.kestrel.core.routeplan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

class ScheduleFormatTest {
    private val zone = ZoneId.of("Europe/Amsterdam")

    private fun at(
        day: Int,
        hour: Int,
        minute: Int,
    ) = ZonedDateTime.of(2026, 9, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    @Test
    fun sameDayShowsOnlyTheTime() {
        val text = formatScheduleStart(at(29, 18, 30), at(29, 9, 0), zone, Locale.UK)
        assertEquals("18:30", text)
    }

    @Test
    fun otherDayShowsTheDate() {
        val text = formatScheduleStart(at(30, 17, 5), at(29, 9, 0), zone, Locale.US)
        assertTrue(text, text.startsWith("Wed, 30 Sep, "))
        assertTrue(text, text.contains("5:05"))
    }

    @Test
    fun durationsReadNaturally() {
        assertEquals("45 s", formatScheduleDuration(45.0))
        assertEquals("12 min", formatScheduleDuration(720.0))
        assertEquals("12 min 30 s", formatScheduleDuration(750.0))
        assertEquals("1 h 05 min", formatScheduleDuration(3900.0))
        assertEquals("0 s", formatScheduleDuration(Double.NaN))
    }

    @Test
    fun distancesSwitchToKilometres() {
        assertEquals("850 m", formatScheduleDistance(850.0))
        assertEquals("12.3 km", formatScheduleDistance(12_340.0))
    }
}
