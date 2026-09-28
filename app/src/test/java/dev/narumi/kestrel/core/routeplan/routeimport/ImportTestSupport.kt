package dev.narumi.kestrel.core.routeplan.routeimport

import dev.narumi.kestrel.core.location.LatLng
import org.junit.Assert.assertTrue
import java.time.OffsetDateTime
import kotlin.math.pow

internal val AMSTERDAM =
    listOf(LatLng(52.3700, 4.9000), LatLng(52.3710, 4.9010), LatLng(52.3725, 4.9032))

internal fun epochMs(iso: String): Long = OffsetDateTime.parse(iso).toInstant().toEpochMilli()

internal fun jsonEscape(raw: String): String = raw.replace("\\", "\\\\").replace("\"", "\\\"")

/** Reference encoder used to build polyline fixtures for the decoder tests. */
internal fun encodePolyline(
    points: List<LatLng>,
    precision: Int,
): String {
    val factor = 10.0.pow(precision)
    val out = StringBuilder()
    var prevLat = 0L
    var prevLng = 0L
    for (p in points) {
        val lat = Math.round(p.lat * factor)
        val lng = Math.round(p.lng * factor)
        appendValue(lat - prevLat, out)
        appendValue(lng - prevLng, out)
        prevLat = lat
        prevLng = lng
    }
    return out.toString()
}

private fun appendValue(
    delta: Long,
    out: StringBuilder,
) {
    var v = if (delta < 0) (delta shl 1).inv() else delta shl 1
    while (v >= 0x20) {
        out.append(((0x20 or (v and 0x1f).toInt()) + 63).toChar())
        v = v shr 5
    }
    out.append((v.toInt() + 63).toChar())
}

internal fun success(outcome: ImportOutcome): ImportedRoute {
    assertTrue("expected success but was $outcome", outcome is ImportOutcome.Success)
    return (outcome as ImportOutcome.Success).route
}

internal fun failureMessage(outcome: ImportOutcome): String {
    assertTrue("expected failure but was $outcome", outcome is ImportOutcome.Failure)
    return (outcome as ImportOutcome.Failure).message
}
