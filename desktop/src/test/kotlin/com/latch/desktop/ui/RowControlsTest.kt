package com.latch.desktop.ui

import com.latch.core.model.ItemType
import com.latch.desktop.capture.DesktopCapture
import com.latch.parser.DateParser
import com.latch.parser.ParseContext
import com.latch.wire.DateSuggestion
import com.latch.wire.SheetEdits
import com.latch.wire.dateFrom
import com.latch.wire.withEdits
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FR-507's override and FR-506 row 3's picker, as the sheet derives them.
 *
 * Both were **dead capability** on this client until 3 Sep 2026: `popupModel` computed
 * `canOverride` and accepted `typeOverrides`, and nothing rendered a control for either, so the
 * model read as though the features existed. These tests pin what the controls now feed on —
 * the rendering itself is Swing and is device-owed, but everything it decides is here.
 */
class RowControlsTest {

    private val now = LocalDateTime.of(2026, 9, 3, 9, 0)
    private val today: LocalDate = now.toLocalDate()
    private val context = ParseContext(now = now)

    private fun sheet(text: String, edits: SheetEdits = SheetEdits()): PopupModel {
        val capture = DesktopCapture(text)
        val edited = DateParser.parse(text, context).withEdits(edits, today)
        return popupModel(
            capture, edited, edited.candidates.indices.toSet(), today,
            edits.titleOverrides, edits.typeOverrides,
        )
    }

    // ---- FR-507 -------------------------------------------------------------------------------

    @Test
    fun `an event row offers to become a to-do, and says what that costs first`() {
        val row = sheet("Kickoff 8 September 2027 at 9am").rows.single()
        assertEquals(DesktopStrings.BADGE_EVENT, row.badge)
        assertTrue(row.canOverride)
        assertEquals(ItemType.TASK, row.otherType)
        // §8.1: this is the one place a user action deliberately discards something they wrote,
        // so the cost has to be readable while the badge still says EVENT.
        assertEquals(DesktopStrings.COST_TIME, row.overrideCost)
    }

    @Test
    fun `overriding flips the badge and keeps the date`() {
        val plain = sheet("Kickoff 8 September 2027 at 9am").rows.single()
        val flipped = sheet(
            "Kickoff 8 September 2027 at 9am",
            SheetEdits(typeOverrides = mapOf(0 to ItemType.TASK)),
        ).rows.single()

        assertEquals(DesktopStrings.BADGE_TASK, flipped.badge)
        assertEquals(ItemType.EVENT, flipped.otherType, "it must offer the way back")
        assertTrue(flipped.whenLine.startsWith(plain.whenLine.take(11)))
    }

    @Test
    fun `a range says it loses its closing day`() {
        val row = sheet("Trip from 20 September to 24 September 2027").rows.single()
        assertEquals(DesktopStrings.COST_END_DATE, row.overrideCost)
    }

    @Test
    fun `a to-do offers to become an event at no cost`() {
        val row = sheet("Fees due 20 September 2027").rows.single()
        assertEquals(DesktopStrings.BADGE_TASK, row.badge)
        assertEquals(ItemType.EVENT, row.otherType)
        // Nothing is discarded going the other way, so there is nothing to warn about.
        assertNull(row.overrideCost)
    }

    @Test
    fun `FR-510 outranks FR-507 — a past row cannot become an event`() {
        val row = sheet("the order dated 12 March").rows.firstOrNull()
        assertNotNull(row)
        assertFalse(row.canOverride, "a past date must not be switchable to an event")
        assertEquals(DesktopStrings.PAST_DATE, row.note)
    }

    @Test
    fun `in a chain each row overrides on its own`() {
        val text = "PTM on 14 September 2027. Fees due 20 September 2027."
        val rows = sheet(text, SheetEdits(typeOverrides = mapOf(0 to ItemType.TASK))).rows
        assertTrue(rows.size >= 2)
        assertEquals(DesktopStrings.BADGE_TASK, rows[0].badge)
        assertEquals(sheet(text).rows[1].badge, rows[1].badge, "the neighbour must be untouched")
    }

    // ---- FR-506 row 3 --------------------------------------------------------------------------

    @Test
    fun `a time with no day asks for one and says it has none`() {
        val row = sheet("call at 4pm").rows.firstOrNull()
        assertNotNull(row)
        assertTrue(row.needsDate, "the row must offer a way out, not only complain")
        assertEquals(DesktopStrings.NO_DAY_YET, row.whenLine)
        assertEquals(DesktopStrings.PICK_A_DAY, row.note)
    }

    @Test
    fun `a suggestion completes the row and keeps the time`() {
        val assigned = sheet(
            "call at 4pm",
            SheetEdits(assignedDates = mapOf(0 to DateSuggestion.TOMORROW.dateFrom(today))),
        ).rows.single()

        assertFalse(assigned.needsDate)
        assertTrue(assigned.whenLine.startsWith("4 Sep"), assigned.whenLine)
        assertTrue(assigned.whenLine.endsWith("16:00"), assigned.whenLine)
        assertNull(assigned.note)
    }

    @Test
    fun `the three suggestions are the days they claim to be`() {
        assertEquals(today, DateSuggestion.TODAY.dateFrom(today))
        assertEquals(today.plusDays(1), DateSuggestion.TOMORROW.dateFrom(today))
        assertEquals(today.plusDays(7), DateSuggestion.IN_A_WEEK.dateFrom(today))
    }

    @Test
    fun `completing the row unblocks the save`() {
        // The condition that would make this fail: a picker that fills the date and leaves the
        // sheet unsaveable, which is what "inert" would look like from the user's side.
        val before = sheet("call at 4pm")
        val after = sheet(
            "call at 4pm",
            SheetEdits(assignedDates = mapOf(0 to today.plusDays(1))),
        )
        assertFalse(before.canSave, before.blocker.orEmpty())
        assertTrue(after.canSave)
    }

    @Test
    fun `the picker cannot overwrite a date the parser already found`() {
        // `withAssignedDates` fills only a row whose date is null, which is right: FR-506 row 3
        // is about a row that has *no* day, and a picker that could move a date the writer
        // actually wrote would be a different and much larger power.
        //
        // Two earlier fixtures for this test were wrong rather than the code — "call at 4pm.
        // Fees due 20 September 2027." does not yield a dateless row beside a dated one,
        // because the lone time pairs with the date — and the premise is worth recording
        // alongside what survived it.
        val text = "PTM on 14 September 2027. Fees due 20 September 2027."
        val plain = sheet(text)
        assertTrue(plain.rows.size >= 2, plain.rows.map { it.whenLine }.toString())

        val edited = sheet(text, SheetEdits(assignedDates = mapOf(0 to today.plusDays(1))))
        assertEquals(plain.rows[0].whenLine, edited.rows[0].whenLine, "a written date was moved")
        assertEquals(plain.rows[1].whenLine, edited.rows[1].whenLine, "the neighbour moved")
    }
}
