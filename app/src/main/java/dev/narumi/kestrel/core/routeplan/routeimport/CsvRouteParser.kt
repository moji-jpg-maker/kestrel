package dev.narumi.kestrel.core.routeplan.routeimport

import dev.narumi.kestrel.core.location.LatLng
import dev.narumi.kestrel.core.location.parseCoordInput
import dev.narumi.kestrel.core.routeplan.TrackPoint

/**
 * Reads delimited text (comma, semicolon or tab) and plain coordinate lists.
 *
 * With a header row, columns are found by name (`lat`, `lng`/`lon`, `time`). Without one the order
 * is latitude, longitude, then an optional absolute timestamp. Lines without a delimiter are handed
 * to [parseCoordInput], so `52.37 4.90`, `geo:` URIs and map links also work. With `;` or tab
 * delimiters a comma is accepted as the decimal separator.
 */
internal object CsvRouteParser {
    private val LAT_NAMES = setOf("lat", "latitude", "y")
    private val LNG_NAMES = setOf("lng", "lon", "long", "longitude", "x")
    private val TIME_NAMES = setOf("time", "timestamp", "datetime", "date", "t")
    private val SPEED_NAMES = setOf("speed", "velocity")
    private const val MAX_QUOTED_PREVIEW = 40

    private class Columns(
        val lat: Int,
        val lng: Int,
        val time: Int?,
        val hasSpeed: Boolean,
        val hasHeader: Boolean,
    )

    fun parse(text: String): ImportedRoute {
        val numbered =
            text
                .removePrefix("\uFEFF")
                .lineSequence()
                .mapIndexed { index, line -> (index + 1) to line.trim() }
                .filter { (_, line) -> line.isNotEmpty() && !line.startsWith("#") }
                .toList()
        if (numbered.isEmpty()) throw RouteImportException("The file is empty.")
        val delimiter = detectDelimiter(numbered.first().second)
        val columns = findColumns(splitFields(numbered.first().second, delimiter)) ?: Columns(lat = 0, lng = 1, time = 2, hasSpeed = false, hasHeader = false)
        val warnings = ArrayList<String>()
        if (columns.hasSpeed) warnings += "The speed column is ignored; choose the speed in the schedule instead."
        val rows = if (columns.hasHeader) numbered.drop(1) else numbered
        val points = rows.map { (lineNumber, line) -> readRow(lineNumber, line, delimiter, columns) }
        return ImportedRoute(null, points, warnings = warnings)
    }

    private fun readRow(
        lineNumber: Int,
        line: String,
        delimiter: Char?,
        columns: Columns,
    ): TrackPoint {
        if (delimiter != null) {
            val fields = splitFields(line, delimiter)
            val decimalComma = delimiter != ','
            val lat = number(fields.getOrNull(columns.lat), decimalComma)
            val lng = number(fields.getOrNull(columns.lng), decimalComma)
            if (lat != null && lng != null) {
                val timeField = columns.time?.let { fields.getOrNull(it) }
                // A headerless third column is more likely elevation than a time, so only absolute
                // timestamps count there.
                val time = timeField?.let { parseTimeMs(it, allowRelative = columns.hasHeader) }
                return TrackPoint(LatLng(lat, lng), time)
            }
        }
        val fallback = parseCoordInput(line)
        if (fallback != null && !columns.hasHeader) return TrackPoint(fallback)
        throw RouteImportException("Line $lineNumber is not a coordinate: '${line.take(MAX_QUOTED_PREVIEW)}'")
    }

    private fun number(
        field: String?,
        decimalComma: Boolean,
    ): Double? {
        val raw = field?.trim().orEmpty()
        if (raw.isEmpty()) return null
        return (if (decimalComma) raw.replace(',', '.') else raw).toDoubleOrNull()?.takeIf { it.isFinite() }
    }

    private fun findColumns(header: List<String>): Columns? {
        val names = header.map { it.trim().lowercase() }
        val lat = names.indexOfFirst { it in LAT_NAMES }
        val lng = names.indexOfFirst { it in LNG_NAMES }
        if (lat < 0 || lng < 0) return null
        val time = names.indexOfFirst { it in TIME_NAMES }.takeIf { it >= 0 }
        return Columns(lat, lng, time, names.any { it in SPEED_NAMES }, hasHeader = true)
    }

    private fun detectDelimiter(firstLine: String): Char? =
        when {
            '\t' in firstLine -> '\t'
            ';' in firstLine -> ';'
            ',' in firstLine -> ','
            else -> null
        }

    private fun splitFields(
        line: String,
        delimiter: Char?,
    ): List<String> {
        if (delimiter == null) return listOf(line)
        val fields = ArrayList<String>()
        val current = StringBuilder()
        var quoted = false
        for (c in line) {
            when {
                c == '"' -> quoted = !quoted
                c == delimiter && !quoted -> {
                    fields += current.toString().trim()
                    current.setLength(0)
                }
                else -> current.append(c)
            }
        }
        fields += current.toString().trim()
        return fields
    }
}
