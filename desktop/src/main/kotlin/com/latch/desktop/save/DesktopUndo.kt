package com.latch.desktop.save

import com.latch.core.model.ItemType
import com.latch.desktop.queue.WriteQueue
import com.latch.google.CalendarApi
import com.latch.google.GoogleRejected
import com.latch.google.ItemDates
import com.latch.google.TasksApi
import java.time.Duration
import java.time.Instant

/**
 * What a save produced, in the shape an undo needs.
 *
 * The same three cases Android's `CreatedItem` has, and for the same reason: **undo is one of
 * three operations chosen by what the save did** (SRS 1.16) — delete what was created, drop
 * what was queued, restore what was updated. A single "delete it" would destroy an item the
 * user already had.
 */
sealed interface CreatedItem {
    /**
     * [containerId] is the calendar id for an event and the task list id for a task. Both APIs
     * address an item by its container and its own id, and neither can be deleted by id alone.
     */
    data class Written(
        val type: ItemType,
        val containerId: String,
        val remoteId: String,
    ) : CreatedItem

    /**
     * FR-806: still in the queue. The drain will not touch an entry inside its undo window, so
     * this stays droppable for as long as the offer stands — `drainable` is that rule, and it
     * was written before this existed for exactly this reason.
     */
    data class Queued(val queueId: String) : CreatedItem

    /**
     * FR-804: an item that already existed and was **moved**, not created.
     *
     * Undoing this is a restore and never a delete. The item was the user's before this save
     * touched it, and deleting it would destroy something they already had — the one outcome
     * FR-807 must not produce.
     *
     * [priorDates] is what the item held when the match was made, which is the only place those
     * values still exist once the patch has gone through. SRS 1.19 records the staleness that
     * follows: they are what the item held before *this app* changed it, not necessarily what
     * it holds now if something else has touched it since.
     */
    data class Updated(
        val type: ItemType,
        val containerId: String,
        val remoteId: String,
        val priorDates: ItemDates,
        /**
         * The description or notes the item held **before** FR-804's move note was appended.
         *
         * Carried for the same reason [priorDates] is, and it became necessary the moment an
         * update started writing one: an undo that put the dates back and left the note saying
         * the item had moved would leave a statement in the user's calendar that is no longer
         * true. Null where the update wrote no note, in which case undo leaves the body alone.
         */
        val priorBody: String? = null,
    ) : CreatedItem
}

/**
 * How far an undo got.
 *
 * NFR-303: a partial removal has to say so, because the recourse is to remove the rest in
 * Google by hand and a user cannot do that without being told how many.
 */
data class RemovalOutcome(val removed: Int, val total: Int) {
    val complete: Boolean get() = removed == total
}

/** FR-807's ten seconds. Named once so the offer and the queue cannot disagree about it. */
val UNDO_WINDOW: Duration = Duration.ofSeconds(10)

/**
 * The offer, as a pure function of when the save happened.
 *
 * FR-807 says "not less than 10 seconds". `CLAUDE.md` records an observation against that
 * number rather than a change to it — the window was missed twice on the phone while checking
 * Google to confirm the write had landed, which is the natural thing to do before deciding to
 * take it back — and the desktop keeps the same ten seconds so the two clients behave alike.
 */
fun undoSecondsLeft(savedAt: Instant, now: Instant, window: Duration = UNDO_WINDOW): Int {
    val remaining = Duration.between(now, savedAt.plus(window))
    return if (remaining.isNegative) 0 else Math.ceil(remaining.toMillis() / 1000.0).toInt()
}

fun undoIsOpen(savedAt: Instant, now: Instant, window: Duration = UNDO_WINDOW): Boolean =
    undoSecondsLeft(savedAt, now, window) > 0

/**
 * FR-807's undo, performed.
 *
 * **The loop does not stop at the first failure.** A chain half in the account is worse than
 * one wholly there or wholly gone, so every item still gets its turn and the outcome reports
 * how far it got.
 *
 * **A delete of something already gone is a success.** The caller asked for it not to be in the
 * account and it is not; reporting a failure there would tell the user their item survived an
 * undo when it did not, which is the more damaging of the two possible lies.
 *
 * A free function taking its two APIs, for the reason `drainEntry` is: a destructive operation
 * inside a class that needs a live sign-in is a destructive operation no test will reach.
 */
suspend fun undoCreated(
    created: List<CreatedItem>,
    calendar: CalendarApi,
    tasks: TasksApi,
    queue: WriteQueue? = null,
): RemovalOutcome {
    var removed = 0
    created.forEach { item ->
        val ok = runCatching {
            when (item) {
                is CreatedItem.Written -> when (item.type) {
                    ItemType.EVENT -> calendar.deleteEvent(item.containerId, item.remoteId)
                    ItemType.TASK -> tasks.deleteTask(item.containerId, item.remoteId)
                }

                is CreatedItem.Queued -> queue?.remove(item.queueId) ?: Unit

                // The restore. Never a delete: see CreatedItem.Updated.
                // The restore. Never a delete, and it puts the **body** back with the dates:
                // an undo that reverted the move and left FR-804's note saying it had moved
                // would leave a false statement standing in the user's own calendar.
                is CreatedItem.Updated -> when (val prior = item.priorDates) {
                    is ItemDates.Event ->
                        calendar.patchEventDates(item.containerId, item.remoteId, prior, item.priorBody)
                    is ItemDates.Task ->
                        tasks.patchTaskDates(item.containerId, item.remoteId, prior, item.priorBody)
                }
            }
            true
        }.getOrElse { failure -> alreadyGoneEnough(failure) }
        if (ok) removed++
    }
    return RemovalOutcome(removed, created.size)
}

/**
 * Whether a failure means the item is already in the state the undo wanted.
 *
 * 404 for an item that is not there, 410 for one deleted since. Both mean the account no longer
 * holds it, which is what was asked for.
 */
internal fun alreadyGoneEnough(failure: Throwable): Boolean =
    failure is GoogleRejected && (failure.status == 404 || failure.status == 410)
