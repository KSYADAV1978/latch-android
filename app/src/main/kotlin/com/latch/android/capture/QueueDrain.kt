package com.latch.android.capture

import com.latch.core.model.ItemType
import com.latch.data.CalendarApi
import com.latch.data.DuplicateSearch
import com.latch.data.EventWrite
import com.latch.data.ItemDates
import com.latch.data.PendingWrite
import com.latch.data.QueuedWrite
import com.latch.data.TaskWrite
import com.latch.data.TasksApi
import com.latch.data.WriteOperation
import com.latch.data.WriteQueue
import java.time.LocalDate

/**
 * What one queue entry does when it drains (FR-806).
 *
 * **Extracted from `WriteQueueWorker` so that it can be tested at all.** It was a private
 * method of a `CoroutineWorker`, which needs a `Context`, so no JVM test could reach it —
 * and the consequence was not theoretical. FR-806's own note calls the FR-803 re-check below
 * "the last line for FR-803"; it had **no test of any kind** until this move, while the five
 * tests that did exist all covered `drainable`, the pure scheduling rule beside it. A
 * duplicate written by this path reached a user's calendar on 31 Aug 2026 and was found by
 * counting items in the account, which is the only instrument that was left.
 *
 * Nothing here changes what the drain does. The move is deliberately behaviour-preserving:
 * same order, same `requireNotNull` messages, same points at which the entry is retired.
 */

/**
 * The FR-803 query this entry will make before writing anything — as a value, so that a test
 * can inspect **which** query the drain would issue rather than only what it did afterwards.
 *
 * That is the whole reason this is a type. The candidates for the 31 Aug defect are all
 * inputs to a query whose code is shared with the saver and therefore already exonerated: the
 * `calendarId` carried on the entry, the scoping that id produces, the hash the entry carries.
 * A value that names them can be asserted on directly.
 */
sealed interface DuplicateProbe {
    val sourceHash: String

    data class Event(val calendarId: String, override val sourceHash: String) : DuplicateProbe
    data class Task(
        val taskListId: String,
        override val sourceHash: String,
        val due: LocalDate?,
    ) : DuplicateProbe
}

/**
 * FR-803, once for the whole chain and before any of it is written — the same rule the saver
 * follows, stated once for both sites in SRS §7.1 at v1.23.
 *
 * The entry holds every item of one capture and they share a `source_hash` by design, so a
 * check per item would find the first insert and abandon the rest of the chain. Which
 * transport is asked follows the **leading** item, which is a consequence worth seeing
 * plainly now that it has cost something: a chain leading with a task is protected by the
 * task scan even if the event query fails, and an event-only capture has nothing else to fall
 * back on. That asymmetry is why the 31 Aug duplicate was an event-only capture and why the
 * chain queued beside it retired correctly.
 */
fun duplicateProbeFor(write: PendingWrite): DuplicateProbe {
    val leader = write.items.first()
    return when (leader.type) {
        ItemType.EVENT -> DuplicateProbe.Event(
            calendarId = requireNotNull(leader.calendarId) { "A queued event has no calendar" },
            sourceHash = write.metadata.sourceHash,
        )

        ItemType.TASK -> DuplicateProbe.Task(
            taskListId = requireNotNull(leader.taskListId) { "A queued task has no list" },
            sourceHash = write.metadata.sourceHash,
            due = leader.dueDate,
        )
    }
}

/** Issues the probe against whichever transport it names. */
suspend fun runDuplicateProbe(
    probe: DuplicateProbe,
    calendarApi: CalendarApi,
    tasksApi: TasksApi,
): DuplicateSearch = when (probe) {
    is DuplicateProbe.Event ->
        calendarApi.findEventBySourceHash(probe.calendarId, probe.sourceHash)

    is DuplicateProbe.Task ->
        tasksApi.findTaskBySourceHash(probe.taskListId, probe.sourceHash, probe.due)
}

/**
 * Drains one entry: the FR-803 re-check, then the chain's inserts, then retirement.
 *
 * Throws on a failed write, which is the caller's signal to mark the entry failed and decide
 * whether it is worth retrying — the behaviour `WriteQueueWorker` had before this was moved.
 */
suspend fun drainEntry(
    entry: QueuedWrite,
    queue: WriteQueue,
    calendarApi: CalendarApi,
    tasksApi: TasksApi,
) {
    val write = entry.write

    // FR-804: an update moves an item that already exists, so there is nothing to check for a
    // duplicate of and nothing to insert. The FR-803 re-check below is deliberately not run
    // for one — it answers "has this message been saved", and the answer is yes, by the very
    // item this entry is about to move.
    if (entry.operation == WriteOperation.UPDATE) {
        applyQueuedUpdate(entry, queue, calendarApi, tasksApi)
        return
    }

    val alreadySaved = runDuplicateProbe(duplicateProbeFor(write), calendarApi, tasksApi)
    if (alreadySaved.found) {
        queue.markWritten(entry.id, alreadySaved.existingId.orEmpty())
        return
    }

    var lastRemoteId = ""
    for (item in write.items) {
        lastRemoteId = when (item.type) {
            ItemType.EVENT -> calendarApi.insertEvent(
                calendarId = requireNotNull(item.calendarId) { "A queued event has no calendar" },
                event = EventWrite(
                    summary = item.title,
                    description = write.body,
                    location = item.location,
                    start = requireNotNull(item.start),
                    end = requireNotNull(item.end),
                    allDay = item.allDay,
                    timeZone = write.timeZone,
                    metadata = write.metadata,
                ),
            )

            ItemType.TASK -> tasksApi.insertTask(
                taskListId = requireNotNull(item.taskListId) { "A queued task has no list" },
                task = TaskWrite(
                    title = item.title,
                    notes = write.body,
                    due = item.dueDate,
                    metadata = write.metadata,
                ),
            )
        }
    }

    // The entry is done when the whole chain is in the account. A failure part-way leaves it
    // queued and the drain retries it — FR-803's check at the head of the next attempt sees
    // the chain's first item and stops there, which is the one case where that check can
    // leave a chain short. Recorded rather than solved: solving it needs a per-item written
    // marker, which is the local index FR-701 will bring.
    queue.markWritten(entry.id, lastRemoteId)
}

/**
 * FR-804's update, drained.
 *
 * The target and the new dates both come off the entry, which is why SRS §7.1 had to be
 * corrected before this could exist: an entry that named only the item would wake with
 * nothing to say which remote item it meant. The prior state travels with it too, unused
 * here — it is what an FR-807 undo of this update would write back, and SRS 1.19 records
 * that a delayed drain makes those values that much older.
 */
private suspend fun applyQueuedUpdate(
    entry: QueuedWrite,
    queue: WriteQueue,
    calendarApi: CalendarApi,
    tasksApi: TasksApi,
) {
    val write = entry.write
    val item = write.item
    val target = requireNotNull(write.targetRemoteId) { "A queued update has no target" }

    when (item.type) {
        ItemType.EVENT -> calendarApi.patchEventDates(
            calendarId = requireNotNull(item.calendarId) { "A queued event has no calendar" },
            eventId = target,
            dates = ItemDates.Event(
                start = requireNotNull(item.start),
                end = requireNotNull(item.end),
                allDay = item.allDay,
                timeZone = write.timeZone,
            ),
        )

        ItemType.TASK -> tasksApi.patchTaskDates(
            taskListId = requireNotNull(item.taskListId) { "A queued task has no list" },
            taskId = target,
            dates = ItemDates.Task(item.dueDate),
        )
    }

    queue.markWritten(entry.id, target)
}
