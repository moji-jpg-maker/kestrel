package dev.narumi.kestrel.core.routeplan

import dev.narumi.kestrel.core.location.LatLng

const val MIN_UPDATE_INTERVAL_MS = 200L
const val MAX_UPDATE_INTERVAL_MS = 10_000L
const val DEFAULT_UPDATE_INTERVAL_MS = 1_000L

private const val MAX_LAT = 90.0
private const val MAX_LNG = 180.0

/** A route vertex. [timeMs] is the point's own timestamp (GPX `<time>`, CSV `time`), if any. */
data class TrackPoint(
    val point: LatLng,
    val timeMs: Long? = null,
)

/** Where the per-leg travel time comes from. */
sealed interface SpeedSource {
    /** Constant speed for the whole route. */
    data class Constant(
        val kmh: Double,
    ) : SpeedSource

    /** Replay the file's own timestamps. Every point must carry a time. */
    data object FromTimestamps : SpeedSource

    /** Replay the file's timestamps sped up ([factor] > 1) or slowed down ([factor] < 1). */
    data class TimestampsScaled(
        val factor: Double,
    ) : SpeedSource
}

/**
 * Everything needed to play a route back: what to follow, when to start, how fast, how often.
 *
 * [source] is where the device sits before the start time. If it differs from the first route
 * point, it becomes point 0 and the device travels to the route first (a lead-in leg). With a
 * timestamp-based [speed] the lead-in has no timestamps of its own, so [leadInSpeedKmh] is required.
 */
data class PlaybackPlan(
    val name: String? = null,
    val source: LatLng? = null,
    val points: List<TrackPoint>,
    val startAtEpochMs: Long,
    val speed: SpeedSource,
    val updateIntervalMs: Long = DEFAULT_UPDATE_INTERVAL_MS,
    val leadInSpeedKmh: Double? = null,
) {
    init {
        require(points.size >= 2) { "a route needs at least 2 points" }
        require(updateIntervalMs in MIN_UPDATE_INTERVAL_MS..MAX_UPDATE_INTERVAL_MS) {
            "update interval must be within $MIN_UPDATE_INTERVAL_MS..$MAX_UPDATE_INTERVAL_MS ms"
        }
        require(points.all { it.point.isValid() } && (source?.isValid() ?: true)) {
            "coordinates must be finite and within lat ±90, lng ±180"
        }
        when (speed) {
            is SpeedSource.Constant -> requirePositive(speed.kmh, "speed")
            is SpeedSource.TimestampsScaled -> requirePositive(speed.factor, "speed factor")
            SpeedSource.FromTimestamps -> Unit
        }
        leadInSpeedKmh?.let { requirePositive(it, "lead-in speed") }
    }
}

private fun LatLng.isValid(): Boolean = lat.isFinite() && lng.isFinite() && lat in -MAX_LAT..MAX_LAT && lng in -MAX_LNG..MAX_LNG

private fun requirePositive(
    value: Double,
    label: String,
) = require(value.isFinite() && value > 0.0) { "$label must be finite and positive" }
