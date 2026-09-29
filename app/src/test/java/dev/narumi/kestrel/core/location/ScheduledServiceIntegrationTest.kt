package dev.narumi.kestrel.core.location

import dev.narumi.kestrel.core.routeplan.PlaybackPlan
import dev.narumi.kestrel.core.routeplan.SpeedSource
import dev.narumi.kestrel.core.routeplan.TrackPoint
import dev.narumi.kestrel.feature.map.RunState
import dev.narumi.kestrel.feature.map.reconcileMapRender
import dev.narumi.kestrel.feature.map.runtimeMatchesDraft
import dev.narumi.kestrel.ui.components.PlaybackBarAction
import dev.narumi.kestrel.ui.components.playbackBarPresentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ScheduledServiceIntegrationTest {
    private val plan =
        PlaybackPlan(
            source = LatLng(0.0, -0.001),
            points = listOf(TrackPoint(LatLng(0.0, 0.0)), TrackPoint(LatLng(0.0, 0.001))),
            startAtEpochMs = 30_000L,
            speed = SpeedSource.Constant(36.0),
        )

    @Test
    fun handoffOnlyConsumesTheLatestMatchingTokenOnce() {
        val oldToken = ScheduledPlanHandoff.put(plan)
        val replacement = plan.copy(name = "Replacement")
        val newToken = ScheduledPlanHandoff.put(replacement)

        assertNull(ScheduledPlanHandoff.take(oldToken))
        assertNull(ScheduledPlanHandoff.take(null))
        assertEquals(replacement, ScheduledPlanHandoff.take(newToken))
        assertNull(ScheduledPlanHandoff.take(newToken))
    }

    @Test
    fun replacedSessionsCannotPublishMovingArrivalOrFailure() {
        var current = true
        val events = mutableListOf<String>()
        val callbacks =
            ScheduledPlaybackCallbacks(
                lock = Any(),
                isCurrent = { current },
                moving = { events += "moving" },
                arrived = { events += "arrived" },
                failed = { events += "failed" },
            )
        callbacks.onMoving()
        current = false
        callbacks.onMoving()
        callbacks.onArrived(plan.points.last().point)
        callbacks.onFailed(IllegalStateException("old session failed"))

        assertEquals(listOf("moving"), events)
    }

    @Test
    fun armedSchedulesExposeStopOnlyAndUseTheScheduledGeometry() {
        val runtime = RuntimeState.Scheduled(plan, 36.0, SchedulePhase.Armed, false, "schedule")
        val presentation = requireNotNull(playbackBarPresentation(runtime))
        val render = reconcileMapRender(runtime, emptyList(), 1.0, MovementEngine.Mode.Loop)

        assertEquals("Route scheduled", presentation.title)
        assertNull(presentation.primaryAction)
        assertEquals(RunState.ScheduledArmed, render.runState)
        assertEquals(listOf(plan.source) + plan.points.map { it.point }, render.waypoints)
        assertEquals(MovementEngine.Mode.Once, render.routeMode)
        assertFalse(runtimeMatchesDraft(runtime, plan.points.map { it.point }, 36.0, MovementEngine.Mode.Once))
    }

    @Test
    fun movingSchedulesExposePauseAndResumeFromRuntimeState() {
        val moving = RuntimeState.Scheduled(plan, 36.0, SchedulePhase.Moving, false, "schedule")
        val paused = moving.copy(paused = true)

        assertEquals(PlaybackBarAction.Pause, playbackBarPresentation(moving)?.primaryAction)
        assertEquals(PlaybackBarAction.Resume, playbackBarPresentation(paused)?.primaryAction)
        assertEquals(RunState.RoutePaused, reconcileMapRender(paused, emptyList(), 1.0, MovementEngine.Mode.Loop).runState)
    }
}
