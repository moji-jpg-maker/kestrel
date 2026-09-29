package dev.narumi.kestrel.core.routeplan

import dev.narumi.kestrel.core.location.LatLng
import dev.narumi.kestrel.core.routeplan.routeimport.ImportedRoute

/** Which speed model the schedule form has selected. */
enum class SpeedMode { Constant, FromTimestamps, TimestampsScaled }

const val DEFAULT_SCHEDULE_SPEED_KMH = 20.0

/** How far in the past a start time may be before the form calls it a mistake. */
const val START_IN_PAST_TOLERANCE_MS = 60_000L

/**
 * The state of the schedule form: everything the user can set before a [PlaybackPlan] exists.
 * Pure data, so it can be tested without Android.
 *
 * [startAtEpochMs] null means "start now". The `*Touched` flags record that the user changed a
 * value by hand, so importing another file afterwards does not overwrite it with the file's hint.
 */
data class ScheduleDraft(
    val route: ImportedRoute? = null,
    val source: LatLng? = null,
    val startAtEpochMs: Long? = null,
    val speedMode: SpeedMode = SpeedMode.Constant,
    val speedKmh: Double = DEFAULT_SCHEDULE_SPEED_KMH,
    val timestampFactor: Double = 1.0,
    val leadInSpeedKmh: Double = DEFAULT_SCHEDULE_SPEED_KMH,
    val updateIntervalMs: Long = DEFAULT_UPDATE_INTERVAL_MS,
    val sourceTouched: Boolean = false,
    val startTouched: Boolean = false,
    val speedTouched: Boolean = false,
    val speedModeTouched: Boolean = false,
    val intervalTouched: Boolean = false,
) {
    val hasTimestamps: Boolean get() = route?.hasTimestamps == true

    /**
     * Replaces the route with a freshly imported one, keeping anything the user already set.
     * When [nowMs] is given, a start-time hint that is already past is ignored: a recorded track's
     * timestamps describe when it was driven, not when the user wants to replay it.
     */
    fun withImportedRoute(
        imported: ImportedRoute,
        nowMs: Long? = null,
    ): ScheduleDraft {
        val hints = imported.hints
        val nextMode =
            when {
                speedModeTouched && (speedMode == SpeedMode.Constant || imported.hasTimestamps) -> speedMode
                imported.hasTimestamps -> SpeedMode.FromTimestamps
                else -> SpeedMode.Constant
            }
        return copy(
            route = imported,
            source = if (sourceTouched) source else hints.source ?: source,
            startAtEpochMs =
                if (startTouched) {
                    startAtEpochMs
                } else {
                    hints.startAtEpochMs?.takeUnless { nowMs != null && it < nowMs - START_IN_PAST_TOLERANCE_MS } ?: startAtEpochMs
                },
            speedKmh = if (speedTouched) speedKmh else hints.speedKmh?.takeIf { it.isFinite() && it > 0.0 } ?: speedKmh,
            updateIntervalMs =
                if (intervalTouched) {
                    updateIntervalMs
                } else {
                    hints.updateIntervalMs?.takeIf { it in MIN_UPDATE_INTERVAL_MS..MAX_UPDATE_INTERVAL_MS } ?: updateIntervalMs
                },
            speedMode = nextMode,
        )
    }

    fun withSource(value: LatLng?) = copy(source = value, sourceTouched = true)

    fun withStart(epochMs: Long?) = copy(startAtEpochMs = epochMs, startTouched = true)

    fun withSpeedMode(mode: SpeedMode) = copy(speedMode = mode, speedModeTouched = true)

    fun withSpeedKmh(value: Double) = copy(speedKmh = value, speedTouched = true)

    fun withTimestampFactor(value: Double) = copy(timestampFactor = value, speedTouched = true)

    fun withLeadInSpeedKmh(value: Double) = copy(leadInSpeedKmh = value, speedTouched = true)

    fun withUpdateIntervalMs(value: Long) = copy(updateIntervalMs = value, intervalTouched = true)
}

sealed interface PlanBuildResult {
    data class Ready(
        val plan: PlaybackPlan,
    ) : PlanBuildResult

    data class Invalid(
        val message: String,
    ) : PlanBuildResult
}

/** Turns the form into a [PlaybackPlan], or says what is wrong with it. [nowMs] is the wall clock. */
fun buildPlaybackPlan(
    draft: ScheduleDraft,
    nowMs: Long,
): PlanBuildResult {
    val error = validateScheduleDraft(draft, nowMs)
    if (error != null) return PlanBuildResult.Invalid(error)
    val route = requireNotNull(draft.route)
    val speed: SpeedSource =
        when (draft.speedMode) {
            SpeedMode.Constant -> SpeedSource.Constant(draft.speedKmh)
            SpeedMode.FromTimestamps -> SpeedSource.FromTimestamps
            SpeedMode.TimestampsScaled -> SpeedSource.TimestampsScaled(draft.timestampFactor)
        }
    val timestampBased = draft.speedMode != SpeedMode.Constant
    return try {
        val plan =
            PlaybackPlan(
                name = route.name,
                source = draft.source,
                points = route.points,
                startAtEpochMs = draft.startAtEpochMs ?: nowMs,
                speed = speed,
                updateIntervalMs = draft.updateIntervalMs,
                leadInSpeedKmh = if (timestampBased) draft.leadInSpeedKmh.takeIf { it.isPositiveFinite() } else null,
            )
        // The service builds the same timeline; failing here keeps an unplayable plan off the Schedule button.
        RouteTimeline(plan)
        PlanBuildResult.Ready(plan)
    } catch (e: IllegalArgumentException) {
        PlanBuildResult.Invalid(e.message ?: "The plan is not valid.")
    }
}

private fun validateScheduleDraft(
    draft: ScheduleDraft,
    nowMs: Long,
): String? {
    val route = draft.route
    return when {
        route == null -> "Import a route first."
        route.points.size < 2 -> "A route needs at least 2 points."
        draft.startAtEpochMs != null && draft.startAtEpochMs < nowMs - START_IN_PAST_TOLERANCE_MS ->
            "The start time is in the past."
        draft.updateIntervalMs !in MIN_UPDATE_INTERVAL_MS..MAX_UPDATE_INTERVAL_MS ->
            "The update interval must be between $MIN_UPDATE_INTERVAL_MS and $MAX_UPDATE_INTERVAL_MS ms."
        draft.speedMode == SpeedMode.Constant && !draft.speedKmh.isPositiveFinite() -> "Speed must be greater than 0 km/h."
        draft.speedMode != SpeedMode.Constant && !route.hasTimestamps -> "This route has no timestamps to replay."
        draft.speedMode == SpeedMode.TimestampsScaled && !draft.timestampFactor.isPositiveFinite() ->
            "The speed factor must be greater than 0."
        draft.speedMode != SpeedMode.Constant && draft.source != null && draft.source != route.points.first().point &&
            !draft.leadInSpeedKmh.isPositiveFinite() -> "Set a speed for the leg from the source to the route."
        else -> null
    }
}

private fun Double.isPositiveFinite(): Boolean = isFinite() && this > 0.0
