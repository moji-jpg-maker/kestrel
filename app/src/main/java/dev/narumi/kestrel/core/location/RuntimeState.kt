package dev.narumi.kestrel.core.location

import dev.narumi.kestrel.core.routeplan.PlaybackPlan

/**
 * Snapshot of what [LocationService] is currently doing, surfaced to the UI as a [kotlinx.coroutines.flow.StateFlow].
 *
 * Emitted only on real state transitions (start / stop / pause / resume / single / route-finish /
 * restoreState / settings update), never from the per-tick movement loop, so UI consumers do not recompose every
 * second.
 */
sealed interface RuntimeState {
    data object Idle : RuntimeState

    data class Single(
        val point: LatLng,
    ) : RuntimeState

    data class Route(
        val waypoints: List<LatLng>,
        val speedKmh: Double,
        val mode: MovementEngine.Mode,
        val paused: Boolean,
        // Assigned by LocationService on start/restore, stable until route replacement, and never persisted.
        val playbackId: String,
    ) : RuntimeState

    /**
     * A route that starts at a set time. [phase] is Armed until the start time, then Moving; arrival
     * is not a phase because playback then becomes [Single] at the destination, like a finished [Route].
     * [speedKmh] is the overall average, for display.
     */
    data class Scheduled(
        val plan: PlaybackPlan,
        val speedKmh: Double,
        val phase: SchedulePhase,
        val paused: Boolean,
        // Assigned when the plan is armed or restored, and never persisted.
        val playbackId: String,
    ) : RuntimeState
}

enum class SchedulePhase { Armed, Moving }
