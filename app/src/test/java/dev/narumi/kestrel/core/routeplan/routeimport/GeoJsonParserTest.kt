package dev.narumi.kestrel.core.routeplan.routeimport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoJsonParserTest {
    private fun parse(json: String) = GeoJsonParser.parse(MiniJson.parse(json).asJsonObject()!!)

    @Test
    fun readsABRouterStyleLineStringWithAltitude() {
        val route =
            parse(
                """{"type":"FeatureCollection","features":[{"type":"Feature",
                   "properties":{"creator":"BRouter","name":"trekking"},
                   "geometry":{"type":"LineString","coordinates":[[4.9,52.37,1.5],[4.901,52.371,2.0],[4.902,52.372,2.5]]}}]}""",
            )

        assertEquals("trekking", route.name)
        assertEquals(3, route.points.size)
        assertEquals(52.37, route.points[0].point.lat, 1e-9)
        assertEquals(4.9, route.points[0].point.lng, 1e-9)
    }

    @Test
    fun readsPerVertexTimesFromCoordinateProperties() {
        val route =
            parse(
                """{"type":"Feature","properties":{"coordinateProperties":{"times":
                   ["2026-10-01T08:00:00Z","2026-10-01T08:00:30Z"]}},
                   "geometry":{"type":"LineString","coordinates":[[4.9,52.37],[4.91,52.38]]}}""",
            )

        val start = epochMs("2026-10-01T08:00:00Z")
        assertEquals(listOf(start, start + 30_000), route.points.map { it.timeMs })
    }

    @Test
    fun ignoresTimesThatDoNotMatchTheCoordinates() {
        val route =
            parse(
                """{"type":"Feature","properties":{"coordinateProperties":{"times":["2026-10-01T08:00:00Z"]}},
                   "geometry":{"type":"LineString","coordinates":[[4.9,52.37],[4.91,52.38]]}}""",
            )

        assertNull(route.points[0].timeMs)
        assertTrue(route.warnings.any { "coordinate count" in it })
    }

    @Test
    fun prefersLinesOverPointFeatures() {
        val route =
            parse(
                """{"type":"FeatureCollection","features":[
                   {"type":"Feature","properties":{},"geometry":{"type":"Point","coordinates":[9,9]}},
                   {"type":"Feature","properties":{},"geometry":{"type":"LineString","coordinates":[[1,2],[3,4]]}}]}""",
            )

        assertEquals(2, route.points.size)
        assertEquals(2.0, route.points[0].point.lat, 0.0)
        assertTrue(route.warnings.isEmpty())
    }

    @Test
    fun usesPointFeaturesWhenThereAreNoLines() {
        val route =
            parse(
                """{"type":"FeatureCollection","features":[
                   {"type":"Feature","geometry":{"type":"Point","coordinates":[1,2]}},
                   {"type":"Feature","geometry":{"type":"Point","coordinates":[3,4]}}]}""",
            )

        assertEquals(2, route.points.size)
        assertTrue(route.warnings.any { "Point features" in it })
    }

    @Test
    fun joinsMultiLineStringParts() {
        val route = parse("""{"type":"MultiLineString","coordinates":[[[1,2],[3,4]],[[5,6],[7,8]]]}""")

        assertEquals(4, route.points.size)
        assertTrue(route.warnings.any { "2 separate lines" in it })
    }

    @Test
    fun followsGeometryCollections() {
        val route = parse("""{"type":"GeometryCollection","geometries":[{"type":"LineString","coordinates":[[1,2],[3,4]]}]}""")

        assertEquals(2, route.points.size)
    }

    @Test
    fun rejectsSwappedCoordinateOrder() {
        val error =
            assertThrows(RouteImportException::class.java) {
                parse("""{"type":"LineString","coordinates":[[35.68,139.69],[35.7,139.7]]}""")
            }

        assertTrue(error.message!!.contains("[longitude, latitude]"))
    }

    @Test
    fun rejectsDocumentsWithoutRouteGeometry() {
        assertThrows(RouteImportException::class.java) { parse("""{"type":"FeatureCollection","features":[]}""") }
        assertThrows(RouteImportException::class.java) { parse("""{"type":"LineString","coordinates":[["a","b"]]}""") }
    }
}
