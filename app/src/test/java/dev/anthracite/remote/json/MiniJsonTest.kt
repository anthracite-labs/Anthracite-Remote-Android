package dev.anthracite.remote.json

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniJsonTest {
    @Test
    fun `objects arrays and scalars are read without reflection`() {
        val root = MiniJson.parse(
            """{"name":"anthracite","count":3,"ratio":1.5,"ok":true,"none":null,
               "items":[{"id":1},{"id":2}],"nested":{"deep":{"value":"x"}}}""",
        ).asObject()!!
        assertEquals("anthracite", root.string("name"))
        assertEquals(3, root.int("count"))
        assertEquals(true, root.bool("ok"))
        assertEquals(JsonValue.Null, root["none"])
        assertEquals(2, root.array("items")!!.items.size)
        assertEquals("x", root.obj("nested")!!.obj("deep")!!.string("value"))
        assertNull(root.string("missing"))
    }

    @Test
    fun `escapes are decoded including surrogate pairs`() {
        val root = MiniJson.parse("""{"a":"line\nbreak","b":"quote\"inside","c":"\u00e9\u4e2d","d":"\ud83d\ude00"}""")
            .asObject()!!
        assertEquals("line\nbreak", root.string("a"))
        assertEquals("quote\"inside", root.string("b"))
        assertEquals("é中", root.string("c"))
        assertEquals("😀", root.string("d"))
    }

    @Test
    fun `trailing data is rejected instead of silently ignored`() {
        assertThrows(JsonException::class.java) { MiniJson.parse("{} {}") }
        assertThrows(JsonException::class.java) { MiniJson.parse("[1,2]x") }
    }

    @Test
    fun `control characters and bad escapes are rejected`() {
        assertThrows(JsonException::class.java) { MiniJson.parse("{\"a\":\"\u0001\"}") }
        assertThrows(JsonException::class.java) { MiniJson.parse("""{"a":"\q"}""") }
        assertThrows(JsonException::class.java) { MiniJson.parse("""{"a":"\u12"}""") }
        assertThrows(JsonException::class.java) { MiniJson.parse("""{"a":"\uZZZZ"}""") }
    }

    @Test
    fun `malformed structure is rejected`() {
        for (bad in listOf("", "{", "}", "[", "]", "{\"a\":}", "{\"a\" 1}", "{,}", "[1,]", "{\"a\":1,}")) {
            assertThrows("expected a rejection for: $bad", JsonException::class.java) { MiniJson.parse(bad) }
        }
    }

    @Test
    fun `deep nesting fails at the depth cap`() {
        val deep = "[".repeat(MiniJson.MAX_DEPTH + 5) + "]".repeat(MiniJson.MAX_DEPTH + 5)
        assertThrows(JsonException::class.java) { MiniJson.parse(deep) }
    }

    @Test
    fun `element and string caps bound the work a hostile response can cause`() {
        val many = "[" + List(MiniJson.MAX_ELEMENTS + 1) { "1" }.joinToString(",") + "]"
        assertThrows(JsonException::class.java) { MiniJson.parse(many) }

        val longString = "\"" + "a".repeat(MiniJson.MAX_STRING + 1) + "\""
        assertThrows(JsonException::class.java) { MiniJson.parse("{\"a\":$longString}") }
    }

    @Test
    fun `numbers are finite and keep their integer part exact enough for ids`() {
        val root = MiniJson.parse("""{"big":9007199254740991,"neg":-12,"exp":1e3,"frac":0.25}""").asObject()!!
        assertEquals(9007199254740991L, root.long("big"))
        assertEquals(-12, root.int("neg"))
        assertEquals(1000L, root.long("exp"))
        assertEquals(0.25, (root["frac"] as JsonValue.Num).value, 0.0001)
        for (bad in listOf("{\"a\":NaN}", "{\"a\":Infinity}", "{\"a\":01}", "{\"a\":.5}", "{\"a\":1.}")) {
            assertThrows("expected a rejection for: $bad", JsonException::class.java) { MiniJson.parse(bad) }
        }
    }

    @Test
    fun `the parser never allocates an unbounded object`() {
        val text = "{" + List(50) { "\"k$it\":$it" }.joinToString(",") + "}"
        val root = MiniJson.parse(text).asObject()!!
        assertEquals(50, root.entries.size)
        assertTrue(root.entries.keys.first() == "k0")
    }
}
