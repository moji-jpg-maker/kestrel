package dev.narumi.kestrel.core.routeplan

import dev.narumi.kestrel.core.data.RouteState
import dev.narumi.kestrel.core.location.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduledPlanCodecTest {
    private val a = LatLng(52.37, 4.90)
    private val b = LatLng(52.38, 4.91)

    @Test
    fun invalidPersistedSchedulesFailClosed() {
        val valid =
            RouteState(
                lats = doubleArrayOf(1.0, 2.0),
                lngs = doubleArrayOf(3.0, 4.0),
                speedKmh = 20.0,
                startAtEpochMs = 0L,
            )
        val broken =
            listOf(
                valid.copy(speedKmh = 0.0),
                valid.copy(speedSource = "TimestampsScaled", speedFactor = 0.0, timesMs = longArrayOf(0L, 1_000L)),
                valid.copy(speedSource = "Timestamps", timesMs = longArrayOf(1_000L, 0L)),
                valid.copy(speedSource = "Timestamps", timesMs = longArrayOf(0L, 0L)),
                valid.copy(sourceLat = 5.0),
                valid.copy(updateIntervalMs = 1L),
            )
        for (state in broken) assertNull(ScheduledPlanCodec.fromRouteState(state))
    }

    @Test
    fun aConstantSpeedPlanRoundTrips() {
        val plan =
            PlaybackPlan(
                name = "Commute",
                source = LatLng(52.36, 4.89),
                points = listOf(TrackPoint(a), TrackPoint(b)),
                startAtEpochMs = 1_700_000_000_000L,
                speed = SpeedSource.Constant(kmh = 42.0),
                updateIntervalMs = 500L,
            )

        val state = ScheduledPlanCodec.toRouteState(plan, averageSpeedKmh = 42.0, pausedTotalMs = 3_000L)
        val restored = ScheduledPlanCodec.fromRouteState(state)

        assertEquals(plan, restored)
        assertEquals(3_000L, ScheduledPlanCodec.pausedTotalMs(state))
        assertNull(state.timesMs)
    }

    @Test
    fun aTimestampPlanRoundTripsAndCarriesTimes() {
        val plan =
            PlaybackPlan(
                points = listOf(TrackPoint(a, 0L), TrackPoint(b, 10_000L)),
                startAtEpochMs = 1_700_000_000_000L,
                speed = SpeedSource.FromTimestamps,
            )

        val state = ScheduledPlanCodec.toRouteState(plan, averageSpeedKmh = 10.0, pausedTotalMs = 0L)
        val restored = ScheduledPlanCodec.fromRouteState(state)

        assertEquals(plan, restored)
        assertEquals(listOf(0L, 10_000L), state.timesMs!!.toList())
    }

    @Test
    fun aScaledTimestampPlanRoundTrips() {
        val plan =
            PlaybackPlan(
                points = listOf(TrackPoint(a, 0L), TrackPoint(b, 10_000L)),
                startAtEpochMs = 0L,
                speed = SpeedSource.TimestampsScaled(factor = 2.5),
                leadInSpeedKmh = 30.0,
            )

        val restored = ScheduledPlanCodec.fromRouteState(ScheduledPlanCodec.toRouteState(plan, 10.0, 0L))

        assertEquals(plan, restored)
    }

    @Test
    fun anOrdinaryRouteStateIsNotATimeline() {
        val ordinary = RouteState(lats = doubleArrayOf(1.0, 2.0), lngs = doubleArrayOf(3.0, 4.0), speedKmh = 20.0)

        assertNull(ScheduledPlanCodec.fromRouteState(ordinary))
    }

    @Test
    fun aTimestampModeWithoutStoredTimesFailsClosed() {
        val broken = RouteState(lats = doubleArrayOf(1.0, 2.0), lngs = doubleArrayOf(3.0, 4.0), speedKmh = 1.0, startAtEpochMs = 0L, speedSource = "Timestamps", timesMs = null)

        assertNull(ScheduledPlanCodec.fromRouteState(broken))
    }

    @Test
    fun mismatchedArrayLengthsFailClosedInsteadOfCrashing() {
        val mismatched =
            RouteState(lats = doubleArrayOf(1.0, 2.0, 3.0), lngs = doubleArrayOf(3.0, 4.0), speedKmh = 1.0, startAtEpochMs = 0L)
        val mismatchedTimes =
            RouteState(
                lats = doubleArrayOf(1.0, 2.0),
                lngs = doubleArrayOf(3.0, 4.0),
                speedKmh = 1.0,
                startAtEpochMs = 0L,
                speedSource = "Timestamps",
                timesMs = longArrayOf(0L),
            )

        assertNull(ScheduledPlanCodec.fromRouteState(mismatched))
        assertNull(ScheduledPlanCodec.fromRouteState(mismatchedTimes))
    }

    @Test
    fun zeroOrNegativeSpeedFallsBackToAPositivePlaceholderForOlderClientsToReplay() {
        val plan =
            PlaybackPlan(
                points = listOf(TrackPoint(a, 0L), TrackPoint(b, 10_000L)),
                startAtEpochMs = 0L,
                speed = SpeedSource.FromTimestamps,
            )

        val state = ScheduledPlanCodec.toRouteState(plan, averageSpeedKmh = 0.0, pausedTotalMs = 0L)

        assertTrue(state.speedKmh > 0.0)
    }
}
