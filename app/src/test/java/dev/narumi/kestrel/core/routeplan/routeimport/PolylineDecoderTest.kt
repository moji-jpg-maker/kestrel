package dev.narumi.kestrel.core.routeplan.routeimport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PolylineDecoderTest {
    @Test
    fun decodesTheCanonicalGoogleExample() {
        val points = PolylineDecoder.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@", precision = 5)

        assertEquals(3, points.size)
        assertEquals(38.5, points[0].lat, 1e-9)
        assertEquals(-120.2, points[0].lng, 1e-9)
        assertEquals(40.7, points[1].lat, 1e-9)
        assertEquals(-120.95, points[1].lng, 1e-9)
        assertEquals(43.252, points[2].lat, 1e-9)
        assertEquals(-126.453, points[2].lng, 1e-9)
    }

    @Test
    fun roundTripsPrecisionSix() {
        val decoded = PolylineDecoder.decode(encodePolyline(AMSTERDAM, 6), precision = 6)

        AMSTERDAM.forEachIndexed { i, p ->
            assertEquals(p.lat, decoded[i].lat, 1e-9)
            assertEquals(p.lng, decoded[i].lng, 1e-9)
        }
    }

    @Test
    fun guessesPrecisionFromTheCoordinateRange() {
        val (five, precisionFive) = PolylineDecoder.decodeGuessingPrecision(encodePolyline(AMSTERDAM, 5))
        val (six, precisionSix) = PolylineDecoder.decodeGuessingPrecision(encodePolyline(AMSTERDAM, 6))

        assertEquals(5, precisionFive)
        assertEquals(6, precisionSix)
        assertEquals(52.3725, five.last().lat, 1e-9)
        assertEquals(52.3725, six.last().lat, 1e-9)
    }

    @Test
    fun rejectsTruncatedAndMalformedInput() {
        val encoded = encodePolyline(AMSTERDAM, 5)

        assertThrows(RouteImportException::class.java) { PolylineDecoder.decode(encoded.dropLast(1), 5) }
        assertThrows(RouteImportException::class.java) { PolylineDecoder.decode("abc def", 5) }
    }

    @Test
    fun emptyStringDecodesToNothing() {
        assertEquals(0, PolylineDecoder.decode("", 5).size)
    }
}
