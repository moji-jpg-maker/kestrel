package dev.narumi.kestrel.ui.components

import dev.narumi.kestrel.core.location.LatLng
import dev.narumi.kestrel.core.location.RuntimeState
import dev.narumi.kestrel.core.location.SchedulePhase
import dev.narumi.kestrel.core.routeplan.PlaybackPlan
import dev.narumi.kestrel.core.routeplan.SpeedSource
import dev.narumi.kestrel.core.routeplan.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackStatusBarScheduledTest {
    private fun scheduled(
        phase: SchedulePhase,
        paused: Boolean = false,
    ) = RuntimeState.Scheduled(
        plan =
            PlaybackPlan(
                points = listOf(TrackPoint(LatLng(0.0, 0.0)), TrackPoint(LatLng(0.0, 0.01))),
                startAtEpochMs = 1_800_000_000_000L,
                speed = SpeedSource.Constant(20.0),
            ),
        speedKmh = 20.0,
        phase = phase,
        paused = paused,
        playbackId = "sched",
    )

    @Test
    fun armedPlanShowsItsStartAndOffersStopOnly() {
        val bar = requireNotNull(playbackBarPresentation(scheduled(SchedulePhase.Armed)))
        assertEquals("Route scheduled", bar.title)
        assertTrue(bar.details, bar.details.startsWith("Starts "))
        assertNull(bar.primaryAction)
    }

    @Test
    fun movingPlanShowsSpeedAndPauseResumeFollowsState() {
        val moving = requireNotNull(playbackBarPresentation(scheduled(SchedulePhase.Moving)))
        assertEquals("Route playing", moving.title)
        assertEquals("2 points · 20 km/h", moving.details)

        val paused = requireNotNull(playbackBarPresentation(scheduled(SchedulePhase.Moving, paused = true)))
        assertEquals("Route paused", paused.title)
        assertEquals(PlaybackBarAction.Resume, paused.primaryAction)
    }
}
