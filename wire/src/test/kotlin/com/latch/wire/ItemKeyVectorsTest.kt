package com.latch.wire

import com.latch.parser.DateParser
import com.latch.parser.ParseContext
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * SRS v1.11's promised conformance suite for §7.2's date-free title.
 *
 * Read `item_key_vectors.tsv` before this file: it says what the vectors are for now that the
 * first two clients share one compiled derivation, which is not what v1.11 anticipated.
 *
 * The clock is fixed and no row depends on it. A date's *span* is its position in the text,
 * which does not move with the calendar — `ItemKeyAcrossClientsTest` proves that separately
 * for every field of `ParseContext`.
 */
class ItemKeyVectorsTest {

    private data class Vector(
        val name: String,
        val input: String,
        val title: String,
        val digest: String,
    )

    private val clock = LocalDateTime.of(2026, 9, 3, 9, 0)

    private fun capture(text: String, subject: String? = null) = object : WireCapture {
        override val text = text
        override val preferredTitle = subject
        override val ocrUsed = false
    }

    @Test
    fun `every vector reproduces its date-free title and its digest`() {
        val vectors = load()
        assertTrue(vectors.size >= 20, "the vector file lost rows: " + vectors.size)

        vectors.forEach { vector ->
            // `subject_wins` is FR-206's step 1: the sending application supplied a subject, so
            // that is the key's input verbatim and the body is never consulted.
            val subject = if (vector.name == "subject_wins") "Q3 board pack" else null
            val result = DateParser.parse(vector.input, ParseContext(now = clock))
            val title = itemKeyTitle(capture(vector.input, subject), result)

            assertEquals(vector.title, title, "date-free title changed for '" + vector.name + "'")
            assertEquals(vector.digest, itemKeyOf(title), "item_key changed for '" + vector.name + "'")
        }
    }

    @Test
    fun `the rows that must share a digest still do`() {
        // Stated as relationships rather than only as digests, because this is what the file is
        // *for*: a reviewer can check these by reading, where a column of hashes proves nothing
        // to a person. If any of these ever separates, FR-804 stops matching and starts writing
        // duplicates — silently, which is the failure mode that makes it worth a test of its own.
        val byName = load().associateBy { it.name }
        fun same(a: String, b: String, why: String) =
            assertEquals(byName.getValue(a).digest, byName.getValue(b).digest, why)

        same("reschedule_before", "reschedule_after", "moving a meeting must not change its identity")
        same("year_first", "reschedule_across_years", "a renewal must be reschedulable across a year")
        same("weekday_corroboration", "weekday_absent", "a corroborating weekday must be absorbed")
        same("numeric_date", "numeric_no_year", "writing the year or not is the same message")
    }

    @Test
    fun `the file is pure ASCII, so an editor cannot normalise a case away`() {
        val bytes = checkNotNull(javaClass.getResourceAsStream("/metadata/item_key_vectors.tsv"))
            .readBytes()
        val offending = bytes.indexOfFirst { it < 0 }
        assertEquals(-1, offending, "a non-ASCII byte reached the vector file at offset " + offending)
    }

    private fun load(): List<Vector> =
        checkNotNull(javaClass.getResourceAsStream("/metadata/item_key_vectors.tsv")) {
            "Missing item_key_vectors.tsv"
        }.bufferedReader().use { it.readText() }
            .lineSequence()
            // Only the line ending, never the columns. Git rewrites this file to CRLF on
            // checkout and any client reading it on another platform faces the same.
            .map { it.removeSuffix("\r") }
            .filterNot { it.isBlank() || it.startsWith("#") }
            .map { line ->
                val columns = line.split("\t")
                require(columns.size == 4) { "Malformed vector row: " + line }
                Vector(columns[0], unescape(columns[1]), unescape(columns[2]), columns[3])
            }
            .toList()

    /** The same escaping `hash_vectors.tsv` uses, and for the same reason. */
    private fun unescape(escaped: String): String {
        val out = StringBuilder()
        var at = 0
        while (at < escaped.length) {
            val character = escaped[at]
            if (character != '\\') {
                out.append(character)
                at++
                continue
            }
            at++
            when (val marker = escaped[at++]) {
                'n' -> out.append('\n')
                't' -> out.append('\t')
                'r' -> out.append('\r')
                '\\' -> out.append('\\')
                'u' -> {
                    out.append(escaped.substring(at, at + 4).toInt(16).toChar())
                    at += 4
                }
                else -> error("unknown escape in vector file: " + marker)
            }
        }
        return out.toString()
    }
}
