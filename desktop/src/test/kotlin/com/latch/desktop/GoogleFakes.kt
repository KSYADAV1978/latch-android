package com.latch.desktop

import com.latch.google.CalendarApi
import com.latch.google.DuplicateSearch
import com.latch.google.EventWrite
import com.latch.google.GoogleRejected
import com.latch.google.ItemDates
import com.latch.google.RescheduleSearch
import com.latch.google.TaskList
import com.latch.google.TaskWrite
import com.latch.google.TasksApi
import com.latch.google.WritableCalendar
import java.time.LocalDate

/*
 * The doubles both suites share.
 *
 * One file rather than one per suite, so the save path and the drain are asserted against the
 * same recorded surface. `:app` keeps `CaptureFakes.kt` for the same reason: a fake that
 * counted inserts one way for the saver and another for the drain would let the two disagree
 * about what actually reached Google, which is the thing these tests exist to pin.
 */

/**
 * A calendar that answers by the hash it was actually given.
 *
 * `CLAUDE.md` records why that sentence is in this file: the Android fake's
 * `findEventBySourceHash` ignored its argument and returned a preset id, so it agreed with a
 * broken implementation for as long as the implementation was broken. A fake that answers by
 * fixture rather than by matching cannot catch a matching bug.
 */
internal class FakeCalendar : CalendarApi {
    val events = mutableMapOf<String, EventWrite>()
    val indexed = mutableMapOf<String, String>()
    var created: String? = null
    var calendars: List<WritableCalendar> = emptyList()
    var failInsert: Exception? = null

    /**
     * Inserts succeed this many times, then throw [failInsert] — a chain that stops part-way
     * (SRS 1.191). Distinct from [failInsert] alone, which refuses from the first item.
     */
    var failInsertAfter: Int? = null

    /**
     * SRS 1.195's race fixture: hold the insert open so two drains are genuinely inside the
     * write together, and count with an atomic rather than by map size — two threads racing a
     * plain map is the very condition under test.
     */
    var insertDelayMillis: Long = 0
    val insertCount = java.util.concurrent.atomic.AtomicInteger(0)
    var failList: Exception? = null
    var failFind: Exception? = null
    var failDelete: Exception? = null
    var rescheduleMatch: RescheduleSearch = RescheduleSearch()
    var itemKeyQueries: Int = 0
    val patched = mutableMapOf<String, ItemDates>()
    val deleted = mutableListOf<String>()

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
        if (insertDelayMillis > 0) Thread.sleep(insertDelayMillis)
        val refuses =
            if (failInsertAfter == null) failInsert != null else events.size >= failInsertAfter!!
        if (refuses) throw (failInsert ?: GoogleRejected(400, "invalid", "events.insert refused"))
        return synchronized(this) {
            val id = "ev" + (events.size + 1)
            events[id] = event
            indexed[event.metadata.sourceHash] = id
            insertCount.incrementAndGet()
            id
        }
    }

    override suspend fun findEventBySourceHash(calendarId: String, sourceHash: String): DuplicateSearch {
        failFind?.let { throw it }
        return DuplicateSearch(existingId = indexed[sourceHash])
    }

    override suspend fun findEventByItemKey(calendarId: String, itemKey: String): RescheduleSearch {
        itemKeyQueries++
        return rescheduleMatch
    }

    /** FR-804's move note is recorded, not swallowed: a fake that ignored it could not fail. */
    val patchedBodies = mutableMapOf<String, String?>()

    override suspend fun patchEventDates(
        calendarId: String,
        eventId: String,
        dates: ItemDates.Event,
        description: String?,
    ) {
        patched[eventId] = dates
        patchedBodies[eventId] = description
    }

    override suspend fun deleteEvent(calendarId: String, eventId: String) {
        failDelete?.let { throw it }
        deleted += eventId
    }
}

internal class FakeTasks : TasksApi {
    val tasks = mutableMapOf<String, TaskWrite>()
    val indexed = mutableMapOf<String, String>()
    var lists: List<TaskList> = listOf(TaskList("list-1", "My Tasks", isDefault = true))
    var failInsert: Exception? = null
    var cappedScan: Boolean = false
    var lastScanWasCapped: Boolean = false
    var failDelete: Exception? = null
    var rescheduleMatch: RescheduleSearch = RescheduleSearch()
    var itemKeyQueries: Int = 0
    val patched = mutableMapOf<String, ItemDates>()
    val deleted = mutableListOf<String>()

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
    ): DuplicateSearch {
        lastScanWasCapped = cappedScan
        return DuplicateSearch(existingId = indexed[sourceHash], scanCapped = cappedScan)
    }

    override suspend fun findTaskByItemKey(taskListId: String, itemKey: String): RescheduleSearch {
        itemKeyQueries++
        return rescheduleMatch
    }

    val patchedBodies = mutableMapOf<String, String?>()

    override suspend fun patchTaskDates(
        taskListId: String,
        taskId: String,
        dates: ItemDates.Task,
        notes: String?,
    ) {
        patched[taskId] = dates
        patchedBodies[taskId] = notes
    }

    override suspend fun deleteTask(taskListId: String, taskId: String) {
        failDelete?.let { throw it }
        deleted += taskId
    }
}

