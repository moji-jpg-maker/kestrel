package dev.narumi.kestrel.core.routeplan.routeimport

import dev.narumi.kestrel.core.location.LatLng
import kotlin.math.pow

/** Decoder for the Google encoded-polyline format (precision 5) and its 1e6 variant (Valhalla). */
internal object PolylineDecoder {
    private const val CHUNK_OFFSET = 63
    private const val CHUNK_PAYLOAD_MASK = 0x1f
    private const val CHUNK_CONTINUE_BIT = 0x20
    private const val BITS_PER_CHUNK = 5
    private const val MAX_SHIFT = 60

    fun decode(
        encoded: String,
        precision: Int,
    ): List<LatLng> {
        val factor = 10.0.pow(precision)
        val out = ArrayList<LatLng>()
        val cursor = intArrayOf(0)
        var lat = 0L
        var lng = 0L
        while (cursor[0] < encoded.length) {
            lat += nextValue(encoded, cursor)
            lng += nextValue(encoded, cursor)
            out += LatLng(lat / factor, lng / factor)
        }
        return out
    }

    /** Tries precision 5 first and falls back to 6 when the result is not a valid coordinate set. */
    fun decodeGuessingPrecision(encoded: String): Pair<List<LatLng>, Int> {
        for (precision in intArrayOf(5, 6)) {
            val points = decode(encoded, precision)
            if (points.all { it.lat in -90.0..90.0 && it.lng in -180.0..180.0 }) return points to precision
        }
        throw RouteImportException("The route geometry is not a valid encoded polyline.")
    }

    private fun nextValue(
        encoded: String,
        cursor: IntArray,
    ): Long {
        var result = 0L
        var shift = 0
        var chunk: Int
        do {
            if (cursor[0] >= encoded.length) throw RouteImportException("The encoded polyline is truncated.")
            chunk = encoded[cursor[0]++].code - CHUNK_OFFSET
            if (chunk !in 0..(CHUNK_PAYLOAD_MASK or CHUNK_CONTINUE_BIT) || shift > MAX_SHIFT) {
                throw RouteImportException("The encoded polyline is malformed.")
            }
            result = result or ((chunk and CHUNK_PAYLOAD_MASK).toLong() shl shift)
            shift += BITS_PER_CHUNK
        } while (chunk >= CHUNK_CONTINUE_BIT)
        return if (result and 1L != 0L) (result shr 1).inv() else result shr 1
    }
}
