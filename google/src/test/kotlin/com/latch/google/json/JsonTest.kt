package com.latch.google.json

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * This is a wire format, so it is tested like one.
 *
 * The bar is not "the library works". It is that a request body composed here and a response
 * read here are byte-for-byte what `org.json` would have produced and understood, because an
 * 826-line write path that a device verified against the live API was moved onto it by
 * changing two import lines. Anything this gets subtly wrong, it gets wrong in a user's
 * calendar.
 */
class JsonTest {

    // ---- the semantics §7.2 depends on ------------------------------------------------------

    @Test
    fun `putting null removes the key, because latch_recipe must be absent and not null`() {
        // §7.2 requires `latch.recipe` to be absent for a capture with no recipe, and the call
        // site says so by putting a nullable. A JSON library that stored `null` instead would
        // put `"latch.recipe":null` into every item ever written, and the device pass of
        // 27 Aug 2026 checked for exactly this key being absent.
        val body = JSONObject().put("latch.chain_id", "abc").put("latch.recipe", null)
        assertEquals("""{"latch.chain_id":"abc"}""", body.toString())
        assertFalse(body.has("latch.recipe"))
    }

    @Test
    fun `a key put and then nulled is removed rather than left behind`() {
        val body = JSONObject().put("k", "v").put("k", null)
        assertEquals("{}", body.toString())
    }

    @Test
    fun `key order is the order they were put`() {
        // Not cosmetic: a deterministic body is what makes a request diffable in a log and a
        // test assertable against a literal.
        val body = JSONObject().put("z", 1).put("a", 2).put("m", 3)
        assertEquals("""{"z":1,"a":2,"m":3}""", body.toString())
    }

    // ---- escaping ---------------------------------------------------------------------------

    @Test
    fun `a description carrying quotes, backslashes and newlines survives a round trip`() {
        // FR-805's description is free user text. All three of these reach it routinely and
        // any one unescaped produces a body Google rejects outright.
        val text = "She said \"yes\"\nC:\\Users\\kulve\tdone"
        val encoded = JSONObject().put("description", text).toString()
        assertEquals(
            """{"description":"She said \"yes\"\nC:\\Users\\kulve\tdone"}""",
            encoded,
        )
        assertEquals(text, JSONObject(encoded).optString("description"))
    }

    @Test
    fun `control characters are escaped and come back`() {
        // Two paths, and the fixture exercises both: the named shortcuts, and the numeric
        // form for a character that has none. A control byte written raw makes the document
        // malformed, which Google answers with a parse error naming a byte offset.
        //
        // Built from character codes rather than written as escapes so that what is being
        // tested cannot be confused with how this file spells it.
        val backslash = 92.toChar().toString()
        val text = "abc" + 8.toChar() + "d" + 13.toChar() + "e" + 1.toChar() + "f"
        val encoded = JSONObject().put("t", text).toString()
        assertTrue(encoded.contains(backslash + "b"), encoded)
        assertTrue(encoded.contains(backslash + "r"), encoded)
        assertTrue(encoded.contains(backslash + "u0001"), encoded)
        assertEquals(text, JSONObject(encoded).optString("t"))
    }

    @Test
    fun `Devanagari and emoji go out as UTF-8 rather than as escapes`() {
        // NFR-404's territory, and the transport is already UTF-8. Escaping them would be
        // valid JSON and would make every logged body unreadable.
        val text = "फ़ीस due 20/09/2027 🎓"
        val encoded = JSONObject().put("summary", text).toString()
        assertTrue("फ़ीस" in encoded, encoded)
        assertEquals(text, JSONObject(encoded).optString("summary"))
    }

    @Test
    fun `an escaped surrogate pair from Google decodes to one emoji`() {
        // Google escapes astral characters as two \u units. Decoding each to a code point
        // separately would produce two broken halves; taking them as UTF-16 units is what
        // makes the String correct.
        val decoded = JSONObject("""{"s":"🎓"}""").optString("s")
        assertEquals("🎓", decoded)
        assertEquals(2, decoded.length)
    }

    // ---- reading a response -----------------------------------------------------------------

