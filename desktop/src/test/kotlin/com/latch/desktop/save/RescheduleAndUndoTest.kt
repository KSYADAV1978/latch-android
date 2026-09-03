package com.latch.desktop.save

import com.latch.core.model.Item
import com.latch.core.model.ItemType
import com.latch.desktop.FakeCalendar
import com.latch.desktop.FakeTasks
import com.latch.desktop.capture.DesktopCapture
import com.latch.desktop.queue.WriteQueue
import com.latch.desktop.store.BridgeReply
import com.latch.desktop.store.DesktopDefaults
import com.latch.desktop.store.WindowsSecrets
import com.latch.desktop.store.base64
import com.latch.desktop.store.unbase64
import com.latch.desktop.ui.rescheduleOfferText
import com.latch.desktop.ui.undoLabel
import com.latch.desktop.ui.undoOutcomeText
import com.latch.google.GoogleRejected
import com.latch.google.GoogleUnreachable
import com.latch.google.ItemDates
import com.latch.google.RescheduleMatch
import com.latch.google.RescheduleSearch
import com.latch.parser.DateParser
import com.latch.parser.ParseContext
import com.latch.wire.DraftResult
import com.latch.wire.draftItems
import kotlinx.coroutines.test.runTest
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RescheduleTest {

    private val defaults = DesktopDefaults("a@example.com", "cal-1", "Latch", "list-1")
    private val context = ParseContext(now = LocalDateTime.of(2026, 9, 3, 9, 0))

    private fun itemsFor(text: String): Pair<com.latch.parser.ParseResult, List<Item>> {
        val result = DateParser.parse(text, context)
        val draft = draftItems(
            captured = DesktopCapture(text), result = result, context = context,
            destination = defaults.toWireDestination(), captureId = "c", chainId = "c",
        )
        assertIs<DraftResult.Ready>(draft)
        return result to draft.items
    }

    private fun matchAt(start: LocalDateTime, id: String = "ev-existing") = RescheduleMatch(
        remoteId = id,
        title = "Project sync",
        dates = ItemDates.Event(start, start.plusHours(1), allDay = false, timeZone = "Asia/Kolkata"),
    )

    @Test
    fun `a same identity at a different date offers rather than writes`() = runTest {
        // FR-804's central case. What would make this fail is a second item appearing: the
        // fake records every insert, so an implementation that wrote first and asked after
        // would be caught by the emptiness assertion rather than by the return type alone.
        val calendar = FakeCalendar().apply {
            rescheduleMatch = RescheduleSearch(matchAt(LocalDateTime.of(2027, 9, 8, 11, 0)))
        }
        val (result, items) = itemsFor("Project sync on 9 September 2027 at 11:00")

        val outcome = DesktopSaver(calendar, FakeTasks(), "Asia/Kolkata")
            .save(DesktopCapture("Project sync on 9 September 2027 at 11:00"), result, items, defaults, "c")

        assertIs<SaveResult.RescheduleOffered>(outcome)
        assertEquals("Project sync", outcome.storedTitle)
        assertTrue(calendar.events.isEmpty(), "an item was written while the offer stood")
    }

    @Test
    fun `SRS 1_25 a multi-item capture is never offered as a reschedule`() = runTest {
        // Accepting an offer patches one item, so offering one for a chain would move one date
        // and discard the rest. The fake would happily return a match; the saver must not ask.
        val calendar = FakeCalendar().apply {
            rescheduleMatch = RescheduleSearch(matchAt(LocalDateTime.of(2027, 9, 8, 11, 0)))
        }
        val text = "PTM on 14 September 2027. Fees due 20 September 2027."
        val (result, items) = itemsFor(text)
        assertTrue(items.size >= 2, "this fixture needs a chain")

        val outcome = DesktopSaver(calendar, FakeTasks(), "Asia/Kolkata")
            .save(DesktopCapture(text), result, items, defaults, "c")

        assertIs<SaveResult.Written>(outcome)
        assertEquals(0, calendar.itemKeyQueries, "FR-804 was queried for a chain")
    }

    @Test
    fun `the same key at the same date is a duplicate, not an offer`() = runTest {
        // §7.2's row 3. A message restated — forwarded, quoted back, sent again with a
        // different greeting — is different text naming the same date. Offering to move an item
        // to where it already is would be the app misreading a restatement as a change.
        val calendar = FakeCalendar().apply {
            rescheduleMatch = RescheduleSearch(matchAt(LocalDateTime.of(2027, 9, 9, 11, 0)))
        }
        val (result, items) = itemsFor("Project sync on 9 September 2027 at 11:00")

        val outcome = DesktopSaver(calendar, FakeTasks(), "Asia/Kolkata")
            .save(DesktopCapture("Fwd: Project sync on 9 September 2027 at 11:00"), result, items, defaults, "c")

        assertEquals(SaveResult.AlreadySaved, outcome)
        assertTrue(calendar.events.isEmpty())
    }

    @Test
    fun `a same-day change of time is a reschedule, because an update would move something`() =
        runTest {
            val calendar = FakeCalendar().apply {
                rescheduleMatch = RescheduleSearch(matchAt(LocalDateTime.of(2027, 9, 9, 15, 0)))
            }
            val (result, items) = itemsFor("Project sync on 9 September 2027 at 11:00")
            val outcome = DesktopSaver(calendar, FakeTasks(), "Asia/Kolkata")
                .save(DesktopCapture("x"), result, items, defaults, "c")
            assertIs<SaveResult.RescheduleOffered>(outcome)
        }

    @Test
    fun `FR-803 settles it first, so a duplicate never presents as a reschedule`() = runTest {
        val calendar = FakeCalendar().apply {
            rescheduleMatch = RescheduleSearch(matchAt(LocalDateTime.of(2027, 9, 8, 11, 0)))
        }
        val text = "Project sync on 9 September 2027 at 11:00"
        val (result, items) = itemsFor(text)
        calendar.indexed[com.latch.wire.sourceHashOf(text)] = "ev-already"

        val outcome = DesktopSaver(calendar, FakeTasks(), "Asia/Kolkata")
            .save(DesktopCapture(text), result, items, defaults, "c")

        assertEquals(SaveResult.AlreadySaved, outcome)
        assertEquals(0, calendar.itemKeyQueries, "the key was consulted for a known duplicate")
    }

    @Test
    fun `Update patches the existing item and creates nothing`() = runTest {
        val calendar = FakeCalendar().apply {
            rescheduleMatch = RescheduleSearch(matchAt(LocalDateTime.of(2027, 9, 8, 11, 0)))
        }
        val (result, items) = itemsFor("Project sync on 9 September 2027 at 11:00")
        val saver = DesktopSaver(calendar, FakeTasks(), "Asia/Kolkata")
        val offer = saver.save(DesktopCapture("x"), result, items, defaults, "c") as SaveResult.RescheduleOffered

        val outcome = saver.applyReschedule(offer)

        assertIs<SaveResult.Updated>(outcome)
        assertTrue(calendar.events.isEmpty(), "Update created a second item")
        assertEquals(1, calendar.patched.size)
        assertEquals("ev-existing", calendar.patched.keys.single())
        // The dates the item held before the patch, which after it exist nowhere else.
        assertEquals(
            LocalDateTime.of(2027, 9, 8, 11, 0),
            (outcome.created.priorDates as ItemDates.Event).start,
        )
    }

    @Test
    fun `Create new writes a second item and patches nothing`() = runTest {
        val calendar = FakeCalendar().apply {
            rescheduleMatch = RescheduleSearch(matchAt(LocalDateTime.of(2027, 9, 8, 11, 0)))
        }
        val (result, items) = itemsFor("Project sync on 9 September 2027 at 11:00")
        val saver = DesktopSaver(calendar, FakeTasks(), "Asia/Kolkata")
        val offer = saver.save(DesktopCapture("x"), result, items, defaults, "c") as SaveResult.RescheduleOffered

        val outcome = saver.createAnyway(offer)

        assertIs<SaveResult.Written>(outcome)
        assertEquals(1, calendar.events.size)
        assertTrue(calendar.patched.isEmpty(), "Create new patched the existing item")
    }

    @Test
    fun `a capped task scan falls through to a create rather than reading as a reschedule`() =
        runTest {
            // SRS §5.8. `RescheduleSearch` with no match and a capped scan is "I could not
            // finish looking", and treating it as "not a reschedule" is the accepted reading.
            val tasks = FakeTasks().apply {
                rescheduleMatch = RescheduleSearch(match = null, scanCapped = true)
            }
            val (result, items) = itemsFor("Fees due 20 September 2027")
            val outcome = DesktopSaver(FakeCalendar(), tasks, "Asia/Kolkata")
                .save(DesktopCapture("x"), result, items, defaults, "c")
            assertIs<SaveResult.Written>(outcome)
        }
}

