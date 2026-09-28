package dev.narumi.kestrel.core.routeplan.routeimport

import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

private const val EPOCH_MS_THRESHOLD = 1e11
private const val EPOCH_SECONDS_THRESHOLD = 1e9
private const val MS_PER_SECOND = 1000.0
private const val DATE_TIME_SEPARATOR_INDEX = 10

/**
 * Parses a timestamp into milliseconds.
 *
 * Accepts ISO-8601 with an offset or `Z`, and `yyyy-MM-dd HH:mm:ss` or a zoneless ISO date-time
 * (both read as UTC). Numbers of 1e11 or more are epoch milliseconds, 1e9 or more epoch seconds,
 * and anything smaller is seconds relative to the start of the route, accepted only when
 * [allowRelative] is true. Only differences between a route's timestamps matter for playback.
 */
internal fun parseTimeMs(
    raw: String,
    allowRelative: Boolean = true,
): Long? {
    val text = raw.trim()
    if (text.isEmpty()) return null
    text.toDoubleOrNull()?.let { return numericTimeMs(it, allowRelative) }
    val normalized =
        if (text.length > DATE_TIME_SEPARATOR_INDEX && text[DATE_TIME_SEPARATOR_INDEX] == ' ') {
            text.replaceRange(DATE_TIME_SEPARATOR_INDEX, DATE_TIME_SEPARATOR_INDEX + 1, "T")
        } else {
            text
        }
    return try {
        OffsetDateTime.parse(normalized).toInstant().toEpochMilli()
    } catch (_: DateTimeParseException) {
        try {
            LocalDateTime.parse(normalized).toInstant(ZoneOffset.UTC).toEpochMilli()
        } catch (_: DateTimeParseException) {
            null
        }
    }
}

internal fun timeFromJson(
    value: Any?,
    allowRelative: Boolean = true,
): Long? =
    when (value) {
        is Double -> numericTimeMs(value, allowRelative)
        is String -> parseTimeMs(value, allowRelative)
        else -> null
    }

private fun numericTimeMs(
    value: Double,
    allowRelative: Boolean,
): Long? =
    when {
        !value.isFinite() || value < 0.0 -> null
        value >= EPOCH_MS_THRESHOLD -> value.toLong()
        value >= EPOCH_SECONDS_THRESHOLD -> (value * MS_PER_SECOND).toLong()
        allowRelative -> (value * MS_PER_SECOND).toLong()
        else -> null
    }
