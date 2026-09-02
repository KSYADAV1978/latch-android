package com.latch.desktop.save

import com.latch.core.model.Item
import com.latch.core.model.ItemType
import com.latch.desktop.capture.DesktopCapture
import com.latch.desktop.store.DesktopDefaults
import com.latch.google.CalendarApi
import com.latch.google.DuplicateSearch
import com.latch.google.EventWrite
import com.latch.google.GoogleRejected
import com.latch.google.GoogleUnreachable
import com.latch.google.ItemDates
import com.latch.google.RescheduleSearch
import com.latch.google.TaskList
import com.latch.google.TaskWrite
import com.latch.google.TasksApi
import com.latch.google.WritableCalendar
import com.latch.parser.DateParser
import com.latch.parser.ParseContext
import com.latch.wire.DraftResult
import com.latch.wire.draftItems
import com.latch.wire.sourceHashOf
import kotlinx.coroutines.test.runTest
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A calendar that answers by the hash it was actually given.
 *
 * `CLAUDE.md` records why that sentence is in this file: the Android fake's
 * `findEventBySourceHash` ignored its argument and returned a preset id, so it agreed with a
 * broken implementation for as long as the implementation was broken. A fake that answers by
 * fixture rather than by matching cannot catch a matching bug.
 */
private class FakeCalendar : CalendarApi {
    val events = mutableMapOf<String, EventWrite>()
    val indexed = mutableMapOf<String, String>()
    var created: String? = null
    var calendars: List<WritableCalendar> = emptyList()
    var failInsert: Exception? = null
    var failList: Exception? = null

    override suspend fun listWritableCalendars(): List<WritableCalendar> {
        failList?.let { throw it }
        return calendars
    }

    override suspend fun createLatchCalendar(summary: String, description: String): String {
        created = summary
        return "made-" + summary
    }

    override suspend fun setColourAndVisibility(calendarId: String, colorId: String, visible: Boolean): String? = null

    override suspend fun makeVisible(calendarId: String) = Unit

    override suspend fun insertEvent(calendarId: String, event: EventWrite): String {
        failInsert?.let { throw it }
        val id = "ev" + (events.size + 1)
        events[id] = event
        indexed[event.metadata.sourceHash] = id
        return id
    }

    override suspend fun findEventBySourceHash(calendarId: String, sourceHash: String): DuplicateSearch =
        DuplicateSearch(existingId = indexed[sourceHash])

    override suspend fun findEventByItemKey(calendarId: String, itemKey: String): RescheduleSearch =
        RescheduleSearch()

    override suspend fun patchEventDates(calendarId: String, eventId: String, dates: ItemDates.Event) = Unit

    override suspend fun deleteEvent(calendarId: String, eventId: String) = Unit
}

private class FakeTasks : TasksApi {
    val tasks = mutableMapOf<String, TaskWrite>()
    val indexed = mutableMapOf<String, String>()
    var lists: List<TaskList> = listOf(TaskList("list-1", "My Tasks", isDefault = true))
    var failInsert: Exception? = null

    override suspend fun listTaskLists(): List<TaskList> = lists

    override suspend fun insertTask(taskListId: String, task: TaskWrite): String {
        failInsert?.let { throw it }
        val id = "tk" + (tasks.size + 1)
        tasks[id] = task
        indexed[task.metadata.sourceHash] = id
        return id
    }

    override suspend fun findTaskBySourceHash(
        taskListId: String,
        sourceHash: String,
        due: LocalDate?,
    ): DuplicateSearch = DuplicateSearch(existingId = indexed[sourceHash], scanCapped = false)

    override suspend fun findTaskByItemKey(taskListId: String, itemKey: String): RescheduleSearch =
        RescheduleSearch()

    override suspend fun patchTaskDates(taskListId: String, taskId: String, dates: ItemDates.Task) = Unit

    override suspend fun deleteTask(taskListId: String, taskId: String) = Unit
}

class DesktopSaverTest {

    private val defaults = DesktopDefaults("a@example.com", "cal-1", "Latch", "list-1")
    private val context = ParseContext(now = LocalDateTime.of(2026, 9, 2, 12, 0))

    private fun itemsFor(text: String, capture: DesktopCapture = DesktopCapture(text)): Pair<com.latch.parser.ParseResult, List<Item>> {
        val result = DateParser.parse(text, context)
        val draft = draftItems(
            captured = capture,
            result = result,
            context = context,
            destination = defaults.toWireDestination(),
            captureId = "cap-1",
            chainId = "chain-1",
        )
        assertIs<DraftResult.Ready>(draft)
        return result to draft.items
    }

