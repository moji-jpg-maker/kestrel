package dev.narumi.kestrel.core.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class GeoInterpolationTest {
    @Test
    fun endpointsAreReturned() {
        val a = LatLng(10.0, 20.0)
        val b = LatLng(12.0, 25.0)

        val start = interpolateGreatCircle(a, b, 0.0)
        val end = interpolateGreatCircle(a, b, 1.0)

        assertEquals(a.lat, start.lat, 1e-9)
        assertEquals(a.lng, start.lng, 1e-9)
        assertEquals(b.lat, end.lat, 1e-9)
        assertEquals(b.lng, end.lng, 1e-9)
    }

    @Test
    fun midpointIsEquidistantFromBothEnds() {
        val a = LatLng(52.37, 4.90)
        val b = LatLng(48.85, 2.35)

        val mid = interpolateGreatCircle(a, b, 0.5)

        assertEquals(haversineMeters(a, mid), haversineMeters(mid, b), 1e-3)
        assertEquals(haversineMeters(a, b) / 2, haversineMeters(a, mid), 1e-3)
    }

    @Test
    fun longEquatorSegmentKeepsLatitudeZero() {
        val mid = interpolateGreatCircle(LatLng(0.0, 0.0), LatLng(0.0, 90.0), 0.5)

        assertEquals(0.0, mid.lat, 1e-9)
        assertEquals(45.0, mid.lng, 1e-9)
    }

    @Test
    fun crossesTheAntimeridianTheShortWay() {
        val mid = interpolateGreatCircle(LatLng(0.0, 179.9), LatLng(0.0, -179.9), 0.5)

        assertTrue(abs(mid.lng) > 179.99)
    }

    @Test
    fun identicalPointsDoNotProduceNaN() {
        val p = LatLng(1.0, 2.0)

        val result = interpolateGreatCircle(p, p, 0.5)

        assertEquals(1.0, result.lat, 1e-12)
        assertEquals(2.0, result.lng, 1e-12)
    }
}
