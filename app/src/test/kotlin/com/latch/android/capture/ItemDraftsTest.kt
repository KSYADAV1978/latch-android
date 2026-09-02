package com.latch.android.capture

import com.latch.core.model.CaptureLayer
import com.latch.core.model.ItemType
import com.latch.core.model.RoutingMode
import com.latch.data.AccountDefaults
import com.latch.parser.Classification
import com.latch.parser.Confidence
import com.latch.parser.DateParser
import com.latch.parser.DatedCandidate
import com.latch.parser.Field
import com.latch.parser.ParseContext
import com.latch.parser.ParseResult
import com.latch.wire.itemKeyOf
import com.latch.wire.itemKeyTitle
import com.latch.wire.sourceHashOf
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FR-506's rows turned into items. Pure Kotlin and clock-free — the ids and the capture time
 * are the caller's to mint — so this runs on the JVM beside the setup reducer.
 */
/** One image row per line, which is what §7.2's reading-order rule guarantees. */
private const val NEWLINE = "\n"

class ItemDraftsTest {

    private val defaults = AccountDefaults(
        accountId = "acct",
        email = "you@example.com",
        routingMode = RoutingMode.LATCH_CALENDAR,
        destinationCalendarId = "latch-cal",
        destinationCalendarName = "Latch",
        destinationCalendarColour = "#d50000",
        taskListId = "list-1",
    )

    private val context = ParseContext(now = LocalDateTime.parse("2026-08-26T09:00:00"))
    private val date = LocalDate.parse("2026-09-05")

    private fun captured(text: String = "Team sync 5 Sep at 3pm", subject: String? = null) =
        CapturedText(text = text, layer = CaptureLayer.SHARE_SHEET, preferredTitle = subject)

    private fun result(
        candidate: DatedCandidate,
        title: String = "Team sync",
        location: String? = null,
    ) = ParseResult(
        title = Field(title, Confidence.HIGH),
        candidates = listOf(candidate),
        location = location?.let { Field(it, Confidence.MEDIUM) },
    )

    // ----- FR-509a: an OCR capture titles from the row carrying its own date -----

    /** A recognised screenshot as assembled: one image row per line, chrome first. */
    private val screenshot = listOf(
        "Sharma Ji",
        "online",
        "Paid the uniform bill, Rs 12,500 in total.",
        "Yes. PTM on Monday 14 September 2026.",
        "Fees due 20/09/2027.",
    ).joinToString(NEWLINE)

    private fun ocrCapture(text: String = screenshot, subject: String? = null) = CapturedText(
        text = text,
        layer = CaptureLayer.SHARE_SHEET,
        preferredTitle = subject,
        ocrUsed = true,
    )

    private fun titlesFrom(captured: CapturedText, text: String = screenshot): List<String> {
        val parsed = DateParser.parse(text, context)
        val drafted = draftItems(captured, parsed, context, defaults, "cap", "chain")
        return (drafted as DraftResult.Ready).items.map { it.title }
    }

    @Test
    fun `an ocr title comes from its own date's row, not the top of the screen`() {
        // The observed defect: the title was "Sharma Ji online Paid the uniform bill, Rs
        // 12,500 in total." - the chat header and the first message, neither of which the
        // user captured, and both of which FR-805b had just excluded from the description.
        val titles = titlesFrom(ocrCapture())

        assertTrue(titles.isNotEmpty(), "nothing was drafted")
        titles.forEach { title ->
            assertFalse(title.contains("Sharma Ji"), "the chat header reached the title: <$title>")
            assertFalse(title.contains("12,500"), "the amount paid reached the title: <$title>")
        }
        assertTrue(titles.any { it.contains("PTM") }, "no title came from a date's own row: $titles")
    }

