package com.latch.google.json

/**
 * Just enough JSON to compose and read Google's API, hand-written.
 *
 * **Why this exists.** `org.json` ships inside `android.jar` and nowhere else, so the Google
 * client could either stay Android-only, or take a dependency, or bring its own JSON. The
 * third is the same answer this project has already given twice — REST over
 * `HttpURLConnection` rather than a client library, and RFC 5545 by hand rather than an
 * iCalendar one — and for the same reason: the surface actually used is small and closed, and
 * what it buys is a module both clients of §4.1 compile.
 *
 * **Why the names are `org.json`'s.** They are deliberately identical, method for method, so
 * that moving an 826-line write path a device has already verified is a change of two import
 * lines rather than a rewrite of every call site. A diff a person can read is worth more here
 * than a nicer API: this is the code that writes to a user's calendar. The mirror is not
 * accidental and should not be tidied into idiomatic Kotlin without a reason better than
 * taste.
 *
 * **Where it is faithful on purpose.** `put(name, null)` **removes** the key, exactly as
 * `org.json` does, and §7.2 depends on that: `latch.recipe` is required to be absent rather
 * than null for a capture with no recipe, and the call site expresses it as a `put` of a
 * nullable.
 *
 * **Where the same distinction is drawn the other way.** `JSONObject.NULL` writes an explicit
 * `null`, and that is a different instruction from `put(name, null)` rather than a spelling of
 * it: Google's `tasks.patch` clears a due date only when the field is present and null, while
 * omitting it leaves the existing value alone. FR-807's undo of an update needs to put a task
 * back to having no due date at all, so both have to be sayable. Reading is deliberately not
 * symmetrical — a `null` in a response is reported by every `opt` as the fallback, with no way
 * to tell it from an absent key, because nothing needs to and offering the distinction would
 * invite code that came to depend on it.
 */
class JSONException(message: String) : RuntimeException(message)

/** What a parsed `null` becomes, and what [JSONObject.NULL] writes. */
internal object JsonNull {
    override fun toString(): String = "null"
}

class JSONObject {
    private val values = LinkedHashMap<String, Any>()

    constructor()

    constructor(json: String) {
        val reader = JsonReader(json)
        val parsed = reader.readValue()
        reader.expectEnd()
        if (parsed !is JSONObject) throw JSONException("expected an object")
        values.putAll(parsed.values)
    }

    constructor(from: Map<*, *>) {
        from.forEach { (key, value) -> put(key.toString(), value) }
    }

    companion object {
        /**
         * An explicit JSON `null`, as distinct from omitting the key.
         *
         * This is the one place the distinction earns its keep, so it is exposed here and
         * nowhere else. Google's `tasks.patch` clears a due date only when the field is
         * **present and null** — omitting it leaves the existing value alone — so FR-807's
         * undo of an update, which has to put a task back to having no due date at all,
         * depends on being able to say the difference. `put(name, null)` removing the key is
         * therefore not an alternative spelling of this; it is the opposite instruction.
         */
        val NULL: Any = JsonNull
    }

    /** `org.json` semantics: a null value removes the key rather than storing one. */
    fun put(name: String, value: Any?): JSONObject {
        if (value == null) values.remove(name) else values[name] = value
        return this
    }

    fun optString(name: String, fallback: String = ""): String {
        val value = values[name]
        return if (value == null || value === JsonNull) fallback else value.toString()
    }

    fun optJSONObject(name: String): JSONObject? = values[name] as? JSONObject

    fun optJSONArray(name: String): JSONArray? = values[name] as? JSONArray

    fun optBoolean(name: String, fallback: Boolean = false): Boolean =
        when (val value = values[name]) {
            is Boolean -> value
            is String -> value.toBooleanStrictOrNull() ?: fallback
            else -> fallback
        }

    fun optInt(name: String, fallback: Int = 0): Int =
        when (val value = values[name]) {
            is Number -> value.toInt()
            is String -> value.toIntOrNull() ?: fallback
            else -> fallback
        }

    /**
     * The same shape as [optInt], and it exists because a confidence is not an integer.
     *
     * FR-505's confidence reaches a stored record as a fraction, and reading it back through
     * [optInt] would have turned every value below 1.0 into zero — a below-threshold capture
     * reporting perfect certainty, silently. The parser writes it, so a string fallback is
     * carried for the same reason the others carry one: a number that has been through a text
     * format and back should still read.
     */
    fun optDouble(name: String, fallback: Double = 0.0): Double =
        when (val value = values[name]) {
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull() ?: fallback
            else -> fallback
        }

