package dev.narumi.kestrel.core.routeplan.routeimport

import dev.narumi.kestrel.core.location.LatLng
import dev.narumi.kestrel.core.routeplan.TrackPoint

const val MAX_IMPORT_POINTS = 200_000

private const val MAX_LAT = 90.0
private const val MAX_LNG = 180.0

/** Checks and normalises whatever a parser produced so every format ends up equally clean. */
internal object RouteSanitizer {
    fun clean(route: ImportedRoute): ImportedRoute {
        val warnings = route.warnings.toMutableList()
        if (route.points.size > MAX_IMPORT_POINTS) {
            throw RouteImportException(
                "This route has ${route.points.size} points; the limit is $MAX_IMPORT_POINTS. Simplify it first.",
            )
        }
        route.points.forEachIndexed { index, p -> requireValid(p.point, "Point ${index + 1}") }
        route.hints.source?.let { requireValid(it, "The source") }

        val timed = dropUnusableTimestamps(route.points, warnings)
        val points = removeRedundantPoints(timed, warnings)
        if (points.size < 2) throw RouteImportException("The route needs at least two distinct points.")
        return route.copy(points = points, warnings = warnings)
    }

    private fun requireValid(
        p: LatLng,
        label: String,
    ) {
        if (!p.lat.isFinite() || !p.lng.isFinite()) throw RouteImportException("$label has a non-numeric coordinate.")
        if (p.lat !in -MAX_LAT..MAX_LAT || p.lng !in -MAX_LNG..MAX_LNG) {
            throw RouteImportException(
                "$label (${p.lat}, ${p.lng}) is out of range. Are latitude and longitude swapped?",
            )
        }
    }

    // Timestamps are all-or-nothing: a partly timed route cannot be replayed from its own times.
    private fun dropUnusableTimestamps(
        points: List<TrackPoint>,
        warnings: MutableList<String>,
    ): List<TrackPoint> {
        val timedCount = points.count { it.timeMs != null }
        val reason =
            when {
                timedCount == 0 -> return points
                timedCount < points.size -> "only $timedCount of ${points.size} points have a timestamp"
                points.zipWithNext().any { (a, b) -> b.timeMs!! < a.timeMs!! } -> "they are not in order"
                else -> return points
            }
        warnings += "Ignored the timestamps because $reason; use a constant speed."
        return points.map { it.copy(timeMs = null) }
    }

    private fun removeRedundantPoints(
        points: List<TrackPoint>,
        warnings: MutableList<String>,
    ): List<TrackPoint> {
        val out = ArrayList<TrackPoint>(points.size)
        var duplicates = 0
        var simultaneous = 0
        for (p in points) {
            val prev = out.lastOrNull()
            when {
                prev == null -> out += p
                prev.point == p.point && (p.timeMs == null || p.timeMs == prev.timeMs) -> duplicates++
                // Two different places at the same instant cannot be replayed; keep the first.
                p.timeMs != null && p.timeMs == prev.timeMs -> simultaneous++
                else -> out += p
            }
        }
        if (duplicates > 0) warnings += "Removed $duplicates repeated points."
        if (simultaneous > 0) warnings += "Removed $simultaneous points that shared a timestamp with the previous point."
        return out
    }
}
