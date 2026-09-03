package com.latch.android.capture

import com.latch.data.AccountDefaults
import com.latch.data.AccountDefaultsStore
import com.latch.google.CalendarApi
import com.latch.data.CaptureInbox
import com.latch.google.DuplicateSearch
import com.latch.google.EventWrite
import com.latch.google.FailureClass
import com.latch.core.model.InboxCapture
import com.latch.core.model.InboxStatus
import com.latch.google.ItemDates
import com.latch.data.LocalItemIndex
import com.latch.data.PendingWrite
import com.latch.data.QueueStatus
import com.latch.data.QueuedWrite
import com.latch.google.RescheduleSearch
import com.latch.data.StoredUndoOffer
import com.latch.google.TaskList
import com.latch.google.TaskWrite
import com.latch.google.TasksApi
import com.latch.data.UndoOfferStore
import com.latch.google.WritableCalendar
import com.latch.data.WriteOperation
import com.latch.data.WriteQueue
import com.latch.data.WrittenItem
import com.latch.google.GoogleUnreachable
import java.time.Instant
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

    /** Entries a test seeded, so the drain and the resume can be exercised. */
    val queued = mutableListOf<QueuedWrite>()

    override suspend fun pending(): List<QueuedWrite> = queued

    override suspend fun status() = QueueStatus(waiting = entries.size, givenUp = 0)

    /** Which entries were retired, and against which remote id. */
    val written = mutableListOf<Pair<String, String>>()

    override suspend fun markWritten(queueId: String, remoteId: String) {
        written += queueId to remoteId
        entries.remove(queueId)
    }

    /** FR-806a: the reason is recorded, so a test can assert the queue can say "Sign in needed". */
    val signInNeeded = mutableSetOf<String>()

    /** SRS 1.24: which items were marked written, in order, per entry. */
    val itemsWritten = mutableListOf<Triple<String, String, String>>()

    override suspend fun markItemWritten(queueId: String, itemId: String, remoteId: String) {
        itemsWritten += Triple(queueId, itemId, remoteId)
        val index = queued.indexOfFirst { it.id == queueId }
        if (index >= 0) {
            queued[index] = queued[index].let {
                it.copy(writtenItemIds = it.writtenItemIds + itemId)
            }
        }
    }

    /** FR-806's failure classes, so the immediate-drain policy can be asserted against them. */
    val failures = mutableListOf<Triple<String, Boolean, FailureClass>>()

    override suspend fun markFailed(
        queueId: String,
        error: String,
        permanent: Boolean,
        needsSignIn: Boolean,
        failureClass: FailureClass,
    ) {
        if (needsSignIn) signInNeeded += queueId
        failures += Triple(queueId, permanent, failureClass)
    }

    var revived = 0

    override suspend fun reviveGivenUp(): Int {
        revived = queued.count { it.givenUp }
        queued.replaceAll { if (it.givenUp) it.copy(givenUp = false, attempts = 0) else it }
        return revived
    }

    override suspend fun drop(queueId: String): Boolean = entries.remove(queueId) != null
}

/**
 * An in-memory [CaptureInbox]. FR-701's SQLite store needs a device; its record format is
 * tested on its own in `:data`, which is the same split every store in this project keeps.
 */
internal class RecordingInbox : CaptureInbox {
    val rows = linkedMapOf<String, InboxCapture>()
    val discarded = mutableListOf<String>()

    override suspend fun add(capture: InboxCapture) {
        rows[capture.id] = capture
    }

    override suspend fun all(): List<InboxCapture> = rows.values.sortedBy { it.capturedAt }

    override suspend fun due(now: Instant): List<InboxCapture> = all().filter { it.isDue(now) }

    override suspend fun pendingCount(now: Instant): Int = status(now).due

    /**
     * [unreadable] is settable because the count is the *point* of the field: a fake that
     * always answered zero could not show that FR-704's number still reaches zero while rows
     * this build cannot decode are kept.
     */
    var unreadable: Int = 0

    override suspend fun status(now: Instant): InboxStatus = InboxStatus(
        due = all().count { it.isDue(now) },
        snoozed = all().count { !it.isDue(now) },
        unreadable = unreadable,
    )

