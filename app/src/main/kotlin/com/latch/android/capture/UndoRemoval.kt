package com.latch.android.capture

import com.latch.core.model.ItemType
import com.latch.data.CalendarApi
import com.latch.data.CreatedItem
import com.latch.data.ItemDates
import com.latch.data.LocalItemIndex
import com.latch.data.TasksApi
import com.latch.data.WriteQueue
import kotlin.coroutines.cancellation.CancellationException

/**
 * How far an undo got. NFR-303: a partial removal has to say so, because the recourse is to
 * remove the rest in Google by hand and a user cannot do that without being told how many.
 */
data class RemovalOutcome(val removed: Int, val total: Int) {
    val complete: Boolean get() = removed == total
}

/**
 * FR-807's undo, performed. **The single implementation**, called from the capture sheet and
 * from the home screen.
 *
 * It is a top-level function rather than a method on `CaptureSaver` because there are now two
 * places an undo can be taken from — the sheet, inside the ten seconds, and the home screen,
 * where a stored offer surfaces after the process that made it has gone. Two copies of a
 * destructive operation would eventually differ, and the one that differed would be the one
 * nobody watched. Extracting it also gives it a JVM test, which is the lesson `drainEntry` and
 * `findEventPaged` both cost a day to learn.
 *
 * **Undo is one of three operations chosen by what the save did** (SRS 1.16): delete what was
 * created, drop what was queued, restore what was updated. The loop does not stop at the first
 * failure — a chain half in the account is worse than one wholly there or wholly gone, so every
 * item still gets its turn.
 *
 * [index] is forgotten alongside each successful removal, without which FR-803's local index
 * would answer "already saved" about an item the user has just taken back — and there would be
 * no way to capture it again.
 */
suspend fun removeCreated(
    created: List<CreatedItem>,
    calendarApi: CalendarApi,
    tasksApi: TasksApi,
    writeQueue: WriteQueue,
    index: LocalItemIndex,
): RemovalOutcome {
    var removed = 0

    for (item in created) {
        try {
            when (item) {
                is CreatedItem.Written -> {
                    when (item.type) {
                        ItemType.EVENT -> calendarApi.deleteEvent(item.containerId, item.remoteId)
                        ItemType.TASK -> tasksApi.deleteTask(item.containerId, item.remoteId)
                    }
                    index.forget(item.containerId, item.remoteId)
                }

                // FR-806: nothing was written, so there is nothing to delete — the entry stops
                // existing. A false here means the worker got to it first and the item *is* in
                // the account, which `drainable` exists to prevent; if it ever happens this
                // undo did not remove it, so it counts as a failure rather than a quiet success.
                is CreatedItem.Queued -> check(writeQueue.drop(item.queueId)) {
                    "Queue entry was already drained"
                }

                // FR-807, corrected at SRS 1.16: undoing an update is a restore. The item
                // existed before this save touched it, so deleting it would destroy something
                // the user already had — the one thing an undo must not do. The index is not
                // forgotten here: the item is still in the account, and still Latch's.
                is CreatedItem.Updated -> when (val prior = item.priorDates) {
                    is ItemDates.Event ->
                        calendarApi.patchEventDates(item.containerId, item.remoteId, prior)

                    is ItemDates.Task ->
                        tasksApi.patchTaskDates(item.containerId, item.remoteId, prior)
                }
            }
            removed++
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // Counted by omission. The next item still gets its delete.
        }
    }

    return RemovalOutcome(removed = removed, total = created.size)
}
