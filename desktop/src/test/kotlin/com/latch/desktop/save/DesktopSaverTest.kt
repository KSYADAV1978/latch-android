package com.latch.desktop.save

import com.latch.desktop.store.reversingSecrets
import com.latch.core.model.Item
import com.latch.desktop.FakeCalendar
import com.latch.desktop.FakeTasks
import com.latch.core.model.ItemType
import com.latch.desktop.capture.DesktopCapture
import com.latch.desktop.queue.WriteQueue
import com.latch.desktop.store.BridgeReply
import com.latch.desktop.store.DesktopDefaults
import com.latch.desktop.store.WindowsSecrets
import com.latch.desktop.store.base64
import com.latch.desktop.store.unbase64
import java.io.File
import com.latch.google.CalendarApi
import com.latch.google.DuplicateSearch
import com.latch.google.EventWrite
import com.latch.google.GoogleRejected
import com.latch.google.GoogleUnreachable
import com.latch.google.ItemDates
import com.latch.google.RescheduleSearch
import com.latch.google.SignInRequiredException
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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    // ---- SRS 1.191: a chain refused part-way -------------------------------------------

    @Test
    fun `a chain refused part-way is reported as partly written, not as saved`() = runTest {
        // The condition that would make this fail: one event reaches Google and the second is
        // refused with a 400, which no retry fixes. `writeAll` returned
        // `Written(created.size, …)` for exactly this, so the user was told "1 item saved to
        // Latch" about a capture of two — true, and not what happened, with the smaller count
        // the only trace.
        val calendar = FakeCalendar().apply {
            failInsertAfter = 1
            failInsert = GoogleRejected(400, "invalid", "refused")
        }
        val text = "Kickoff 1 September 2027 at 9am, review 8 September 2027 at 10am"
        val (result, items) = itemsFor(text)
        assertEquals(2, items.size, "the fixture must actually produce a chain")

        val outcome = DesktopSaver(calendar, FakeTasks(), "Asia/Kolkata")
            .save(DesktopCapture(text), result, items, defaults, "chain-1")

        val partly = assertIs<SaveResult.PartlyWritten>(outcome)
        assertEquals(1, partly.written)
        assertEquals(2, partly.total)
        assertEquals(1, calendar.events.size, "exactly what the fake let through")
        // What landed is still undoable. Reported as `Written` it was too, which is the one
        // thing the old branch got right and the reason this is a reporting fix and not a
        // recovery one.
        assertEquals(1, partly.created.size)
    }

    @Test
    fun `a chain refused on its first item is a plain failure`() = runTest {
        // The boundary, and the guard on the rule: nothing reached the account, so there is
        // no number to state and nothing to undo.
        val calendar = FakeCalendar().apply { failInsert = GoogleRejected(400, "invalid", "refused") }
        val text = "Kickoff 1 September 2027 at 9am, review 8 September 2027 at 10am"
        val (result, items) = itemsFor(text)

        val outcome = DesktopSaver(calendar, FakeTasks(), "Asia/Kolkata")
            .save(DesktopCapture(text), result, items, defaults, "chain-1")

        assertIs<SaveResult.Failed>(outcome)
        assertEquals(0, calendar.events.size)
    }

    // ---- FR-806: the queue is a fallback, not the path -----------------------------------

    @Test
    fun `an offline save is held rather than lost`() = runTest {
        // AC-10, and design principle 1's central case. Before the queue existed this
        // reported a failure and the capture was gone.
        val directory = tempDirectory()
        val queue = WriteQueue(File(directory, "q.dat"), reversingCipher())
        val calendar = FakeCalendar().apply { failInsert = GoogleUnreachable("no network") }
        val text = "Kickoff 8 September 2027 at 9am"
        val (result, items) = itemsFor(text)

        val outcome = DesktopSaver(calendar, FakeTasks(), "Asia/Kolkata", queue)
            .save(DesktopCapture(text), result, items, defaults, "chain-1")

        assertIs<SaveResult.Queued>(outcome)
        assertEquals(1, outcome.count)
        assertEquals(0, outcome.alsoWritten)
        // FR-807: the entry is identified, so an undo inside the window drops it rather than
        // chasing an item that was never written.
        assertNotNull(outcome.queueId)
        val held = queue.entries().single()
        assertEquals(sourceHashOf(text), held.metadata.sourceHash)
        assertEquals("cal-1", held.calendarId)
        assertEquals("Asia/Kolkata", held.timeZone)
        assertTrue(text in held.body, "FR-805's description must be composed before queueing")
        directory.deleteRecursively()
    }

    @Test
    fun `an expired grant holds the capture and asks for a sign-in`() = runTest {
        // **The 7 Sep 2026 loss, as a test** (SRS 1.178, 1.192). A to-do with a recipe applied
        // was refused and the capture was gone, and the whole account of it was five words.
        // The condition that would make this fail: the save reports `Failed` and `q.dat` is
        // empty afterwards, which is exactly what happened on the machine.
        val directory = tempDirectory()
        val queue = WriteQueue(File(directory, "q.dat"), reversingCipher())
        val calendar = FakeCalendar().apply { failFind = SignInRequiredException("sign in") }
        val text = "Kickoff 8 September 2027 at 9am"
        val (result, items) = itemsFor(text)

        val outcome = DesktopSaver(calendar, FakeTasks(), "Asia/Kolkata", queue)
            .save(DesktopCapture(text), result, items, defaults, "chain-1")

        val queued = assertIs<SaveResult.Queued>(outcome)
        // The capture is on disk, which is the whole of what was lost before.
        assertEquals(1, queue.entries().size)
        // And it says *which* wait this is. "No connection" over a live network with an
        // expired grant names the wrong cure, and the user waits for something that has
        // already happened.
        assertTrue(queued.needsSignIn)
        assertTrue(queue.entries().single().needsSignIn)
        assertTrue(queue.status().needsSignIn, "FR-806a's surface has to have something to read")
        directory.deleteRecursively()
    }

    @Test
    fun `an ordinary offline save is not reported as needing a sign-in`() = runTest {
        // The guard on the change: two different waits, and conflating them would make the
        // tray ask for a sign-in every time the network dropped.
        val directory = tempDirectory()
        val queue = WriteQueue(File(directory, "q.dat"), reversingCipher())
        val calendar = FakeCalendar().apply { failInsert = GoogleUnreachable("no network") }
        val text = "Kickoff 8 September 2027 at 9am"
        val (result, items) = itemsFor(text)

        val outcome = DesktopSaver(calendar, FakeTasks(), "Asia/Kolkata", queue)
            .save(DesktopCapture(text), result, items, defaults, "chain-1")

        assertFalse(assertIs<SaveResult.Queued>(outcome).needsSignIn)
        assertFalse(queue.status().needsSignIn)
        directory.deleteRecursively()
    }

    @Test
    fun `a failure waiting cannot fix is reported rather than queued for ever`() = runTest {
        // A 403 for a scope never granted would sit in the count with nothing said about
        // why, which is what `isWorthRetrying` exists to prevent.
        val directory = tempDirectory()
        val queue = WriteQueue(File(directory, "q.dat"), reversingCipher())
        val calendar = FakeCalendar().apply {
            failInsert = GoogleRejected(403, "insufficientPermissions", "no")
        }
        val (result, items) = itemsFor("Kickoff 8 September 2027 at 9am")

        val outcome = DesktopSaver(calendar, FakeTasks(), "UTC", queue)
            .save(DesktopCapture("x"), result, items, defaults, "c")

        assertEquals(SaveFailure.REFUSED, (outcome as SaveResult.Failed).reason)
        assertTrue(queue.entries().isEmpty(), "an unretryable failure must not be queued")
        directory.deleteRecursively()
    }

    @Test
    fun `a chain that fails halfway queues only what is still owed`() = runTest {
        // SRS 1.24. The queue entry records what was written, so the drain resumes rather
        // than writing the first item a second time — a duplicate FR-803 could not catch,
        // because the entry's own hash is what put it there.
        val directory = tempDirectory()
        val queue = WriteQueue(File(directory, "q.dat"), reversingCipher())
        val tasks = FakeTasks().apply { failInsert = GoogleUnreachable("no network") }
        val text = "PTM on 14 September 2027. Fees due 20 September 2027."
        val (result, items) = itemsFor(text)
        assertTrue(items.size >= 2, "this fixture needs a chain")

        val outcome = DesktopSaver(FakeCalendar(), tasks, "UTC", queue)
            .save(DesktopCapture(text), result, items, defaults, "c")

        assertIs<SaveResult.Queued>(outcome)
        val held = queue.entries().single()
        assertEquals(items.size, held.items.size, "the whole chain must be carried")
        assertEquals(
            outcome.alsoWritten,
            held.items.count { it.writtenId != null },
            "what was already written must be marked so the drain does not repeat it",
        )
        directory.deleteRecursively()
    }

    @Test
    fun `a probe that cannot run queues rather than reporting`() = runTest {
        // FR-803 could not be asked — almost always no network. The drain asks again before
        // it writes, so nothing is duplicated by having been unable to ask now.
        val directory = tempDirectory()
        val queue = WriteQueue(File(directory, "q.dat"), reversingCipher())
        val calendar = FakeCalendar().apply { failFind = GoogleUnreachable("no network") }
        val (result, items) = itemsFor("Kickoff 8 September 2027 at 9am")

        val outcome = DesktopSaver(calendar, FakeTasks(), "UTC", queue)
            .save(DesktopCapture("x"), result, items, defaults, "c")

        assertIs<SaveResult.Queued>(outcome)
        assertEquals(1, queue.entries().size)
        directory.deleteRecursively()
    }

    @Test
    fun `with no queue at all an offline save still reports rather than pretending`() = runTest {
        val calendar = FakeCalendar().apply { failInsert = GoogleUnreachable("no network") }
        val (result, items) = itemsFor("Kickoff 8 September 2027 at 9am")
        val outcome = DesktopSaver(calendar, FakeTasks())
            .save(DesktopCapture("x"), result, items, defaults, "c")
        assertEquals(SaveFailure.OFFLINE, (outcome as SaveResult.Failed).reason)
    }

    private fun tempDirectory(): File = File.createTempFile("latch-saver", "").let {
        it.delete(); it.mkdirs(); it
    }

    private fun reversingCipher() = reversingSecrets()

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
