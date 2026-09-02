package com.latch.android.capture

import com.latch.core.model.Item
import com.latch.core.model.ItemType
import com.latch.data.CreatedItem
import com.latch.data.ItemDates
import com.latch.data.PendingWrite
import com.latch.data.QueuedWrite
import com.latch.data.RemoteMetadata
import com.latch.data.WriteOperation
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * SRS 1.24, and FR-807's undo as one implementation.
 *
 * **The defect this covers is the one the SRS recorded and could not fix.** "A chain that fails
 * part-way through its inserts is left short on the next attempt. The entry stays queued, as it
 * must, but FR-803 at the head of the retry asks whether the message has been saved, finds the
 * items that did get written, and answers yes — so the drain retires the entry with the rest of
 * the chain never written. The rule that makes the common case correct is what makes this one
 * wrong, and no reordering fixes both."
 *
 * The cure named there is a per-item written marker, so a retry resumes rather than re-asking a
 * question the chain's own first item has already answered.
 */
class QueueResumeTest {

    private val calendarId = "latch-cal"
    private val chainHash = "c".repeat(64)

    private fun chainEntry(
        writtenItemIds: Set<String> = emptySet(),
        items: List<Item> = listOf(event("chain#0", 8), event("chain#1", 9), task("chain#2")),
    ) = QueuedWrite(
        id = "entry-chain",
        write = PendingWrite(
            items = items,
            metadata = RemoteMetadata(
                sourceHash = chainHash,
                itemKey = "d".repeat(64),
                chainId = "chain",
                capturedAt = Instant.parse("2026-09-02T08:00:00Z"),
            ),
            body = "the capture",
            timeZone = "Asia/Kolkata",
        ),
        operation = WriteOperation.CREATE,
        attempts = 1,
        lastError = "no network",
        queuedAt = Instant.parse("2026-09-02T08:00:00Z"),
        writtenItemIds = writtenItemIds,
    )

    private fun event(id: String, day: Int) = Item(
        id = id,
        captureId = "capture",
        chainId = "chain",
        type = ItemType.EVENT,
        title = "Row $id",
        start = LocalDateTime.parse("2027-09-0${day}T09:00"),
        end = LocalDateTime.parse("2027-09-0${day}T10:00"),
        calendarId = calendarId,
    )

    private fun task(id: String) = Item(
        id = id,
        captureId = "capture",
        chainId = "chain",
        type = ItemType.TASK,
        title = "Row $id",
        dueDate = LocalDate.parse("2027-09-20"),
        taskListId = "list-1",
    )

    @Test
    fun `a fresh chain asks FR-803 once and writes every item`() = runTest {
        val queue = RecordingQueue()
        val calendar = RecordingCalendarApi()
        val tasks = RecordingTasksApi()
        val entry = chainEntry()
        queue.queued += entry

        drainEntry(entry, queue, calendar, tasks)

        assertEquals(listOf(chainHash), calendar.sourceHashesQueried, "FR-803 runs once per capture")
        assertEquals(2, calendar.inserted)
        assertEquals(1, tasks.inserted)
        assertEquals(
            listOf("chain#0", "chain#1", "chain#2"),
            queue.itemsWritten.map { it.second },
        )
        assertEquals(listOf("entry-chain"), queue.written.map { it.first })
    }

    @Test
    fun `a chain that failed part-way resumes and does not re-ask FR-803`() = runTest {
        // Name the condition that would make this fail: the duplicate query running, finding
        // the chain's own first item under the shared source_hash, and retiring the entry with
        // items 1 and 2 never written. That is the 1.24 defect exactly.
        val queue = RecordingQueue()
        val calendar = RecordingCalendarApi()
        val tasks = RecordingTasksApi()
        // The fixture has to be able to produce that failure, so the index is seeded with the
        // item the first attempt did write — a fake that answered "not found" regardless could
        // not tell a fixed drain from a broken one.
        calendar.seed(calendarId, chainHash, "event-already-there")
        val entry = chainEntry(writtenItemIds = setOf("chain#0"))
        queue.queued += entry

        drainEntry(entry, queue, calendar, tasks)

        assertTrue(calendar.sourceHashesQueried.isEmpty(), "a resumed entry must not re-ask")
        assertEquals(1, calendar.inserted, "only the unwritten event should be inserted")
        assertEquals(1, tasks.inserted)
        assertEquals(listOf("chain#1", "chain#2"), queue.itemsWritten.map { it.second })
        assertEquals(listOf("entry-chain"), queue.written.map { it.first })
    }

