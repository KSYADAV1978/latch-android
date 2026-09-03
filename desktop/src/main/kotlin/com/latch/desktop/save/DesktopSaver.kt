package com.latch.desktop.save

import com.latch.core.model.Item
import com.latch.core.model.ItemType
import com.latch.desktop.capture.desktopSource
import com.latch.desktop.queue.QueuedItem
import com.latch.desktop.queue.QueuedWrite
import com.latch.desktop.queue.WriteQueue
import com.latch.desktop.store.DesktopDefaults
import com.latch.google.CalendarApi
import com.latch.google.EventWrite
import com.latch.google.GoogleFailure
import com.latch.google.GoogleRejected
import com.latch.google.GoogleUnreachable
import com.latch.google.ItemDates
import com.latch.google.RescheduleMatch
import com.latch.google.TaskWrite
import com.latch.google.TasksApi
import com.latch.google.WriteDecision
import com.latch.google.isWorthRetrying
import com.latch.google.itemDatesOf
import com.latch.google.writeDecision
import com.latch.parser.ParseResult
import com.latch.wire.RemoteMetadata
import com.latch.wire.WireCapture
import com.latch.wire.WireDestination
import com.latch.wire.bodyWithNote
import com.latch.wire.dateSpans
import com.latch.wire.itemKeyOf
import com.latch.wire.itemKeyTitle
import com.latch.wire.sourceBlock
import com.latch.wire.sourceHashOf
import java.time.Instant

/** What a save did. Named, so the UI phrases it (NFR-402). */
sealed interface SaveResult {
    data class Written(val count: Int, val created: List<CreatedItem>) : SaveResult

    /** FR-804: an item that already existed was **moved**, not created. */
    data class Updated(val created: CreatedItem.Updated) : SaveResult

    /** FR-803: this message is already in the account, and nothing was written again. */
    data object AlreadySaved : SaveResult

    /**
     * FR-806: held locally, and it will be written when it can be.
     *
     * Told apart from [Written] on screen, and that distinction is AC-10's whole point: saying
     * "Saved" for something that is not in the account yet is the lie the Queued state exists
     * to avoid.
     */
    data class Queued(
        val count: Int,
        val alsoWritten: Int = 0,
        /**
         * FR-807 over a queued capture: undoing it drops the entry rather than chasing an item
         * that was never written. `drainable` already refuses to drain an entry inside its undo
         * window, so this stays droppable for as long as the offer stands.
         */
        val queueId: String? = null,
    ) : SaveResult

    /**
     * FR-804: the same identity at a different date. **Nothing has been written.**
     *
     * The offer carries everything either answer needs, because the alternative is re-running
     * both searches when the user chooses and possibly getting a different answer the second
     * time — which would make the question they answered not the question that was acted on.
     */
    data class RescheduleOffered(
        val match: RescheduleMatch,
        val proposed: ItemDates,
        /** The title as **stored on the existing item**, which is what the offer quotes. */
        val storedTitle: String,
        val pending: PendingWrite,
    ) : SaveResult

    data class Failed(val reason: SaveFailure, val detail: String = "") : SaveResult
}

enum class SaveFailure { NOT_SIGNED_IN, NO_DESTINATION, OFFLINE, REFUSED, NOTHING_TO_WRITE }

/** Everything a save needs to finish, held while an FR-804 offer stands. */
data class PendingWrite(
    val items: List<Item>,
    val metadata: RemoteMetadata,
    val body: String,
    val defaults: DesktopDefaults,
    val chainId: String,
    val timeZone: String,
)

/**
 * FR-801 to FR-804 on the desktop, through the client `:app` uses.
 *
 * **There is almost nothing here, and that is the result rather than a gap.** The metadata, the
 * hashes, the titles, the dates, the request bodies and §7.2's decision table are all `:wire`'s
 * and `:google`'s, so what this file adds is the order the calls go in. A save on this machine
 * and a save on the phone put the same bytes in front of Google because they run the same code.
 */
