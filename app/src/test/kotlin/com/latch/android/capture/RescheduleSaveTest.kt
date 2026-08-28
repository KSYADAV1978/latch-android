package com.latch.android.capture

import com.latch.core.model.CaptureLayer
import com.latch.core.model.ItemType
import com.latch.core.model.RoutingMode
import com.latch.data.AccountDefaults
import com.latch.data.AccountDefaultsStore
import com.latch.data.CalendarApi
import com.latch.data.ItemDates
import com.latch.data.PendingWrite
import com.latch.data.QueueStatus
import com.latch.data.QueuedWrite
import com.latch.data.RescheduleMatch
import com.latch.data.RescheduleSearch
import com.latch.data.TasksApi
import com.latch.data.WriteOperation
import com.latch.data.WriteQueue
import com.latch.parser.DateParser
import com.latch.parser.ParseContext
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
 * FR-804 through the saver: when the key is queried at all, what the offer carries, what each
 * answer does, and that undoing an update restores rather than deletes.
 *
 * The reschedule pair is "Project sync on Tuesday 5 March 2030 at 21:30" moved to Thursday
 * 7 March — the same text the device sequence uses, and chosen so both dates stay in the
 * future indefinitely. 5 March 2030 is a Tuesday and 7 March 2030 is a Thursday.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RescheduleSaveTest {

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
        now = LocalDateTime.parse("2030-03-01T09:00:00"),
        zone = ZoneId.of("Asia/Kolkata"),
    )

    private val movedText = "Project sync on Thursday 7 March 2030 at 21:30"

    /** Where the item stands before the capture moves it. */
    private val standingAt = ItemDates.Event(
        start = LocalDateTime.parse("2030-03-05T21:30"),
        end = LocalDateTime.parse("2030-03-05T22:30"),
        timeZone = "Asia/Kolkata",
    )

    /** What the item in the account is actually called — not what this capture says. */
    private val storedTitle = "Project sync"

    private fun matchAt(dates: ItemDates = standingAt, title: String = storedTitle) =
        RescheduleSearch(RescheduleMatch("ev_1", dates, title))

    // ----- the query, and when it is not made -----

    @Test
    fun `the item key is queried exactly once, and under one derivation only`() = runTest {
        val calendar = RecordingCalendarApi(rescheduleMatch = matchAt())
        val saver = saver(calendar = calendar)

        saver.save(captured(movedText), parse(movedText), context)
        runCurrent()

        // One query, one key. There is deliberately no second lookup under the pre-v1.14
        // derivation to rescue items written before it: §7.2 declares those unmatched, and
        // querying an old-style key would quietly restore a wire contract this project moved.
        assertEquals(1, calendar.itemKeysQueried.size, "expected exactly one item_key query")
        assertEquals(1, calendar.itemKeysQueried.distinct().size)
    }

    @Test
    fun `a source hash match settles it and the item key is never queried`() = runTest {
        // §7.2 row 1: the key is not consulted, so a duplicate cannot present as a
        // reschedule. Asserted here rather than only in the pure function, because the
        // ordering is the saver's to keep.
        val calendar = RecordingCalendarApi(existingEventId = "ev_dup", rescheduleMatch = matchAt())
        val saver = saver(calendar = calendar)

        saver.save(captured(movedText), parse(movedText), context)
        runCurrent()

        assertEquals(SaveState.AlreadySaved, saver.state.value)
        assertTrue(calendar.itemKeysQueried.isEmpty(), "the key must not be consulted at all")
    }

    // ----- the offer -----

    @Test
    fun `a match on a different date offers the move and writes nothing`() = runTest {
        val calendar = RecordingCalendarApi(rescheduleMatch = matchAt())
        val saver = saver(calendar = calendar)

        saver.save(captured(movedText), parse(movedText), context)
        runCurrent()

        val offered = assertIs<SaveState.RescheduleOffered>(saver.state.value)
        assertEquals(standingAt, offered.existing)
        assertEquals(LocalDateTime.parse("2030-03-07T21:30"), (offered.proposed as ItemDates.Event).start)
        // FR-804 forbids a silent update, so nothing may be written before the answer.
        assertTrue(calendar.patched.isEmpty(), "nothing may be written while the offer stands")
    }

    @Test
    fun `the offer names the stored item, not the text of the capture moving it`() = runTest {
        val saver = saver(calendar = RecordingCalendarApi(rescheduleMatch = matchAt()))

        saver.save(captured(movedText), parse(movedText), context)
        runCurrent()

        val offered = assertIs<SaveState.RescheduleOffered>(saver.state.value)
        // Not "Project sync on Thursday, March 7, 2030 at 21:30", which is what FR-509 makes
        // of this short capture and what would read as «"<the new date>" is already saved for
        // <the old date>». The question is about the item in the account.
        assertEquals(storedTitle, offered.title)
        assertTrue(movedText !in offered.title)
    }

    @Test
    fun `an item with no stored title falls back to the drafted one`() = runTest {
        val saver = saver(calendar = RecordingCalendarApi(rescheduleMatch = matchAt(title = "")))

        saver.save(captured(movedText), parse(movedText), context)
        runCurrent()

        // The sentence still needs a subject; a blank would read as a missing word.
        val offered = assertIs<SaveState.RescheduleOffered>(saver.state.value)
        assertTrue(offered.title.isNotBlank())
    }

    @Test
    fun `the Save button is not offered while the question is update-or-create`() {
        assertTrue(
            !saveIsOffered(
                SaveState.RescheduleOffered("Project sync", standingAt, standingAt),
            ),
        )
    }

    // ----- answering it -----

    @Test
    fun `updating patches the existing item and creates nothing`() = runTest {
        val calendar = RecordingCalendarApi(rescheduleMatch = matchAt())
        val saver = saver(calendar = calendar)

        saver.save(captured(movedText), parse(movedText), context)
        runCurrent()
        saver.updateExisting()
        runCurrent()

        val (calendarId, eventId, dates) = calendar.patched.single()
        assertEquals("latch-cal", calendarId)
        assertEquals("ev_1", eventId)
        assertEquals(LocalDateTime.parse("2030-03-07T21:30"), dates.start)
        assertEquals(0, calendar.inserted, "an update must not also create an item")
    }

    @Test
    fun `creating instead inserts a new item and patches nothing`() = runTest {
        val calendar = RecordingCalendarApi(rescheduleMatch = matchAt())
        val saver = saver(calendar = calendar)

        saver.save(captured(movedText), parse(movedText), context)
        runCurrent()
        saver.createNewInstead()
        runCurrent()

        assertEquals(1, calendar.inserted)
        assertTrue(calendar.patched.isEmpty())
        assertIs<SaveState.Saved>(saver.state.value)
    }

    @Test
    fun `an answer that arrives after the offer has gone does nothing`() = runTest {
        val calendar = RecordingCalendarApi(rescheduleMatch = matchAt())
        val saver = saver(calendar = calendar)

        saver.save(captured(movedText), parse(movedText), context)
        runCurrent()
        // What a new capture does. The question belonged to a screen that no longer exists.
        saver.reset()
        saver.updateExisting()
        runCurrent()

        assertEquals(SaveState.Idle, saver.state.value)
        assertTrue(calendar.patched.isEmpty())
    }

    @Test
    fun `a second tap cannot patch the item twice`() = runTest {
        val calendar = RecordingCalendarApi(rescheduleMatch = matchAt())
        val saver = saver(calendar = calendar)

        saver.save(captured(movedText), parse(movedText), context)
        runCurrent()
        saver.updateExisting()
        saver.updateExisting()
        runCurrent()

        assertEquals(1, calendar.patched.size)
    }

    // ----- FR-807 over an update: a restore, never a delete -----

    @Test
    fun `undoing an update puts the previous dates back and deletes nothing`() = runTest {
        val calendar = RecordingCalendarApi(rescheduleMatch = matchAt())
        val saver = saver(calendar = calendar)

        saver.save(captured(movedText), parse(movedText), context)
        runCurrent()
        saver.updateExisting()
        runCurrent()
        saver.undo()
        advanceUntilIdle()

        assertEquals(SaveState.Undone, saver.state.value)
        // The item existed before this save touched it. Deleting it would destroy something
        // the user already had — the outcome FR-807's wording had to be corrected to exclude.
        assertTrue(calendar.deleted.isEmpty(), "an update must never be undone by a delete")

        val restored = calendar.patched.last()
        assertEquals("ev_1", restored.second)
        assertEquals(standingAt.start, restored.third.start)
        assertEquals(standingAt.end, restored.third.end)
    }

    @Test
    fun `the undo offer after an update carries an Updated, not a Written`() = runTest {
        val calendar = RecordingCalendarApi(rescheduleMatch = matchAt())
        val saver = saver(calendar = calendar)

        saver.save(captured(movedText), parse(movedText), context)
        runCurrent()
        saver.updateExisting()
        runCurrent()

        val window = assertNotNull(undoOffer(saver.state.value, Instant.now()))
        val updated = assertIs<CreatedItem.Updated>(window.created.single())
        assertEquals(ItemType.EVENT, updated.type)
        assertEquals(standingAt, updated.priorDates)
    }

    // ----- FR-806 over an update -----

    @Test
    fun `an update that cannot go now queues as an UPDATE carrying its target and prior state`() = runTest {
        val calendar = RecordingCalendarApi(rescheduleMatch = matchAt(), failPatch = true)
        val queue = RecordingQueue()
        val saver = saver(calendar = calendar, queue = queue)

        saver.save(captured(movedText), parse(movedText), context)
        runCurrent()
        saver.updateExisting()
        runCurrent()

        assertIs<SaveState.Queued>(saver.state.value)
        val (id, write) = queue.entries.entries.single().toPair()
        assertEquals(WriteOperation.UPDATE, queue.operations[id])
        // Without these the entry could be neither applied after a restart nor undone.
        assertEquals("ev_1", write.targetRemoteId)
        assertEquals(standingAt, write.priorState)
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
        requestDrain = { queue.drainsRequested++ },
        scope = this,
        sourceLinkTemplate = "Captured from %1\$s",
    )
}