    @Test
    fun `the same entry drained twice without markers loses the rest of the chain`() = runTest {
        // The behaviour being replaced, asserted so the cure is visibly a cure rather than a
        // refactor. Without markers, a chain whose first item is in the account retires with
        // nothing further written.
        val queue = RecordingQueue()
        val calendar = RecordingCalendarApi()
        val tasks = RecordingTasksApi()
        calendar.seed(calendarId, chainHash, "event-already-there")
        val entry = chainEntry(writtenItemIds = emptySet())
        queue.queued += entry

        drainEntry(entry, queue, calendar, tasks)

        assertEquals(0, calendar.inserted)
        assertEquals(0, tasks.inserted)
        assertEquals(listOf("entry-chain" to "event-already-there"), queue.written)
    }

    @Test
    fun `an entry whose every item is marked is simply retired`() = runTest {
        // A crash between the last marker and the retirement. There is nothing left to write,
        // and re-inserting the chain would be the duplicate the markers exist to avoid.
        val queue = RecordingQueue()
        val calendar = RecordingCalendarApi()
        val tasks = RecordingTasksApi()
        val entry = chainEntry(writtenItemIds = setOf("chain#0", "chain#1", "chain#2"))
        queue.queued += entry

        drainEntry(entry, queue, calendar, tasks)

        assertEquals(0, calendar.inserted)
        assertEquals(0, tasks.inserted)
        assertTrue(calendar.sourceHashesQueried.isEmpty())
        assertEquals(listOf("entry-chain"), queue.written.map { it.first })
    }

    @Test
    fun `a marker is written after each insert, not after the last`() = runTest {
        // The whole value of the marker is that it survives a process death *between* two
        // inserts. Written once at the end it would record nothing the retry could use.
        val queue = RecordingQueue()
        val calendar = RecordingCalendarApi(failInsert = null)
        val tasks = RecordingTasksApi()
        val entry = chainEntry(items = listOf(event("chain#0", 8), event("chain#1", 9)))
        queue.queued += entry

        drainEntry(entry, queue, calendar, tasks)

        assertEquals(2, queue.itemsWritten.size)
        assertEquals("event-1", queue.itemsWritten[0].third)
        assertEquals("event-2", queue.itemsWritten[1].third)
    }
}

/**
 * FR-807's removal, as the one implementation both the capture sheet and the home screen call.
 *
 * Extracted from `CaptureSaver` when the offer became something that outlives its process:
 * two copies of a destructive operation would eventually differ, and the one that differed
 * would be the one nobody watched.
 */
class UndoRemovalTest {

    @Test
    fun `a chain of events and tasks is removed, and the index forgets each`() = runTest {
        val calendar = RecordingCalendarApi()
        val tasks = RecordingTasksApi()
        val queue = RecordingQueue()
        val index = RecordingIndex()

        val outcome = removeCreated(
            listOf(
                CreatedItem.Written(ItemType.EVENT, "latch-cal", "event-1"),
                CreatedItem.Written(ItemType.TASK, "list-1", "task-1"),
            ),
            calendar, tasks, queue, index,
        )

        assertTrue(outcome.complete)
        assertEquals(listOf("latch-cal" to "event-1"), calendar.deleted)
        assertEquals(listOf("list-1" to "task-1"), tasks.deleted)
        assertEquals(
            listOf("latch-cal" to "event-1", "list-1" to "task-1"),
            index.forgotten,
        )
    }

