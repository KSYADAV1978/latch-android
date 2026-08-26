package com.latch.parser

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The §10 acceptance criteria that can be settled inside the parser. The rest (AC-07 to
 * AC-12, AC-17 to AC-21) are about writes, routing and network behaviour and belong to the
 * app once those layers exist.
 */
class AcceptanceCriteriaTest {

    private val context = ParseContext(
        now = LocalDateTime.of(2026, 8, 25, 9, 0), // Tuesday
        zone = ZoneId.of("Asia/Kolkata"),
        dateOrder = DateOrder.DAY_FIRST,
    )

    @Test
    fun `AC-01 - text with a date and a time becomes an Event`() {
        val result = DateParser.parse("PTM on Friday 12 September at 11:00 AM", context)

        assertEquals(1, result.candidates.size, "the weekday restates the date, it is not a second item")
        assertEquals(Classification.EVENT, result.primary.classification)
        assertEquals(LocalDate.of(2026, 9, 12), result.primary.date?.value)
        assertEquals(LocalTime.of(11, 0), result.primary.time?.value)
    }

    @Test
    fun `AC-02 - the same text without a time becomes a Task with a due date`() {
        val result = DateParser.parse("PTM on Friday 12 September", context)

        assertEquals(Classification.TASK_WITH_DUE_DATE, result.primary.classification)
        assertEquals(LocalDate.of(2026, 9, 12), result.primary.date?.value)
        assertEquals(null, result.primary.time, "Google Tasks discards the time (SRS §8.1)")
    }

    @Test
    fun `AC-03 - text with no date produces an undated task, never a guessed date`() {
        val result = DateParser.parse("Remember to renew the gym membership", context)

        assertEquals(Classification.TASK_UNDATED, result.primary.classification)
        assertEquals(null, result.primary.date, "design principle 1: never invent a date")
    }

    @Test
    fun `AC-04 - a past date is resolved and flagged, not rolled forward`() {
        val result = DateParser.parse("the order dated 12 March", context)

        assertEquals(LocalDate.of(2026, 3, 12), result.primary.date?.value)
        assertTrue(result.primary.isPast, "FR-510 depends on the parser reporting past-ness")
    }

    @Test
    fun `AC-05 - three dates in one capture become three candidates`() {
        val result = DateParser.parse(
            "Fees due 5 September, sports day 19 September, results 30 September",
            context,
        )

        assertEquals(
            listOf(
                LocalDate.of(2026, 9, 5),
                LocalDate.of(2026, 9, 19),
                LocalDate.of(2026, 9, 30),
            ),
            result.candidates.mapNotNull { it.date?.value },
        )
    }

    @Test
    fun `AC-13 - kal 4 baje meeting resolves to tomorrow at 16 00`() {
        val result = DateParser.parse("kal 4 baje meeting", context)

        assertEquals(LocalDate.of(2026, 8, 26), result.primary.date?.value)
        assertEquals(LocalTime.of(16, 0), result.primary.time?.value)
        assertEquals(Classification.EVENT, result.primary.classification)
    }

    @Test
    fun `AC-14 - 05 slash 09 is read as 5 September under DD-MM and flagged ambiguous`() {
        val result = DateParser.parse("05/09", context)

        assertEquals(LocalDate.of(2026, 9, 5), result.primary.date?.value)
        assertTrue(
            result.primary.ambiguousOrder,
            "FR-504 requires the chosen interpretation to be shown to the user",
        )
    }

    @Test
    fun `AC-14b - the same string under MM-DD is read as 9 May`() {
        val result = DateParser.parse("05/09", context.copy(dateOrder = DateOrder.MONTH_FIRST))

        assertEquals(LocalDate.of(2026, 5, 9), result.primary.date?.value)
    }

    @Test
    fun `25 slash 12 is unambiguous whatever the date-order setting says`() {
        val dayFirst = DateParser.parse("25/12", context).primary
        val monthFirst = DateParser.parse("25/12", context.copy(dateOrder = DateOrder.MONTH_FIRST)).primary

        assertEquals(LocalDate.of(2026, 12, 25), dayFirst.date?.value)
        assertEquals(LocalDate.of(2026, 12, 25), monthFirst.date?.value)
        assertFalse(dayFirst.ambiguousOrder)
    }

    @Test
    fun `a working-day offset is left for the recipes module to resolve`() {
        val result = DateParser.parse("Send the draft 3 working days before the meeting", context)

        val offset = requireNotNull(result.pendingOffset).value
        assertEquals(3, offset.value)
        assertEquals(com.latch.core.model.OffsetUnit.WORKING_DAYS, offset.unit)
        assertEquals(com.latch.core.model.Direction.BEFORE, offset.direction)
    }

    @Test
    fun `FR-512 - a low-confidence capture stays below the inbox threshold`() {
        val vague = DateParser.parse("catch up next week", context)

        assertTrue(
            vague.overallConfidence < context.confidenceThreshold,
            "an ambiguous relative reference should land in the Capture Inbox, not on the calendar",
        )
    }
}