    @Test
    fun `blanking a date does not leave its punctuation stranded`() {
        // Observed on a device: "Yes. PTM on Monday 14 September 2026." blanked of its span
        // became "Yes. PTM on ." and that stray full stop reached a real title.
        val titles = titlesFrom(ocrCapture())

        titles.forEach { title ->
            assertFalse(title.contains(" ."), "orphaned punctuation in <$title>")
            assertFalse(title.endsWith("."), "a title should not end on the date's full stop: <$title>")
            assertFalse(title.contains("  "), "the date left a gap in <$title>")
        }
    }

    @Test
    fun `a row that is only a date falls back to the row above it`() {
        // "Fees due 20/09/2027" blanked of its span still says "Fees due"; a bare
        // "20/09/2027" says nothing, and a title of punctuation is worse than the fallback.
        val bare = listOf("School office", "Circular attached", "20/09/2027").joinToString(NEWLINE)
        val titles = titlesFrom(ocrCapture(bare), bare)

        assertTrue(
            titles.single().contains("Circular"),
            "a bare date row should borrow the row above it, got: ${titles.single()}",
        )
    }

    @Test
    fun `a typed capture keeps FR-509's title exactly`() {
        // FR-509a narrows nothing for text. This is the regression guard: the overwhelming
        // majority of captures are typed, and their titles must not move.
        val typed = CapturedText(text = screenshot, layer = CaptureLayer.SHARE_SHEET)
        val parsed = DateParser.parse(screenshot, context)

        val drafted = draftItems(typed, parsed, context, defaults, "cap", "chain")

        (drafted as DraftResult.Ready).items.forEach {
            assertEquals(parsed.title.value, it.title, "a typed capture's title changed")
        }
    }

    @Test
    fun `a subject line still wins over the date's row`() {
        // FR-206: a mail client's subject names the thing better than any row of a page, and
        // a shared PDF carries one. FR-509a must not take that away.
        val titles = titlesFrom(ocrCapture(subject = "Term dates 2026-27"))

        titles.forEach { assertEquals("Term dates 2026-27", it) }
    }

    @Test
    fun `the item key is unchanged by the per-item titles`() {
        // SRS 1.41's decision, asserted rather than assumed: one capture still yields one
        // key, so a chain shares it and FR-804 still identifies what the message is about
        // rather than which occurrence of it.
        val captured = ocrCapture()
        val parsed = DateParser.parse(screenshot, context)

        val key = itemKeyOf(itemKeyTitle(captured, parsed))
        val titles = titlesFrom(captured)

        assertTrue(titles.size > 1, "this test needs a chain to be meaningful")
        assertTrue(titles.toSet().size > 1, "the titles should differ per row")
        assertEquals(key, itemKeyOf(itemKeyTitle(captured, parsed)), "item_key must not move")
    }

    private fun draft(candidate: DatedCandidate, title: String = "Team sync", location: String? = null) =
        draftItems(
            captured = captured(),
            result = result(candidate, title, location),
            context = context,
            defaults = defaults,
            captureId = "cap-1",
            chainId = "chain-1",
        )

    private fun single(candidate: DatedCandidate) =
        (draft(candidate) as DraftResult.Ready).items.single()

    // ----- FR-506 row by row -----

    @Test
    fun `date and time become a timed event`() {
        val item = single(
            DatedCandidate(
                date = Field(date, Confidence.HIGH),
                time = Field(LocalTime.parse("15:00"), Confidence.HIGH),
                classification = Classification.EVENT,
            )
        )

        assertEquals(ItemType.EVENT, item.type)
        assertEquals(LocalDateTime.parse("2026-09-05T15:00"), item.start)
        assertEquals("latch-cal", item.calendarId)
        assertNull(item.dueDate)
        assertTrue(!item.allDay)
    }

    @Test
    fun `a date alone becomes a task with a due date`() {
        val item = single(
            DatedCandidate(
                date = Field(date, Confidence.HIGH),
                classification = Classification.TASK_WITH_DUE_DATE,
            )
        )

        assertEquals(ItemType.TASK, item.type)
        assertEquals(date, item.dueDate)
        assertEquals("list-1", item.taskListId)
        // Google Tasks discards a time (§9.1), and Item's init rejects one outright.
        assertNull(item.start)
    }

