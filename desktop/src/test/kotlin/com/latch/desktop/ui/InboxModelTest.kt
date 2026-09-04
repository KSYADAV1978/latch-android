package com.latch.desktop.ui

import com.latch.core.model.CaptureLayer
import com.latch.core.model.InboxCapture
import com.latch.core.model.InboxReason
import com.latch.core.model.ItemType
import com.latch.desktop.capture.DesktopCapture
import com.latch.desktop.capture.desktopSource
import com.latch.core.model.InboxStatus
import com.latch.parser.Confidence
import com.latch.parser.DateParser
import com.latch.parser.ParseContext
import com.latch.wire.SaveRoute
import com.latch.wire.saveRoute
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FR-700's surface on Windows, as the pure function that decides it.
 *
 * `InboxWindow` renders this and nothing else, so everything a user reads in that window is
 * asserted here — including the two things a screen would hide: FR-704's silence at zero, and
 * FR-515's re-parse against the captured instant.
 */
class InboxModelTest {

    private val zone = ZoneId.of("Asia/Kolkata")
    private val capturedAt: Instant = Instant.parse("2026-09-03T09:00:00Z")
    private val capturedLocal: LocalDateTime = LocalDateTime.parse("2026-09-03T14:30:00")
    private val today: LocalDate = LocalDate.parse("2026-09-03")

    private fun row(
        id: String = "r1",
        text: String = "Ask about the uniform order",
        reason: InboxReason = InboxReason.UNDATED,
        capturedAt: Instant = this.capturedAt,
        capturedLocal: LocalDateTime = this.capturedLocal,
    ) = InboxCapture(
        id = id,
        rawText = text,
        layer = CaptureLayer.SHARE_SHEET,
        capturedAt = capturedAt,
        capturedLocal = capturedLocal,
        zone = zone.id,
        confidence = 0.4,
        reason = reason,
    )

    private fun modelOf(
        vararg rows: InboxCapture,
        now: Instant = capturedAt,
        today: LocalDate = this.today,
        unreadable: Int = 0,
    ) = inboxModel(rows.toList(), now, today, zone, unreadable)

    // ---- FR-704: the count, and the silence -----------------------------------------------

    @Test
    fun `FR-704 an empty inbox says nothing at all rather than zero`() {
        // The requirement is "shall not nag", and a permanent menu entry announcing an empty
        // list is a reminder about nothing. Null is the assertion; "0 waiting" is the failure.
        assertNull(inboxCountLabel(InboxStatus(due = 0, snoozed = 0, unreadable = 0)))
        // Not even when there is something the user cannot act on.
        assertNull(inboxCountLabel(InboxStatus(due = 0, snoozed = 3, unreadable = 2)))
    }

    @Test
    fun `FR-704 counts what is waiting on the user and nothing else`() {
        assertEquals("1 waiting in the Inbox", inboxCountLabel(InboxStatus(1, 0, 0)))
        assertEquals("4 waiting in the Inbox", inboxCountLabel(InboxStatus(4, 9, 3)))
    }

    @Test
    fun `an empty list says so rather than showing an empty window`() {
        val model = modelOf()
        assertEquals(emptyList(), model.rows)
        assertNotNull(model.emptyLine)
        // FR-703, on screen: the guarantee is worth stating even with nothing in the list.
        assertTrue("Nothing reaches Google" in model.localOnlyLine)
    }

    @Test
    fun `rows this build could not read are named rather than silently absent`() {
        assertNull(modelOf(row()).unreadableLine)
        val line = assertNotNull(modelOf(row(), unreadable = 2).unreadableLine)
        assertTrue("2" in line)
        assertTrue("nothing has been deleted" in line, line)
    }

    // ---- FR-515: the re-parse is against the capture, not against today --------------------

    @Test
    fun `a relative date reads against the instant it was captured at, not today`() {
        // **The load-bearing test in this file.** "kal" is tomorrow; the row was captured on
        // 3 September, so it must read 4 September for ever. Parsing against today would walk
        // it a day further every time this window was opened — design principle 1's failure
        // inverted, not inventing a date but quietly moving one.
        val held = row(text = "kal 4 baje meeting", reason = InboxReason.LOW_CONFIDENCE)

        val onTheDay = modelOf(held, now = capturedAt, today = today).rows.single()
        val aWeekLater = modelOf(
            held,
            now = capturedAt.plus(Duration.ofDays(7)),
            today = today.plusDays(7),
        ).rows.single()

        assertTrue("4 Sep" in onTheDay.whenLine, onTheDay.whenLine)
        assertEquals(onTheDay.whenLine, aWeekLater.whenLine, "the held date walked forward")
    }

    // ---- FR-705: surfaced for review, never deleted -----------------------------------------

    @Test
    fun `a row is surfaced for review at a fortnight and not before`() {
        val held = row()
        assertNull(
            modelOf(held, now = capturedAt.plus(Duration.ofDays(13))).rows.single().agedLine,
            "a row was surfaced before its review period",
        )
        val aged = modelOf(held, now = capturedAt.plus(Duration.ofDays(14))).rows.single()
        assertNotNull(aged.agedLine)
        assertTrue("Still want it?" in aged.agedLine!!)
    }

    @Test
    fun `an aged row is still in the list, because FR-705 surfaces and never deletes`() {
        val model = modelOf(row(), now = capturedAt.plus(Duration.ofDays(400)))
        assertEquals(1, model.rows.size)
    }

