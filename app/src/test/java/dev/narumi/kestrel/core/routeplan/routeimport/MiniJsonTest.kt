package dev.narumi.kestrel.core.routeplan.routeimport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class MiniJsonTest {
    @Test
    fun parsesNestedValuesAndEscapes() {
        val root = MiniJson.parse("""{"a":[1,2.5,{"b":null}],"c":"x\ny\u0041\"","d":true,"e":-1e2}""").asJsonObject()!!

        val list = root["a"].asJsonArray()!!
        assertEquals(1.0, list[0] as Double, 0.0)
        assertEquals(2.5, list[1] as Double, 0.0)
        assertNull(list[2].asJsonObject()!!["b"])
        assertEquals("x\nyA\"", root["c"])
        assertEquals(true, root["d"])
        assertEquals(-100.0, root["e"] as Double, 0.0)
    }

    @Test
    fun toleratesWhitespaceAndBom() {
        val root = MiniJson.parse("  \n {\"a\" : [ ] }  ").asJsonObject()!!

        assertEquals(0, root["a"].asJsonArray()!!.size)
    }

    @Test
    fun rejectsMalformedInput() {
        for (bad in listOf("""{"a":1} x""", """{"a":""", "[1,]", """{"a" 1}""", "[1 2]", "\"abc", "nul", """{"a":"\q"}""", "")) {
            assertThrows(JsonSyntaxException::class.java) { MiniJson.parse(bad) }
        }
    }

    @Test
    fun rejectsExcessiveNesting() {
        val deep = "[".repeat(200) + "]".repeat(200)

        assertThrows(JsonSyntaxException::class.java) { MiniJson.parse(deep) }
    }

    @Test
    fun acceptsNestingWithinTheLimit() {
        val ok = "[".repeat(60) + "]".repeat(60)

        assertEquals(1, MiniJson.parse(ok).asJsonArray()!!.size)
    }
}