    @Test
    fun `neither becomes an undated task`() {
        val item = single(DatedCandidate(date = null, classification = Classification.TASK_UNDATED))

        assertEquals(ItemType.TASK, item.type)
        assertNull(item.dueDate)
        assertNull(item.start)
    }

    @Test
    fun `an incomplete event that still has a date becomes an all-day event`() {
        // classify() tests ambiguousRelative before it tests date-and-time, so this row can
        // arrive carrying a date. Without a time there is nothing to put on a clock.
        val item = single(
            DatedCandidate(
                date = Field(date, Confidence.LOW),
                classification = Classification.EVENT_INCOMPLETE,
                ambiguousRelative = true,
            )
        )

        assertEquals(ItemType.EVENT, item.type)
        assertTrue(item.allDay)
        assertEquals(LocalDateTime.parse("2026-09-05T00:00"), item.start)
    }

    @Test
    fun `an incomplete event with no date cannot be drafted`() {
        val blocked = draft(
            DatedCandidate(
                date = null,
                time = Field(LocalTime.parse("15:00"), Confidence.HIGH),
                classification = Classification.EVENT_INCOMPLETE,
            )
        )

        assertEquals(DraftResult.Blocked(DraftBlocker.NEEDS_A_DATE), blocked)
    }

    @Test
    fun `the screen and the draft agree on what cannot be saved`() {
        // One source of truth: a screen that decided separately would drift from the mapping.
        val noDate = result(
            DatedCandidate(
                date = null,
                time = Field(LocalTime.parse("15:00"), Confidence.HIGH),
                classification = Classification.EVENT_INCOMPLETE,
            )
        )
        assertEquals(DraftBlocker.NEEDS_A_DATE, draftBlocker(noDate))

        val dated = result(
            DatedCandidate(date = Field(date, Confidence.HIGH), classification = Classification.TASK_WITH_DUE_DATE)
        )
        assertNull(draftBlocker(dated))
    }

    // ----- The end of an event -----

    @Test
    fun `a second time in the text sets the end`() {
        val item = single(
            DatedCandidate(
                date = Field(date, Confidence.HIGH),
                time = Field(LocalTime.parse("15:00"), Confidence.HIGH),
                endTime = Field(LocalTime.parse("16:30"), Confidence.HIGH),
                classification = Classification.EVENT,
            )
        )

        assertEquals(LocalDateTime.parse("2026-09-05T16:30"), item.end)
    }

    @Test
    fun `a date range becomes an all-day event ending the day after its close`() {
        // SRS 1.23: the parser holds the end inclusive — 12 to 14 September ends *on* the
        // 14th — and Google's all-day API reads the end date as exclusive. The +1 lives here
        // and only here, which is what stops the off-by-one being discovered on a phone.
        val item = single(
            DatedCandidate(
                date = Field(LocalDate.parse("2026-09-12"), Confidence.HIGH),
                endDate = Field(LocalDate.parse("2026-09-14"), Confidence.HIGH),
                classification = Classification.EVENT,
            )
        )

        assertTrue(item.allDay)
        assertEquals(LocalDateTime.parse("2026-09-12T00:00"), item.start)
        assertEquals(LocalDateTime.parse("2026-09-15T00:00"), item.end)
        // The single-day form of the same rule is `an all-day event ends on the following day`.
    }

    @Test
    fun `an end time before the start rolls the end to the next day`() {
        // "from 11 pm to 1 am" is one sitting. Same-day arithmetic made this end two hours
        // before it began, which Google would have taken literally.
        val item = single(
            DatedCandidate(
                date = Field(date, Confidence.HIGH),
                time = Field(LocalTime.parse("23:00"), Confidence.HIGH),
                endTime = Field(LocalTime.parse("01:00"), Confidence.HIGH),
                classification = Classification.EVENT,
            )
        )

        assertEquals(LocalDateTime.parse("2026-09-06T01:00"), item.end)
        // Only the day moves. The clock time is what the writer wrote.
        assertEquals(LocalDateTime.parse("2026-09-05T23:00"), item.start)
    }