class RescheduleOfferTextTest {

    private fun event(start: LocalDateTime, allDay: Boolean = false) =
        ItemDates.Event(start, start.plusHours(1), allDay, "Asia/Kolkata")

    @Test
    fun `the offer quotes the stored title and computes both weekdays`() {
        // 8 Sep 2027 is a Wednesday and 9 Sep a Thursday. The weekdays are computed from the
        // dates and never taken from the captured text, which may name one that contradicts
        // the date beside it — §7.2 requires such a contradiction to be absorbed, not repeated
        // back at the user as though the app believed it.
        val text = rescheduleOfferText(
            "Project sync",
            event(LocalDateTime.of(2027, 9, 8, 11, 0)),
            event(LocalDateTime.of(2027, 9, 9, 11, 0)),
        )
        assertTrue(text.startsWith("This looks like a reschedule. \"Project sync\" is already saved for "), text)
        // The month's abbreviation is the platform's for this locale ("Sep" on some JVMs,
        // "Sept" on others), so what is asserted is the weekday, the day and the time — which
        // is what the sentence is for. Pinning the abbreviation would pin CLDR data.
        assertTrue(Regex("""Wed 8 Sep\w* 2027, 11:00""").containsMatchIn(text), text)
        assertTrue(Regex("""Thu 9 Sep\w* 2027, 11:00""").containsMatchIn(text), text)
        assertTrue(text.endsWith("?"), text)
    }

