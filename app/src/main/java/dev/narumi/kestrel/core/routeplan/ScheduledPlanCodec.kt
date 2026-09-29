package dev.narumi.kestrel.core.routeplan

import dev.narumi.kestrel.core.data.RouteState
import dev.narumi.kestrel.core.location.LatLng

/**
 * Maps a [PlaybackPlan] to and from the persisted [RouteState]. A scheduled plan is a `RouteState`
 * whose [RouteState.startAtEpochMs] is set, so no new `MockState.Mode` is needed and older payloads
 * (which never set it) keep decoding as ordinary routes.
 *
 * Playback progress is not persisted: it is recomputed from the start time on restore.
 */
internal object ScheduledPlanCodec {
    private const val CONSTANT = "Constant"
    private const val TIMESTAMPS = "Timestamps"
    private const val TIMESTAMPS_SCALED = "TimestampsScaled"
    private const val FALLBACK_SPEED_KMH = 1.0

    /** [averageSpeedKmh] fills the legacy `speedKmh` field for timestamp-driven plans. */
    fun toRouteState(
        plan: PlaybackPlan,
        averageSpeedKmh: Double,
        pausedTotalMs: Long,
    ): RouteState {
        val speed = plan.speed
        val legacySpeed = averageSpeedKmh.takeIf { it.isFinite() && it > 0.0 } ?: FALLBACK_SPEED_KMH
        val timesNeeded = speed !is SpeedSource.Constant
        return RouteState(
            lats = DoubleArray(plan.points.size) { plan.points[it].point.lat },
            lngs = DoubleArray(plan.points.size) { plan.points[it].point.lng },
            speedKmh = (speed as? SpeedSource.Constant)?.kmh ?: legacySpeed,
            mode = "Once",
            timesMs =
                if (timesNeeded) {
                    LongArray(plan.points.size) { requireNotNull(plan.points[it].timeMs) { "timestamps are missing" } }
                } else {
                    null
                },
            startAtEpochMs = plan.startAtEpochMs,
            updateIntervalMs = plan.updateIntervalMs,
            sourceLat = plan.source?.lat,
            sourceLng = plan.source?.lng,
            speedSource =
                when (speed) {
                    is SpeedSource.Constant -> CONSTANT
                    SpeedSource.FromTimestamps -> TIMESTAMPS
                    is SpeedSource.TimestampsScaled -> TIMESTAMPS_SCALED
                },
            speedFactor = (speed as? SpeedSource.TimestampsScaled)?.factor,
            leadInSpeedKmh = plan.leadInSpeedKmh,
            pausedTotalMs = pausedTotalMs,
            name = plan.name,
        )
    }

    /** Null when [state] is an ordinary route or the stored plan is no longer valid. */
    fun fromRouteState(state: RouteState): PlaybackPlan? = runCatching { decodePlan(state) }.getOrNull()

    private fun decodePlan(state: RouteState): PlaybackPlan? {
        val startAt = state.startAtEpochMs ?: return null
        val times = state.timesMs
        if (state.lats.size != state.lngs.size ||
            (times != null && times.size != state.lats.size) ||
            ((state.sourceLat == null) != (state.sourceLng == null)) ||
            (state.speedSource in setOf(TIMESTAMPS, TIMESTAMPS_SCALED) && times == null)
        ) {
            return null
        }
        val speed = decodeSpeed(state) ?: return null
        val sourceLat = state.sourceLat
        val sourceLng = state.sourceLng
        return PlaybackPlan(
            name = state.name,
            source = if (sourceLat != null && sourceLng != null) LatLng(sourceLat, sourceLng) else null,
            points = state.lats.indices.map { TrackPoint(LatLng(state.lats[it], state.lngs[it]), times?.get(it)) },
            startAtEpochMs = startAt,
            speed = speed,
            updateIntervalMs = state.updateIntervalMs ?: DEFAULT_UPDATE_INTERVAL_MS,
            leadInSpeedKmh = state.leadInSpeedKmh,
        ).also { RouteTimeline(it) }
    }

    private fun decodeSpeed(state: RouteState): SpeedSource? =
        when (state.speedSource ?: CONSTANT) {
            CONSTANT -> SpeedSource.Constant(state.speedKmh)
            TIMESTAMPS -> SpeedSource.FromTimestamps
            TIMESTAMPS_SCALED -> state.speedFactor?.let { SpeedSource.TimestampsScaled(it) }
            else -> null
        }

    fun pausedTotalMs(state: RouteState): Long = (state.pausedTotalMs ?: 0L).coerceAtLeast(0L)
}