    @Test
    fun `an end time equal to the start is a full day, not a zero-length event`() {
        val item = single(
            DatedCandidate(
                date = Field(date, Confidence.HIGH),
                time = Field(LocalTime.parse("09:00"), Confidence.HIGH),
                endTime = Field(LocalTime.parse("09:00"), Confidence.HIGH),
                classification = Classification.EVENT,
            )
        )
        assertEquals(LocalDateTime.parse("2026-09-06T09:00"), item.end)
    }

    @Test
    fun `an end time after the start stays on the same day`() {
        val item = single(
            DatedCandidate(
                date = Field(date, Confidence.HIGH),
                time = Field(LocalTime.parse("15:00"), Confidence.HIGH),
                endTime = Field(LocalTime.parse("16:30"), Confidence.HIGH),
                classification = Classification.EVENT,
            )
        )
        assertEquals(LocalDateTime.parse("2026-09-05T16:30"), item.end)
    }

    @Test
    fun `with no end time the default duration applies`() {
        // ParseContext.defaultEventDuration was declared and read by nothing until this
        // mapping; its KDoc says "applied when a start time is found but no end".
        val item = single(
            DatedCandidate(
                date = Field(date, Confidence.HIGH),
                time = Field(LocalTime.parse("15:00"), Confidence.HIGH),
                classification = Classification.EVENT,
            )
        )
        assertEquals(LocalDateTime.parse("2026-09-05T16:00"), item.end)

        val twoHours = draftItems(
            captured = captured(),
            result = result(
                DatedCandidate(
                    date = Field(date, Confidence.HIGH),
                    time = Field(LocalTime.parse("15:00"), Confidence.HIGH),
                    classification = Classification.EVENT,
                )
            ),
            context = context.copy(defaultEventDuration = Duration.ofHours(2)),
            defaults = defaults,
            captureId = "cap-1",
            chainId = "chain-1",
        )
        assertEquals(
            LocalDateTime.parse("2026-09-05T17:00"),
            (twoHours as DraftResult.Ready).items.single().end,
        )
    }

    @Test
    fun `an all-day event ends on the following day`() {
        // Google reads an all-day end date as exclusive, and EventWrite passes end through
        // untouched by design — so the day has to be added here or every all-day event is
        // written a day short.
        val item = single(
            DatedCandidate(
                date = Field(date, Confidence.LOW),
                classification = Classification.EVENT_INCOMPLETE,
                ambiguousRelative = true,
            )
        )
        assertEquals(LocalDateTime.parse("2026-09-06T00:00"), item.end)
    }

    // ----- Title and provenance -----

    @Test
    fun `a subject line beats the parsed title`() {
        val drafted = draftItems(
            captured = captured(subject = "Sprint review"),
            result = result(
                DatedCandidate(date = Field(date, Confidence.HIGH), classification = Classification.TASK_WITH_DUE_DATE)
            ),
            context = context,
            defaults = defaults,
            captureId = "cap-1",
            chainId = "chain-1",
        )
        assertEquals("Sprint review", (drafted as DraftResult.Ready).items.single().title)
    }

    @Test
    fun `every item of one save shares the chain id`() {
        val item = single(
            DatedCandidate(date = Field(date, Confidence.HIGH), classification = Classification.TASK_WITH_DUE_DATE)
        )
        assertEquals("chain-1", item.chainId)
        assertEquals("cap-1", item.captureId)
    }

    @Test
    fun `a location reaches the item`() {
        val drafted = draft(
            DatedCandidate(
                date = Field(date, Confidence.HIGH),
                time = Field(LocalTime.parse("15:00"), Confidence.HIGH),
                classification = Classification.EVENT,
            ),
            location = "Room 4",
        )
        assertEquals("Room 4", (drafted as DraftResult.Ready).items.single().location)
    }
}

