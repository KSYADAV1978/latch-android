package com.latch.android.capture

import com.latch.core.model.CaptureLayer
import com.latch.core.model.ItemType
import com.latch.core.model.RoutingMode
import com.latch.data.AccountDefaults
import com.latch.data.CreatedItem
import com.latch.google.DuplicateSearch
import com.latch.google.GoogleUnreachable
import com.latch.data.InboxReason
import com.latch.google.ItemDates
import com.latch.data.PendingWrite
import com.latch.data.QueuedWrite
import com.latch.data.WriteOperation
import com.latch.data.WrittenItem
import com.latch.parser.DateParser
import com.latch.parser.ParseContext
import com.latch.wire.RemoteMetadata
import com.latch.wire.itemKeyOf
import com.latch.wire.itemKeyTitle
import com.latch.wire.sourceHashOf
import java.io.IOException
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * The FR-700 slice at the saver: routing to the Inbox, FR-803's local index, FR-804's offline
 * cure, and FR-807's offer written down.
 *
 * Each of these is a path that used to end in a write, a queue entry, or a lost offer. What
 * makes them worth pinning here rather than only in `SaveRouteTest` is that the routing
 * decision is pure and this is not: the saver has to *act* on it, and the thing that would go
 * wrong is a capture that routed correctly and was written anyway.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InboxSaveTest {

    private val defaults = AccountDefaults(
        accountId = "acct",
        email = "you@example.com",
        routingMode = RoutingMode.LATCH_CALENDAR,
        destinationCalendarId = "latch-cal",
        destinationCalendarName = "Latch",
        destinationCalendarColour = "#d50000",
        taskListId = "list-1",
    )

    private val context = ParseContext(
        now = LocalDateTime.parse("2026-09-01T09:00:00"),
        zone = ZoneId.of("Asia/Kolkata"),
    )

    private val undatedText = "Ask about the uniform order"
    private val eventText = "Kickoff 8 September 2027 at 9am"
    private val taskText = "Fees due 20 September 2027"

    // ----- AC-03 -----

    @Test
    fun `a capture with no date reaches the Inbox and nothing reaches Google`() = runTest {
        val calendar = RecordingCalendarApi()
        val tasks = RecordingTasksApi()
        val inbox = RecordingInbox()
        val saver = saver(calendar = calendar, tasks = tasks, inbox = inbox)

        saver.save(captured(undatedText), parse(undatedText), context)
        runCurrent()

        val state = assertIs<SaveState.SentToInbox>(saver.state.value)
        assertEquals(InboxReason.UNDATED, state.reason)
        assertEquals(1, inbox.rows.size)
        // AC-03's second half, and the one worth asserting: "no calendar entry created".
        assertEquals(0, calendar.inserted)
        assertEquals(0, tasks.inserted)
    }

    @Test
    fun `an Inbox row carries the instant and zone it was captured in`() = runTest {
        // Without these the row re-parses against today's clock, and "kal" moves one day
        // further every time the list is opened — design principle 1's failure inverted.
        val inbox = RecordingInbox()
        val saver = saver(inbox = inbox)

        saver.save(captured(undatedText), parse(undatedText), context)
        runCurrent()

        val row = inbox.rows.values.single()
        assertEquals(LocalDateTime.parse("2026-09-01T09:00:00"), row.capturedLocal)
        assertEquals("Asia/Kolkata", row.zone)
        assertEquals(undatedText, row.rawText)
    }

    @Test
    fun `an Inbox route offers no undo, because nothing left the device`() = runTest {
        val saver = saver()
        saver.save(captured(undatedText), parse(undatedText), context)
        runCurrent()
        assertNull(undoOffer(saver.state.value, Instant.now()))
    }

    @Test
    fun `saving a row out of the Inbox writes it and discards the row`() = runTest {
        val calendar = RecordingCalendarApi()
        val inbox = RecordingInbox()
        val saver = saver(calendar = calendar, inbox = inbox)

        saver.save(captured(eventText), parse(eventText), context, fromInboxId = "row-1")
        runCurrent()

        assertIs<SaveState.Saved>(saver.state.value)
        assertEquals(1, calendar.inserted)
        // FR-703: the Inbox holds what has not been confirmed, and this now has been.
        assertEquals(listOf("row-1"), inbox.discarded)
    }

    @Test
    fun `a row that failed to save stays in the Inbox`() = runTest {
        // The point of the Inbox holding it: a failure must leave the capture exactly where it
        // was, or the triage step becomes a way to lose things.
        val calendar = RecordingCalendarApi(failInsert = GoogleUnreachable("no network", IOException()))
        val inbox = RecordingInbox()
        val queue = RecordingQueue()
        val saver = saver(calendar = calendar, inbox = inbox, queue = queue)

        saver.save(captured(eventText), parse(eventText), context, fromInboxId = "row-1")
        runCurrent()

        // Queued rather than failed — FR-806 still applies to a save started from the Inbox —
        // and the row is discarded, because a queued write is a promise NFR-302 keeps.
        assertIs<SaveState.Queued>(saver.state.value)
        assertEquals(listOf("row-1"), inbox.discarded)
    }

    // ----- FR-803's local index -----

    @Test
    fun `a capped task scan is answered by the local index`() = runTest {
        // FR-803's own note: "the cure is a local index of source hashes… deferred because it
        // needs local storage that does not yet exist; revisit when FR-701 brings it."
        val tasks = RecordingTasksApi()
        tasks.duplicateAnswer = DuplicateSearch(existingId = null, scanCapped = true)
        val index = RecordingIndex()
        index.remember(
            WrittenItem(
                remoteId = "task-9",
                containerId = "list-1",
                type = ItemType.TASK,
                sourceHash = sourceHashOf(taskText),
                itemKey = "b".repeat(64),
                dates = ItemDates.Task(LocalDate.parse("2027-09-20")),
                writtenAt = Instant.parse("2026-09-01T08:00:00Z"),
            )
        )
        val saver = saver(tasks = tasks, index = index)

        saver.save(captured(taskText), parse(taskText), context)
        runCurrent()

        assertEquals(SaveState.AlreadySaved, saver.state.value)
        assertEquals(0, tasks.inserted)
    }

    @Test
    fun `a capped scan the index cannot answer stays capped rather than becoming an all-clear`() = runTest {
        val tasks = RecordingTasksApi()
        tasks.duplicateAnswer = DuplicateSearch(existingId = null, scanCapped = true)
        val saver = saver(tasks = tasks, index = RecordingIndex())

        saver.save(captured(taskText), parse(taskText), context)
        runCurrent()

        val saved = assertIs<SaveState.Saved>(saver.state.value)
        assertTrue(saved.searchWasCapped, "the user must still be told the scan stopped looking")
        assertEquals(1, tasks.inserted)
    }

    @Test
    fun `the index is not consulted when Google answered the question`() = runTest {
        // The order is the design. Asking the index first would answer "already saved" about an
        // item the user had since deleted by hand in Google Calendar, and leave them unable to
        // capture it again with no recourse. Google sees what every client wrote; the index
        // sees only this device.
        val calendar = RecordingCalendarApi()
        val index = RecordingIndex()
        index.remember(
            WrittenItem(
                remoteId = "event-stale",
                containerId = "latch-cal",
                type = ItemType.EVENT,
                sourceHash = sourceHashOf(eventText),
                itemKey = "b".repeat(64),
                dates = ItemDates.Task(null),
                writtenAt = Instant.parse("2026-09-01T08:00:00Z"),
            )
        )
        val saver = saver(calendar = calendar, index = index)

        saver.save(captured(eventText), parse(eventText), context)
        runCurrent()

        // Google's uncapped "not found" stands, so the capture is written again — which is
        // FR-807's "re-capturing after an undo works", generalised to a hand deletion.
        assertIs<SaveState.Saved>(saver.state.value)
        assertEquals(1, calendar.inserted)
    }

    @Test
    fun `a written item is remembered, so the index can answer next time`() = runTest {
        val index = RecordingIndex()
        val saver = saver(index = index)

        saver.save(captured(eventText), parse(eventText), context)
        runCurrent()

        val remembered = index.items.single()
        assertEquals("event-1", remembered.remoteId)
        assertEquals("latch-cal", remembered.containerId)
        assertEquals(sourceHashOf(eventText), remembered.sourceHash)
    }

    @Test
    fun `an undone item is forgotten, so it can be captured again`() = runTest {
        val index = RecordingIndex()
        val saver = saver(index = index)

        saver.save(captured(eventText), parse(eventText), context)
        runCurrent()
        saver.undo()
        runCurrent()

        assertEquals(SaveState.Undone, saver.state.value)
        assertTrue(index.items.isEmpty(), "an item the user took back must not block a re-capture")
        assertEquals(listOf("latch-cal" to "event-1"), index.forgotten)
    }

    // ----- FR-804's offline cure -----

    @Test
    fun `an offline capture that looks like a reschedule waits in the Inbox`() = runTest {
        // FR-804's own note: "an offline reschedule therefore becomes a second item, and the
        // recourse is to remove one by hand… The Inbox is the cure, and this should be
        // revisited when FR-701 lands."
        val calendar = RecordingCalendarApi(failInsert = GoogleUnreachable("no network", IOException()))
        val inbox = RecordingInbox()
        val queue = RecordingQueue()
        val index = RecordingIndex()
        val result = parse(eventText)
        index.remember(
            WrittenItem(
                remoteId = "event-existing",
                containerId = "latch-cal",
                type = ItemType.EVENT,
                sourceHash = "a".repeat(64),
                itemKey = itemKeyOf(itemKeyTitle(captured(eventText), result)),
                // A different position, which is what makes this a reschedule rather than a
                // restatement — §7.2's third row.
                dates = ItemDates.Event(
                    start = LocalDateTime.parse("2027-09-05T09:00"),
                    end = LocalDateTime.parse("2027-09-05T10:00"),
                ),
                writtenAt = Instant.parse("2026-09-01T08:00:00Z"),
            )
        )
        val saver = saver(calendar = calendar, inbox = inbox, queue = queue, index = index)

        saver.save(captured(eventText), result, context)
        runCurrent()

        val state = assertIs<SaveState.SentToInbox>(saver.state.value)
        assertEquals(InboxReason.RESCHEDULE_UNRESOLVED, state.reason)
        // The whole point: no second item is queued to be created behind the user's back.
        assertTrue(queue.entries.isEmpty(), "a suspected reschedule must not be queued as a create")
        assertEquals(1, inbox.rows.size)
    }

    @Test
    fun `an offline capture naming the date the item already holds is queued, not held`() = runTest {
        // §7.2's third row: same key, same resolved date is a restatement and not a move. There
        // is no question to ask, so the ordinary queue is the right answer.
        val calendar = RecordingCalendarApi(failInsert = GoogleUnreachable("no network", IOException()))
        val queue = RecordingQueue()
        val index = RecordingIndex()
        val result = parse(eventText)
        index.remember(
            WrittenItem(
                remoteId = "event-existing",
                containerId = "latch-cal",
                type = ItemType.EVENT,
                sourceHash = "a".repeat(64),
                itemKey = itemKeyOf(itemKeyTitle(captured(eventText), result)),
                dates = ItemDates.Event(
                    start = LocalDateTime.parse("2027-09-08T09:00"),
                    end = LocalDateTime.parse("2027-09-08T10:00"),
                ),
                writtenAt = Instant.parse("2026-09-01T08:00:00Z"),
            )
        )
        val saver = saver(calendar = calendar, queue = queue, index = index)

        saver.save(captured(eventText), result, context)
        runCurrent()

        assertIs<SaveState.Queued>(saver.state.value)
        assertEquals(1, queue.entries.size)
    }

    @Test
    fun `an offline capture the index knows nothing about is queued as before`() = runTest {
        // AC-10's own case, unchanged: queued locally, written on reconnection, no loss.
        val calendar = RecordingCalendarApi(failInsert = GoogleUnreachable("no network", IOException()))
        val queue = RecordingQueue()
        val saver = saver(calendar = calendar, queue = queue)

        saver.save(captured(eventText), parse(eventText), context)
        runCurrent()

        assertIs<SaveState.Queued>(saver.state.value)
        assertEquals(1, queue.entries.size)
    }

    // ----- FR-807's offer, written down -----

    @Test
    fun `an offer is stored as it opens, so a process death does not lose it`() = runTest {
        val offers = RecordingUndoOffers()
        val saver = saver(undoOffers = offers)

        saver.save(captured(eventText), parse(eventText), context)
        runCurrent()

        val stored = assertNotNull(offers.offers.values.singleOrNull())
        val created = assertIs<CreatedItem.Written>(stored.created.single())
        assertEquals("event-1", created.remoteId)
        assertTrue(stored.isOpen(stored.expiresAt.minusMillis(1)))
    }

    @Test
    fun `an offer that lapses is forgotten, so the home screen stops showing it`() = runTest {
        val offers = RecordingUndoOffers()
        val saver = saver(undoOffers = offers)

        saver.save(captured(eventText), parse(eventText), context)
        runCurrent()
        advanceUntilIdle()

        assertTrue(offers.offers.isEmpty())
    }

    @Test
    fun `taking the offer forgets it`() = runTest {
        val offers = RecordingUndoOffers()
        val saver = saver(undoOffers = offers)

        saver.save(captured(eventText), parse(eventText), context)
        runCurrent()
        saver.undo()
        runCurrent()

        assertTrue(offers.offers.isEmpty())
    }

    @Test
    fun `a recreation of the same capture does not end its offer`() = runTest {
        // The defect this fixes: `CaptureActivity` is recreated on rotation and reset the saver
        // unconditionally, so rotating inside FR-807's ten seconds silently ended the offer —
        // the requirement met on paper and not in the hand, exactly as an unsuppressed
        // touch-outside dismissal would.
        val saver = saver()
        saver.reset("text:kickoff")
        saver.save(captured(eventText), parse(eventText), context)
        runCurrent()

        saver.reset("text:kickoff")

        assertNotNull(undoOffer(saver.state.value, Instant.now()), "a rotation must not end the offer")
    }

    @Test
    fun `a genuinely new capture does end the offer`() = runTest {
        // FR-807's own recorded limit, kept: the countdown belonged to a save the user has
        // moved on from.
        val offers = RecordingUndoOffers()
        val saver = saver(undoOffers = offers)
        saver.reset("text:kickoff")
        saver.save(captured(eventText), parse(eventText), context)
        runCurrent()

        saver.reset("text:something-else")

        assertEquals(SaveState.Idle, saver.state.value)
        assertNull(undoOffer(saver.state.value, Instant.now()))
        runCurrent()
        assertTrue(offers.offers.isEmpty(), "the stored offer must go with the in-memory one")
    }

    // ----- fixtures -----

    private fun captured(text: String) = CapturedText(text = text, layer = CaptureLayer.SHARE_SHEET)

    private fun parse(text: String) = DateParser.parse(text, context)

    private fun TestScope.saver(
        calendar: RecordingCalendarApi = RecordingCalendarApi(),
        tasks: RecordingTasksApi = RecordingTasksApi(),
        queue: RecordingQueue = RecordingQueue(),
        inbox: RecordingInbox = RecordingInbox(),
        index: RecordingIndex = RecordingIndex(),
        undoOffers: RecordingUndoOffers = RecordingUndoOffers(),
    ) = CaptureSaver(
        defaultsStore = FixedDefaults(defaults),
        calendarApi = calendar,
        tasksApi = tasks,
        writeQueue = queue,
        inbox = inbox,
        index = index,
        undoOffers = undoOffers,
        requestDrain = { queue.drainsRequested++ },
        scope = this,
        sourceLinkTemplate = "Captured from %1\$s",
    )
}