    @Test
    fun `a paged events response reads the way FR-803's query needs`() {
        // The shape that mattered: an empty page carrying a nextPageToken is what made the
        // duplicate query wrong for five days, so reading both of those correctly is the
        // whole point of this test rather than a general parse check.
        val page = JSONObject("""{"items":[],"nextPageToken":"CigKGjB..."}""")
        assertEquals(0, page.optJSONArray("items")?.length())
        assertEquals("CigKGjB...", page.optString("nextPageToken"))
    }

    @Test
    fun `an absent token reads as empty, which is how exhaustion is detected`() {
        val page = JSONObject("""{"items":[]}""")
        assertEquals("", page.optString("nextPageToken"))
    }

    @Test
    fun `nested private extended properties come back`() {
        val event = JSONObject(
            """{"id":"e1","extendedProperties":{"private":{"latch.source_hash":"abc","latch.version":"1"}}}"""
        )
        val private = event.optJSONObject("extendedProperties")?.optJSONObject("private")
        assertEquals("abc", private?.optString("latch.source_hash"))
        assertEquals("1", private?.optString("latch.version"))
    }

    @Test
    fun `an array of items is indexable`() {
        val items = JSONObject("""{"items":[{"id":"a"},{"id":"b"}]}""").optJSONArray("items")
        assertEquals(2, items?.length())
        assertEquals("b", items?.optJSONObject(1)?.optString("id"))
    }

    @Test
    fun `a metadata map becomes an object`() {
        // `toEventProperties()` returns a Map and the body puts it directly.
        val properties = mapOf("latch.version" to "1", "latch.source_hash" to "d1")
        assertEquals(
            """{"latch.version":"1","latch.source_hash":"d1"}""",
            JSONObject(properties).toString(),
        )
    }

    // ---- types ------------------------------------------------------------------------------

    @Test
    fun `numbers, booleans and null read as themselves`() {
        val body = JSONObject("""{"n":250,"big":9007199254740993,"d":1.5,"neg":-2,"b":true,"z":null}""")
        assertEquals(250, body.optInt("n"))
        assertEquals("9007199254740993", body.optString("big"))
        assertEquals("1.5", body.optString("d"))
        assertEquals(-2, body.optInt("neg"))
        assertTrue(body.optBoolean("b"))
        assertEquals("fallback", body.optString("z", "fallback"))
    }

    @Test
    fun `a whole number written as a double does not gain a decimal point`() {
        assertEquals("""{"n":3}""", JSONObject().put("n", 3.0).toString())
    }

    @Test
    fun `an absent key gives the fallback rather than throwing`() {
        val body = JSONObject("{}")
        assertEquals("", body.optString("missing"))
        assertEquals("x", body.optString("missing", "x"))
        assertNull(body.optJSONObject("missing"))
        assertNull(body.optJSONArray("missing"))
        assertFalse(body.optBoolean("missing"))
    }

    @Test
    fun `a value of the wrong type gives the fallback rather than a cast failure`() {
        // Defensive on purpose: this reads somebody else's API, and a field that changes shape
        // should degrade rather than crash a capture.
        val body = JSONObject("""{"items":"not an array","n":{"a":1}}""")
        assertNull(body.optJSONArray("items"))
        assertEquals(7, body.optInt("n", 7))
    }

    // ---- malformed input --------------------------------------------------------------------

    @Test
    fun `malformed documents throw rather than returning something plausible`() {
        listOf(
            "", "{", "}", "[", """{"a"}""", """{"a":}""", """{"a":1,}""",
            """{"a":1}trailing""", """{"a":"unterminated}""", """{"a":tru}""",
        ).forEach { bad ->
            assertFailsWith<JSONException>("expected '$bad' to be rejected") { JSONObject(bad) }
        }
    }

    @Test
    fun `an array where an object was expected is rejected`() {
        assertFailsWith<JSONException> { JSONObject("""[1,2]""") }
        assertFailsWith<JSONException> { JSONArray("""{"a":1}""") }
    }

    @Test
    fun `whitespace between tokens is tolerated, as a pretty-printed response has it`() {
        val body = JSONObject("{\n  \"a\" : [ 1 , 2 ]\n}")
        assertEquals(2, body.optJSONArray("a")?.length())
    }

    @Test
    fun `an empty object and an empty array round trip`() {
        assertEquals("{}", JSONObject("{}").toString())
        assertEquals("[]", JSONArray("[]").toString())
        assertEquals("""{"a":{},"b":[]}""", JSONObject("""{"a":{},"b":[]}""").toString())
    }
}