/**
 * The Save gate. Every reason the button is unavailable must be a named value, because a
 * disabled button with nothing beside it is unexplainable to the person looking at it — and
 * that is exactly the defect these tests exist to prevent recurring.
 */
class SaveBlockerTest {

    private val defaults = AccountDefaults(
        accountId = "acct",
        email = "you@example.com",
        routingMode = RoutingMode.LATCH_CALENDAR,
        destinationCalendarId = "latch-cal",
        destinationCalendarName = "Latch",
        destinationCalendarColour = "#d50000",
        taskListId = "list-1",
    )

    private val ready = DestinationState.Ready(defaults)

    private val goodParse = ParseResult(
        title = Field("Team sync", Confidence.HIGH),
        candidates = listOf(
            DatedCandidate(
                date = Field(LocalDate.parse("2026-08-31"), Confidence.HIGH),
                time = Field(LocalTime.parse("21:30"), Confidence.HIGH),
                classification = Classification.EVENT,
            )
        ),
    )

    @Test
    fun `a good parse with a destination can be saved`() {
        assertNull(saveBlocker(ready, goodParse, SaveState.Idle))
    }

    @Test
    fun `no destination is reported, not silently disabling the button`() {
        // The reported defect: a fully parsed item, Save greyed, and nothing on screen
        // saying why. FR-906 is the reason it must stay disabled; NFR-303's spirit is the
        // reason the user has to be told.
        assertEquals(SaveBlocker.NO_DESTINATION, saveBlocker(DestinationState.None, goodParse, SaveState.Idle))
    }

    @Test
    fun `a destination still being read is not the same as none`() {
        // Momentary and says nothing useful, so it stays quiet — but it must not be
        // confused with None, which is permanent and must speak.
        assertEquals(
            SaveBlocker.READING_DESTINATION,
            saveBlocker(DestinationState.Loading, goodParse, SaveState.Idle),
        )
    }

    @Test
    fun `a time with no day still blocks on the date`() {
        val noDate = ParseResult(
            title = Field("Standup", Confidence.HIGH),
            candidates = listOf(
                DatedCandidate(
                    date = null,
                    time = Field(LocalTime.parse("09:00"), Confidence.HIGH),
                    classification = Classification.EVENT_INCOMPLETE,
                )
            ),
        )
        assertEquals(SaveBlocker.NEEDS_A_DATE, saveBlocker(ready, noDate, SaveState.Idle))
    }

    @Test
    fun `a save already in flight blocks another`() {
        assertEquals(SaveBlocker.NOT_IDLE, saveBlocker(ready, goodParse, SaveState.Saving))
        assertEquals(SaveBlocker.NOT_IDLE, saveBlocker(ready, goodParse, SaveState.Saved()))
    }

    @Test
    fun `every blocker except the momentary one is explainable to the user`() {
        // A guard on the enum itself: adding a reason obliges someone to decide whether it
        // needs words. READING_DESTINATION and NOT_IDLE are the only two that may stay
        // silent, and both are transient.
        val silent = setOf(SaveBlocker.READING_DESTINATION, SaveBlocker.NOT_IDLE)
        val explained = SaveBlocker.entries.filterNot { it in silent }
        assertEquals(setOf(SaveBlocker.NO_DESTINATION, SaveBlocker.NEEDS_A_DATE), explained.toSet())
    }
}

/**
 * FR-804's identity. Driven through the real `DateParser`, because the whole mechanism rests
 * on the spans it reports — a hand-built `Field` with an invented span would test nothing.
 */
class ItemKeyTitleTest {

    private val context = ParseContext(now = LocalDateTime.parse("2026-08-27T09:00:00"))

    private fun capture(text: String, subject: String? = null) =
        CapturedText(text = text, layer = CaptureLayer.SHARE_SHEET, preferredTitle = subject)

