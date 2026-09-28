package dev.narumi.kestrel.core.routeplan.routeimport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CsvRouteParserTest {
    @Test
    fun readsHeaderColumnsByNameInAnyOrder() {
        val route =
            CsvRouteParser.parse(
                "Time,\"Longitude\",Latitude\n2026-10-01T08:00:00Z,4.9,52.37\n2026-10-01 08:00:10,4.901,52.371\n",
            )

        val start = epochMs("2026-10-01T08:00:00Z")
        assertEquals(52.37, route.points[0].point.lat, 1e-9)
        assertEquals(4.9, route.points[0].point.lng, 1e-9)
        assertEquals(listOf(start, start + 10_000), route.points.map { it.timeMs })
    }

    @Test
    fun readsRelativeSecondsWhenTheHeaderNamesATimeColumn() {
        val route = CsvRouteParser.parse("lat,lng,time\n52.37,4.9,0\n52.371,4.901,10\n")

        assertEquals(listOf(0L, 10_000L), route.points.map { it.timeMs })
    }

    @Test
    fun semicolonFilesMayUseDecimalCommas() {
        val route = CsvRouteParser.parse("lat;lng\n52,37;4,9\n52,371;4,901\n")

        assertEquals(52.37, route.points[0].point.lat, 1e-9)
        assertEquals(4.901, route.points[1].point.lng, 1e-9)
    }

    @Test
    fun headerlessThirdColumnIsElevationUnlessItIsAnAbsoluteTime() {
        val elevation = CsvRouteParser.parse("52.37,4.9,120\n52.371,4.901,121\n")
        val timed = CsvRouteParser.parse("52.37,4.9,2026-10-01T08:00:00Z\n52.371,4.901,2026-10-01T08:00:05Z\n")

        assertNull(elevation.points[0].timeMs)
        assertEquals(5_000L, timed.points[1].timeMs!! - timed.points[0].timeMs!!)
    }

    @Test
    fun readsSpaceSeparatedListsGeoUrisAndSkipsComments() {
        val route = CsvRouteParser.parse("# my stops\n52.37 4.9\n\ngeo:52.372,4.902\n52.373 4.903\n")

        assertEquals(3, route.points.size)
        assertEquals(52.372, route.points[1].point.lat, 1e-9)
    }

    @Test
    fun fallsBackToCoordinateParsingOnCommaFiles() {
        val route = CsvRouteParser.parse("52.37,4.9\ngeo:52.372,4.902\n")

        assertEquals(2, route.points.size)
        assertEquals(4.902, route.points[1].point.lng, 1e-9)
    }

    @Test
    fun handlesBomAndWindowsLineEndings() {
        val route = CsvRouteParser.parse("\uFEFFlat,lng\r\n52.37,4.9\r\n52.371,4.901\r\n")

        assertEquals(2, route.points.size)
    }

    @Test
    fun warnsThatASpeedColumnIsIgnored() {
        val route = CsvRouteParser.parse("lat,lng,speed\n52.37,4.9,10\n52.371,4.901,12\n")

        assertEquals(2, route.points.size)
        assertTrue(route.warnings.any { "speed" in it })
    }

    @Test
    fun reportsTheOffendingLine() {
        val error = assertThrows(RouteImportException::class.java) { CsvRouteParser.parse("52.37,4.9\nhello\n52.371,4.901\n") }

        assertTrue(error.message!!.contains("Line 2"))
    }

    @Test
    fun rejectsEmptyInput() {
        assertThrows(RouteImportException::class.java) { CsvRouteParser.parse("  \n# only a comment\n") }
    }
}
