package com.latch.android.capture

import com.latch.core.model.CaptureLayer
import com.latch.core.model.ItemType
import com.latch.core.model.RoutingMode
import com.latch.data.AccountDefaults
import com.latch.parser.Classification
import com.latch.parser.DateParser
import com.latch.parser.ParseContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FR-506 row 3, FR-507 and FR-510 — the three requirements FR-807's note listed as unmet for
 * want of UI since SRS 1.12.
 *
 * All three are decided by pure functions, so what a device pass has to check is that the
 * controls are reachable and legible; what they *do* is settled here.
 */
class SheetEditsTest {

    private val context = ParseContext(
        now = LocalDateTime.parse("2026-09-02T09:00:00"),
        zone = ZoneId.of("Asia/Kolkata"),
    )
    private val today: LocalDate = LocalDate.parse("2026-09-02")

    private val defaults = AccountDefaults(
        accountId = "acct",
        email = "you@example.com",
        routingMode = RoutingMode.LATCH_CALENDAR,
        destinationCalendarId = "latch-cal",
        destinationCalendarName = "Latch",
        destinationCalendarColour = "#d50000",
        taskListId = "list-1",
    )

    private fun parse(text: String) = DateParser.parse(text, context)
    private fun captured(text: String) = CapturedText(text = text, layer = CaptureLayer.SHARE_SHEET)

    private fun draft(
        text: String,
        edits: SheetEdits = SheetEdits(),
        note: String = "Originally dated %1\$s.",
    ): List<com.latch.core.model.Item> {
        val result = parse(text).withEdits(edits, today)
        val drafted = draftItems(
            captured = captured(text),
            result = result,
            context = context,
            defaults = defaults,
            captureId = "capture",
            chainId = "chain",
            pastDateNoteTemplate = note,
            // FR-509b travels beside the parse rather than in it, so the helper has to carry
            // it explicitly — which is the whole reason it is a separate channel.
            titleOverrides = edits.titleOverrides,
        )
        return assertIs<DraftResult.Ready>(drafted).items
    }

    // ----- FR-506 row 3: a time with no day -----

    private val timeOnly = "call at 4pm"

    @Test
    fun `a time with no day is blocked before a date is given`() {
        val result = parse(timeOnly)
        // Name the condition that makes the check meaningful: the fixture must genuinely be a
        // time with no date, or this passes for the wrong reason.
        assertNull(result.primary.date, "the fixture must hold no date")
        assertNotNull(result.primary.time, "the fixture must hold a time")
        assertEquals(DraftBlocker.NEEDS_A_DATE, candidateBlocker(result.primary))
    }

    @Test
    fun `a suggestion chip completes the row and it becomes an ordinary event`() {
        val edits = SheetEdits(assignedDates = mapOf(0 to DateSuggestion.TOMORROW.dateFrom(today)))
        val result = parse(timeOnly).withEdits(edits, today)

        assertNull(candidateBlocker(result.primary), "the row should be writable now")
        assertEquals(Classification.EVENT, result.primary.classification)

        val item = draft(timeOnly, edits).single()
        assertEquals(ItemType.EVENT, item.type)
        assertEquals(LocalDateTime.parse("2026-09-03T16:00"), item.start)
    }

    @Test
    fun `the three chips are the three days they say they are`() {
        assertEquals(today, DateSuggestion.TODAY.dateFrom(today))
        assertEquals(LocalDate.parse("2026-09-03"), DateSuggestion.TOMORROW.dateFrom(today))
        assertEquals(LocalDate.parse("2026-09-09"), DateSuggestion.IN_A_WEEK.dateFrom(today))
    }

    @Test
    fun `assigning a date to one row leaves its neighbours alone`() {
        val result = parse("Kickoff 8 September 2027 at 9am")
        val twoRows = result.copy(
            candidates = listOf(
                result.primary,
                result.primary.copy(date = null, classification = Classification.EVENT_INCOMPLETE),
            ),
        )
        val edited = withAssignedDates(twoRows, mapOf(1 to LocalDate.parse("2027-10-01")), today)

        assertEquals(LocalDate.parse("2027-09-08"), edited.candidates[0].date?.value)
        assertEquals(LocalDate.parse("2027-10-01"), edited.candidates[1].date?.value)
    }

    // ----- FR-507: the Event/Task override -----

    private val timedEvent = "Kickoff 8 September 2027 at 9am"

    @Test
    fun `an event overridden to a task becomes a task with that due date`() {
        val items = draft(timedEvent, SheetEdits(typeOverrides = mapOf(0 to ItemType.TASK)))
        val item = items.single()
        assertEquals(ItemType.TASK, item.type)
        assertEquals(LocalDate.parse("2027-09-08"), item.dueDate)
        // §8.1: a task cannot carry a start, and `Item`'s own init refuses one.
        assertNull(item.start)
    }

    @Test
    fun `the cost of losing the time is available before the tap`() {
        val candidate = parse(timedEvent).primary
        assertEquals(TypeChangeCost.LOSES_TIME, typeChangeCost(candidate, ItemType.TASK))
        assertNull(typeChangeCost(candidate, ItemType.EVENT), "an event loses nothing")
    }

