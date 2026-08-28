package com.latch.parser

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SRS 1.23: "from X to Y" is one candidate carrying both ends, and an Event even with no
 * time.
 *
 * The corpus cannot express an end date — its columns are input, classification, date and
 * time — so the end lives here, the arrangement `CandidateRankingTest` already uses for
 * assertions the corpus has no column for.
 *
 * **The end is inclusive here and nowhere else.** 12 to 14 September ends *on* the 14th.
 * Google's all-day API reads an end date as exclusive and wants the 15th, and that
 * conversion belongs to the drafting step in `:app` — `ItemDraftsTest` asserts it there.
 * Splitting the two is deliberate: a parser that returned a wire-shaped date would move
 * every end by a day for anything reading it directly.
 */
class RangeTest {

    private val context = ParseContext(
        now = LocalDateTime.of(2026, 8, 25, 9, 0), // a Tuesday
        zone = ZoneId.of("Asia/Kolkata"),
        dateOrder = DateOrder.DAY_FIRST,
    )

    private fun parse(text: String) = DateParser.parse(text, context)

    // ----- the shape of a range -----

    @Test
    fun `from X to Y is one candidate with both ends`() {
        val candidates = parse("Team offsite from 12 September to 14 September").candidates

        assertEquals(1, candidates.size, "a range is one commitment, not two dates")
        assertEquals(LocalDate.of(2026, 9, 12), candidates[0].date?.value)
        // Inclusive, as written. The offsite runs *on* the 14th.
        assertEquals(LocalDate.of(2026, 9, 14), candidates[0].endDate?.value)
    }

    @Test
    fun `a range with no time is an Event, not a task with a due date`() {
        // The exception to FR-506's second row. A Google task carries one due date and no
        // end, so classifying this a task would silently discard the 14th.
        val candidate = parse("Team offsite from 12 September to 14 September").candidates.single()
        assertEquals(Classification.EVENT, candidate.classification)
    }

    @Test
    fun `the connectives are recognised, and the dash arrives folded`() {
        for (text in listOf(
            "Conference 12 September to 14 September 2026",
            "Conference 12 September until 14 September 2026",
            "Conference 12 September till 14 September 2026",
            "Conference 12 September through 14 September 2026",
            // Normalizer folds en and em dashes onto the hyphen before any rule sees them.
            "Conference 12 September – 14 September 2026",
            "Conference 12 September — 14 September 2026",
        )) {
            val candidate = parse(text).candidates.single()
            assertEquals(LocalDate.of(2026, 9, 14), candidate.endDate?.value, text)
        }
    }

    @Test
    fun `a range spanning a month boundary keeps both ends`() {
        val candidate = parse("Leave from 1 Oct 2026 until 5 Nov 2026").candidates.single()
        assertEquals(LocalDate.of(2026, 10, 1), candidate.date?.value)
        assertEquals(LocalDate.of(2026, 11, 5), candidate.endDate?.value)
    }

    // ----- what is not a range -----

    @Test
    fun `two dates merely listed are two candidates`() {
        // The connective list is closed rather than a distance, because no threshold
        // separates this from "5 September to 6 September" reliably.
        val candidates = parse("Review 5 September, final on 6 September 2026").candidates
        assertEquals(2, candidates.size)
        assertTrue(candidates.all { it.endDate == null })
    }

    @Test
    fun `a backwards pair is not a range`() {
        // A writer's slip, or two unrelated dates. Reading it as a range would produce an
        // item that ends before it starts.
        val candidates = parse("Moved from 14 September to 12 September 2026").candidates
        assertTrue(candidates.all { it.endDate == null }, "must not invent a backwards range")
    }

    @Test
    fun `a time range is not a date range`() {
        val candidate = parse("Diwali party on 8 Nov 2026 from 7 pm to 10 pm").candidates.single()
        assertNull(candidate.endDate, "7 pm to 10 pm is an end *time*")
        assertEquals(LocalTime.of(19, 0), candidate.time?.value)
        assertEquals(LocalTime.of(22, 0), candidate.endTime?.value)
    }

    @Test
    fun `a connective between things that are not dates joins nothing`() {
        val candidate = parse("Class test on 3 tarikh, chapters 1 to 4").candidates.single()
        assertNull(candidate.endDate)
        assertEquals(LocalDate.of(2026, 9, 3), candidate.date?.value)
    }

    // ----- the v1.22 dictation reading -----

    @Test
    fun `a dictated date with a comma before the day is read`() {
        // Android voice typing punctuates: "March, 8, 2030". Found on a device, SRS 1.21.
        val candidate = parse("Project sync on friday. March, 8, 2030 at 21:30").primary
        assertEquals(LocalDate.of(2030, 3, 8), candidate.date?.value)
        assertEquals(LocalTime.of(21, 30), candidate.time?.value)
    }

    @Test
    fun `a comma after a month with no year following is still a clause boundary`() {
        // The narrow half of the reading. Widening the main pattern instead would have made
        // this the 8th of March.
        val candidates = parse("In March, 8 people confirmed").candidates
        assertTrue(candidates.all { it.date == null }, "must not invent a date from a clause")
    }
}
