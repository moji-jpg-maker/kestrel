package dev.narumi.kestrel.core.routeplan.routeimport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GpxParserTest {
    private fun gpx(body: String) =
        """<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="test" xmlns="http://www.topografix.com/GPX/1/1">$body</gpx>"""

    @Test
    fun readsTrackSegmentsTimesAndName() {
        val route =
            GpxParser.parse(
                gpx(
                    """
                    <metadata><name>Morning ride</name></metadata>
                    <trk><name>Ride</name>
                      <trkseg>
                        <trkpt lat="52.3700" lon="4.9000"><ele>1</ele><time>2026-10-01T08:00:00Z</time>
                          <extensions><time>bogus</time></extensions></trkpt>
                        <trkpt lat="52.3710" lon="4.9010"><time>2026-10-01T08:00:10.500Z</time></trkpt>
                      </trkseg>
                      <trkseg>
                        <trkpt lat="52.3720" lon="4.9020"><time>2026-10-01T10:00:20+02:00</time></trkpt>
                      </trkseg>
                    </trk>
                    """,
                ),
            )

        val start = epochMs("2026-10-01T08:00:00Z")
        assertEquals("Ride", route.name)
        assertEquals(3, route.points.size)
        assertEquals(52.3700, route.points[0].point.lat, 1e-9)
        assertEquals(4.9010, route.points[1].point.lng, 1e-9)
        assertEquals(listOf(start, start + 10_500, start + 20_000), route.points.map { it.timeMs })
        assertTrue(route.warnings.any { "2 track segments" in it })
    }

    @Test
    fun fallsBackToMetadataNameAndRoutePoints() {
        val route =
            GpxParser.parse(
                gpx(
                    """
                    <metadata><name>Planned</name></metadata>
                    <rte><rtept lat="1" lon="2"/><rtept lat="1.5" lon="2.5"/><rtept lat="2" lon="3"/></rte>
                    """,
                ),
            )

        assertEquals("Planned", route.name)
        assertEquals(3, route.points.size)
        assertNull(route.points[0].timeMs)
    }

    @Test
    fun usesWaypointsOnlyAsALastResort() {
        val route = GpxParser.parse(gpx("""<wpt lat="1" lon="2"><name>A</name></wpt><wpt lat="3" lon="4"/>"""))

        assertEquals(2, route.points.size)
        assertTrue(route.warnings.any { "waypoints" in it })
    }

    @Test
    fun trackWinsOverRouteWithAWarning() {
        val route =
            GpxParser.parse(
                gpx(
                    """
                    <rte><rtept lat="9" lon="9"/><rtept lat="8" lon="8"/></rte>
                    <trk><trkseg><trkpt lat="1" lon="2"/><trkpt lat="3" lon="4"/></trkseg></trk>
                    """,
                ),
            )

        assertEquals(2, route.points.size)
        assertEquals(1.0, route.points[0].point.lat, 0.0)
        assertTrue(route.warnings.any { "using the track" in it })
    }

    @Test
    fun readsNamespacePrefixedGpx() {
        val text =
            """<g:gpx xmlns:g="http://www.topografix.com/GPX/1/1"><g:trk><g:trkseg>
               <g:trkpt lat="1" lon="2"/><g:trkpt lat="3" lon="4"/></g:trkseg></g:trk></g:gpx>"""

        assertEquals(2, GpxParser.parse(text).points.size)
    }

    @Test
    fun rejectsDoctypeAndEntities() {
        val xxe =
            """<?xml version="1.0"?><!DOCTYPE gpx [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
               <gpx><trk><name>&xxe;</name></trk></gpx>"""

        assertThrows(RouteImportException::class.java) { GpxParser.parse(xxe) }
    }

    @Test
    fun rejectsMalformedXmlAndMissingCoordinates() {
        assertThrows(RouteImportException::class.java) { GpxParser.parse("<gpx><trk>") }
        assertThrows(RouteImportException::class.java) {
            GpxParser.parse(gpx("""<trk><trkseg><trkpt lon="1"/><trkpt lat="1" lon="2"/></trkseg></trk>"""))
        }
    }
}