    @Test
    fun `a range overridden to a task loses its end date, and says so first`() {
        val text = "Trip from 20 September to 24 September 2027"
        val candidate = parse(text).primary
        assertNotNull(candidate.endDate, "the fixture must genuinely be a range")
        assertEquals(TypeChangeCost.LOSES_END_DATE, typeChangeCost(candidate, ItemType.TASK))

        val item = draft(text, SheetEdits(typeOverrides = mapOf(0 to ItemType.TASK))).single()
        assertEquals(ItemType.TASK, item.type)
        assertEquals(LocalDate.parse("2027-09-20"), item.dueDate)
    }

    @Test
    fun `a task overridden to an event with no time becomes an all-day event`() {
        val text = "Fees due 20 September 2027"
        assertEquals(ItemType.TASK, parse(text).primary.classification.itemType)

        val item = draft(text, SheetEdits(typeOverrides = mapOf(0 to ItemType.EVENT))).single()
        assertEquals(ItemType.EVENT, item.type)
        assertTrue(item.allDay)
        assertEquals(LocalDateTime.parse("2027-09-20T00:00"), item.start)
        // Google reads an all-day end as exclusive: one day on the 20th ends on the 21st.
        assertEquals(LocalDateTime.parse("2027-09-21T00:00"), item.end)
    }

    @Test
    fun `an override to a task is a second way out of FR-506 row three`() {
        // A consequence rather than a design, and recorded as one: a time with no day, made a
        // to-do, is an undated to-do and needs no date at all.
        val result = parse(timeOnly).withEdits(SheetEdits(typeOverrides = mapOf(0 to ItemType.TASK)), today)
        assertEquals(Classification.TASK_UNDATED, result.primary.classification)
        assertNull(candidateBlocker(result.primary))

        val item = draft(timeOnly, SheetEdits(typeOverrides = mapOf(0 to ItemType.TASK))).single()
        assertEquals(ItemType.TASK, item.type)
        assertNull(item.dueDate)
    }

    @Test
    fun `overriding to the type a row already has changes nothing`() {
        val result = parse(timedEvent)
        assertEquals(result, withTypeOverrides(result, mapOf(0 to ItemType.EVENT)))
    }

    @Test
    fun `an override tapped twice returns the row to where it was`() {
        // The fields are kept and only the classification is replaced, which is what makes this
        // reversible — a drafting step that had already discarded the time could not undo it.
        val result = parse(timedEvent)
        val there = withTypeOverrides(result, mapOf(0 to ItemType.TASK))
        val back = withTypeOverrides(there, mapOf(0 to ItemType.EVENT))
        assertEquals(result.primary.time, back.primary.time)
        assertEquals(Classification.EVENT, back.primary.classification)
    }

    // ----- FR-510: the past-date follow-up -----

    private val pastText = "the order dated 12 March"

    @Test
    fun `a past date is a past date, which is what AC-04 rests on`() {
        val candidate = parse(pastText).primary
        // FR-513: a year-less date resolves to the current year and is never rolled forward.
        assertEquals(LocalDate.parse("2026-03-12"), candidate.date?.value)
        assertTrue(candidate.isPast)
    }

    @Test
    fun `AC-04 - a past date creates no dated item, only a follow-up`() {
        val item = draft(pastText).single()
        assertEquals(ItemType.TASK, item.type)
        assertNull(item.dueDate, "a follow-up is undated; picking a date would be a guess")
        assertNull(item.start)
    }

    @Test
    fun `the past date is recorded in the notes`() {
        // FR-510's own words: "with the past date recorded in the notes".
        val item = draft(pastText).single()
        val notes = assertNotNull(item.notes)
        assertTrue(notes.contains("12"), "the note should name the date: $notes")
        assertTrue(notes.contains("March") || notes.contains("Mar"), "the note should name the month: $notes")
    }

    @Test
    fun `a past row is never blocked, because a follow-up needs no date`() {
        assertNull(candidateBlocker(parse(pastText).primary))
    }

    @Test
    fun `a past row cannot be overridden into an event`() {
        // FR-510 outranks FR-507: an event is a dated item, and the requirement forbids
        // creating one for a past date. The override is refused rather than accepted and then
        // quietly undone by the drafting step.
        val candidate = parse(pastText).primary
        assertTrue(candidate.isPast)
        assertEquals(false, canOverrideTo(candidate, ItemType.EVENT))

        val result = parse(pastText).withEdits(SheetEdits(typeOverrides = mapOf(0 to ItemType.EVENT)), today)
        assertEquals(ItemType.TASK, result.primary.classification.itemType)
    }