    @Test
    fun `a dated capture becomes an event in the chosen calendar`() = runTest {
        val calendar = FakeCalendar()
        val tasks = FakeTasks()
        val text = "Kickoff 8 September 2027 at 9am"
        val (result, items) = itemsFor(text)

        val outcome = DesktopSaver(calendar, tasks, zone = "Asia/Kolkata")
            .save(DesktopCapture(text), result, items, defaults, "chain-1")

        assertIs<SaveResult.Written>(outcome)
        assertEquals(1, outcome.count)
        val event = calendar.events.values.single()
        assertEquals("Asia/Kolkata", event.timeZone)
        assertEquals(sourceHashOf(text), event.metadata.sourceHash)
        assertTrue(event.metadata.itemKey.length == 64)
        assertEquals("chain-1", event.metadata.chainId)
    }

    @Test
    fun `FR-803 the same message a second time writes nothing`() = runTest {
        // AC-07's mechanism, and the fake indexes on the hash the write actually carried — so
        // a saver that queried a different hash than it wrote would fail this.
        val calendar = FakeCalendar()
        val tasks = FakeTasks()
        val text = "Kickoff 8 September 2027 at 9am"
        val (result, items) = itemsFor(text)
        val saver = DesktopSaver(calendar, tasks)

        assertIs<SaveResult.Written>(saver.save(DesktopCapture(text), result, items, defaults, "chain-1"))
        val second = saver.save(DesktopCapture(text), result, items, defaults, "chain-2")

        assertEquals(SaveResult.AlreadySaved, second)
        assertEquals(1, calendar.events.size, "a duplicate reached Google")
    }

    @Test
    fun `FR-805 the description carries the captured text`() = runTest {
        val calendar = FakeCalendar()
        val text = "Kickoff 8 September 2027 at 9am"
        val (result, items) = itemsFor(text)
        DesktopSaver(calendar, FakeTasks()).save(DesktopCapture(text), result, items, defaults, "c")
        assertTrue(text in calendar.events.values.single().description)
    }

    @Test
    fun `FR-805b an OCR capture is excerpted rather than quoted whole`() = runTest {
        // SRS 1.32's stated test of any implementation, applied to the second client: the
        // description must be strictly shorter than what the recogniser saw, and must not
        // carry the rows around it.
        val screen = buildString {
            appendLine("Sharma Ji")
            appendLine("Paid the uniform bill, Rs 12,500 in total.")
            appendLine("Thanks. Did the school send the circular?")
            appendLine("Yes. PTM on 14 September 2026.")
            appendLine("Ok noted. Can you also send the form?")
            appendLine("Will forward it.")
        }
        val capture = DesktopCapture(text = screen, ocrUsed = true)
        val calendar = FakeCalendar()
        val tasks = FakeTasks()
        val (result, items) = itemsFor(screen, capture)

        DesktopSaver(calendar, tasks).save(capture, result, items, defaults, "c")

        val body = (calendar.events.values.firstOrNull()?.description
            ?: tasks.tasks.values.first().notes)
        assertTrue(body.length < screen.length, "the whole screen reached the description")
        assertTrue("Rs 12,500" !in body, "the amount paid reached the description: " + body)
    }

    @Test
    fun `offline is told apart from refused, because only one of them is worth retrying`() = runTest {
        val text = "Kickoff 8 September 2027 at 9am"
        val (result, items) = itemsFor(text)

        val offline = FakeCalendar().apply { failInsert = GoogleUnreachable("no network") }
        assertEquals(
            SaveFailure.OFFLINE,
            (DesktopSaver(offline, FakeTasks()).save(DesktopCapture(text), result, items, defaults, "c") as SaveResult.Failed).reason,
        )

        val refused = FakeCalendar().apply { failInsert = GoogleRejected(403, "insufficientPermissions", "insufficient scope") }
        assertEquals(
            SaveFailure.REFUSED,
            (DesktopSaver(refused, FakeTasks()).save(DesktopCapture(text), result, items, defaults, "c") as SaveResult.Failed).reason,
        )
    }

    @Test
    fun `an empty chain is reported rather than written as a success`() = runTest {
        val (result, _) = itemsFor("Kickoff 8 September 2027 at 9am")
        val outcome = DesktopSaver(FakeCalendar(), FakeTasks())
            .save(DesktopCapture("x"), result, emptyList(), defaults, "c")
        assertEquals(SaveFailure.NOTHING_TO_WRITE, (outcome as SaveResult.Failed).reason)
    }
}

class DesktopSetupTest {

