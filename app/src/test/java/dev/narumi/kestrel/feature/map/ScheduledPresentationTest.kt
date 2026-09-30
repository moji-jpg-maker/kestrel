package dev.narumi.kestrel.feature.map

import dev.narumi.kestrel.core.location.LatLng
import dev.narumi.kestrel.core.location.MovementEngine
import dev.narumi.kestrel.core.location.RuntimeState
import dev.narumi.kestrel.core.location.SchedulePhase
import dev.narumi.kestrel.core.routeplan.PlaybackPlan
import dev.narumi.kestrel.core.routeplan.SpeedSource
import dev.narumi.kestrel.core.routeplan.TrackPoint
import dev.narumi.kestrel.core.routeplan.scheduledStatusTitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduledPresentationTest {
    private val a = LatLng(52.0, 4.0)
    private val b = LatLng(52.01, 4.0)
    private val src = LatLng(52.0, 3.99)

    private fun scheduled(
        phase: SchedulePhase = SchedulePhase.Armed,
        paused: Boolean = false,
        source: LatLng? = null,
        name: String? = "Commute",
    ) = RuntimeState.Scheduled(
        plan =
            PlaybackPlan(
                name = name,
                source = source,
                points = listOf(TrackPoint(a), TrackPoint(b)),
                startAtEpochMs = 1_800_000_000_000L,
                speed = SpeedSource.Constant(30.0),
            ),
        speedKmh = 30.0,
        phase = phase,
        paused = paused,
        playbackId = "sched-1",
    )

    @Test
    fun scheduledRuntimeIsAuthoritativeAndPausable() {
        val drafts = listOf(LatLng(1.0, 1.0), LatLng(2.0, 2.0))
        val render = reconcileMapRender(scheduled(), drafts, 12.5, MovementEngine.Mode.Loop)
        assertEquals(RunState.ScheduledArmed, render.runState)
        assertEquals(listOf(a, b), render.waypoints)
        assertEquals(30.0, render.speedKmh, 0.0)
        assertTrue(render.scheduled)

        val paused = reconcileMapRender(scheduled(phase = SchedulePhase.Moving, paused = true), drafts, 12.5, MovementEngine.Mode.Loop)
        assertEquals(RunState.RoutePaused, paused.runState)
    }

    @Test
    fun otherRuntimesAreNotMarkedScheduled() {
        assertFalse(reconcileMapRender(RuntimeState.Idle, emptyList(), 10.0, MovementEngine.Mode.Once).scheduled)
    }

    @Test
    fun sourceComesFirstInTheDrawnRoute() {
        assertEquals(listOf(src, a, b), scheduledRoutePoints(scheduled(source = src)))
    }

    @Test
    fun statusTitleFollowsPhaseAndPause() {
        assertEquals("Route scheduled", scheduledStatusTitle(scheduled()))
        assertEquals("Scheduled route paused", scheduledStatusTitle(scheduled(paused = true)))
        assertEquals("Route playing", scheduledStatusTitle(scheduled(phase = SchedulePhase.Moving)))
        assertEquals("Route paused", scheduledStatusTitle(scheduled(phase = SchedulePhase.Moving, paused = true)))
    }

    @Test
    fun detailsShowTheStartOnlyWhileArmed() {
        val armed = scheduledStatusDetails(scheduled(), nowMs = 1_800_000_000_000L - 60_000L)
        assertTrue(armed, armed.startsWith("Starts "))
        assertTrue(armed, armed.endsWith("Commute · 2 points · 30 km/h"))

        val moving = scheduledStatusDetails(scheduled(phase = SchedulePhase.Moving, name = null))
        assertEquals("2 points · 30 km/h", moving)
    }

    @Test
    fun summaryAndDraftMatchingTreatScheduledAsItsOwnThing() {
        assertTrue(currentMockSummary(scheduled()).startsWith("Scheduled · "))
        assertFalse(runtimeMatchesDraft(scheduled(), listOf(a, b), 30.0, MovementEngine.Mode.Once))
    }
}