    @Test
    fun `an all-day event reads as a day and not a midnight`() {
        val text = rescheduleOfferText(
            "Trip",
            event(LocalDateTime.of(2027, 9, 20, 0, 0), allDay = true),
            event(LocalDateTime.of(2027, 9, 21, 0, 0), allDay = true),
        )
        assertFalse("00:00" in text, text)
        assertTrue(Regex("""Mon 20 Sep\w* 2027""").containsMatchIn(text), text)
    }

    @Test
    fun `a task with no due date reads as no date rather than a gap`() {
        val text = rescheduleOfferText(
            "Fees",
            ItemDates.Task(null),
            ItemDates.Task(LocalDate.of(2027, 9, 20)),
        )
        assertTrue("saved for no date" in text, text)
        assertTrue(Regex("""Mon 20 Sep\w* 2027""").containsMatchIn(text), text)
    }

    @Test
    fun `the title is quoted verbatim, including one that looks like markup`() {
        // Swing renders a label as HTML. A stored title carrying angle brackets must reach the
        // sentence unchanged here; escaping is the window's job and is done at render time.
        val text = rescheduleOfferText("<b>Review</b>", ItemDates.Task(null), ItemDates.Task(null))
        assertTrue("\"<b>Review</b>\"" in text, text)
    }
}

class UndoTest {

    private val directory: File = File.createTempFile("latch-undo", "").let {
        it.delete(); it.mkdirs(); it
    }

    @AfterTest
    fun cleanUp() = directory.deleteRecursively().let { }

    private fun queue() = WriteQueue(
        File(directory, "q.dat"),
        WindowsSecrets { _, payload -> BridgeReply("OK " + base64(unbase64(payload).reversedArray())) },
    )

    private val savedAt: Instant = Instant.parse("2026-09-03T12:00:00Z")

    // ---- the window ------------------------------------------------------------------------

    @Test
    fun `the window is ten seconds and counts down`() {
        assertEquals(10, undoSecondsLeft(savedAt, savedAt))
        assertEquals(7, undoSecondsLeft(savedAt, savedAt.plusSeconds(3)))
        assertEquals(1, undoSecondsLeft(savedAt, savedAt.plusMillis(9500)))
        assertEquals(0, undoSecondsLeft(savedAt, savedAt.plusSeconds(10)))
        assertEquals(0, undoSecondsLeft(savedAt, savedAt.plusSeconds(30)))
    }

    @Test
    fun `the offer is open until it is not`() {
        assertTrue(undoIsOpen(savedAt, savedAt.plusSeconds(9)))
        assertFalse(undoIsOpen(savedAt, savedAt.plusSeconds(10)))
    }

