package com.latch.android.capture

import com.latch.core.model.CaptureLayer
import com.latch.core.model.ItemType
import com.latch.core.model.RoutingMode
import com.latch.data.AccountDefaults
import com.latch.data.AccountDefaultsStore
import com.latch.data.CalendarApi
import com.latch.data.DuplicateSearch
import com.latch.data.EventWrite
import com.latch.data.TaskList
import com.latch.data.TaskWrite
import com.latch.data.TasksApi
import com.latch.data.WritableCalendar
import com.latch.parser.DateParser
import com.latch.parser.ParseContext
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
        val created = window.created.single()
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

    // ----- fixtures -----

    private fun captured(text: String) =
        CapturedText(text = text, layer = CaptureLayer.SHARE_SHEET)

    private fun parse(text: String) = DateParser.parse(text, context)

    private fun TestScope.saver(
        calendar: CalendarApi = RecordingCalendarApi(),
        tasks: TasksApi = RecordingTasksApi(),
    ) = CaptureSaver(
        defaultsStore = FixedDefaults(defaults),
        calendarApi = calendar,
        tasksApi = tasks,
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

private class FixedDefaults(private val defaults: AccountDefaults) : AccountDefaultsStore {
    override suspend fun defaultsFor(accountId: String) = defaults
    override suspend fun allAccounts() = listOf(defaults)
    override suspend fun save(defaults: AccountDefaults) = Unit
    override suspend fun remove(accountId: String) = Unit
}

private class RecordingCalendarApi(
    private val existingEventId: String? = null,
    private val failDelete: Boolean = false,
) : CalendarApi {
    val deleted = mutableListOf<Pair<String, String>>()

    override suspend fun insertEvent(calendarId: String, event: EventWrite) = "event-1"

    override suspend fun findEventBySourceHash(calendarId: String, sourceHash: String) =
        DuplicateSearch(existingEventId)

    override suspend fun deleteEvent(calendarId: String, eventId: String) {
        if (failDelete) throw IllegalStateException("events.delete refused")
        deleted += calendarId to eventId
    }

    override suspend fun listWritableCalendars(): List<WritableCalendar> = emptyList()
    override suspend fun createLatchCalendar(summary: String, description: String) = "unused"
    override suspend fun setColourAndVisibility(calendarId: String, colorId: String, visible: Boolean): String? = null
    override suspend fun makeVisible(calendarId: String) = Unit
}

private class RecordingTasksApi(private val failDelete: Boolean = false) : TasksApi {
    val deleted = mutableListOf<Pair<String, String>>()

    override suspend fun insertTask(taskListId: String, task: TaskWrite) = "task-1"

    override suspend fun findTaskBySourceHash(taskListId: String, sourceHash: String, due: LocalDate?) =
        DuplicateSearch(existingId = null)

    override suspend fun deleteTask(taskListId: String, taskId: String) {
        if (failDelete) throw IllegalStateException("tasks.delete refused")
        deleted += taskListId to taskId
    }

    override suspend fun listTaskLists(): List<TaskList> = emptyList()
}