    // ---- the row itself -----------------------------------------------------------------------

    @Test
    fun `an undated row says why it is here and offers a date`() {
        val model = modelOf(row())
        val only = model.rows.single()
        assertEquals("No date was found in this capture.", only.reason)
        assertTrue(only.needsDate)
        assertEquals("No date yet", only.whenLine)
        // FR-506 row 4: a capture with no date is a to-do, and an undated to-do is a real item
        // Google Tasks holds happily — so Save is offered rather than refused.
        assertTrue(only.canSave)
        assertEquals("TO-DO", only.badge)
    }

    @Test
    fun `a time with no day cannot be saved until it has one`() {
        // FR-506 row 3. The condition that would make this fail is offering Save on a row that
        // would draft nothing — a button that fails on the press rather than saying why.
        val only = modelOf(row(text = "call at 4pm", reason = InboxReason.INCOMPLETE)).rows.single()
        assertEquals("A time, but no day to put it on.", only.reason)
        assertTrue(only.needsDate)
        assertFalse(only.canSave, "a row with nothing draftable offered Save")
    }

    @Test
    fun `FR-702 an assigned date completes the row and makes it saveable`() {
        val given = row(text = "call at 4pm", reason = InboxReason.INCOMPLETE)
            .copy(assignedDate = LocalDate.parse("2027-09-20"))
        val only = modelOf(given).rows.single()
        assertFalse(only.needsDate)
        assertTrue(only.canSave)
        assertTrue("20 Sep" in only.whenLine, only.whenLine)
    }

    @Test
    fun `FR-702 an edited title wins over the derived one`() {
        val edited = row(text = "Fees due 20 September 2027").copy(editedTitle = "School fees")
        assertEquals("School fees", modelOf(edited).rows.single().title)
    }

    @Test
    fun `FR-507 the override applies on this surface too, and states its cost first`() {
        val timed = row(text = "Kickoff 8 September 2027 at 9am", reason = InboxReason.LOW_CONFIDENCE)
        val asFound = modelOf(timed).rows.single()
        assertEquals("EVENT", asFound.badge)
        assertEquals(ItemType.TASK, asFound.otherType)
        assertTrue(asFound.canOverride)
        // §8.1's cost, readable while the badge still says EVENT.
        assertTrue("not the time" in assertNotNull(asFound.overrideCost))

        val flipped = modelOf(timed.copy(typeOverride = ItemType.TASK)).rows.single()
        assertEquals("TO-DO", flipped.badge)
    }

    @Test
    fun `FR-510 outranks FR-507 here as well - a past row cannot become an event`() {
        val past = row(text = "the order dated 12 March", reason = InboxReason.LOW_CONFIDENCE)
        val only = modelOf(past).rows.single()
        assertEquals("TO-DO", only.badge)
        assertFalse(only.canOverride, "a past date was offered as an event")
    }

    @Test
    fun `every reason has a sentence of its own`() {
        val sentences = InboxReason.entries.map(::reasonText)
        assertEquals(sentences.size, sentences.toSet().size, "two reasons read the same")
        assertTrue(sentences.none { it.isBlank() })
    }

    @Test
    fun `the excerpt is one line and bounded`() {
        // A capture can be an entire recognised screen (FR-805b's own case); a row that grew to
        // fit one would make the rest of the list unreadable.
        val long = row(text = "x".repeat(400) + "\nsecond line")
        val only = modelOf(long).rows.single()
        assertFalse("\n" in only.excerpt)
        assertTrue(only.excerpt.length <= 91, only.excerpt.length.toString())

        assertEquals("Fees due", modelOf(row(text = "\n\nFees due\nmore")).rows.single().excerpt)
    }

    @Test
    fun `the captured line is in the reader's own zone`() {
        // 09:00 UTC is 14:30 in Asia/Kolkata, which is what the user was doing when they
        // captured it. Showing UTC would name a moment they never experienced.
        val line = modelOf(row()).rows.single().capturedLine
        assertTrue("3 Sep" in line, line)
        assertTrue("14:30" in line, line)
    }

    // ---- the routing that puts rows here at all ---------------------------------------------

    @Test
    fun `a desktop capture is routable to the Inbox`() {
        // SRS 1.55 recorded the interim reading for this client: an undated capture was saved
        // as an undated to-do "because there is no Inbox on Windows yet". There is one, so the
        // route has to actually reach it — which needs `desktopSource` to be a layer NFR-206
        // permits. The condition that would make this fail is a source reading as NOTIFICATION.
        val context = ParseContext(now = capturedLocal, zone = zone)
        val parsed = DateParser.parse("Ask about the uniform order", context)
        val route = saveRoute(
            parsed,
            parsed.candidates.indices.toSet(),
            context.confidenceThreshold,
            desktopSource(DesktopCapture("Ask about the uniform order")),
        )
        assertEquals(InboxReason.UNDATED, assertIs<SaveRoute.Inbox>(route).reason)
    }

    @Test
    fun `an ordinary dated capture is not routed`() {
        val context = ParseContext(now = capturedLocal, zone = zone)
        val text = "Kickoff 8 September 2027 at 9am"
        val parsed = DateParser.parse(text, context)
        assertEquals(
            SaveRoute.Google,
            saveRoute(
                parsed,
                parsed.candidates.indices.toSet(),
                Confidence(0.6),
                desktopSource(DesktopCapture(text)),
            ),
        )
    }

}