    /**
     * The throwing accessors, which exist for the tests rather than for the client.
     *
     * A caller reading somebody else's API wants `opt` and a fallback, because a field that
     * changed shape should degrade rather than crash a capture. A *test* wants the opposite:
     * an assertion about a body that is missing the key it names should say so, not compare
     * two empty strings and pass. `CLAUDE.md` records what that failure costs — a test that
     * pins the wrong behaviour is worse than no test — so both halves are offered and named
     * apart, exactly as `org.json` names them.
     */
    fun get(name: String): Any = values[name] ?: throw JSONException("no value for " + name)

    fun getString(name: String): String = get(name).let {
        if (it === JsonNull) throw JSONException("null value for " + name) else it.toString()
    }

    fun getInt(name: String): Int = get(name).let { value ->
        when (value) {
            is Number -> value.toInt()
            is String -> value.toIntOrNull() ?: throw JSONException("not a number at " + name)
            else -> throw JSONException("not a number at " + name)
        }
    }

    fun getJSONObject(name: String): JSONObject =
        optJSONObject(name) ?: throw JSONException("no object at " + name)

    fun getJSONArray(name: String): JSONArray =
        optJSONArray(name) ?: throw JSONException("no array at " + name)

    fun remove(name: String): Any? = values.remove(name)

    fun has(name: String): Boolean = values.containsKey(name)

    fun keys(): Iterator<String> = values.keys.toList().iterator()

    fun length(): Int = values.size

    override fun toString(): String = buildString { writeObject(this@JSONObject, this) }

    internal fun entries(): Map<String, Any> = values
}

class JSONArray {
    private val values = mutableListOf<Any>()

    constructor()

    constructor(json: String) {
        val reader = JsonReader(json)
        val parsed = reader.readValue()
        reader.expectEnd()
        if (parsed !is JSONArray) throw JSONException("expected an array")
        values.addAll(parsed.values)
    }

    internal constructor(from: List<Any>) {
        values.addAll(from)
    }

    fun put(value: Any?): JSONArray {
        if (value != null) values.add(value)
        return this
    }

    fun length(): Int = values.size

    fun optJSONObject(index: Int): JSONObject? = values.getOrNull(index) as? JSONObject

    fun getJSONObject(index: Int): JSONObject =
        optJSONObject(index) ?: throw JSONException("no object at index " + index)

    fun optString(index: Int, fallback: String = ""): String {
        val value = values.getOrNull(index)
        return if (value == null || value === JsonNull) fallback else value.toString()
    }

    override fun toString(): String = buildString { writeArray(this@JSONArray, this) }

    internal fun items(): List<Any> = values
}

// ---------------------------------------------------------------------------- writing

private fun writeObject(value: JSONObject, out: StringBuilder) {
    out.append('{')
    var first = true
    value.entries().forEach { (key, item) ->
        if (!first) out.append(',')
        first = false
        writeString(key, out)
        out.append(':')
        writeValue(item, out)
    }
    out.append('}')
}

private fun writeArray(value: JSONArray, out: StringBuilder) {
    out.append('[')
    value.items().forEachIndexed { index, item ->
        if (index > 0) out.append(',')
        writeValue(item, out)
    }
    out.append(']')
}

private fun writeValue(value: Any, out: StringBuilder) {
    when (value) {
        is JSONObject -> writeObject(value, out)
        is JSONArray -> writeArray(value, out)
        is Boolean -> out.append(value)
        JsonNull -> out.append("null")
        is Double, is Float -> writeDouble((value as Number).toDouble(), out)
        is Number -> out.append(value.toString())
        else -> writeString(value.toString(), out)
    }
}

private fun writeDouble(number: Double, out: StringBuilder) {
    // A non-finite number is not JSON at all. Emitting `NaN` would produce a body Google
    // rejects with a parse error naming a byte offset, which is a poor way to find out; a
    // quoted string is at least legible in the response.
    if (!number.isFinite()) {
        writeString(number.toString(), out)
        return
    }
    if (number == Math.floor(number) && !number.isInfinite() && Math.abs(number) < 1e15) {
        out.append(number.toLong().toString())
    } else {
        out.append(number.toString())
    }
}

