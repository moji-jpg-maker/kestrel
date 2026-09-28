package dev.narumi.kestrel.core.routeplan.routeimport

import dev.narumi.kestrel.core.location.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonRouteParserTest {
    private fun parse(json: String) = JsonRouteParser.parse(MiniJson.parse(json).asJsonObject()!!)

    private fun coordsJson(points: List<LatLng>) = points.joinToString(",", "[", "]") { "[${it.lng},${it.lat}]" }

    @Test
    fun readsAFullKestrelPlan() {
        val route =
            parse(
                """{"name":"Commute","source":{"lat":52.36,"lng":4.89},"start":"2026-10-01T08:00:00+02:00",
                   "speedKmh":40,"updateIntervalMs":500,
                   "points":[{"lat":52.37,"lng":4.9,"time":"2026-10-01T08:10:00Z"},{"lat":52.38,"lng":4.91,"time":"2026-10-01T08:20:00Z"}]}""",
            )

        assertEquals("Commute", route.name)
        assertEquals(52.36, route.hints.source!!.lat, 1e-9)
        assertEquals(epochMs("2026-10-01T06:00:00Z"), route.hints.startAtEpochMs)
        assertEquals(40.0, route.hints.speedKmh!!, 0.0)
        assertEquals(500L, route.hints.updateIntervalMs)
        assertEquals(600_000L, route.points[1].timeMs!! - route.points[0].timeMs!!)
    }

    @Test
    fun acceptsWaypointsAliasAndLonKey() {
        val route = parse("""{"waypoints":[{"lat":1,"lon":2},{"latitude":3,"longitude":4}]}""")

        assertEquals(2, route.points.size)
        assertEquals(4.0, route.points[1].point.lng, 0.0)
        assertNull(route.hints.startAtEpochMs)
    }

    @Test
    fun clampsAnOutOfRangeInterval() {
        val route = parse("""{"updateIntervalMs":50,"points":[{"lat":1,"lng":2},{"lat":3,"lng":4}]}""")

        assertEquals(200L, route.hints.updateIntervalMs)
        assertTrue(route.warnings.any { "adjusted" in it })
    }

    @Test
    fun rejectsBadKestrelValues() {
        val pts = """"points":[{"lat":1,"lng":2},{"lat":3,"lng":4}]"""
        assertThrows(RouteImportException::class.java) { parse("""{"speedKmh":-1,$pts}""") }
        assertThrows(RouteImportException::class.java) { parse("""{"start":"soon",$pts}""") }
        assertThrows(RouteImportException::class.java) { parse("""{"start":100,$pts}""") } // relative, not a start time
        assertThrows(RouteImportException::class.java) { parse("""{"points":[{"lat":1}]}""") }
        assertThrows(RouteImportException::class.java) { parse("""{"points":[5]}""") }
    }

    @Test
    fun readsGraphHopperEncodedAndUnencodedPoints() {
        val encoded = jsonEscape(encodePolyline(AMSTERDAM, 5))
        val five = parse("""{"paths":[{"points":"$encoded","points_encoded":true}]}""")
        val six = parse("""{"paths":[{"points":"${jsonEscape(encodePolyline(AMSTERDAM, 6))}","points_encoded_multiplier":1000000.0}]}""")
        val plain = parse("""{"paths":[{"points":{"type":"LineString","coordinates":${coordsJson(AMSTERDAM)}}}]}""")

        for (route in listOf(five, six, plain)) {
            assertEquals(3, route.points.size)
            assertEquals(
                52.3725,
                route.points
                    .last()
                    .point.lat,
                1e-9,
            )
        }
    }

    @Test
    fun joinsValhallaLegsWithoutDuplicatingTheJointPoint() {
        val leg1 = jsonEscape(encodePolyline(AMSTERDAM.take(2), 6))
        val leg2 = jsonEscape(encodePolyline(AMSTERDAM.drop(1), 6))

        val route = parse("""{"trip":{"legs":[{"shape":"$leg1"},{"shape":"$leg2"}]}}""")

        assertEquals(3, route.points.size)
        assertEquals(AMSTERDAM[2].lng, route.points[2].point.lng, 1e-9)
    }

    @Test
    fun readsOsrmPolylineAndGeoJsonGeometry() {
        val five = parse("""{"routes":[{"geometry":"${jsonEscape(encodePolyline(AMSTERDAM, 5))}"}]}""")
        val six = parse("""{"routes":[{"geometry":"${jsonEscape(encodePolyline(AMSTERDAM, 6))}"}]}""")
        val geo = parse("""{"routes":[{"geometry":{"type":"LineString","coordinates":${coordsJson(AMSTERDAM)}}}]}""")

        for (route in listOf(five, six, geo)) {
            assertEquals(3, route.points.size)
            assertEquals(
                52.3725,
                route.points
                    .last()
                    .point.lat,
                1e-9,
            )
        }
        assertTrue(five.warnings.any { "precision 5" in it })
        assertTrue(six.warnings.any { "precision 6" in it })
        assertTrue(geo.warnings.isEmpty())
    }

    @Test
    fun rejectsUnrecognisedStructures() {
        assertThrows(RouteImportException::class.java) { parse("""{"foo":1}""") }
        assertThrows(RouteImportException::class.java) { parse("""{"paths":[]}""") }
        assertThrows(RouteImportException::class.java) { parse("""{"trip":{"legs":[]}}""") }
        assertThrows(RouteImportException::class.java) { parse("""{"routes":[{}]}""") }
    }
}
