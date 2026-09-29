package dev.narumi.kestrel.core.location

import dev.narumi.kestrel.core.data.MockState
import dev.narumi.kestrel.core.data.RouteState
import dev.narumi.kestrel.core.routeplan.AndroidTimeSource
import dev.narumi.kestrel.core.routeplan.LocationSink
import dev.narumi.kestrel.core.routeplan.PlaybackClock
import dev.narumi.kestrel.core.routeplan.PlaybackPlan
import dev.narumi.kestrel.core.routeplan.RouteTimeline
import dev.narumi.kestrel.core.routeplan.ScheduledPlanCodec
import dev.narumi.kestrel.core.routeplan.ScheduledPlaybackRunner
import java.util.UUID

/** The scheduled playback [LocationService] is currently running. Identity is used as a token. */
internal class ActiveScheduled(
    val plan: PlaybackPlan,
    val timeline: RouteTimeline,
    val clock: PlaybackClock,
    // The start request to report a late failure against; null when restored after a restart.
    val requestId: String?,
    val playbackId: String = UUID.randomUUID().toString(),
) {
    @Volatile
    var phase: SchedulePhase = SchedulePhase.Armed

    fun toRuntimeState(paused: Boolean): RuntimeState.Scheduled =
        RuntimeState.Scheduled(
            plan = plan,
            speedKmh = timeline.averageSpeedKmh,
            phase = phase,
            paused = paused,
            playbackId = playbackId,
        )

    fun runner(
        sink: LocationSink,
        listener: ScheduledPlaybackRunner.Listener,
    ): ScheduledPlaybackRunner = ScheduledPlaybackRunner(plan, timeline, clock, sink, listener)

    fun toMockState(): MockState =
        MockState(
            mode = MockState.Mode.Route,
            route = ScheduledPlanCodec.toRouteState(plan, timeline.averageSpeedKmh, clock.pausedTotalMs()),
        )

    fun failureResult(error: Exception): LocationOperationResult? =
        requestId?.let {
            LocationOperationResult(
                requestId = it,
                action = LocationOperationAction.StartScheduled,
                succeeded = false,
                message = mockOperationErrorMessage(error, previousMockActive = false),
            )
        }

    companion object {
        fun restore(
            route: RouteState,
        ): ActiveScheduled? {
            val plan = ScheduledPlanCodec.fromRouteState(route) ?: return null
            return runCatching { create(plan, null, ScheduledPlanCodec.pausedTotalMs(route)) }.getOrNull()
        }

        fun create(
            plan: PlaybackPlan,
            requestId: String?,
            pausedTotalMs: Long,
        ): ActiveScheduled =
            ActiveScheduled(
                plan = plan,
                timeline = RouteTimeline(plan),
                clock = PlaybackClock(AndroidTimeSource, plan.startAtEpochMs, pausedTotalMs),
                requestId = requestId,
            )
    }
}
