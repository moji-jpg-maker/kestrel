package dev.narumi.kestrel.core.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteStateSerializationTest {
    // Mirrors the Json config in KestrelPrefs so the test exercises the same decoder used at
    // runtime. `ignoreUnknownKeys` must stay on for forward compatibility, and missing optional
    // fields must resolve to "start of route" defaults.
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun decodesLegacyJsonWithoutProgressOrForward() {
        // This is exactly the shape `RouteState` had before the route-progress-persistence change.
        // Devices that upgrade across this commit will read this blob from DataStore once.
        val legacy =
            """
            {
              "lats": [0.0, 0.001],
              "lngs": [0.0, 0.001],
              "speedKmh": 30.0,
              "mode": "Loop"
            }
            """.trimIndent()

        val decoded = json.decodeFromString(RouteState.serializer(), legacy)

        assertEquals(30.0, decoded.speedKmh, 1e-9)
        assertEquals("Loop", decoded.mode)
        assertNull(decoded.startAtEpochMs)
        assertNull(decoded.timesMs)
        assertNull(decoded.speedSource)
        assertEquals(
            "legacy payloads must resume from the start, not a random offset",
            0.0,
            decoded.progressMeters,
            1e-9,
        )
        assertTrue(
            "legacy payloads must resume in the natural forward direction",
            decoded.forward,
        )
    }

    @Test
    fun scheduledFieldsRoundTripWhileRetainingUnknownFields() {
        val previous = """{
          "mode":"Route","futureMock":true,"route":{
            "lats":[0.0,0.001],"lngs":[0.0,0.001],"speedKmh":30.0,
            "startAtEpochMs":1700000000000,"updateIntervalMs":500,
            "timesMs":[0,1000],"speedSource":"TimestampsScaled","speedFactor":2.0,
            "sourceLat":0.0,"sourceLng":-0.001,"leadInSpeedKmh":20.0,
            "pausedTotalMs":1000,"name":"Commute","futureRoute":"keep"
          }
        }"""
        val state = json.decodeFromString(MockState.serializer(), previous)
        val updated = state.copy(route = requireNotNull(state.route).copy(pausedTotalMs = 2_000L))
        val encoded = json.encodePreservingUnknown(MockState.serializer(), updated, previous)
        val restored = requireNotNull(json.decodeFromString(MockState.serializer(), encoded).route)

        assertEquals(1_700_000_000_000L, restored.startAtEpochMs)
        assertEquals(listOf(0L, 1_000L), restored.timesMs!!.toList())
        assertEquals(500L, restored.updateIntervalMs)
        assertEquals(2.0, restored.speedFactor)
        assertEquals(2_000L, restored.pausedTotalMs)
        assertEquals("Commute", restored.name)
        val root = json.parseToJsonElement(encoded).jsonObject
        assertEquals("true", root.getValue("futureMock").jsonPrimitive.content)
        assertEquals(
            "keep",
            root
                .getValue("route")
                .jsonObject
                .getValue("futureRoute")
                .jsonPrimitive.content,
        )
    }

    @Test
    fun roundTripsNewFields() {
        val state =
            RouteState(
                lats = doubleArrayOf(0.0, 0.001),
                lngs = doubleArrayOf(0.0, 0.001),
                speedKmh = 30.0,
                mode = "PingPong",
                progressMeters = 42.5,
                forward = false,
            )

        val encoded = json.encodeToString(RouteState.serializer(), state)
        val decoded = json.decodeFromString(RouteState.serializer(), encoded)

        assertEquals(42.5, decoded.progressMeters, 1e-9)
        assertEquals(false, decoded.forward)
        assertEquals("PingPong", decoded.mode)
    }
}
