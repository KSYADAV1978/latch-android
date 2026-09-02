package com.latch.android.capture

import com.latch.core.model.CaptureLayer
import com.latch.core.model.ItemType
import com.latch.core.model.RoutingMode
import com.latch.data.AccountDefaults
import com.latch.data.AccountDefaultsStore
import com.latch.google.CalendarApi
import com.latch.data.CreatedItem
import com.latch.google.DuplicateSearch
import com.latch.google.EventWrite
import com.latch.google.GoogleRejected
import com.latch.google.GoogleUnreachable
import com.latch.google.ItemDates
import com.latch.data.PendingWrite
import com.latch.data.QueueStatus
import com.latch.data.QueuedWrite
import com.latch.google.RescheduleSearch
import com.latch.google.SignInRequiredException
import com.latch.google.TaskList
import com.latch.google.TaskWrite
import com.latch.google.TasksApi
import com.latch.google.WritableCalendar
import com.latch.data.WriteOperation
import com.latch.data.WriteQueue
import com.latch.google.isWorthRetrying
import com.latch.parser.DateParser
import com.latch.parser.ParseContext
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
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * FR-807: every save offers an undo for not less than ten seconds, removing all items that
 * save created.
 *
 * Runs on virtual time, so the ten seconds cost nothing to wait out — `runTest` drives the
 * saver's own `delay`, which is what actually closes the offer. The real clock is still what
 * `UndoWindow.expiresAt` is set from, and that is deliberate: the expiry the user sees is a
 * wall-clock one, and only the *waiting* is virtual here.
 *
 * A chain is one item on every path this test can reach, because `draftItems` drafts only
 * `ParseResult.primary` until FR-511's per-date checkboxes exist. The removal loop is written
 * for a chain of any size and reports how far it got; the multi-item case becomes reachable,
 * and testable, with that requirement.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CaptureSaverTest {

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
        now = LocalDateTime.parse("2026-08-26T09:00:00"),
        zone = ZoneId.of("Asia/Kolkata"),
    )

    private val eventText = "meeting 15/12/26 at 9:30 am"
    private val taskText = "submit the form by 12/09/2026"

    // ----- the offer -----

    @Test
    fun `a saved event offers an undo, carrying what it created`() = runTest {
        val calendar = RecordingCalendarApi()
        val saver = saver(calendar = calendar)

        saver.save(captured(eventText), parse(eventText), context)
        runToSaved(saver)

        val window = assertNotNull(undoOffer(saver.state.value, Instant.now()))
        val created = assertIs<CreatedItem.Written>(window.created.single())
        assertEquals(ItemType.EVENT, created.type)
        assertEquals("latch-cal", created.containerId)
        assertEquals("event-1", created.remoteId)
    }

    @Test
    fun `the offer stands for at least ten seconds`() = runTest {
        val saver = saver()
        saver.save(captured(eventText), parse(eventText), context)
        runToSaved(saver)

        val window = assertNotNull((saver.state.value as SaveState.Saved).undo)
        // FR-807 says "not less than", so the boundary itself has to be inside the offer.
        assertTrue(window.isOpen(window.expiresAt.minusMillis(1)))
        assertEquals(10, window.secondsRemaining(window.expiresAt.minusSeconds(10)))
        assertEquals(1, window.secondsRemaining(window.expiresAt.minusMillis(1)))
        assertEquals(0, window.secondsRemaining(window.expiresAt))
    }

    @Test
    fun `the offer lapses on its own, and the save stands`() = runTest {
        val calendar = RecordingCalendarApi()
        val saver = saver(calendar = calendar)

        saver.save(captured(eventText), parse(eventText), context)
        runToSaved(saver)
        assertNotNull((saver.state.value as SaveState.Saved).undo)

        advanceTimeBy(UNDO_WINDOW.toMillis() + 1)

        val state = assertIs<SaveState.Saved>(saver.state.value)
        assertNull(state.undo, "the offer should have closed itself")
        assertTrue(calendar.deleted.isEmpty(), "lapsing must not delete anything")
    }

    // ----- taking it -----

    @Test
    fun `undo deletes the event that was written`() = runTest {
        val calendar = RecordingCalendarApi()
        val saver = saver(calendar = calendar)

        saver.save(captured(eventText), parse(eventText), context)
        runToSaved(saver)
        saver.undo()
        advanceUntilIdle()

        assertEquals(SaveState.Undone, saver.state.value)
        assertEquals(listOf("latch-cal" to "event-1"), calendar.deleted)
    }

    @Test
    fun `undo deletes the task that was written`() = runTest {
        val tasks = RecordingTasksApi()
        val saver = saver(tasks = tasks)

        saver.save(captured(taskText), parse(taskText), context)
        runToSaved(saver)
        saver.undo()
        advanceUntilIdle()

        assertEquals(SaveState.Undone, saver.state.value)
        assertEquals(listOf("list-1" to "task-1"), tasks.deleted)
    }

    @Test
    fun `a delete that fails says the item is still there`() = runTest {
        val calendar = RecordingCalendarApi(failDelete = true)
        val saver = saver(calendar = calendar)

        saver.save(captured(eventText), parse(eventText), context)
        runToSaved(saver)
        saver.undo()
        advanceUntilIdle()

        // NFR-303: the user has to be told, because the recourse is to go and remove it in
        // Google by hand — and they cannot do that if the app claims it is gone.
        val state = assertIs<SaveState.UndoFailed>(saver.state.value)
        assertEquals(0, state.removed)
        assertEquals(1, state.total)
    }

    // ----- when the offer is gone -----

    @Test
    fun `undo after the offer has lapsed does nothing`() = runTest {
        val calendar = RecordingCalendarApi()
        val saver = saver(calendar = calendar)

        saver.save(captured(eventText), parse(eventText), context)
        runToSaved(saver)
        advanceTimeBy(UNDO_WINDOW.toMillis() + 1)

        saver.undo()
        advanceUntilIdle()

        assertTrue(calendar.deleted.isEmpty())
        assertIs<SaveState.Saved>(saver.state.value)
    }

    @Test
    fun `a new capture ends the offer`() = runTest {
        val calendar = RecordingCalendarApi()
        val saver = saver(calendar = calendar)

        saver.save(captured(eventText), parse(eventText), context)
        runToSaved(saver)

        // What CaptureActivity.onCreate does when the next capture arrives. The countdown
        // was on a screen that no longer exists.
        saver.reset()
        saver.undo()
        advanceUntilIdle()

        assertEquals(SaveState.Idle, saver.state.value)
        assertTrue(calendar.deleted.isEmpty())
    }

    @Test
    fun `a duplicate offers no undo, because it wrote nothing`() = runTest {
        val calendar = RecordingCalendarApi(existingEventId = "already-there")
        val saver = saver(calendar = calendar)

        saver.save(captured(eventText), parse(eventText), context)
        advanceUntilIdle()

        // FR-803 stopped the write, so there is nothing to take back — and offering to
        // would delete the item the *previous* capture legitimately saved.
        assertEquals(SaveState.AlreadySaved, saver.state.value)
        assertNull(undoOffer(saver.state.value, Instant.now()))
    }

    @Test
    fun `the Save button is gone once a save has happened either way`() {
        assertTrue(saveIsOffered(SaveState.Idle))
        assertTrue(saveIsOffered(SaveState.Failed(SaveFailure.WRITE_FAILED)))
        assertFalse(saveIsOffered(SaveState.Saved()))
        assertFalse(saveIsOffered(SaveState.Undone))
        assertFalse(saveIsOffered(SaveState.UndoFailed(removed = 0, total = 1)))
    }

    // ----- FR-806: offline -----

    @Test
    fun `a capture with no network is queued, not failed`() = runTest {
        val queue = RecordingQueue()
        val saver = saver(calendar = RecordingCalendarApi(failInsert = offline()), queue = queue)

        saver.save(captured(eventText), parse(eventText), context)
        runCurrent()

        // Not Failed: nothing was lost, and telling the user it was would send them to
        // recapture something the app is already holding.
        assertIs<SaveState.Queued>(saver.state.value)
        assertEquals(1, queue.entries.size)
        assertEquals(1, queue.drainsRequested, "a queued write must ask for a drain")
    }

    @Test
    fun `a capture needing a sign-in is queued, never left waiting`() = runTest {
        // FR-806a. The device case, 1 Sep 2026: the cached token had expired, Play services
        // could not refresh it without a network, and the authorization came back requiring a
        // consent screen. Only the main screen can present one, and a capture is not the main
        // screen — so the save suspended for thirteen minutes with nothing on screen and
        // nothing in the queue. A capture must never wait on an interactive authorization.
        val queue = RecordingQueue()
        val needsSignIn = SignInRequiredException("Authorization needs consent, no Activity attached")
        val saver = saver(calendar = RecordingCalendarApi(failInsert = needsSignIn), queue = queue)

        saver.save(captured(eventText), parse(eventText), context)
        runCurrent()

        assertIs<SaveState.Queued>(saver.state.value, "the capture was not queued")
        assertEquals(1, queue.entries.size, "nothing reached the queue")
        assertEquals(1, queue.drainsRequested, "a queued write must ask for a drain")
    }

    @Test
    fun `a sign-in requirement is retryable, so the entry is held and not given up on`() {
        // The drain side of FR-806a. This is not a failure waiting cannot fix; it is one a
        // sign-in fixes, and giving up would lose a capture the user could recover in a tap.
        assertTrue(isWorthRetrying(SignInRequiredException("needs consent")))
    }

    @Test
    fun `a queued write carries everything the worker needs`() = runTest {
        val queue = RecordingQueue()
        val saver = saver(calendar = RecordingCalendarApi(failInsert = offline()), queue = queue)

        saver.save(captured(eventText), parse(eventText), context)
        runCurrent()

        val write = queue.entries.values.single()
        // §7.2 cannot be corrected after the write, so it has to survive the wait.
        assertEquals(sourceHashOf(eventText), write.metadata.sourceHash)
        assertTrue(write.metadata.itemKey.isNotBlank())
        assertEquals("Asia/Kolkata", write.timeZone)
        assertEquals("latch-cal", write.item.calendarId)
        assertEquals(ItemType.EVENT, write.item.type)
    }

    @Test
    fun `a failure that is not offline is still a failure`() = runTest {
        val queue = RecordingQueue()
        val rejected = GoogleRejected(403, "ACCESS_TOKEN_SCOPE_INSUFFICIENT", "Forbidden")
        val saver = saver(calendar = RecordingCalendarApi(failInsert = rejected), queue = queue)

        saver.save(captured(eventText), parse(eventText), context)
        runCurrent()

        // Queueing this would put it on the home screen for ever: no amount of waiting
        // supplies a scope the user has not granted.
        assertEquals(SaveState.Failed(SaveFailure.WRITE_FAILED), saver.state.value)
        assertTrue(queue.entries.isEmpty())
    }

    @Test
    fun `undo of a queued write drops the entry and deletes nothing`() = runTest {
        val queue = RecordingQueue()
        val calendar = RecordingCalendarApi(failInsert = offline())
        val saver = saver(calendar = calendar, queue = queue)

        saver.save(captured(eventText), parse(eventText), context)
        runCurrent()
        saver.undo()
        advanceUntilIdle()

        assertEquals(SaveState.Undone, saver.state.value)
        assertTrue(queue.entries.isEmpty(), "the queue entry should be gone")
        // There was never anything in the account, so nothing may be sent to Google.
        assertTrue(calendar.deleted.isEmpty())
    }

    @Test
    fun `a queued write offers an undo for the same ten seconds`() = runTest {
        val saver = saver(calendar = RecordingCalendarApi(failInsert = offline()))

        saver.save(captured(eventText), parse(eventText), context)
        runCurrent()
        assertNotNull(undoOffer(saver.state.value, Instant.now()))

        advanceTimeBy(UNDO_WINDOW.toMillis() + 1)

        val state = assertIs<SaveState.Queued>(saver.state.value)
        assertNull(state.undo, "the offer should have closed itself")
    }

    @Test
    fun `the Save button is gone once a capture has been queued`() {
        assertFalse(saveIsOffered(SaveState.Queued()))
    }

    // ----- fixtures -----

    private fun offline() = GoogleUnreachable("no network", IOException())

    private fun captured(text: String) =
        CapturedText(text = text, layer = CaptureLayer.SHARE_SHEET)

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

    /**
     * Runs the save to completion without running out the undo window with it.
     *
     * `advanceUntilIdle` would: the saver waits out the ten seconds in the same coroutine
     * that performed the write, so "idle" is on the far side of the offer. `runCurrent` runs
     * everything already due without moving the clock, which is exactly the write.
     */
    private fun TestScope.runToSaved(saver: CaptureSaver) {
        runCurrent()
        assertIs<SaveState.Saved>(saver.state.value, "the save should have completed")
    }
}