    private fun keyTitle(text: String, subject: String? = null): String {
        val captured = capture(text, subject)
        return itemKeyTitle(captured, DateParser.parse(text, context))
    }

    @Test
    fun `the date and time are gone from the identity`() {
        val title = keyTitle("Team sync Monday 31 August at 9:30pm")

        assertFalse(title.contains("31"), title)
        assertFalse(title.contains("August", ignoreCase = true), title)
        assertFalse(title.contains("9:30"), title)
        assertTrue(title.contains("Team sync"), title)
    }

    @Test
    fun `a reschedule keeps the same identity`() {
        // The whole reason latch.item_key exists. These two are the same meeting moved, so
        // FR-804 must see one identity and two different source hashes.
        val original = keyTitle("Team sync 31 August at 9:30pm")
        val moved = keyTitle("Team sync 1 September at 11:00am")

        assertEquals(original, moved)
        assertEquals(itemKeyOf(original), itemKeyOf(moved))
        assertNotEquals(
            sourceHashOf("Team sync 31 August at 9:30pm"),
            sourceHashOf("Team sync 1 September at 11:00am"),
        )
    }

    @Test
    fun `a named weekday no longer breaks the identity`() {
        // This was the gap the first attempt left. The parser already recognised the weekday
        // in "Friday 12 September" as the writer corroborating their own date rather than a
        // second one, and then discarded it — span and all. It now absorbs that span into the
        // date it corroborates, so the whole phrase is cut out of the identity.
        assertEquals(
            keyTitle("Team sync Monday 31 August at 9:30pm"),
            keyTitle("Team sync Tuesday 1 September at 11:00am"),
        )
    }

    @Test
    fun `the weekday span is absorbed whatever the date format`() {
        // Absorbing centrally rather than in one rule's regex is what makes this hold for
        // every format — written, numeric, and any added later.
        val written = keyTitle("Standup Monday 31 August at 9am")
        val numeric = keyTitle("Standup Monday 31/08 at 9am")

        listOf(written, numeric).forEach { title ->
            assertFalse(title.contains("Monday", ignoreCase = true), title)
            assertTrue(title.contains("Standup"), title)
        }
    }

    @Test
    fun `a weekday far from the date is still its own date`() {
        // The corroboration window is what separates "Friday 12 September" from "Friday, and
        // again on 12 September". Absorbing must not swallow a genuinely separate mention.
        val result = DateParser.parse("Gym Friday, and the review on 12 September", context)
        assertTrue(result.candidates.size > 1, "expected two distinct dates")
    }

    @Test
    fun `item key and source hash are no longer the same value`() {
        // The defect this fixes: for a capture under FR-509's 60-character limit the title
        // was the whole text, so both hashes covered the same string and moved together.
        val text = "Team sync Monday 31 August at 9:30pm"
        assertNotEquals(sourceHashOf(text), itemKeyOf(keyTitle(text)))
    }

    @Test
    fun `a genuinely different meeting keeps a different identity`() {
        assertNotEquals(
            itemKeyOf(keyTitle("Team sync Monday 31 August at 9:30pm")),
            itemKeyOf(keyTitle("Dentist Monday 31 August at 9:30pm")),
        )
    }

    @Test
    fun `a subject line is the identity as it stands`() {
        // FR-206. The spans index into the body, so they do not apply to a subject.
        assertEquals("Sprint review", keyTitle("Moved to 31 August at 9:30pm", subject = "Sprint review"))
    }

    @Test
    fun `a capture with no date at all still yields an identity`() {
        val title = keyTitle("Buy cardamom and rice")
        assertEquals("Buy cardamom and rice", title)
    }

    @Test
    fun `blanking does not run two words together`() {
        // Deleting the span rather than blanking it would give "syncat", which hashes to
        // something no other client would reproduce from the same message.
        val title = keyTitle("Team sync 31 August at 9:30pm")
        assertFalse(title.contains("syncat"), title)
    }
}
