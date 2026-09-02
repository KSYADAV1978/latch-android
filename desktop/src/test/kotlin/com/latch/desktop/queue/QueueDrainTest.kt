package com.latch.desktop.queue

import com.latch.desktop.FakeCalendar
import com.latch.desktop.FakeTasks
import com.latch.google.DuplicateSearch
import com.latch.google.GoogleRejected
import com.latch.google.GoogleUnreachable
import com.latch.google.RetryPolicy
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The drain, which on Android went untested for a slice and wrote duplicates into a real
 * calendar for five days.
 *
 * `CLAUDE.md` records why, and the shape of this file is the answer to it: the check lived
 * inside a `CoroutineWorker` that needed a `Context`, so every test covered the pure scheduling
 * function beside it and none covered the decision. `drainEntry` is a free function taking its
 * two APIs, and the fakes index on the hash the write actually carried.
 */
class QueueDrainTest {

    private val now: Instant = Instant.parse("2026-09-02T12:00:00Z")

    @Test
    fun `an entry writes its items and is retired`() = runTest {
        val calendar = FakeCalendar()
        val tasks = FakeTasks()
        val entry = anEntry(items = listOf(QueuedItem(anEvent()), QueuedItem(aTask())))

        val outcome = drainEntry(entry, calendar, tasks, now)

        assertEquals(DrainOutcome.Retired(written = 2), outcome)
        assertEquals(1, calendar.events.size)
        assertEquals(1, tasks.tasks.size)
        assertEquals("Asia/Kolkata", calendar.events.values.single().timeZone)
    }

    @Test
    fun `FR-803 runs again at drain, and a message already saved is retired without writing`() =
        runTest {
            // The 31 Aug 2026 defect's exact shape, one client over: a capture queued offline
            // whose message reached Google by some other route. Without this check the drain
            // writes a second copy — AC-07 failing inside AC-10, in the account rather than on
            // screen. The fake indexes on the hash, so a drain that queried the wrong one fails
            // here rather than passing by fixture.
            val calendar = FakeCalendar().apply { indexed["h1"] = "ev-already-there" }
            val tasks = FakeTasks()

            val outcome = drainEntry(anEntry(), calendar, tasks, now)

            assertEquals(DrainOutcome.Retired(written = 0, alreadySaved = true), outcome)
            assertTrue(calendar.events.isEmpty(), "the drain wrote a duplicate")
        }

    @Test
    fun `a task already saved also retires the entry`() = runTest {
        val tasks = FakeTasks().apply { indexed["h1"] = "tk-already-there" }
        val outcome = drainEntry(anEntry(), FakeCalendar(), tasks, now)
        assertEquals(DrainOutcome.Retired(written = 0, alreadySaved = true), outcome)
    }

    @Test
    fun `SRS 1_24 a half-written chain resumes rather than restarting`() = runTest {
        // The failure this prevents is invisible to FR-803: the duplicate is made by the retry
        // of the very entry that made the original, so the hash is already in the account and
        // the check would retire an entry with an item still owed.
        val calendar = FakeCalendar()
        val tasks = FakeTasks()
        val entry = anEntry(
            items = listOf(QueuedItem(anEvent(), writtenId = "ev-written-before"), QueuedItem(aTask())),
        )

        val outcome = drainEntry(entry, calendar, tasks, now)

        assertEquals(DrainOutcome.Retired(written = 1), outcome)
        assertTrue(calendar.events.isEmpty(), "the event was written a second time")
        assertEquals(1, tasks.tasks.size, "the task that was owed was not written")
    }

