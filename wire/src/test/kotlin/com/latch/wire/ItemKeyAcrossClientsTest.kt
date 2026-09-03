package com.latch.wire

import com.latch.parser.DateOrder
import com.latch.parser.DateParser
import com.latch.parser.ParseContext
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * §7.2's `latch.item_key`, asked the question SRS v1.11 promised a vector file for.
 *
 * v1.11 said "a vector file for date-free titles should be added when the second client is
 * built, and until then FR-804 should be understood as reliable within a client and unproven
 * across them". The second client exists now, and the promise is discharged by answering the
 * question underneath it rather than only by producing the file.
 *
 * **Both clients call one compiled derivation.** `CaptureSaver` and `DesktopSaver` contain the
 * byte-identical line `itemKeyOf(itemKeyTitle(captured, result))`, and both `itemKeyTitle` and
 * `itemKeyOf` live in `:wire`, which each client compiles from the same source. There is no
 * second implementation for a vector file to catch drifting from the first.
 *
 * **So the risk moves from the code to its inputs**, and that is what this file tests. The
 * derivation is a pure function of the captured text and the *spans* a `ParseResult` reports;
 * two clients agreeing on the code but disagreeing about the spans would derive different keys
 * with no error anywhere. `ParseContext` is where that could come from, so each of its fields
 * is asked here whether it can move a span.
 */
class ItemKeyAcrossClientsTest {

    private val phoneNow = LocalDateTime.of(2026, 9, 3, 9, 0)

    private fun keyFor(
        text: String,
        now: LocalDateTime = phoneNow,
        order: DateOrder = DateOrder.DAY_FIRST,
        zone: ZoneId = ZoneId.of("Asia/Kolkata"),
        duration: Duration = Duration.ofHours(1),
    ): String {
        val context = ParseContext(now = now, zone = zone, dateOrder = order, defaultEventDuration = duration)
        val capture = object : WireCapture {
            override val text = text
            override val preferredTitle: String? = null
            override val ocrUsed = false
        }
        return itemKeyOf(itemKeyTitle(capture, DateParser.parse(text, context)))
    }

    // ---- the field most likely to differ between two machines ------------------------------

    @Test
    fun `a different date order does not move the key for an unambiguous date`() {
        val text = "PTM on 14 September 2026"
        assertEquals(
            keyFor(text, order = DateOrder.DAY_FIRST),
            keyFor(text, order = DateOrder.MONTH_FIRST),
        )
    }

    @Test
    fun `a different date order does not move the key for an ambiguous numeric date`() {
        // The case FR-504 exists for. `05/09` resolves to 5 September under one setting and
        // 9 May under the other — but the key is derived from the text with the span *blanked*,
        // so what the date resolves to never reaches it. If this ever goes red, a phone set to
        // month-first and a desktop left day-first would stop recognising each other's items,
        // silently, and FR-804 would create duplicates across devices.
        val text = "Invoice 05/09 to be paid"
        assertEquals(
            keyFor(text, order = DateOrder.DAY_FIRST),
            keyFor(text, order = DateOrder.MONTH_FIRST),
        )
    }

    // ---- the other ParseContext fields ------------------------------------------------------

    @Test
    fun `the clock does not move the key, even for a relative date`() {
        // "kal" and "tomorrow" resolve differently on two machines whose clocks differ, and
        // must still be the same item. The span is the word's position in the text, which is
        // the same whatever day it is read on.
        listOf("kal 4 baje meeting", "Standup tomorrow at 10", "Review next Friday").forEach { text ->
            assertEquals(
                keyFor(text, now = phoneNow),
                keyFor(text, now = phoneNow.plusDays(200).plusHours(7)),
                text,
            )
        }
    }

    @Test
    fun `the time zone does not move the key`() {
        val text = "Kickoff 8 September 2027 at 9am"
        assertEquals(
            keyFor(text, zone = ZoneId.of("Asia/Kolkata")),
            keyFor(text, zone = ZoneId.of("America/Los_Angeles")),
        )
    }

    @Test
    fun `the default event duration does not move the key`() {
        val text = "Kickoff 8 September 2027 at 9am"
        assertEquals(
            keyFor(text, duration = Duration.ofHours(1)),
            keyFor(text, duration = Duration.ofMinutes(45)),
        )
    }

    // ---- what the key is actually for -------------------------------------------------------

    @Test
    fun `a reschedule keeps the key, which is the whole mechanism`() {
        // FR-804 matches "same title and identifiers, different date". If these two differed,
        // moving a meeting would create a second item rather than offering to move the first —
        // which is the failure `item_key` exists to prevent, and the one AC-08 checks.
        assertEquals(
            keyFor("Project sync on 8 September 2027 at 11:00"),
            keyFor("Project sync on 9 September 2027 at 11:00"),
        )
    }

    @Test
    fun `two different messages do not share a key`() {
        assertNotEquals(
            keyFor("Project sync on 8 September 2027"),
            keyFor("Fee payment on 8 September 2027"),
        )
    }

    @Test
    fun `the key is not simply the source hash`() {
        // The defect this was built against: `TitleExtractor` returns the whole text verbatim
        // for a short capture, so a key hashed from the title as displayed came out
        // byte-identical to `source_hash` — and both then changed together on a reschedule,
        // leaving FR-804 nothing to match.
        val text = "Project sync on 8 September 2027 at 11:00"
        assertNotEquals(sourceHashOf(text), keyFor(text))
    }

    @Test
    fun `a corroborating weekday is absorbed and does not travel with the date`() {
        // §7.2's own example. 12 September 2026 is a Saturday, so the writer's "Friday" is
        // wrong — and it must still be absorbed into the date's span, or the weekday stays in
        // the identity and moves when the meeting does.
        val withWeekday = keyFor("PTM on Friday 12 September 2026")
        val without = keyFor("PTM on 12 September 2026")
        assertEquals(without, withWeekday)
    }

    @Test
    fun `the key survives the date being written a different way`() {
        // Not required by anything, and worth knowing: a reschedule that also changes the date
        // *format* still matches, because every matched span is blanked whatever its shape.
        assertEquals(
            keyFor("Fees due 20 September 2027"),
            keyFor("Fees due 20/09/2027"),
        )
    }

    @Test
    fun `a date-free title is what is hashed, and it carries no digits`() {
        val text = "Trip from 20 September to 24 September 2027"
        val capture = object : WireCapture {
            override val text = text
            override val preferredTitle: String? = null
            override val ocrUsed = false
        }
        val title = itemKeyTitle(capture, DateParser.parse(text, ParseContext(now = phoneNow)))
        assertTrue(title.none(Char::isDigit), "a date survived into the key's input: '" + title + "'")
        assertTrue("September" !in title, "a month name survived: '" + title + "'")
    }
}
