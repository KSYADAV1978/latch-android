package com.latch.android.capture

import com.latch.core.model.Item
import com.latch.core.model.ItemType
import com.latch.data.PendingWrite
import com.latch.data.QueuedWrite
import com.latch.wire.RemoteMetadata
import com.latch.data.WriteOperation
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * FR-803's re-check at drain — the check FR-806's note calls "the last line for FR-803", and
 * which had no test of any kind until a duplicate it should have stopped reached a real
 * calendar on 31 Aug 2026.
 *
 * It had none because it could not have one: it was a private method of a `CoroutineWorker`,
 * which needs a `Context`. The tests that did exist covered `drainable`, the pure scheduling
 * rule beside it, which is exactly the part that was reachable. That is the lesson worth more
 * than the bug — reachability decided what was tested, and the untestable half was the half
 * standing between an offline capture and a duplicate.
 */
class QueueDrainDuplicateTest {

    private val latchCalendar = "latch-cal@group.calendar.google.com"
    private val kickoffHash = "b3375f4a20bb6c1d5cea22c95c59d1f7feb896d40c836671910d9b972543db6a"

    /**
     * The 31 Aug case as it actually happened: an event-only capture, queued while offline,
     * whose message was already in the account under the same `source_hash`.
     */
    private fun kickoffEntry(queuedAt: Instant = Instant.parse("2026-08-31T12:40:33Z")) = QueuedWrite(
        id = "entry-kickoff",
        write = PendingWrite(
            items = listOf(
                Item(
                    id = "kickoff#0",
                    captureId = "cap-kickoff",
                    type = ItemType.EVENT,
                    title = "Kickoff 8 September 2027 at 9am",
                    start = LocalDateTime.parse("2027-09-08T09:00"),
                    end = LocalDateTime.parse("2027-09-08T10:00"),
                    calendarId = latchCalendar,
                ),
            ),
            metadata = RemoteMetadata(
                sourceHash = kickoffHash,
                itemKey = "f5d2496ef32f7a10125274100fc078fad578498fa7ea0580f06a9e8e3d384d34",
                chainId = "389f4fce-1751-4da4-bca5-13718dc679c1",
                capturedAt = queuedAt,
            ),
            body = "Kickoff 8 September 2027 at 9am",
            timeZone = "Asia/Kolkata",
        ),
        operation = WriteOperation.CREATE,
        attempts = 1,
        lastError = null,
        givenUp = false,
        queuedAt = queuedAt,
    )

    @Test
    fun `an event-only entry whose message is already saved retires without writing`() = runTest {
        val calendar = RecordingCalendarApi()
        // As though the earlier save had written it: same calendar, same source hash.
        calendar.seed(latchCalendar, kickoffHash, "event-already-there")
        val tasks = RecordingTasksApi()
        val queue = RecordingQueue()

        drainEntry(kickoffEntry(), queue, calendar, tasks)

        assertEquals(0, calendar.inserted, "the drain wrote a duplicate of a message already saved")
        assertEquals(listOf("entry-kickoff"), queue.written.map { it.first })
    }

    @Test
    fun `the probe queries the calendar the entry carries, with the hash the entry carries`() {
        // The three candidates for the 31 Aug defect, as a value that can be asserted on:
        // the query's calendar scoping and the hash, both taken off the queued entry.
        val probe = duplicateProbeFor(kickoffEntry().write)

        assertTrue(probe is DuplicateProbe.Event, "an event-only entry must probe the calendar")
        assertEquals(latchCalendar, probe.calendarId)
        assertEquals(kickoffHash, probe.sourceHash)
    }

    @Test
    fun `the hash written is the hash queried`() = runTest {
        // The mismatch this fake exists to expose: whatever insertEvent puts on the item is
        // what findEventBySourceHash must be able to find it by. A drain of an unsaved entry
        // followed by a drain of the same entry must find itself the second time.
        val calendar = RecordingCalendarApi()
        val tasks = RecordingTasksApi()
        val queue = RecordingQueue()

        drainEntry(kickoffEntry(), queue, calendar, tasks)
        assertEquals(1, calendar.inserted, "the first drain should write, nothing being saved yet")

        drainEntry(kickoffEntry(), queue, calendar, tasks)
        assertEquals(1, calendar.inserted, "the second drain wrote again: hash written != hash queried")
    }

    @Test
    fun `a chain leading with a task is protected by the task scan`() = runTest {
        // The asymmetry that made the defect visible on one entry and not the other: the
        // probe follows the leading item, so a chain leading with a task never asks the
        // calendar at all. Recorded as behaviour so the coupling is deliberate.
        val chain = kickoffEntry().let { base ->
            base.copy(
                id = "entry-chain",
                write = base.write.copy(
                    items = listOf(
                        Item(
                            id = "chain#0",
                            captureId = "cap-chain",
                            type = ItemType.TASK,
                            title = "Sharma Ji",
                            dueDate = LocalDate.parse("2026-09-14"),
                            taskListId = "list-1",
                        ),
                        base.write.items.first(),
                    ),
                ),
            )
        }

        val probe = duplicateProbeFor(chain.write)

        assertTrue(probe is DuplicateProbe.Task, "a chain leading with a task probes the task list")
        assertEquals(kickoffHash, probe.sourceHash, "both transports ask about the same message")
    }
}
