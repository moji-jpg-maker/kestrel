package dev.narumi.kestrel.core.routeplan

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.roundToLong

/** "18:30", or "Wed, 30 Sep, 18:30" when [epochMs] is not on the same local day as [nowMs]. */
fun formatScheduleStart(
    epochMs: Long,
    nowMs: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String {
    val start = Instant.ofEpochMilli(epochMs).atZone(zone)
    val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(start)
    val sameDay = start.toLocalDate() == Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    return if (sameDay) time else DateTimeFormatter.ofPattern("EEE, d MMM, ", locale).format(start) + time
}

/** "1 h 05 min", "12 min 30 s" or "45 s". */
fun formatScheduleDuration(seconds: Double): String {
    val total = if (seconds.isFinite()) seconds.roundToLong().coerceAtLeast(0L) else 0L
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return when {
        h > 0 -> "%d h %02d min".format(Locale.US, h, m)
        m > 0 -> if (s == 0L) "$m min" else "$m min $s s"
        else -> "$s s"
    }
}

/** "850 m" or "12.3 km". */
fun formatScheduleDistance(meters: Double): String = if (meters >= 1000.0) "%.1f km".format(Locale.US, meters / 1000.0) else "${meters.roundToLong()} m"
