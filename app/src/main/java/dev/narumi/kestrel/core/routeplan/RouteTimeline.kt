package dev.narumi.kestrel.core.routeplan

import dev.narumi.kestrel.core.location.LatLng
import dev.narumi.kestrel.core.location.MockSample
import dev.narumi.kestrel.core.location.bearingDegrees
import dev.narumi.kestrel.core.location.haversineMeters
import dev.narumi.kestrel.core.location.interpolateGreatCircle

private const val KMH_TO_MPS = 1.0 / 3.6
private const val MS_PER_SECOND = 1000.0

// A source closer than this to the first route point does not need a lead-in leg.
private const val LEAD_IN_MIN_METERS = 1.0

// Beyond this the initial bearing of a segment drifts noticeably, so bearing is recomputed live.
private const val LONG_LEG_METERS = 5_000.0

/**
 * Maps elapsed seconds since the start time to a position, speed and bearing along a
 * [PlaybackPlan]. Position is a pure function of time, so a restarted service or a throttled
 * timer never loses ground.
 *
 * Before the start (elapsed <= 0) the device holds at point 0 (the source, if there is one); after
 * the end it holds at the last point with speed 0.
 *
 * Throws [IllegalArgumentException] for plans whose timing cannot be built (missing or decreasing
 * timestamps, movement in zero time, or a lead-in with no speed to travel at).
 */
class RouteTimeline(
    plan: PlaybackPlan,
) {
    private class Leg(
        val from: LatLng,
        val to: LatLng,
        val startSeconds: Double,
        val endSeconds: Double,
        val meters: Double,
        val bearing: Double,
    )

    private val legs: List<Leg> = buildLegs(plan)
    private val ends: DoubleArray = DoubleArray(legs.size) { legs[it].endSeconds }
    private val startPoint: LatLng = plan.source ?: plan.points.first().point
    private val endPoint: LatLng = plan.points.last().point

    val durationSeconds: Double = ends.lastOrNull() ?: 0.0
    val totalMeters: Double = legs.sumOf { it.meters }

    /** Overall average including any stationary periods; 0 for a zero-length route. */
    val averageSpeedKmh: Double = if (durationSeconds > 0.0) totalMeters / durationSeconds / KMH_TO_MPS else 0.0

    fun positionAt(elapsedSeconds: Double): MockSample {
        if (legs.isEmpty()) return MockSample(startPoint, 0.0, 0.0)
        // `!(x > 0)` also catches NaN.
        if (!(elapsedSeconds > 0.0)) return MockSample(startPoint, 0.0, legs.first().bearing)
        if (elapsedSeconds >= durationSeconds) return MockSample(endPoint, 0.0, legs.last().bearing)
        val leg = legs[legIndexAt(elapsedSeconds)]
        val span = leg.endSeconds - leg.startSeconds
        val fraction = (elapsedSeconds - leg.startSeconds) / span
        val point = interpolateGreatCircle(leg.from, leg.to, fraction)
        val bearing =
            if (leg.meters > LONG_LEG_METERS) bearingDegrees(point, leg.to) else leg.bearing
        return MockSample(point, leg.meters / span, bearing)
    }

    // Smallest index whose end is after [t]. Callers guarantee 0 < t < durationSeconds.
    private fun legIndexAt(t: Double): Int {
        var low = 0
        var high = ends.lastIndex
        while (low < high) {
            val mid = (low + high) ushr 1
            if (ends[mid] > t) high = mid else low = mid + 1
        }
        return low
    }

    private data class Node(
        val point: LatLng,
        val timeMs: Long?,
    )

    private companion object {
        fun buildLegs(plan: PlaybackPlan): List<Leg> {
            val legs = ArrayList<Leg>()
            var cursor = 0.0
            var lastBearing = 0.0

            fun addLeg(
                from: LatLng,
                to: LatLng,
                seconds: Double,
            ) {
                val meters = haversineMeters(from, to)
                if (meters == 0.0 && seconds == 0.0) return
                require(seconds > 0.0) { "route moves between points in zero time" }
                if (meters > 0.0) lastBearing = bearingDegrees(from, to)
                legs += Leg(from, to, cursor, cursor + seconds, meters, lastBearing)
                cursor += seconds
            }

            val route = plan.points.map { Node(it.point, it.timeMs) }
            val source = plan.source
            if (source != null && haversineMeters(source, route.first().point) > LEAD_IN_MIN_METERS) {
                addLeg(source, route.first().point, leadInSeconds(plan, source, route.first().point))
            }
            for (i in 0 until route.lastIndex) {
                addLeg(route[i].point, route[i + 1].point, legSeconds(plan.speed, route[i], route[i + 1]))
            }
            return legs
        }

        fun leadInSeconds(
            plan: PlaybackPlan,
            source: LatLng,
            firstPoint: LatLng,
        ): Double {
            val kmh =
                when (val speed = plan.speed) {
                    is SpeedSource.Constant -> speed.kmh
                    else ->
                        requireNotNull(plan.leadInSpeedKmh) {
                            "a source away from the route needs a lead-in speed when using timestamps"
                        }
                }
            return haversineMeters(source, firstPoint) / (kmh * KMH_TO_MPS)
        }

        fun legSeconds(
            speed: SpeedSource,
            from: Node,
            to: Node,
        ): Double =
            when (speed) {
                is SpeedSource.Constant -> haversineMeters(from.point, to.point) / (speed.kmh * KMH_TO_MPS)
                SpeedSource.FromTimestamps -> timestampSeconds(from, to)
                is SpeedSource.TimestampsScaled -> timestampSeconds(from, to) / speed.factor
            }

        fun timestampSeconds(
            from: Node,
            to: Node,
        ): Double {
            val start = requireNotNull(from.timeMs) { "every point needs a timestamp" }
            val end = requireNotNull(to.timeMs) { "every point needs a timestamp" }
            require(end >= start) { "timestamps must not go backwards" }
            return (end - start) / MS_PER_SECOND
        }
    }
}