    @Test
    fun `an account's existing Latch calendar is reused rather than a second one made`() = runTest {
        // AC-07 depends on this. FR-803's event query is scoped to a calendar id, so a desktop
        // that made its own second calendar called Latch would look, to itself, like an account
        // with no duplicates in it — and would write one for every capture already on the phone.
        val calendar = FakeCalendar().apply {
            calendars = listOf(
                WritableCalendar("primary-1", "a@example.com", "#fff", isPrimary = true, visible = true),
                WritableCalendar("latch-1", "Latch", "#f00", isPrimary = false, visible = true),
            )
        }
        val outcome = DesktopSetup(calendar, FakeTasks()).chooseDestination("a@example.com")
        assertIs<SetupResult.Ready>(outcome)
        assertEquals("latch-1", outcome.defaults.calendarId)
        assertNull(calendar.created, "a second Latch calendar was created")
    }

    @Test
    fun `a primary calendar that happens to be called Latch is not adopted`() {
        // FR-906 is emphatic that captures must not silently land in a personal calendar, and
        // a user called Latch, or one whose main calendar is named for their company, is
        // exactly how that would happen quietly.
        val calendars = listOf(
            WritableCalendar("primary-1", "Latch", "#fff", isPrimary = true, visible = true),
        )
        assertNull(existingLatchCalendar(calendars))
    }

    @Test
    fun `with no Latch calendar one is created and given FR-104's colour`() = runTest {
        val calendar = FakeCalendar().apply {
            calendars = listOf(WritableCalendar("p", "me@example.com", "#fff", isPrimary = true, visible = true))
        }
        val outcome = DesktopSetup(calendar, FakeTasks()).chooseDestination("me@example.com")
        assertIs<SetupResult.Ready>(outcome)
        assertEquals("Latch", calendar.created)
        assertEquals("made-Latch", outcome.defaults.calendarId)
    }

    @Test
    fun `an unreadable calendar list fails rather than choosing something`() = runTest {
        // FR-908's instinct: an unreadable list is a network problem far more often than a
        // deleted calendar, and picking a destination on that basis would move a user's
        // captures for a reason that has nothing to do with their calendars.
        val calendar = FakeCalendar().apply { failList = GoogleUnreachable("no network") }
        val outcome = DesktopSetup(calendar, FakeTasks()).chooseDestination("a@example.com")
        assertEquals(SetupFailure.UNREACHABLE, (outcome as SetupResult.Failed).reason)
    }

    @Test
    fun `the default task list is preferred over the first one`() = runTest {
        val tasks = FakeTasks().apply {
            lists = listOf(
                TaskList("other", "Shopping", isDefault = false),
                TaskList("mine", "My Tasks", isDefault = true),
            )
        }
        val calendar = FakeCalendar().apply {
            calendars = listOf(WritableCalendar("latch-1", "Latch", "#f00", isPrimary = false, visible = true))
        }
        val outcome = DesktopSetup(calendar, tasks).chooseDestination("a@example.com")
        assertEquals("mine", (outcome as SetupResult.Ready).defaults.taskListId)
    }

    @Test
    fun `an account with no task list at all is a named failure`() = runTest {
        val tasks = FakeTasks().apply { lists = emptyList() }
        val calendar = FakeCalendar().apply {
            calendars = listOf(WritableCalendar("latch-1", "Latch", "#f00", isPrimary = false, visible = true))
        }
        val outcome = DesktopSetup(calendar, tasks).chooseDestination("a@example.com")
        assertEquals(SetupFailure.NO_TASK_LIST, (outcome as SetupResult.Failed).reason)
    }
}

class DesktopDefaultsTest {

    @Test
    fun `a record round trips`() {
        val defaults = DesktopDefaults("a@example.com", "cal-1", "Latch", "list-1")
        assertEquals(defaults, DesktopDefaults.decode(defaults.encode()))
    }

    @Test
    fun `a record from another version is dropped rather than read as this one`() {
        val future = """{"v":2,"email":"a@example.com","calendar_id":"c","task_list_id":"l"}"""
        assertNull(DesktopDefaults.decode(future))
        assertNull(DesktopDefaults.decode("not json"))
        assertNull(DesktopDefaults.decode(null))
        assertNull(DesktopDefaults.decode(""))
    }

    @Test
    fun `a record missing a field a write needs is dropped`() {
        // Otherwise it becomes a write to a calendar called the empty string, which Google
        // answers with a 404 the user cannot interpret. Setup running again is the better
        // failure.
        assertNull(DesktopDefaults.decode("""{"v":1,"email":"a@example.com","task_list_id":"l"}"""))
        assertNull(DesktopDefaults.decode("""{"v":1,"email":"a@example.com","calendar_id":"c"}"""))
    }

    @Test
    fun `a record with no calendar name falls back to the id rather than showing nothing`() {
        val decoded = DesktopDefaults.decode("""{"v":1,"email":"a@e.com","calendar_id":"c","task_list_id":"l"}""")
        assertEquals("c", decoded?.calendarName)
    }
}
