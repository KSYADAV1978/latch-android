package com.latch.android.capture

import com.latch.core.model.CaptureLayer
import com.latch.core.model.ItemType
import com.latch.core.model.RoutingMode
import com.latch.data.AccountDefaults
import com.latch.parser.Classification
import com.latch.parser.Confidence
import com.latch.parser.DatedCandidate
import com.latch.parser.Field
import com.latch.parser.ParseContext
import com.latch.parser.ParseResult
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FR-506's rows turned into items. Pure Kotlin and clock-free — the ids and the capture time
 * are the caller's to mint — so this runs on the JVM beside the setup reducer.
 */
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
