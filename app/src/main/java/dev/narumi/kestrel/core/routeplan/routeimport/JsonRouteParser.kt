package dev.narumi.kestrel.core.routeplan.routeimport

import dev.narumi.kestrel.core.location.LatLng
import dev.narumi.kestrel.core.routeplan.MAX_UPDATE_INTERVAL_MS
import dev.narumi.kestrel.core.routeplan.MIN_UPDATE_INTERVAL_MS
import dev.narumi.kestrel.core.routeplan.TrackPoint

/**
 * Non-GeoJSON JSON: Kestrel's own plan format, plus GraphHopper, Valhalla and OSRM route responses.
 *
 * Kestrel format:
 * ```
 * {"name": "...", "source": {"lat": 0, "lng": 0}, "start": "2026-10-01T08:00:00+02:00",
 *  "speedKmh": 40, "updateIntervalMs": 1000,
 *  "points": [{"lat": 0, "lng": 0, "time": "2026-10-01T08:00:00Z"}]}
 * ```
 * `waypoints` is accepted as an alias for `points`, and `lon`/`long` for `lng`.
 */
internal object JsonRouteParser {
    private const val POLYLINE5 = 5
    private const val POLYLINE6 = 6
    private const val MULTIPLIER_6 = 1e6

    fun parse(root: Map<String, Any?>): ImportedRoute =
        when {
            root["points"] is List<*> || root["waypoints"] is List<*> -> parseKestrel(root)
            root["paths"] is List<*> -> parseGraphHopper(root)
            root["trip"] is Map<*, *> -> parseValhalla(root)
            root["routes"] is List<*> -> parseOsrm(root)
            else -> throw RouteImportException(
                "Unrecognised JSON. Expected a Kestrel plan, GeoJSON, or a GraphHopper, Valhalla or OSRM response.",
            )
        }

    private fun parseKestrel(root: Map<String, Any?>): ImportedRoute {
        val warnings = ArrayList<String>()
        val list = (root["points"] ?: root["waypoints"]).asJsonArray().orEmpty()
        val points =
            list.mapIndexed { index, item ->
                val obj = item.asJsonObject() ?: throw RouteImportException("Point ${index + 1} must be an object with lat and lng.")
                TrackPoint(latLngOf(obj, "Point ${index + 1}"), timeFromJson(obj["time"]))
            }
        val hints =
            PlanHints(
                source = root["source"].asJsonObject()?.let { latLngOf(it, "source") },
                startAtEpochMs = root["start"]?.let { timeFromJson(it, allowRelative = false) ?: throw RouteImportException("start is not a valid time.") },
                speedKmh = positiveNumber(root["speedKmh"], "speedKmh"),
                updateIntervalMs =
                    positiveNumber(root["updateIntervalMs"], "updateIntervalMs")?.let {
                        clampInterval(it.toLong(), warnings)
                    },
            )
        return ImportedRoute((root["name"] as? String)?.takeIf { it.isNotBlank() }, points, hints, warnings)
    }

    private fun latLngOf(
        obj: Map<String, Any?>,
        label: String,
    ): LatLng {
        val lat = obj["lat"] as? Double ?: obj["latitude"] as? Double
        val lng = obj["lng"] as? Double ?: obj["lon"] as? Double ?: obj["long"] as? Double ?: obj["longitude"] as? Double
        if (lat == null || lng == null) throw RouteImportException("$label needs numeric lat and lng.")
        return LatLng(lat, lng)
    }

    private fun positiveNumber(
        value: Any?,
        label: String,
    ): Double? {
        if (value == null) return null
        val number = value as? Double
        if (number == null || !number.isFinite() || number <= 0.0) throw RouteImportException("$label must be a positive number.")
        return number
    }

    private fun clampInterval(
        value: Long,
        warnings: MutableList<String>,
    ): Long {
        val clamped = value.coerceIn(MIN_UPDATE_INTERVAL_MS, MAX_UPDATE_INTERVAL_MS)
        if (clamped != value) warnings += "updateIntervalMs $value was adjusted to $clamped (allowed $MIN_UPDATE_INTERVAL_MS to $MAX_UPDATE_INTERVAL_MS)."
        return clamped
    }

    private fun parseGraphHopper(root: Map<String, Any?>): ImportedRoute {
        val path = root["paths"].asJsonArray()?.firstOrNull().asJsonObject() ?: throw RouteImportException("The GraphHopper response has no paths.")
        val points =
            when (val geometry = path["points"]) {
                is String -> {
                    val precision = if (path["points_encoded_multiplier"] == MULTIPLIER_6) POLYLINE6 else POLYLINE5
                    PolylineDecoder.decode(geometry, precision)
                }
                is Map<*, *> -> geoJsonCoordinates(geometry.asJsonObject())
                else -> throw RouteImportException("The GraphHopper path has no points.")
            }
        return ImportedRoute(null, points.map { TrackPoint(it) })
    }

    private fun parseValhalla(root: Map<String, Any?>): ImportedRoute {
        val legs =
            root["trip"]
                .asJsonObject()
                ?.get("legs")
                .asJsonArray()
                .orEmpty()
        val points = ArrayList<LatLng>()
        for (leg in legs) {
            val shape = leg.asJsonObject()?.get("shape") as? String ?: throw RouteImportException("A Valhalla leg has no shape.")
            val decoded = PolylineDecoder.decode(shape, POLYLINE6)
            // Legs share their joint point, so skip the duplicate.
            points += if (points.isNotEmpty() && decoded.firstOrNull() == points.last()) decoded.drop(1) else decoded
        }
        if (points.isEmpty()) throw RouteImportException("The Valhalla response has no route shape.")
        return ImportedRoute(null, points.map { TrackPoint(it) })
    }

    private fun parseOsrm(root: Map<String, Any?>): ImportedRoute {
        val route = root["routes"].asJsonArray()?.firstOrNull().asJsonObject() ?: throw RouteImportException("The response has no routes.")
        return when (val geometry = route["geometry"]) {
            is String -> {
                val (points, precision) = PolylineDecoder.decodeGuessingPrecision(geometry)
                ImportedRoute(
                    null,
                    points.map { TrackPoint(it) },
                    warnings = listOf("Polyline geometry read as precision $precision (guessed from the coordinate range)."),
                )
            }
            is Map<*, *> -> ImportedRoute(null, geoJsonCoordinates(geometry.asJsonObject()).map { TrackPoint(it) })
            else -> throw RouteImportException("The route has no geometry.")
        }
    }

    private fun geoJsonCoordinates(geometry: Map<String, Any?>?): List<LatLng> {
        val coordinates = geometry?.get("coordinates").asJsonArray() ?: throw RouteImportException("The geometry has no coordinates.")
        return coordinates.map { GeoJsonParser.coordinate(it) }
    }
}