class DesktopSaver(
    private val calendar: CalendarApi,
    private val tasks: TasksApi,
    /** The zone an event's wall-clock times are read in. The machine's, unless told otherwise. */
    private val zone: String = java.time.ZoneId.systemDefault().id,
    /**
     * FR-806's queue, where there is one.
     *
     * **The queue is a fallback, not the path.** A save writes directly first and enqueues only
     * on a failure that waiting can fix. Routing everything through the queue would be simpler
     * and would cost FR-803 its immediate answer — "Already saved. Nothing was written again."
     * is a synchronous reply today, and AC-07 depends on the user seeing it.
     */
    private val queue: WriteQueue? = null,
    private val clock: () -> Instant = Instant::now,
) {
    suspend fun save(
        captured: WireCapture,
        /**
         * The parse the items came from.
         *
         * Passed in rather than re-derived, because FR-515 makes a parse a function of the
         * instant it was made at: a capture confirmed at 23:59 would derive its key from a
         * different reading of "tomorrow" than the one the user saw.
         */
        result: ParseResult,
        items: List<Item>,
        defaults: DesktopDefaults,
        chainId: String,
        capturedAt: Instant = clock(),
    ): SaveResult {
        if (items.isEmpty()) return SaveResult.Failed(SaveFailure.NOTHING_TO_WRITE)

        val sourceHash = sourceHashOf(captured.text)
        val itemKey = itemKeyOf(itemKeyTitle(captured, result))
        val metadata = RemoteMetadata(
            sourceHash = sourceHash,
            // FR-804's identity, and the reason it is not the source hash: a reschedule *is*
            // different source text, so a key derived from the text could never find the item
            // it is meant to move. `itemKeyTitle` blanks every date span out of the title.
            itemKey = itemKey,
            chainId = chainId,
            capturedAt = capturedAt,
            sourceApp = null,
        )

        // FR-805, and FR-805b where the capture was recognised from an image. Composed once, by
        // the same function `CaptureSaver` uses, so the two clients cannot describe one message
        // differently.
        val body = sourceBlock(
            // The same source `saveRoute` asked about, so what may be *held* and what may be
            // *described* cannot be decided from two different readings of one capture.
            source = desktopSource(captured),
            sourceText = captured.text,
            dateSpans = dateSpans(result),
            sourceLink = null,
        )
        val pending = PendingWrite(items, metadata, body, defaults, chainId, zone)

        // FR-803, once per capture and before anything is written. The chain shares one hash, so
        // one question covers every item in it.
        val duplicate = runCatching { findExisting(sourceHash, defaults) }.getOrElse { failure ->
            // The probe itself could not run — almost always no network. Queueing is right: the
            // drain asks FR-803 again before it writes, so nothing is duplicated by having been
            // unable to ask now.
            return queueOrFail(failure, pending, items.map { QueuedItem(it) }, items.size)
        }

        // FR-804's search, and **only for a capture holding one item** (SRS 1.25). Accepting an
        // offer patches a single item, so offering one for a chain would move one date and
        // silently discard the rest. The narrowing is the same on both clients.
        val reschedule = if (duplicate.found || items.size != 1) null else {
            runCatching { findReschedule(items.single(), defaults, itemKey) }.getOrElse { failure ->
                return queueOrFail(failure, pending, items.map { QueuedItem(it) }, items.size)
            }
        }

        val proposed = itemDatesOf(items.first(), zone)
        return when (val decision = writeDecision(duplicate, reschedule, proposed)) {
            WriteDecision.Duplicate -> SaveResult.AlreadySaved
            WriteDecision.Create -> writeAll(pending)
            is WriteDecision.Reschedule -> SaveResult.RescheduleOffered(
                match = decision.match,
                proposed = proposed,
                // Quoted from what is **stored on the existing item**, not from what was just
                // captured. The user is being asked about something already in their calendar,
                // and naming it by the new text would describe a thing they cannot see.
                storedTitle = decision.match.title,
                pending = pending,
            )
        }
    }

    /** FR-804, answered "Update". A patch, never a second item. */
    suspend fun applyReschedule(offer: SaveResult.RescheduleOffered): SaveResult = runCatching {
        val event = offer.proposed is ItemDates.Event
        val container =
            if (event) offer.pending.defaults.calendarId else offer.pending.defaults.taskListId

        when (val proposed = offer.proposed) {
            is ItemDates.Event -> calendar.patchEventDates(container, offer.match.remoteId, proposed)
            is ItemDates.Task -> tasks.patchTaskDates(container, offer.match.remoteId, proposed)
        }
        SaveResult.Updated(
            CreatedItem.Updated(
                type = if (event) ItemType.EVENT else ItemType.TASK,
                containerId = container,
                remoteId = offer.match.remoteId,
                // What the item held when the match was made. After the patch these exist
                // nowhere else, and an undo has to put them back — FR-807 over an update is a
                // restore and never a delete.
                priorDates = offer.match.dates,
            )
        )
    }.getOrElse { failureFor(it) }

    /** FR-804, answered "Create new". §7.2's row 2 permits either answer. */
    suspend fun createAnyway(offer: SaveResult.RescheduleOffered): SaveResult = writeAll(offer.pending)

    private suspend fun writeAll(pending: PendingWrite): SaveResult {
        val created = mutableListOf<CreatedItem>()
        val writtenIds = mutableMapOf<String, String>()

        pending.items.forEach { item ->
            val id = runCatching { write(item, pending) }.getOrElse { failure ->
                // FR-806. A failure waiting can fix is held; one it cannot is reported.
                if (isWorthRetrying(failure) && queue != null) {
                    // SRS 1.24: what is already written is recorded on the entry, so the drain
                    // resumes the chain rather than writing its first item twice.
                    return queueOrFail(
                        failure, pending,
                        pending.items.map { QueuedItem(it, writtenIds[it.id]) },
                        pending.items.size - created.size,
                        alsoWritten = created.size,
                    )
                }
                return if (created.isEmpty()) failureFor(failure)
                else SaveResult.Written(created.size, created)
            }
            writtenIds[item.id] = id
            created += CreatedItem.Written(
                type = item.type,
                containerId = if (item.type == ItemType.EVENT) {
                    pending.defaults.calendarId
                } else {
                    pending.defaults.taskListId
                },
                remoteId = id,
            )
        }
        return SaveResult.Written(created.size, created)
    }

    private fun queueOrFail(
        failure: Throwable,
        pending: PendingWrite,
        items: List<QueuedItem>,
        count: Int,
        alsoWritten: Int = 0,
    ): SaveResult {
        if (!isWorthRetrying(failure) || queue == null) return failureFor(failure)
        val entryId = java.util.UUID.randomUUID().toString()
        queue.add(
            QueuedWrite(
                id = entryId,
                metadata = pending.metadata,
                body = pending.body,
                calendarId = pending.defaults.calendarId,
                taskListId = pending.defaults.taskListId,
                timeZone = pending.timeZone,
                items = items,
                queuedAt = clock(),
            )
        )
        return SaveResult.Queued(count, alsoWritten, entryId)
    }

    private suspend fun findExisting(sourceHash: String, defaults: DesktopDefaults) =
        calendar.findEventBySourceHash(defaults.calendarId, sourceHash).let { events ->
            if (events.found) events
            // `scanCapped` is the Tasks API admitting it read ten pages and stopped. It is not
            // "no duplicate": `DuplicateSearch` keeps them apart so a caller cannot read one as
            // the other. Android cures the residual with a local index this client has not got,
            // so what §7.2 already records for tasks is present here too.
            else tasks.findTaskBySourceHash(defaults.taskListId, sourceHash, null)
        }

    /**
     * FR-804's search, **scoped by transport** exactly as on Android: a task capture queries
     * only tasks, so two items sharing a date-free title across the two transports cannot match
     * each other and offer to move the wrong one.
     */
    private suspend fun findReschedule(item: Item, defaults: DesktopDefaults, itemKey: String) =
        if (item.type == ItemType.EVENT) {
            calendar.findEventByItemKey(defaults.calendarId, itemKey)
        } else {
            tasks.findTaskByItemKey(defaults.taskListId, itemKey)
        }

    private suspend fun write(item: Item, pending: PendingWrite): String = when (item.type) {
        ItemType.EVENT -> calendar.insertEvent(
            pending.defaults.calendarId,
            EventWrite(
                summary = item.title,
                description = bodyWithNote(item.notes, pending.body),
                location = item.location,
                start = requireNotNull(item.start) { "an event with no start reached the write" },
                end = requireNotNull(item.end) { "an event with no end reached the write" },
                allDay = item.allDay,
                timeZone = pending.timeZone,
                reminderMinutes = item.reminderMinutes,
                metadata = pending.metadata,
            ),
        )
        ItemType.TASK -> tasks.insertTask(
            pending.defaults.taskListId,
            TaskWrite(
                title = item.title,
                // FR-510: "with the past date recorded in the notes".
                notes = bodyWithNote(item.notes, pending.body),
                due = item.dueDate,
                metadata = pending.metadata,
            ),
        )
    }

    private fun failureFor(error: Throwable): SaveResult = when (error) {
        is GoogleUnreachable -> SaveResult.Failed(SaveFailure.OFFLINE, error.message.orEmpty())
        is GoogleRejected -> SaveResult.Failed(SaveFailure.REFUSED, error.message.orEmpty())
        is GoogleFailure -> SaveResult.Failed(SaveFailure.REFUSED, error.message.orEmpty())
        else -> SaveResult.Failed(SaveFailure.REFUSED, error.message.orEmpty())
    }
}

/** Convenience for the wiring: the destination as `:wire` wants it. */
fun DesktopDefaults.toWireDestination(): WireDestination =
    WireDestination(calendarId = calendarId, taskListId = taskListId)