    @Test
    fun `undoing an update restores its prior dates and never deletes`() = runTest {
        // SRS 1.16 corrected FR-807's wording for exactly this: the item existed before the
        // save touched it, and deleting it would destroy something the user already had.
        val calendar = RecordingCalendarApi()
        val tasks = RecordingTasksApi()
        val prior = ItemDates.Event(
            start = LocalDateTime.parse("2030-03-05T21:30"),
            end = LocalDateTime.parse("2030-03-05T22:30"),
        )

        val outcome = removeCreated(
            listOf(CreatedItem.Updated(ItemType.EVENT, "latch-cal", "event-1", prior)),
            calendar, tasks, RecordingQueue(), RecordingIndex(),
        )

        assertTrue(outcome.complete)
        assertTrue(calendar.deleted.isEmpty(), "an update must never be undone by a delete")
        assertEquals(listOf(Triple("latch-cal", "event-1", prior)), calendar.patched)
    }

    @Test
    fun `a restored item stays in the index, because it is still in the account`() = runTest {
        val index = RecordingIndex()
        removeCreated(
            listOf(
                CreatedItem.Updated(
                    ItemType.TASK, "list-1", "task-1", ItemDates.Task(LocalDate.parse("2027-09-20")),
                )
            ),
            RecordingCalendarApi(), RecordingTasksApi(), RecordingQueue(), index,
        )
        assertTrue(index.forgotten.isEmpty())
    }

    @Test
    fun `the loop does not stop at the first failure`() = runTest {
        // A chain half in the account is worse than one wholly there or wholly gone, so every
        // item still gets its turn — and the count is what NFR-303's message reports.
        val calendar = RecordingCalendarApi(failDeleteAfter = 1)
        val outcome = removeCreated(
            listOf(
                CreatedItem.Written(ItemType.EVENT, "latch-cal", "event-1"),
                CreatedItem.Written(ItemType.EVENT, "latch-cal", "event-2"),
                CreatedItem.Written(ItemType.EVENT, "latch-cal", "event-3"),
            ),
            calendar, RecordingTasksApi(), RecordingQueue(), RecordingIndex(),
        )

        assertFalse(outcome.complete)
        assertEquals(1, outcome.removed)
        assertEquals(3, outcome.total)
    }

    @Test
    fun `a queued item is undone by dropping the entry, with nothing sent to Google`() = runTest {
        val queue = RecordingQueue()
        val id = queue.enqueue(
            PendingWrite(
                items = listOf(
                    Item(
                        id = "i", captureId = "c", type = ItemType.TASK, title = "t",
                        dueDate = LocalDate.parse("2027-09-20"), taskListId = "list-1",
                    )
                ),
                metadata = RemoteMetadata("a".repeat(64), "b".repeat(64), "chain", Instant.EPOCH),
                body = "",
                timeZone = "Asia/Kolkata",
            )
        )
        val calendar = RecordingCalendarApi()
        val tasks = RecordingTasksApi()

        val outcome = removeCreated(
            listOf(CreatedItem.Queued(id)), calendar, tasks, queue, RecordingIndex(),
        )

        assertTrue(outcome.complete)
        assertTrue(queue.entries.isEmpty())
        assertTrue(calendar.deleted.isEmpty())
        assertTrue(tasks.deleted.isEmpty())
    }

    @Test
    fun `a queue entry already drained counts as a failure, not a quiet success`() = runTest {
        // `drainable` exists to prevent it; if it happens anyway the item *is* in the account
        // and this undo did not remove it, so saying it succeeded would be the more damaging
        // of the two possible lies.
        val outcome = removeCreated(
            listOf(CreatedItem.Queued("gone")),
            RecordingCalendarApi(), RecordingTasksApi(), RecordingQueue(), RecordingIndex(),
        )
        assertFalse(outcome.complete)
        assertEquals(0, outcome.removed)
    }
}
