package com.latch.parser

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Which of several dates in one capture wins, and which weekday belongs to which date.
 *
 * The corpus can pin what the **primary** comes out as, and does. What it cannot express is
 * structure — how many candidates there are, whose span covers what — and that is where the
 * defect this file was written for actually lived: two candidates whose spans overlapped each
 * other, which the corpus could not have seen because it only ever looks at one of them.
 */
class CandidateRankingTest {

    private val context = ParseContext(
        now = LocalDateTime.of(2026, 8, 25, 9, 0), // a Tuesday
        zone = ZoneId.of("Asia/Kolkata"),
        dateOrder = DateOrder.DAY_FIRST,
    )

    private fun parse(text: String) = DateParser.parse(text, context)

    /** The reported capture, as two lines, exactly as it was shared. */
    private val reported =
        "from August 31 to September 6, 2026\nDate: Monday, August 31, 2026 at 21:30"

    // ----- the invariant that was being violated -----

    @Test
    fun `candidate spans never overlap each other`() {
        // The accepted-date loop guarantees this, and weekday absorption used to break it
        // again afterwards by letting one weekday be absorbed into two different dates. An
        // overlapping span is not cosmetic: §7.2's item_key is the title with the primary's
        // span blanked out, so a span reaching into a neighbouring date blanks part of that
        // date and silently changes an identity that cannot be corrected once written.
        val texts = listOf(
            reported,
            "Sprint ends September 6, 2026 and Monday, August 31, 2026 is the review",
            "PTM on Friday 12 September at 11:00 AM",
            "Gym Friday, and the review on 12 September",
            "Call Monday 31 August, then Tuesday 1 September, then 6 September",
        )

        for (text in texts) {
            val spans = parse(text).candidates.mapNotNull { it.date?.span }.sortedBy { it.first }
            spans.zipWithNext().forEach { (left, right) ->
                assertTrue(
                    left.last < right.first,
                    "spans $left and $right overlap in \"$text\"",
                )
            }
        }
    }

    // ----- the reported failure -----

    @Test
    fun `the reported capture opens on the dated line, not the end of the range`() {
        val result = parse(reported)

        val primary = result.primary
        assertEquals(LocalDate.of(2026, 8, 31), primary.date?.value)
        assertEquals(LocalTime.of(21, 30), primary.time?.value)
        assertEquals(Classification.EVENT, primary.classification)
    }

    @Test
    fun `both dates are still offered, in the order the capture reads`() {
        // FR-511 is not built, but the parser has always returned every date for it, and the
        // list must stay in document order — the ranking decides which one opens, not what
        // order the user will eventually see the checkboxes in.
        val dates = parse(reported).candidates.map { it.date?.value }
        assertEquals(listOf(LocalDate.of(2026, 8, 31), LocalDate.of(2026, 9, 6)), dates)
    }

    @Test
    fun `one date written twice is one candidate, positioned at its first mention`() {
        // 31 August appears twice: once without a year near the start, once with a year near
        // the end. Taking the stronger mention whole used to take its position too, which put
        // 31 August behind a 6 September introduced after it.
        val candidates = parse(reported).candidates
        assertEquals(2, candidates.size)
        assertEquals(1, candidates.count { it.date?.value == LocalDate.of(2026, 8, 31) })
    }

    // ----- exclusive weekday attachment -----

    @Test
    fun `a weekday is absorbed by the date it actually falls on`() {
        // Both dates are inside the corroboration window of the one "Monday". 31 August 2026
        // is a Monday; 6 September 2026 is a Sunday. Proximity alone used to give it to both.
        val text = "Sprint ends September 6, 2026 and Monday, August 31, 2026 is the review"
        val result = parse(text)

        assertEquals(2, result.candidates.size, "Monday restates 31 August; it is not a third date")

        val august = assertNotNull(result.candidates.first { it.date?.value == LocalDate.of(2026, 8, 31) }.date)
        val september = assertNotNull(result.candidates.first { it.date?.value == LocalDate.of(2026, 9, 6) }.date)

        val mondayAt = text.lowercase().indexOf("monday")
        assertTrue(mondayAt in assertNotNull(august.span), "31 August should have absorbed Monday")
        assertTrue(mondayAt !in assertNotNull(september.span), "6 September is a Sunday and must not claim Monday")
    }

    @Test
    fun `a weekday matching no date stays a date of its own`() {
        // 12 September 2026 is a Saturday and "Friday" is nowhere near it in the text, so this
        // is two commitments — which is what the corroboration window was always for.
        val result = parse("Gym Friday, and the review on 12 September")

        assertEquals(2, result.candidates.size)
        assertTrue(result.candidates.any { it.date?.value?.dayOfWeek == DayOfWeek.FRIDAY })
        assertTrue(result.candidates.any { it.date?.value == LocalDate.of(2026, 9, 12) })
    }

    @Test
    fun `a weekday that contradicts the date it is written into is still the same phrase`() {
        // SRS §7.2's own example, and 12 September 2026 is a Saturday — the spec's canonical
        // corroboration case has a weekday matching nothing. Reading the writer's slip as a
        // second commitment would invent an item, and would drop "Friday" out of the blanked
        // span that §7.2 v1.11 calls load-bearing for item_key.
        val text = "PTM on Friday 12 September at 11:00 AM"
        val result = parse(text)

        assertEquals(1, result.candidates.size, "one phrase, one commitment")
        val span = assertNotNull(result.primary.date?.span)
        assertTrue(text.lowercase().indexOf("friday") in span, "the span must cover the weekday")
    }

    // ----- the ranking, key by key -----

    @Test
    fun `a date with a time beats a date without one`() {
        val result = parse("Report due 6 September, review call on 31 August at 9pm")
        assertEquals(LocalDate.of(2026, 8, 31), result.primary.date?.value)
        assertEquals(LocalTime.of(21, 0), result.primary.time?.value)
    }

    @Test
    fun `a written date beats one this app worked out`() {
        // Design principle 1: the app's own arithmetic does not outrank what the user wrote.
        val result = parse("Gym Friday, and the review on 12 September")
        assertEquals(LocalDate.of(2026, 9, 12), result.primary.date?.value)
        assertTrue(result.primary.isExplicit)
    }

    @Test
    fun `position beats a later date that happens to carry a year`() {
        // FR-505's confidence is deliberately not a ranking key. A year is what separates
        // CERTAIN from HIGH, and a range's year is written once at the end, so ranking on it
        // would make the end of every "from A to B, 2026" the primary.
        val result = parse("Review 5 September, final on 6 September 2026")
        assertEquals(LocalDate.of(2026, 9, 5), result.primary.date?.value)
    }

    @Test
    fun `a range whose year is written once still opens on its first date`() {
        // The shape that ruled confidence out as a key: only "September 6" carries the year.
        val result = parse("from August 31 to September 6, 2026")
        assertEquals(LocalDate.of(2026, 8, 31), result.primary.date?.value)
    }

    @Test
    fun `a single date is the primary whatever the ranking says`() {
        // The ranking must not disturb the overwhelmingly common case.
        assertEquals(LocalDate.of(2026, 9, 12), parse("Review on 12 September").primary.date?.value)
        assertEquals(LocalDate.of(2026, 8, 26), parse("Call tomorrow").primary.date?.value)
    }
}
