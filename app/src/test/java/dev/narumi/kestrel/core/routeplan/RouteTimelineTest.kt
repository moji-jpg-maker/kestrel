package dev.narumi.kestrel.core.routeplan

import dev.narumi.kestrel.core.location.LatLng
import dev.narumi.kestrel.core.location.haversineMeters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RouteTimelineTest {
    private val a = LatLng(0.0, 0.0)
    private val b = LatLng(0.0, 0.001)
    private val c = LatLng(0.0, 0.002)
    private val ab = haversineMeters(a, b)
    private val bc = haversineMeters(b, c)

    private fun plan(
        points: List<TrackPoint>,
        speed: SpeedSource = SpeedSource.Constant(kmh = 36.0),
        source: LatLng? = null,
        leadInSpeedKmh: Double? = null,
    ) = PlaybackPlan(
        source = source,
        points = points,
        startAtEpochMs = 0L,
        speed = speed,
        leadInSpeedKmh = leadInSpeedKmh,
    )

    private fun untimed(vararg p: LatLng) = p.map { TrackPoint(it) }

    private fun timed(vararg p: Pair<LatLng, Long>) = p.map { TrackPoint(it.first, it.second) }

    @Test
    fun constantSpeedDurationIsDistanceOverSpeed() {
        val timeline = RouteTimeline(plan(untimed(a, b, c)))

        assertEquals(ab + bc, timeline.totalMeters, 1e-6)
        assertEquals((ab + bc) / 10.0, timeline.durationSeconds, 1e-6)
    }

    @Test
    fun midpointOfConstantSpeedRouteIsHalfway() {
        val timeline = RouteTimeline(plan(untimed(a, b)))

        val sample = timeline.positionAt(timeline.durationSeconds / 2)

        assertEquals(0.0005, sample.point.lng, 1e-9)
        assertEquals(10.0, sample.speedMps, 1e-9)
        assertEquals(90.0, sample.bearingDeg, 1e-6)
    }

    @Test
    fun holdsAtStartBeforeAndAtZeroElapsed() {
        val timeline = RouteTimeline(plan(untimed(a, b)))

        val before = timeline.positionAt(-30.0)
        val atZero = timeline.positionAt(0.0)
        val nan = timeline.positionAt(Double.NaN)

        for (sample in listOf(before, atZero, nan)) {
            assertEquals(a.lng, sample.point.lng, 1e-12)
            assertEquals(0.0, sample.speedMps, 1e-12)
        }
    }

    @Test
    fun holdsAtDestinationAfterTheEnd() {
        val timeline = RouteTimeline(plan(untimed(a, b)))

        val sample = timeline.positionAt(timeline.durationSeconds + 100)

        assertEquals(b.lng, sample.point.lng, 1e-12)
        assertEquals(0.0, sample.speedMps, 1e-12)
    }

    @Test
    fun sourceAwayFromRouteAddsALeadInLeg() {
        val source = LatLng(0.0, -0.001)
        val timeline = RouteTimeline(plan(untimed(a, b), source = source))

        val holdSample = timeline.positionAt(0.0)
        val atRouteStart = timeline.positionAt(haversineMeters(source, a) / 10.0)

        assertEquals(source.lng, holdSample.point.lng, 1e-12)
        assertEquals(a.lng, atRouteStart.point.lng, 1e-7)
        assertEquals(haversineMeters(source, a) + ab, timeline.totalMeters, 1e-6)
    }

    @Test
    fun sourceOnTheFirstPointAddsNoLeadIn() {
        val timeline = RouteTimeline(plan(untimed(a, b), source = a))

        assertEquals(ab, timeline.totalMeters, 1e-6)
    }

    @Test
    fun timestampsDriveLegDurations() {
        val timeline =
            RouteTimeline(
                plan(
                    timed(a to 5_000L, b to 15_000L, c to 35_000L),
                    speed = SpeedSource.FromTimestamps,
                ),
            )

        assertEquals(30.0, timeline.durationSeconds, 1e-9)
        assertEquals(ab / 10.0, timeline.positionAt(5.0).speedMps, 1e-9)
        assertEquals(bc / 20.0, timeline.positionAt(20.0).speedMps, 1e-9)
        assertEquals(b.lng, timeline.positionAt(10.0).point.lng, 1e-9)
    }

    @Test
    fun scaledTimestampsShortenTheRoute() {
        val timeline =
            RouteTimeline(
                plan(
                    timed(a to 0L, b to 20_000L),
                    speed = SpeedSource.TimestampsScaled(factor = 2.0),
                ),
            )

        assertEquals(10.0, timeline.durationSeconds, 1e-9)
    }

    @Test
    fun timestampGapBetweenIdenticalPointsIsADwell() {
        val timeline =
            RouteTimeline(
                plan(
                    timed(a to 0L, b to 10_000L, b to 70_000L, c to 80_000L),
                    speed = SpeedSource.FromTimestamps,
                ),
            )

        val dwell = timeline.positionAt(40.0)

        assertEquals(b.lng, dwell.point.lng, 1e-12)
        assertEquals(0.0, dwell.speedMps, 1e-12)
        assertEquals(90.0, dwell.bearingDeg, 1e-6)
        assertEquals(80.0, timeline.durationSeconds, 1e-9)
    }

    @Test
    fun duplicateConstantSpeedPointsAreSkipped() {
        val timeline = RouteTimeline(plan(untimed(a, a, b)))

        assertEquals(ab, timeline.totalMeters, 1e-6)
        assertEquals(ab / 10.0, timeline.durationSeconds, 1e-9)
    }

    @Test
    fun longSegmentFollowsTheGreatCircle() {
        val timeline = RouteTimeline(plan(untimed(LatLng(0.0, 0.0), LatLng(0.0, 90.0))))

        val mid = timeline.positionAt(timeline.durationSeconds / 2)

        assertEquals(45.0, mid.point.lng, 1e-6)
    }

    @Test
    fun timestampModeRequiresTimestampsOnEveryPoint() {
        val points = listOf(TrackPoint(a, 0L), TrackPoint(b, null))

        assertThrows(IllegalArgumentException::class.java) {
            RouteTimeline(plan(points, speed = SpeedSource.FromTimestamps))
        }
    }

    @Test
    fun decreasingTimestampsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            RouteTimeline(plan(timed(a to 10_000L, b to 5_000L), speed = SpeedSource.FromTimestamps))
        }
    }

    @Test
    fun movingInZeroTimeIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            RouteTimeline(plan(timed(a to 1_000L, b to 1_000L), speed = SpeedSource.FromTimestamps))
        }
    }

    @Test
    fun leadInWithTimestampsNeedsALeadInSpeed() {
        val points = timed(a to 0L, b to 10_000L)
        val source = LatLng(0.0, -0.001)

        assertThrows(IllegalArgumentException::class.java) {
            RouteTimeline(plan(points, speed = SpeedSource.FromTimestamps, source = source))
        }
        val timeline =
            RouteTimeline(
                plan(points, speed = SpeedSource.FromTimestamps, source = source, leadInSpeedKmh = 36.0),
            )
        assertEquals(haversineMeters(source, a) / 10.0 + 10.0, timeline.durationSeconds, 1e-6)
    }

    @Test
    fun planRejectsBadInput() {
        assertThrows(IllegalArgumentException::class.java) { plan(untimed(a)) }
        assertThrows(IllegalArgumentException::class.java) { plan(untimed(a, b), SpeedSource.Constant(0.0)) }
        assertThrows(IllegalArgumentException::class.java) { plan(untimed(a, LatLng(91.0, 0.0))) }
        assertThrows(IllegalArgumentException::class.java) {
            PlaybackPlan(points = untimed(a, b), startAtEpochMs = 0L, speed = SpeedSource.Constant(10.0), updateIntervalMs = 50L)
        }
    }
}
