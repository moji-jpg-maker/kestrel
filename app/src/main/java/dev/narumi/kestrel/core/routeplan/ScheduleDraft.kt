package dev.narumi.kestrel.core.routeplan

import dev.narumi.kestrel.core.location.LatLng
import dev.narumi.kestrel.core.routeplan.routeimport.ImportedRoute

/** Which speed model the schedule form has selected. */
enum class SpeedMode { Constant, FromTimestamps, TimestampsScaled }

const val DEFAULT_SCHEDULE_SPEED_KMH = 20.0

/** How far in the past a start time may be before the form calls it a mistake. */
const val START_IN_PAST_TOLERANCE_MS = 60_000L

/** A single update that moves the device further than this is flagged as a big jump. */
const val MAX_COMFORTABLE_STEP_METERS = 50.0

private const val KMH_TO_MPS = 1.0 / 3.6
private const val MS_PER_SECOND = 1000.0

/**
 * The state of the schedule form: everything the user can set before a [PlaybackPlan] exists.
 * Pure data, so it can be tested without Android.
 *
 * [startAtEpochMs] null means "start now". [joinInProgress] is the user's answer to a start time that
 * already passed: keep the original start so the device joins the route where it would be by now,
 * instead of starting from the beginning. The `*Touched` flags record that the user changed a
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
    val joinInProgress: Boolean = false,
) {
    val hasTimestamps: Boolean get() = route?.hasTimestamps == true

    /** True when the chosen start is further in the past than the form tolerates. */
    fun startIsPast(nowMs: Long): Boolean = startAtEpochMs != null && startAtEpochMs < nowMs - START_IN_PAST_TOLERANCE_MS

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
        val nextStartAtEpochMs =
            if (startTouched) {
                startAtEpochMs
            } else {
                hints.startAtEpochMs?.takeUnless { nowMs != null && it < nowMs - START_IN_PAST_TOLERANCE_MS } ?: startAtEpochMs
            }
        val nextMode =
            when {
                speedModeTouched && (speedMode == SpeedMode.Constant || imported.hasTimestamps) -> speedMode
                imported.hasTimestamps -> SpeedMode.FromTimestamps
                else -> SpeedMode.Constant
            }
        return copy(
            route = imported,
            source = if (sourceTouched) source else hints.source ?: source,
            startAtEpochMs = nextStartAtEpochMs,
            joinInProgress = joinInProgress && nextStartAtEpochMs == startAtEpochMs,
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

    fun withStart(epochMs: Long?) = copy(startAtEpochMs = epochMs, startTouched = true, joinInProgress = false)

    /** Answers a past start with "join at current position". Only meaningful while [startIsPast]. */
    fun withJoinInProgress() = copy(joinInProgress = true)

    fun withSpeedMode(mode: SpeedMode) = copy(speedMode = mode, speedModeTouched = true)

    fun withSpeedKmh(value: Double) = copy(speedKmh = value, speedTouched = true)

    // No file hint sets these two, so editing them must not block a later import's constant-speed hint.
    fun withTimestampFactor(value: Double) = copy(timestampFactor = value)

    fun withLeadInSpeedKmh(value: Double) = copy(leadInSpeedKmh = value)

    fun withUpdateIntervalMs(value: Long) = copy(updateIntervalMs = value, intervalTouched = true)
}

sealed interface PlanBuildResult {
    /** [largeStepMeters] is set when one update moves the device further than [MAX_COMFORTABLE_STEP_METERS]. */
    data class Ready(
        val plan: PlaybackPlan,
        val timeline: RouteTimeline,
        val largeStepMeters: Double? = null,
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
        val timeline = RouteTimeline(plan)
        val finishesAtMs = plan.startAtEpochMs + (timeline.durationSeconds * MS_PER_SECOND).toLong()
        if (finishesAtMs <= nowMs) {
            PlanBuildResult.Invalid("The route would already be finished at that start time. Choose Start now instead.")
        } else {
            PlanBuildResult.Ready(plan, timeline, largeStepMeters(plan, timeline))
        }
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
        draft.startIsPast(nowMs) && !draft.joinInProgress -> "The start time is in the past."
        draft.updateIntervalMs !in MIN_UPDATE_INTERVAL_MS..MAX_UPDATE_INTERVAL_MS ->
            "The update interval must be between $MIN_UPDATE_INTERVAL_MS and $MAX_UPDATE_INTERVAL_MS ms."
        draft.speedMode == SpeedMode.Constant && !draft.speedKmh.isPositiveFinite() -> "Speed must be greater than 0 km/h."
        draft.speedMode != SpeedMode.Constant && !route.hasTimestamps -> "This route has no timestamps to replay."
        draft.speedMode == SpeedMode.TimestampsScaled && !draft.timestampFactor.isPositiveFinite() ->
            "The speed factor must be greater than 0."
        draft.speedMode != SpeedMode.Constant &&
            draft.source != null &&
            draft.source != route.points.first().point &&
            !draft.leadInSpeedKmh.isPositiveFinite() -> "Set a speed for the leg from the source to the route."
        else -> null
    }
}

/**
 * How far one update moves the device, when that exceeds [MAX_COMFORTABLE_STEP_METERS]; otherwise null.
 * Timestamp-based plans have no single speed, so the route's average (or the lead-in leg's speed, if
 * larger) stands in for it.
 */
private fun largeStepMeters(
    plan: PlaybackPlan,
    timeline: RouteTimeline,
): Double? {
    val hasLeadIn = plan.source != null && plan.source != plan.points.first().point
    val kmh =
        when (val speed = plan.speed) {
            is SpeedSource.Constant -> speed.kmh
            else -> maxOf(timeline.averageSpeedKmh, if (hasLeadIn) plan.leadInSpeedKmh ?: 0.0 else 0.0)
        }
    val meters = kmh * KMH_TO_MPS * plan.updateIntervalMs / MS_PER_SECOND
    return meters.takeIf { it > MAX_COMFORTABLE_STEP_METERS }
}

private fun Double.isPositiveFinite(): Boolean = isFinite() && this > 0.0