private fun writeString(value: String, out: StringBuilder) {
    out.append('"')
    value.forEach { character ->
        when (character) {
            '"' -> out.append("\\\"")
            '\\' -> out.append("\\\\")
            '\n' -> out.append("\\n")
            '\r' -> out.append("\\r")
            '\t' -> out.append("\\t")
            '\b' -> out.append("\\b")
            '' -> out.append("\\f")
            else ->
                // Everything below a space must be escaped or the document is malformed.
                // Above it, UTF-8 carries Devanagari and emoji unescaped, which is what the
                // transport already promises and what keeps a body legible in a log.
                if (character < ' ') {
                    out.append("\\u")
                    out.append(character.code.toString(16).padStart(4, '0'))
                } else {
                    out.append(character)
                }
        }
    }
    out.append('"')
}

// ---------------------------------------------------------------------------- reading

private class JsonReader(private val source: String) {
    private var at = 0

    fun readValue(): Any {
        skipSpace()
        if (at >= source.length) throw JSONException("empty document")
        return when (val character = source[at]) {
            '{' -> readObject()
            '[' -> readArray()
            '"' -> readString()
            't', 'f' -> readBoolean()
            'n' -> readNull()
            else ->
                if (character == '-' || character.isDigit()) readNumber()
                else throw JSONException("unexpected '" + character + "' at " + at)
        }
    }

    fun expectEnd() {
        skipSpace()
        if (at < source.length) throw JSONException("trailing content at " + at)
    }

    private fun readObject(): JSONObject {
        val result = JSONObject()
        at++
        skipSpace()
        if (peek() == '}') {
            at++
            return result
        }
        while (true) {
            skipSpace()
            val key = readString()
            skipSpace()
            if (peek() != ':') throw JSONException("expected ':' at " + at)
            at++
            result.put(key, readValue())
            skipSpace()
            when (peek()) {
                ',' -> at++
                '}' -> {
                    at++
                    return result
                }
                else -> throw JSONException("expected ',' or '}' at " + at)
            }
        }
    }

    private fun readArray(): JSONArray {
        val items = mutableListOf<Any>()
        at++
        skipSpace()
        if (peek() == ']') {
            at++
            return JSONArray(items)
        }
        while (true) {
            items += readValue()
            skipSpace()
            when (peek()) {
                ',' -> at++
                ']' -> {
                    at++
                    return JSONArray(items)
                }
                else -> throw JSONException("expected ',' or ']' at " + at)
            }
        }
    }

    private fun readString(): String {
        if (peek() != '"') throw JSONException("expected a string at " + at)
        at++
        val out = StringBuilder()
        while (true) {
            if (at >= source.length) throw JSONException("unterminated string")
            val character = source[at++]
            if (character == '"') return out.toString()
            if (character != '\\') {
                out.append(character)
                continue
            }
            if (at >= source.length) throw JSONException("unterminated escape")
            when (val escape = source[at++]) {
                '"' -> out.append('"')
                '\\' -> out.append('\\')
                '/' -> out.append('/')
                'b' -> out.append('\b')
                'f' -> out.append('')
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                't' -> out.append('\t')
                'u' -> {
                    if (at + 4 > source.length) throw JSONException("truncated unicode escape")
                    // Taken one UTF-16 unit at a time, so a surrogate pair arrives as its two
                    // halves and the String holds it correctly. Decoding to a code point here
                    // would break every emoji and every Devanagari conjunct Google escapes.
                    val code = source.substring(at, at + 4).toIntOrNull(16)
                        ?: throw JSONException("bad unicode escape at " + at)
                    out.append(code.toChar())
                    at += 4
                }
                else -> throw JSONException("unknown escape at " + at + ": " + escape)
            }
        }
    }

    private fun readBoolean(): Boolean = when {
        source.startsWith("true", at) -> {
            at += 4
            true
        }
        source.startsWith("false", at) -> {
            at += 5
            false
        }
        else -> throw JSONException("expected a boolean at " + at)
    }

    private fun readNull(): Any {
        if (!source.startsWith("null", at)) throw JSONException("expected null at " + at)
        at += 4
        return JsonNull
    }

    private fun readNumber(): Any {
        val start = at
        if (peek() == '-') at++
        while (at < source.length && (source[at].isDigit() || source[at] in ".eE+-")) at++
        val text = source.substring(start, at)
        return text.toLongOrNull()
            ?: text.toDoubleOrNull()
            ?: throw JSONException("bad number '" + text + "'")
    }

    private fun peek(): Char =
        if (at < source.length) source[at] else throw JSONException("unexpected end of document")

    private fun skipSpace() {
        while (at < source.length && source[at].isWhitespace()) at++
    }
}
