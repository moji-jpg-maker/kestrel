package dev.narumi.kestrel.core.routeplan.routeimport

import dev.narumi.kestrel.core.routeplan.PlaybackPlan
import dev.narumi.kestrel.core.routeplan.RouteTimeline
import dev.narumi.kestrel.core.routeplan.SpeedSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteImporterTest {
    private fun gpx(points: List<String>) = """<gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1"><trk><name>T</name><trkseg>${points.joinToString("")}</trkseg></trk></gpx>"""

    private fun pt(
        lat: Double,
        lng: Double,
        time: String? = null,
    ) = """<trkpt lat="$lat" lon="$lng">${time?.let { "<time>$it</time>" }.orEmpty()}</trkpt>"""

    @Test
    fun detectsEachFormatFromContent() {
        val gpx = gpx(listOf(pt(1.0, 2.0), pt(3.0, 4.0)))
        val geo = """{"type":"LineString","coordinates":[[2,1],[4,3]]}"""
        val plan = """{"points":[{"lat":1,"lng":2},{"lat":3,"lng":4}]}"""
        val csv = "lat,lng\n1,2\n3,4"
        val list = "1 2\n3 4"

        for (text in listOf(gpx, geo, plan, csv, list, "  \n$gpx")) {
            val route = success(RouteImporter.import(text))
            assertEquals(2, route.points.size)
            assertEquals(1.0, route.points[0].point.lat, 0.0)
        }
    }

    @Test
    fun rejectsUnsupportedOrUnusableInput() {
        assertTrue(failureMessage(RouteImporter.import("<kml><Document/></kml>")).contains("KML"))
        assertTrue(failureMessage(RouteImporter.import("<html></html>")).contains("not GPX"))
        assertTrue(failureMessage(RouteImporter.import("[[1,2],[3,4]]")).contains("ambiguous"))
        assertTrue(failureMessage(RouteImporter.import("   ")).contains("empty"))
        assertTrue(failureMessage(RouteImporter.import("""{"a":""")).startsWith("Invalid JSON"))
        assertTrue(failureMessage(RouteImporter.import("52.37,4.9\nnope")).contains("Line 2"))
    }

    @Test
    fun namesTheRouteFromTheFileWhenItHasNoName() {
        val csv = "lat,lng\n1,2\n3,4"

        assertEquals("morning-ride", success(RouteImporter.import(csv, "content/morning-ride.csv")).name)
        assertEquals("T", success(RouteImporter.import(gpx(listOf(pt(1.0, 2.0), pt(3.0, 4.0))), "other.gpx")).name)
        assertNull(success(RouteImporter.import(csv)).name)
    }

    @Test
    fun enforcesThePointLimit() {
        val tooMany = (0..MAX_IMPORT_POINTS).joinToString("\n") { "1.0,2.0" }

        assertTrue(failureMessage(RouteImporter.import(tooMany)).contains("limit"))
    }

    @Test
    fun removesRepeatedPointsWhenThereAreNoTimestamps() {
        val route = success(RouteImporter.import("1,2\n1,2\n3,4\n3,4\n5,6"))

        assertEquals(3, route.points.size)
        assertTrue(route.warnings.any { "2 repeated" in it })
    }

    @Test
    fun keepsAStationaryPeriodThatHasTimestamps() {
        val text =
            gpx(
                listOf(
                    pt(1.0, 2.0, "2026-10-01T08:00:00Z"),
                    pt(3.0, 4.0, "2026-10-01T08:00:10Z"),
                    pt(3.0, 4.0, "2026-10-01T08:01:10Z"),
                    pt(5.0, 6.0, "2026-10-01T08:01:20Z"),
                ),
            )

        val route = success(RouteImporter.import(text))

        assertEquals(4, route.points.size)
        assertTrue(route.hasTimestamps)
    }

    @Test
    fun dropsTimestampsThatAreOnlyPartlyPresentOrOutOfOrder() {
        val partial = success(RouteImporter.import(gpx(listOf(pt(1.0, 2.0, "2026-10-01T08:00:00Z"), pt(3.0, 4.0)))))
        val backwards =
            success(
                RouteImporter.import(
                    gpx(listOf(pt(1.0, 2.0, "2026-10-01T08:00:10Z"), pt(3.0, 4.0, "2026-10-01T08:00:00Z"))),
                ),
            )

        assertFalse(partial.hasTimestamps)
        assertTrue(partial.warnings.any { "1 of 2" in it })
        assertFalse(backwards.hasTimestamps)
        assertTrue(backwards.warnings.any { "not in order" in it })
    }

    @Test
    fun dropsPointsThatShareATimestampWithDifferentPlaces() {
        val text =
            gpx(
                listOf(
                    pt(1.0, 2.0, "2026-10-01T08:00:00Z"),
                    pt(3.0, 4.0, "2026-10-01T08:00:00Z"),
                    pt(5.0, 6.0, "2026-10-01T08:00:10Z"),
                ),
            )

        val route = success(RouteImporter.import(text))

        assertEquals(2, route.points.size)
        assertEquals(5.0, route.points[1].point.lat, 0.0)
        assertTrue(route.warnings.any { "shared a timestamp" in it })
    }

    @Test
    fun rejectsOutOfRangeCoordinatesAndDegenerateRoutes() {
        assertTrue(failureMessage(RouteImporter.import("lat,lng\n120,4\n1,2")).contains("swapped"))
        assertTrue(failureMessage(RouteImporter.import("1,2\n1,2")).contains("two distinct"))
        assertTrue(
            failureMessage(RouteImporter.import("""{"source":{"lat":95,"lng":0},"points":[{"lat":1,"lng":2},{"lat":3,"lng":4}]}""")).contains("source", ignoreCase = true),
        )
    }

    @Test
    fun aTimedGpxFeedsAPlaybackTimelineDirectly() {
        val text =
            gpx(
                listOf(
                    pt(52.3700, 4.9000, "2026-10-01T08:00:00Z"),
                    pt(52.3710, 4.9010, "2026-10-01T08:00:10Z"),
                    pt(52.3720, 4.9020, "2026-10-01T08:00:20Z"),
                ),
            )
        val route = success(RouteImporter.import(text))

        val plan = PlaybackPlan(points = route.points, startAtEpochMs = 0L, speed = SpeedSource.FromTimestamps)
        val timeline = RouteTimeline(plan)

        assertEquals(20.0, timeline.durationSeconds, 1e-9)
        assertEquals(52.3710, timeline.positionAt(10.0).point.lat, 1e-6)
    }

    @Test
    fun anUntimedImportFeedsAConstantSpeedTimeline() {
        val route = success(RouteImporter.import("52.37,4.9\n52.371,4.901"))

        val timeline = RouteTimeline(PlaybackPlan(points = route.points, startAtEpochMs = 0L, speed = SpeedSource.Constant(36.0)))

        assertTrue(timeline.durationSeconds > 0.0)
        assertEquals(timeline.totalMeters / 10.0, timeline.durationSeconds, 1e-9)
    }
}