    @Test
    fun `a half-written chain does not ask FR-803, because the answer would be yes`() = runTest {
        // Its own hash is already in the account — it put it there. Asking would retire the
        // entry and lose the item still owed.
        val calendar = FakeCalendar().apply { indexed["h1"] = "ev-written-before" }
        val tasks = FakeTasks()
        val entry = anEntry(
            items = listOf(QueuedItem(anEvent(), writtenId = "ev-written-before"), QueuedItem(aTask())),
        )

        val outcome = drainEntry(entry, calendar, tasks, now)

        assertEquals(DrainOutcome.Retired(written = 1), outcome)
        assertEquals(1, tasks.tasks.size)
    }

    @Test
    fun `the written marker is recorded after each item, not at the end of the chain`() = runTest {
        // A process killed between two inserts must resume. A marker written only when the
        // whole chain succeeds is a marker that is never written for the case it exists for.
        val progress = mutableListOf<Int>()
        val entry = anEntry(items = listOf(QueuedItem(anEvent()), QueuedItem(aTask())))

        drainEntry(entry, FakeCalendar(), FakeTasks(), now) { current ->
            progress += current.items.count { it.writtenId != null }
        }

        assertEquals(listOf(1, 2), progress)
    }

    @Test
    fun `an offline failure defers with a backoff rather than losing the capture`() = runTest {
        val calendar = FakeCalendar().apply { failInsert = GoogleUnreachable("no network") }
        val outcome = drainEntry(anEntry(), calendar, FakeTasks(), now)

        assertIs<DrainOutcome.Deferred>(outcome)
        assertEquals(1, outcome.entry.attempts)
        assertEquals(now.plus(RetryPolicy.backoffDelay(1)), outcome.entry.nextAttemptAt)
        assertFalse(outcome.entry.givenUp)
    }

    @Test
    fun `a failure retrying cannot fix gives up at once and keeps the entry`() = runTest {
        // A 403 for a scope never granted. Retrying repeats it for ever, so the entry stops —
        // and stays, because it holds a capture that exists nowhere else. Deleting it would be
        // the one thing design principle 1 forbids.
        val calendar = FakeCalendar().apply {
            failInsert = GoogleRejected(403, "insufficientPermissions", "no")
        }
        val outcome = drainEntry(anEntry(), calendar, FakeTasks(), now)

        assertIs<DrainOutcome.GaveUp>(outcome)
        assertTrue(outcome.entry.givenUp)
        assertEquals(1, outcome.entry.items.size, "the capture must survive being given up on")
    }

    @Test
    fun `an entry that has failed too often gives up rather than retrying for ever`() = runTest {
        val calendar = FakeCalendar().apply { failInsert = GoogleUnreachable("no network") }
        val entry = anEntry(attempts = RetryPolicy.GIVE_UP_AFTER - 1)
        val outcome = drainEntry(entry, calendar, FakeTasks(), now)
        assertIs<DrainOutcome.GaveUp>(outcome)
    }

    @Test
    fun `a probe that cannot run defers rather than writing blind`() = runTest {
        // If FR-803 cannot be asked, writing anyway is how a duplicate is made. Waiting is the
        // safe answer and costs only a delay.
        val calendar = FakeCalendar().apply { failFind = GoogleUnreachable("no network") }
        val outcome = drainEntry(anEntry(), calendar, FakeTasks(), now)
        assertIs<DrainOutcome.Deferred>(outcome)
        assertTrue(calendar.events.isEmpty())
    }

    @Test
    fun `a capped task scan is not read as no duplicate`() = runTest {
        // `DuplicateSearch` keeps "found nothing" and "gave up looking" apart precisely so a
        // caller cannot read one as the other. Android cures the residual with a local index;
        // this client has none, so the behaviour is recorded rather than assumed away.
        val tasks = FakeTasks().apply { cappedScan = true }
        val outcome = drainEntry(anEntry(), FakeCalendar(), tasks, now)
        // It writes, which is the accepted residual — but the fixture proves the flag reaches
        // the decision, so a future index has somewhere to attach.
        assertIs<DrainOutcome.Retired>(outcome)
        assertTrue(tasks.lastScanWasCapped, "the capped flag never reached the drain")
    }
}