    @Test
    fun `FR-510 is applied per candidate, so a past date beside a future one is still a follow-up`() {
        // SRS 1.44. The requirement's "where the only date found is in the past" was written
        // when a capture produced one item; with FR-511 a capture can hold both, and writing
        // the past one as a dated item because it was not the *only* date is exactly what this
        // requirement exists to prevent.
        val text = "Invoice dated 12 March, payment due 20 September 2027"
        val result = parse(text)
        assertEquals(2, result.candidates.size, "the fixture must hold two dates")
        assertTrue(result.candidates.any { it.isPast }, "one of them must be past")

        val items = draft(text)
        val followUp = items.single { it.dueDate == null }
        val dated = items.single { it.dueDate != null }
        assertEquals(ItemType.TASK, followUp.type)
        assertNotNull(followUp.notes)
        assertEquals(LocalDate.parse("2027-09-20"), dated.dueDate)
        assertNull(dated.notes, "an ordinary row carries no past-date note")
    }

    @Test
    fun `the note is a template the app supplies, so NFR-402 holds`() {
        val item = draft(pastText, note = "").single()
        assertNull(item.notes, "no template means no note, rather than English baked into :app")
    }

    // ----- FR-509b: the title, corrected before anything is written -----

    @Test
    fun `an edited title is what the item is written with`() {
        val items = draft(timedEvent, SheetEdits(titleOverrides = mapOf(0 to "Status review")))
        assertEquals("Status review", items.single().title)
    }

    @Test
    fun `an edited title does NOT change latch item_key`() {
        // FR-509b's clause with teeth. §7.2 writes metadata once at insert and forbids
        // recomputing a key, so if a correction moved it, fixing an OCR error would make the
        // item permanently unmatchable by FR-804 — a later reschedule would create a second
        // item — and two users correcting one garbled capture differently would derive
        // different identities for the same message.
        val result = parse(timedEvent)
        val before = itemKeyTitle(captured(timedEvent), result)

        val edited = result.withEdits(SheetEdits(titleOverrides = mapOf(0 to "Something else entirely")), today)
        val after = itemKeyTitle(captured(timedEvent), edited)

        assertEquals(before, after)
    }

    @Test
    fun `an edited title outranks FR-206's subject line`() {
        // The user has seen what the app derived and replaced it; nothing inferred outranks that.
        val withSubject = CapturedText(
            text = timedEvent,
            layer = CaptureLayer.SHARE_SHEET,
            preferredTitle = "Weekly sync",
        )
        val result = parse(timedEvent)
        assertEquals("Weekly sync", titleFor(withSubject, result, result.primary))
        assertEquals("Corrected", titleFor(withSubject, result, result.primary, "Corrected"))
    }

    @Test
    fun `a subject line still governs the item key, because it is not a correction`() {
        // §7.2 step 1: a subject comes from the sending application, not from the user, so it
        // remains the key's input. FR-509b is explicit that this is not an exception it invents.
        val withSubject = CapturedText(
            text = timedEvent,
            layer = CaptureLayer.SHARE_SHEET,
            preferredTitle = "Weekly sync",
        )
        assertEquals("Weekly sync", itemKeyTitle(withSubject, parse(timedEvent)))
    }

    @Test
    fun `a blank edit reverts to the derived title rather than writing an empty one`() {
        // A cleared field is a user midway through retyping, not a request for an item with no
        // name. The screen and the write must agree about that, so the rule lives in `titleFor`.
        val result = parse(timedEvent)
        val derived = titleFor(captured(timedEvent), result, result.primary)
        assertEquals(derived, titleFor(captured(timedEvent), result, result.primary, "   "))
        assertEquals(derived, draft(timedEvent, SheetEdits(titleOverrides = mapOf(0 to " "))).single().title)
    }

    @Test
    fun `an edit is trimmed`() {
        val items = draft(timedEvent, SheetEdits(titleOverrides = mapOf(0 to "  Status review  ")))
        assertEquals("Status review", items.single().title)
    }

    @Test
    fun `editing one row of a chain leaves the others alone`() {
        // FR-509a gives an OCR capture a title per row; FR-509b makes each editable on its own.
        // The override is keyed on the **candidate** index, which is not the position in the
        // chain once unticked rows are dropped — this is what that distinction is for.
        val text = "Invoice dated 20 September 2027, review on 24 September 2027"
        val result = parse(text)
        assertEquals(2, result.candidates.size, "the fixture must hold two dates")

        val items = draft(text, SheetEdits(titleOverrides = mapOf(1 to "Second only")))
        assertEquals("Second only", items[1].title)
        assertTrue(items[0].title != "Second only")
    }

    @Test
    fun `an override for an unticked row does not shift onto its neighbour`() {
        // The failure this pins: keying the override on the drafted position rather than the
        // candidate index would move row 1's correction onto row 0 the moment row 0 was unticked.
        val text = "Invoice dated 20 September 2027, review on 24 September 2027"
        val result = parse(text)
        val drafted = draftItems(
            captured = captured(text),
            result = result,
            context = context,
            defaults = defaults,
            captureId = "capture",
            chainId = "chain",
            selected = setOf(1),
            titleOverrides = mapOf(1 to "Second only"),
        )
        assertEquals("Second only", assertIs<DraftResult.Ready>(drafted).items.single().title)
    }
}
