package dev.narumi.kestrel.core.routeplan.routeimport

import dev.narumi.kestrel.core.location.LatLng
import dev.narumi.kestrel.core.routeplan.TrackPoint

internal val GEOJSON_TYPES =
    setOf(
        "FeatureCollection",
        "Feature",
        "GeometryCollection",
        "LineString",
        "MultiLineString",
        "Point",
        "MultiPoint",
    )

/**
 * Reads GeoJSON (RFC 7946: coordinates are `[longitude, latitude, altitude?]`). Line geometries are
 * followed in file order; only when there are none do Point features become a route. Per-vertex
 * times are read from `properties.coordinateProperties.times`, the convention used by togeojson.
 */
internal object GeoJsonParser {
    private const val MAX_DEPTH = 16

    private class Line(
        val points: List<LatLng>,
        val times: List<Long?>?,
    )

    private class Collector {
        val lines = ArrayList<Line>()
        val pointFeatures = ArrayList<LatLng>()
        val warnings = ArrayList<String>()
        var name: String? = null
    }

    fun parse(root: Map<String, Any?>): ImportedRoute {
        val collector = Collector()
        collect(root, null, collector, 0)
        return when {
            collector.lines.isNotEmpty() -> fromLines(collector)
            collector.pointFeatures.size >= 2 -> {
                collector.warnings += "No line geometry found; using Point features in file order."
                ImportedRoute(collector.name, collector.pointFeatures.map { TrackPoint(it) }, warnings = collector.warnings)
            }
            else -> throw RouteImportException("The GeoJSON has no LineString/MultiLineString (or 2+ Point) geometry.")
        }
    }

    private fun fromLines(collector: Collector): ImportedRoute {
        val lines = collector.lines
        if (lines.size > 1) collector.warnings += "Joined ${lines.size} separate lines into one path."
        val points =
            lines.flatMap { line ->
                line.points.mapIndexed { index, point -> TrackPoint(point, line.times?.getOrNull(index)) }
            }
        return ImportedRoute(collector.name, points, warnings = collector.warnings)
    }

    private fun collect(
        node: Any?,
        times: Any?,
        collector: Collector,
        depth: Int,
    ) {
        if (depth > MAX_DEPTH) throw RouteImportException("The GeoJSON is nested too deeply.")
        val obj = node.asJsonObject() ?: return
        when (obj["type"]) {
            "FeatureCollection" -> obj["features"].asJsonArray()?.forEach { collect(it, null, collector, depth + 1) }
            "Feature" -> {
                val properties = obj["properties"].asJsonObject()
                if (collector.name == null) collector.name = (properties?.get("name") as? String)?.takeIf { it.isNotBlank() }
                val featureTimes = properties?.get("coordinateProperties").asJsonObject()?.get("times")
                collect(obj["geometry"], featureTimes, collector, depth + 1)
            }
            "GeometryCollection" -> obj["geometries"].asJsonArray()?.forEach { collect(it, null, collector, depth + 1) }
            "LineString" -> addLine(obj["coordinates"], times, collector)
            "MultiLineString" -> {
                val parts = obj["coordinates"].asJsonArray() ?: throw RouteImportException("MultiLineString has no coordinates.")
                val timeParts = times.asJsonArray()
                parts.forEachIndexed { index, part -> addLine(part, timeParts?.getOrNull(index), collector) }
            }
            "Point" -> collector.pointFeatures += coordinate(obj["coordinates"])
            "MultiPoint" ->
                obj["coordinates"].asJsonArray()?.forEach { collector.pointFeatures += coordinate(it) }
        }
    }

    private fun addLine(
        coordinates: Any?,
        times: Any?,
        collector: Collector,
    ) {
        val list = coordinates.asJsonArray() ?: throw RouteImportException("A line geometry has no coordinate list.")
        val points = list.map { coordinate(it) }
        val timeList = times.asJsonArray()
        val parsedTimes =
            when {
                timeList == null -> null
                timeList.size != points.size -> {
                    collector.warnings += "Ignored coordinateProperties.times: it does not match the coordinate count."
                    null
                }
                else -> timeList.map { timeFromJson(it, allowRelative = false) }
            }
        collector.lines += Line(points, parsedTimes)
    }

    /** Public so router adapters can reuse it for GeoJSON geometries. */
    fun coordinate(raw: Any?): LatLng {
        val values = raw.asJsonArray()
        val lng = values?.getOrNull(0) as? Double
        val lat = values?.getOrNull(1) as? Double
        if (lng == null || lat == null) throw RouteImportException("A GeoJSON coordinate must be [longitude, latitude].")
        if (lat !in -90.0..90.0) {
            throw RouteImportException(
                "A GeoJSON coordinate has latitude $lat, which is out of range. GeoJSON order is [longitude, latitude].",
            )
        }
        return LatLng(lat, lng)
    }
}