    override suspend fun find(id: String): InboxCapture? = rows[id]

    override suspend fun update(capture: InboxCapture) {
        rows[capture.id] = capture
    }

    override suspend fun discard(id: String) {
        discarded += id
        rows.remove(id)
    }

    override suspend fun deleteAll() = rows.clear()
}

/** An in-memory [LocalItemIndex], keyed the way the real one is indexed. */
internal class RecordingIndex : LocalItemIndex {
    val items = mutableListOf<WrittenItem>()
    val forgotten = mutableListOf<Pair<String, String>>()

    override suspend fun remember(item: WrittenItem) {
        items.removeAll { it.containerId == item.containerId && it.remoteId == item.remoteId }
        items += item
    }

    override suspend fun bySourceHash(sourceHash: String): WrittenItem? =
        items.lastOrNull { it.sourceHash == sourceHash }

    override suspend fun byItemKey(itemKey: String): List<WrittenItem> =
        items.filter { it.itemKey == itemKey }.sortedByDescending { it.writtenAt }

    override suspend fun forget(containerId: String, remoteId: String) {
        forgotten += containerId to remoteId
        items.removeAll { it.containerId == containerId && it.remoteId == remoteId }
    }

    override suspend fun clear() = items.clear()
}

/** An in-memory [UndoOfferStore], so FR-807's persistence can be asserted without a device. */
internal class RecordingUndoOffers : UndoOfferStore {
    val offers = linkedMapOf<String, StoredUndoOffer>()
    val forgotten = mutableListOf<String>()

    override suspend fun remember(offer: StoredUndoOffer) {
        offers[offer.chainId] = offer
    }

    override suspend fun open(now: Instant): StoredUndoOffer? =
        offers.values.filter { it.isOpen(now) }.maxByOrNull { it.expiresAt }

    override suspend fun forget(chainId: String) {
        forgotten += chainId
        offers.remove(chainId)
    }

    override suspend fun clear() = offers.clear()
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

    /**
     * What Google's index does, as far as FR-803 cares: an event is findable by the calendar
     * it was written to and the `source_hash` **it actually carries**.
     *
     * Keyed on the metadata of the write rather than on a value the test hands in, which is
     * the point. The previous fake ignored `sourceHash` entirely and returned a preset id, so
     * it answered "duplicate" or "not duplicate" by fixture — it could not have caught a
     * mismatch between the hash written and the hash queried, and a duplicate that reached a
     * real calendar on 31 Aug 2026 is what that cost.
     */
    val index = mutableMapOf<Pair<String, String>, String>()

    /** Every (calendarId, sourceHash) pair this fake was asked about, in order. */
    val dedupQueries = mutableListOf<Pair<String, String>>()

    /** Seed the index as though a previous save had written this event. */
    fun seed(calendarId: String, sourceHash: String, eventId: String) {
        index[calendarId to sourceHash] = eventId
    }

    override suspend fun insertEvent(calendarId: String, event: EventWrite): String {
        failInsert?.let { throw it }
        written += event
        val id = "event-${written.size}"
        index[calendarId to event.metadata.sourceHash] = id
        return id
    }

    override suspend fun findEventBySourceHash(calendarId: String, sourceHash: String): DuplicateSearch {
        sourceHashesQueried += sourceHash
        dedupQueries += calendarId to sourceHash
        // existingEventId keeps the older tests that set it working; the index answers the rest.
        return DuplicateSearch(existingEventId ?: index[calendarId to sourceHash])
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
        if (failPatch) throw com.latch.google.GoogleUnreachable("no network", java.io.IOException())
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

    /** Set to make the FR-803 task scan report that it stopped looking (`scanCapped`). */
    var duplicateAnswer: DuplicateSearch = DuplicateSearch(existingId = null)

    override suspend fun findTaskBySourceHash(taskListId: String, sourceHash: String, due: LocalDate?): DuplicateSearch {
        sourceHashesQueried += sourceHash
        return duplicateAnswer
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
        if (failPatch) throw com.latch.google.GoogleUnreachable("no network", java.io.IOException())
        patched += Triple(taskListId, taskId, dates)
    }

    override suspend fun listTaskLists(): List<TaskList> = emptyList()
}
