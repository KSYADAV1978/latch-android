package com.latch.android.capture

import com.latch.core.model.CaptureLayer
import com.latch.core.model.RoutingMode
import com.latch.data.AccountDefaults
import com.latch.google.CalendarApi
import com.latch.data.CreatedItem
import com.latch.google.TasksApi
import com.latch.google.GoogleUnreachable
import com.latch.google.ItemDates
import com.latch.google.RescheduleMatch
import com.latch.google.RescheduleSearch
import com.latch.parser.DateParser
import com.latch.parser.ParseContext
import com.latch.wire.itemKeyOf
import com.latch.wire.itemKeyTitle
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * FR-511 through the saver: a capture with several dates writes a chain, and the chain is
 * one message as far as FR-803 is concerned.
 *
 * The four-date capture here is the one AC-11 asks for — "save a four-item chain, then press
 * undo" — which had no reachable path until this requirement was built.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MultiDateSaveTest {

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
        now = LocalDateTime.parse("2026-08-25T09:00:00"),
        zone = ZoneId.of("Asia/Kolkata"),
    )

    /** Four dates, each with a time, so every one drafts as an event. */
    private val fourDates =
        "Kickoff 1 September at 9am, review 8 September at 10am, " +
            "demo 15 September at 11am, retro 22 September at 4pm"

    @Test
    fun `every ticked date is written, as one chain`() = runTest {
        val calendar = RecordingCalendarApi()
        val saver = saver(calendar = calendar)

        saver.save(captured(fourDates), parse(fourDates), context)
        runCurrent()

        assertIs<SaveState.Saved>(saver.state.value)
        assertEquals(4, calendar.inserted, "all four ticked dates should be written")
    }

    @Test
    fun `FR-803 is asked once for the capture, not once per item`() = runTest {
        // The items of one chain share a source_hash by design (§7.2), so a check per item
        // would find the first insert and abandon the rest. Asked once, before the chain.
        val calendar = RecordingCalendarApi()
        val saver = saver(calendar = calendar)

        saver.save(captured(fourDates), parse(fourDates), context)
        runCurrent()

        assertEquals(1, calendar.sourceHashesQueried.size, "one message, one duplicate check")
        // The item_key query is not made at all for a chain — see the SRS 1.25 pair below.
        // It was asserted as exactly one here until that reading; a four-date capture cannot
        // act on the answer, so there is nothing to ask.
        assertTrue(calendar.itemKeysQueried.isEmpty())
    }

    @Test
    fun `a duplicate capture writes nothing at all, not merely nothing after the first`() = runTest {
        val calendar = RecordingCalendarApi(existingEventId = "ev_dup")
        val saver = saver(calendar = calendar)

        saver.save(captured(fourDates), parse(fourDates), context)
        runCurrent()

        assertEquals(SaveState.AlreadySaved, saver.state.value)
        assertEquals(0, calendar.inserted)
    }

    @Test
    fun `unticking a date leaves it out and writes the rest`() = runTest {
        val calendar = RecordingCalendarApi()
        val saver = saver(calendar = calendar)
        val result = parse(fourDates)

        saver.save(captured(fourDates), result, context, selected = setOf(0, 2, 3))
        runCurrent()

        assertEquals(3, calendar.inserted)
    }

    @Test
    fun `every item of the chain shares one chain id and one item key`() = runTest {
        val calendar = RecordingCalendarApi()
        val saver = saver(calendar = calendar)

        saver.save(captured(fourDates), parse(fourDates), context)
        runCurrent()

        val chainIds = calendar.written.map { it.metadata.chainId }.distinct()
        assertEquals(1, chainIds.size, "one save, one chain")

        // SRS 1.23: §7.2 step 2 blanks every date span, so one capture derives one key. Each
        // item is separately addressable by its remote id; the key says what the message is
        // about, which is what FR-804 matches a reschedule on.
        val keys = calendar.written.map { it.metadata.itemKey }.distinct()
        assertEquals(1, keys.size, "one capture, one item_key")
        assertEquals(1, calendar.written.map { it.metadata.sourceHash }.distinct().size)
    }

    @Test
    fun `the item key is derived with every date blanked, not just the primary's`() {
        // The divergence SRS 1.23 corrected. With the primary's spans alone, a neighbour's
        // date stays in the title and the key moves whenever any date in the message changes.
        val result = parse(fourDates)
        val key = itemKeyTitle(captured(fourDates), result)

        assertTrue("September" !in key, "a neighbouring date is still in the title: $key")
        assertTrue("9am" !in key && "4pm" !in key, "a time is still in the title: $key")
    }

    @Test
    fun `two captures differing only in their dates derive the same key`() {
        // The property the reading exists for, and the one a per-item key would have broken.
        val moved = "Kickoff 2 September at 9am, review 9 September at 10am, " +
            "demo 16 September at 11am, retro 23 September at 4pm"

        assertEquals(
            itemKeyOf(itemKeyTitle(captured(fourDates), parse(fourDates))),
            itemKeyOf(itemKeyTitle(captured(moved), parse(moved))),
        )
    }

    // ----- SRS 1.25: a multi-item capture is never offered as a reschedule -----

    @Test
    fun `a multi-date capture is saved, not offered as a reschedule`() = runTest {
        // The account already holds this chain, so the key matches. Accepting an offer would
        // patch one item and write none of the other three — four items in front of the user
        // becoming one moved event and three discarded silently.
        val calendar = RecordingCalendarApi(
            rescheduleMatch = com.latch.google.RescheduleSearch(
                com.latch.google.RescheduleMatch(
                    remoteId = "ev_old",
                    dates = com.latch.google.ItemDates.Event(
                        start = LocalDateTime.parse("2026-09-30T09:00"),
                        end = LocalDateTime.parse("2026-09-30T10:00"),
                    ),
                    title = "Kickoff",
                ),
            ),
        )
        val saver = saver(calendar = calendar)

        saver.save(captured(fourDates), parse(fourDates), context)
        runCurrent()

        assertIs<SaveState.Saved>(saver.state.value)
        assertEquals(4, calendar.inserted, "every item is written; nothing is discarded")
        // The query is skipped rather than its answer ignored: nothing to ask when no answer
        // could be acted on.
        assertTrue(calendar.itemKeysQueried.isEmpty(), "no reschedule query for a chain")
        assertTrue(calendar.patched.isEmpty())
    }

    @Test
    fun `a single-date capture is still offered as a reschedule`() = runTest {
        // FR-804 is unchanged for the captures it was written against.
        val single = "Kickoff 1 September at 9am"
        val calendar = RecordingCalendarApi(
            rescheduleMatch = com.latch.google.RescheduleSearch(
                com.latch.google.RescheduleMatch(
                    remoteId = "ev_old",
                    dates = com.latch.google.ItemDates.Event(
                        start = LocalDateTime.parse("2026-09-30T09:00"),
                        end = LocalDateTime.parse("2026-09-30T10:00"),
                    ),
                    title = "Kickoff",
                ),
            ),
        )
        val saver = saver(calendar = calendar)

        saver.save(captured(single), parse(single), context)
        runCurrent()

        assertIs<SaveState.RescheduleOffered>(saver.state.value)
        assertEquals(1, calendar.itemKeysQueried.size)
    }

    // ----- AC-11: the four-item chain, and undoing it -----

    @Test
    fun `undo removes all four items of the chain`() = runTest {
        val calendar = RecordingCalendarApi()
        val saver = saver(calendar = calendar)

        saver.save(captured(fourDates), parse(fourDates), context)
        runCurrent()

        val window = assertNotNull(undoOffer(saver.state.value, Instant.now()))
        assertEquals(4, window.created.size, "the offer must carry every item it created")

        saver.undo()
        advanceUntilIdle()

        assertEquals(SaveState.Undone, saver.state.value)
        assertEquals(4, calendar.deleted.size)
    }

    @Test
    fun `an undo that removes only some of the chain says how far it got`() = runTest {
        // NFR-303, and the branch of the removal loop that has never been reachable: a chain
        // half in the account is worse than one wholly there or wholly gone, so every item
        // still gets its delete and the user is told the count.
        val calendar = RecordingCalendarApi(failDeleteAfter = 2)
        val saver = saver(calendar = calendar)

        saver.save(captured(fourDates), parse(fourDates), context)
        runCurrent()
        saver.undo()
        advanceUntilIdle()

        val failed = assertIs<SaveState.UndoFailed>(saver.state.value)
        assertEquals(2, failed.removed)
        assertEquals(4, failed.total)
    }

    // ----- FR-806: the chain is one queue entry -----

    @Test
    fun `a chain that cannot be written now is queued as one entry`() = runTest {
        val queue = RecordingQueue()
        val saver = saver(
            calendar = RecordingCalendarApi(failInsert = com.latch.google.GoogleUnreachable("offline", java.io.IOException())),
            queue = queue,
        )

        saver.save(captured(fourDates), parse(fourDates), context)
        runCurrent()

        assertIs<SaveState.Queued>(saver.state.value)
        // One entry, not four: per item, the second to drain would find the first entry's
        // item under the shared source_hash and remove itself without writing (SRS §7.1).
        assertEquals(1, queue.entries.size)
        assertEquals(4, queue.entries.values.single().items.size)
    }

    // ----- fixtures -----

    private fun captured(text: String) = CapturedText(text = text, layer = CaptureLayer.SHARE_SHEET)

    private fun parse(text: String) = DateParser.parse(text, context)

    private fun TestScope.saver(
        calendar: CalendarApi = RecordingCalendarApi(),
        tasks: TasksApi = RecordingTasksApi(),
        queue: RecordingQueue = RecordingQueue(),
    ) = CaptureSaver(
        defaultsStore = FixedDefaults(defaults),
        calendarApi = calendar,
        tasksApi = tasks,
        writeQueue = queue,
        inbox = RecordingInbox(),
        index = RecordingIndex(),
        undoOffers = RecordingUndoOffers(),
        requestDrain = { queue.drainsRequested++ },
        scope = this,
        sourceLinkTemplate = "Captured from %1\$s",
    )
}
