package com.latch.android.capture

import com.latch.data.AccountDefaults
import com.latch.data.AccountDefaultsStore
import com.latch.data.CalendarApi
import com.latch.data.DuplicateSearch
import com.latch.data.EventWrite
import com.latch.data.ItemDates
import com.latch.data.PendingWrite
import com.latch.data.QueueStatus
import com.latch.data.QueuedWrite
import com.latch.data.RescheduleSearch
import com.latch.data.TaskList
import com.latch.data.TaskWrite
import com.latch.data.TasksApi
import com.latch.data.WritableCalendar
import com.latch.data.WriteOperation
import com.latch.data.WriteQueue
import java.time.LocalDate

/**
 * The doubles the capture tests share.
 *
 * They live in one file rather than in each suite so that FR-803, FR-804 and FR-807 are all
 * asserted against the same recorded surface — a fake that counted patches one way for the
 * undo tests and another for the reschedule tests would let the two disagree about what the
 * saver did.
 */

/**
 * An in-memory [WriteQueue]. Deliberately not the encrypted store: that one needs a Context
 * and a real Android Keystore, and its record format is tested on its own in `:data`.
 */
internal class RecordingQueue : WriteQueue {
    val entries = linkedMapOf<String, PendingWrite>()
    val operations = linkedMapOf<String, WriteOperation>()
    var drainsRequested = 0
    private var next = 0

    override suspend fun enqueue(write: PendingWrite, operation: WriteOperation): String {
        val id = "queue-${next++}"
        entries[id] = write
        operations[id] = operation
        return id
    }

    override suspend fun pending(): List<QueuedWrite> = emptyList()

    override suspend fun status() = QueueStatus(waiting = entries.size, givenUp = 0)

    override suspend fun markWritten(queueId: String, remoteId: String) {
        entries.remove(queueId)
    }

    override suspend fun markFailed(queueId: String, error: String, permanent: Boolean) = Unit

    override suspend fun drop(queueId: String): Boolean = entries.remove(queueId) != null
}

internal class FixedDefaults(private val defaults: AccountDefaults) : AccountDefaultsStore {
    override suspend fun defaultsFor(accountId: String) = defaults
    override suspend fun allAccounts() = listOf(defaults)
    override suspend fun save(defaults: AccountDefaults) = Unit
    override suspend fun remove(accountId: String) = Unit
}

internal class RecordingCalendarApi(
    private val existingEventId: String? = null,
    private val failDelete: Boolean = false,
    private val failInsert: Exception? = null,
    private val rescheduleMatch: RescheduleSearch = RescheduleSearch(),
    private val failPatch: Boolean = false,
    /** Deletes succeed this many times, then refuse — for a partly-undone chain. */
    private val failDeleteAfter: Int? = null,
) : CalendarApi {
    val deleted = mutableListOf<Pair<String, String>>()
    val patched = mutableListOf<Triple<String, String, ItemDates.Event>>()

    /** Every event written, in order, so a chain's shared metadata can be asserted. */
    val written = mutableListOf<EventWrite>()
    val inserted: Int get() = written.size

    /** Every key this fake was asked about, so a test can assert exactly one query shape. */
    val itemKeysQueried = mutableListOf<String>()

    /** The same, for FR-803 — one message should mean one duplicate check. */
    val sourceHashesQueried = mutableListOf<String>()

    override suspend fun insertEvent(calendarId: String, event: EventWrite): String {
        failInsert?.let { throw it }
        written += event
        return "event-${written.size}"
    }

    override suspend fun findEventBySourceHash(calendarId: String, sourceHash: String): DuplicateSearch {
        sourceHashesQueried += sourceHash
        return DuplicateSearch(existingEventId)
    }

    override suspend fun deleteEvent(calendarId: String, eventId: String) {
        if (failDelete) throw IllegalStateException("events.delete refused")
        if (failDeleteAfter != null && deleted.size >= failDeleteAfter) {
            throw IllegalStateException("events.delete refused")
        }
        deleted += calendarId to eventId
    }

    override suspend fun findEventByItemKey(calendarId: String, itemKey: String): RescheduleSearch {
        itemKeysQueried += itemKey
        return rescheduleMatch
    }

    override suspend fun patchEventDates(calendarId: String, eventId: String, dates: ItemDates.Event) {
        if (failPatch) throw com.latch.data.GoogleUnreachable("no network", java.io.IOException())
        patched += Triple(calendarId, eventId, dates)
    }

    override suspend fun listWritableCalendars(): List<WritableCalendar> = emptyList()
    override suspend fun createLatchCalendar(summary: String, description: String) = "unused"
    override suspend fun setColourAndVisibility(calendarId: String, colorId: String, visible: Boolean): String? = null
    override suspend fun makeVisible(calendarId: String) = Unit
}

internal class RecordingTasksApi(
    private val failDelete: Boolean = false,
    private val rescheduleMatch: RescheduleSearch = RescheduleSearch(),
    private val failPatch: Boolean = false,
) : TasksApi {
    val deleted = mutableListOf<Pair<String, String>>()
    val patched = mutableListOf<Triple<String, String, ItemDates.Task>>()
    val inserted: Int get() = written.size

    val itemKeysQueried = mutableListOf<String>()

    val written = mutableListOf<TaskWrite>()
    val sourceHashesQueried = mutableListOf<String>()

    override suspend fun insertTask(taskListId: String, task: TaskWrite): String {
        written += task
        return "task-${written.size}"
    }

    override suspend fun findTaskBySourceHash(taskListId: String, sourceHash: String, due: LocalDate?): DuplicateSearch {
        sourceHashesQueried += sourceHash
        return DuplicateSearch(existingId = null)
    }

    override suspend fun deleteTask(taskListId: String, taskId: String) {
        if (failDelete) throw IllegalStateException("tasks.delete refused")
        deleted += taskListId to taskId
    }

    override suspend fun findTaskByItemKey(taskListId: String, itemKey: String): RescheduleSearch {
        itemKeysQueried += itemKey
        return rescheduleMatch
    }

    override suspend fun patchTaskDates(taskListId: String, taskId: String, dates: ItemDates.Task) {
        if (failPatch) throw com.latch.data.GoogleUnreachable("no network", java.io.IOException())
        patched += Triple(taskListId, taskId, dates)
    }

    override suspend fun listTaskLists(): List<TaskList> = emptyList()
}