    @Test
    fun `the button says how long is left, because the offer expires`() {
        assertEquals("Undo (10)", undoLabel(10))
        assertEquals("Undo (1)", undoLabel(1))
    }

    // ---- the three operations ---------------------------------------------------------------

    @Test
    fun `an undo of a created item deletes it`() = runTest {
        val calendar = FakeCalendar()
        val tasks = FakeTasks()
        val created = listOf(
            CreatedItem.Written(ItemType.EVENT, "cal-1", "ev1"),
            CreatedItem.Written(ItemType.TASK, "list-1", "tk1"),
        )
        val outcome = undoCreated(created, calendar, tasks)
        assertEquals(RemovalOutcome(2, 2), outcome)
        assertEquals(listOf("ev1"), calendar.deleted)
        assertEquals(listOf("tk1"), tasks.deleted)
    }

    @Test
    fun `an undo of an update RESTORES and never deletes`() = runTest {
        // The one outcome FR-807 must not produce. The item was the user's before the save
        // touched it, so deleting it would destroy something they already had.
        val calendar = FakeCalendar()
        val prior = ItemDates.Event(
            LocalDateTime.of(2027, 9, 8, 11, 0),
            LocalDateTime.of(2027, 9, 8, 12, 0),
            allDay = false, timeZone = "Asia/Kolkata",
        )
        val outcome = undoCreated(
            listOf(CreatedItem.Updated(ItemType.EVENT, "cal-1", "ev-existing", prior)),
            calendar, FakeTasks(),
        )
        assertEquals(RemovalOutcome(1, 1), outcome)
        assertTrue(calendar.deleted.isEmpty(), "an update was undone by deleting the item")
        assertEquals(prior, calendar.patched["ev-existing"])
    }

    @Test
    fun `an undo of a queued capture drops the entry`() = runTest {
        val queue = queue()
        queue.add(com.latch.desktop.queue.anEntryForUndo("q1"))
        val outcome = undoCreated(listOf(CreatedItem.Queued("q1")), FakeCalendar(), FakeTasks(), queue)
        assertEquals(RemovalOutcome(1, 1), outcome)
        assertTrue(queue.entries().isEmpty())
    }

    @Test
    fun `an item already gone counts as removed`() = runTest {
        // The caller asked for it not to be in the account and it is not. Reporting a failure
        // would tell the user their item survived an undo when it did not, which is the more
        // damaging of the two possible lies.
        val calendar = FakeCalendar().apply { failDelete = GoogleRejected(404, "notFound", "gone") }
        val outcome = undoCreated(
            listOf(CreatedItem.Written(ItemType.EVENT, "cal-1", "ev1")), calendar, FakeTasks(),
        )
        assertEquals(RemovalOutcome(1, 1), outcome)
    }

    @Test
    fun `a partial undo reports how far it got, and does not stop at the first failure`() =
        runTest {
            // A chain half in the account is worse than one wholly there or wholly gone, so
            // every item still gets its turn — and NFR-303 requires the number, because the
            // recourse is to remove the rest by hand.
            val calendar = FakeCalendar().apply { failDelete = GoogleUnreachable("no network") }
            val tasks = FakeTasks()
            val outcome = undoCreated(
                listOf(
                    CreatedItem.Written(ItemType.EVENT, "cal-1", "ev1"),
                    CreatedItem.Written(ItemType.TASK, "list-1", "tk1"),
                    CreatedItem.Written(ItemType.EVENT, "cal-1", "ev2"),
                ),
                calendar, tasks,
            )
            assertEquals(RemovalOutcome(1, 3), outcome)
            assertFalse(outcome.complete)
            assertEquals(listOf("tk1"), tasks.deleted, "the loop stopped at the first failure")
        }

    @Test
    fun `what the user is told matches what happened`() {
        assertEquals("Put back where it was.", undoOutcomeText(RemovalOutcome(1, 1), wasUpdate = true))
        assertEquals("Removed.", undoOutcomeText(RemovalOutcome(1, 1), wasUpdate = false))
        assertEquals("Removed all 4.", undoOutcomeText(RemovalOutcome(4, 4), wasUpdate = false))
        assertTrue(undoOutcomeText(RemovalOutcome(2, 4), wasUpdate = false).contains("2 of 4"))
        assertTrue(undoOutcomeText(RemovalOutcome(0, 2), wasUpdate = false).contains("Could not undo"))
    }
}
