package com.latch.android.capture

import com.latch.core.model.CaptureSource
import com.latch.core.model.Item
import com.latch.core.model.ItemType
import com.latch.data.AccountDefaults
import com.latch.data.AccountDefaultsStore
import com.latch.data.CalendarApi
import com.latch.data.EventWrite
import com.latch.data.ItemDates
import com.latch.data.RemoteMetadata
import com.latch.data.RescheduleMatch
import com.latch.data.TaskWrite
import com.latch.data.PendingWrite
import com.latch.data.TasksApi
import com.latch.data.WriteOperation
import com.latch.data.WriteQueue
import com.latch.data.isWorthRetrying
import com.latch.data.itemKeyOf
import com.latch.data.sourceBlock
import com.latch.data.sourceHashOf
import com.latch.parser.ParseContext
import com.latch.parser.ParseResult
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** NFR-402: the saver names what went wrong; the app phrases it. */
enum class SaveFailure {
    /** Setup has not run, or its defaults were unreadable. */
    NO_DESTINATION,

    /** FR-506 row 3: a time with no date, and no picker to finish it with. */
    NEEDS_A_DATE,

    /** The write did not reach Google. NFR-303: this must be said, never swallowed. */
    WRITE_FAILED,
}

/**
 * Where a capture would go, as three distinct states.
 *
 * Not a nullable `AccountDefaults`: "not read yet" and "setup has not run" are opposite
 * facts, and collapsing them is what makes a screen go quiet instead of explaining itself.
 * The first is momentary and says nothing; the second is the answer to "why can I not save
 * this", and the user has to be told.
 */
sealed interface DestinationState {
    data object Loading : DestinationState
    data object None : DestinationState
    data class Ready(val defaults: AccountDefaults) : DestinationState
}

/** Why Save is unavailable. Null means it is available. */
enum class SaveBlocker {
    /** Momentary, and deliberately silent — there is nothing useful to say for a few frames. */
    READING_DESTINATION,

    /** Setup has not run, or its defaults were dropped. FR-906: there is nowhere safe to route. */
    NO_DESTINATION,

    /** FR-506 row 3: a time with no day, and no picker to finish it with. */
    NEEDS_A_DATE,

    /** A save is already in flight, or has finished. */
    NOT_IDLE,
}

/**
 * The single decision about whether this capture can be saved, and why not.
 *
 * Pure, so the screen and the tests cannot drift apart, and so every reason has to be a named
 * value that the UI is obliged to handle rather than an unexplained disabled button.
 */
fun saveBlocker(
    destination: DestinationState,
    result: ParseResult?,
    saveState: SaveState,
): SaveBlocker? = when {
    result == null -> SaveBlocker.NEEDS_A_DATE
    saveState !is SaveState.Idle -> SaveBlocker.NOT_IDLE
    destination is DestinationState.Loading -> SaveBlocker.READING_DESTINATION
    destination is DestinationState.None -> SaveBlocker.NO_DESTINATION
    draftBlocker(result) == DraftBlocker.NEEDS_A_DATE -> SaveBlocker.NEEDS_A_DATE
    else -> null
}

/**
 * One item a save produced, and the whole of what is needed to take it back again.
 *
 * Two shapes, because FR-806 gave a save two possible outcomes. A write that reached Google
 * is undone by deleting it; a write that was queued because there was no network is undone by
 * dropping the queue entry, and there is no remote id to delete because nothing was created.
 * Collapsing the two would mean undo guessing, and the wrong guess either leaves an item in
 * the account or sends a delete for an id that does not exist.
 */
sealed interface CreatedItem {
    /**
     * [containerId] is the calendar id for an event and the task list id for a task — both
     * APIs address an item by its container and its own id, and neither can be deleted by
     * id alone.
     */
    data class Written(
        val type: ItemType,
        val containerId: String,
        val remoteId: String,
    ) : CreatedItem

    /**
     * FR-806: still in the queue. `WriteQueueWorker` will not drain an entry inside its undo
     * window, so this stays droppable for as long as the offer stands — see [drainable].
     */
    data class Queued(val queueId: String) : CreatedItem

    /**
     * FR-804: an item that already existed and was **moved**, not created.
     *
     * Undoing this is a restore and never a delete. The item was the user's before this save
     * touched it, and deleting it would destroy something they already had — the one outcome
     * FR-807 must not produce, and the reason its wording had to be corrected at SRS 1.16.
     *
     * [priorDates] is what the item held when the match was made, which is the only place
     * those values still exist once the patch has gone through. SRS 1.19 records the
     * staleness that follows: where the update waited in the queue, this restores what was
     * true at match time and overwrites any hand edit made in between.
     */
    data class Updated(
        val type: ItemType,
        val containerId: String,
        val remoteId: String,
        val priorDates: ItemDates,
    ) : CreatedItem
}

/** FR-807: not less than ten seconds. */
val UNDO_WINDOW: Duration = Duration.ofSeconds(10)

/**
 * FR-807's offer, for as long as it stands.
 *
 * **Undo deletes the ids recorded here rather than re-querying `latch.chain_id`.** The chain
 * id is what makes these items a group (§2.4) and is written on every one of them, but it
 * cannot be used to *find* them: the Tasks API has no content filter of any kind — the same
 * limitation that makes FR-803 a bounded scan — so a task chain could only be recovered by
 * re-scanning pages, and an undated one by a scan that is allowed to give up. Undo would
 * then be exact for events and best-effort for tasks, which is the wrong shape for a
 * destructive operation. Inside a ten-second window in the process that did the writing, we
 * know exactly what was created, and the delete is exact on both transports.
 *
 * The consequence, recorded against FR-807 in the SRS: this offer does not survive the
 * process. Undoing from another device is a different feature and would have to go by chain
 * id, for events only.
 */
data class UndoWindow(
    val chainId: String,
    val created: List<CreatedItem>,
    val expiresAt: Instant,
) {
    fun isOpen(now: Instant): Boolean = now.isBefore(expiresAt)

    /** Counts down 10, 9, … 1 and then the offer is gone — never a visible zero. */
    fun secondsRemaining(now: Instant): Int {
        val millis = Duration.between(now, expiresAt).toMillis()
        return if (millis <= 0) 0 else ((millis + 999) / 1000).toInt()
    }
}

sealed interface SaveState {
    data object Idle : SaveState
    data object Saving : SaveState

    /**
     * [searchWasCapped] carries `DuplicateSearch.scanCapped` up to the UI rather than
     * discarding it: the item was written, but the duplicate check gave up before it had
     * read everything, so this is "saved, and it might be a second copy".
     *
     * [undo] is FR-807's offer, and goes to null when it lapses. A `Saved` with no window is
     * therefore "saved, and it is too late to take it back" — the state this screen used to
     * reach immediately.
     */
    data class Saved(
        val searchWasCapped: Boolean = false,
        val undo: UndoWindow? = null,
    ) : SaveState

    /**
     * FR-806: there was no network, so the capture is on disk and will be written when there
     * is one. Deliberately **not** [Saved] — nothing is in the user's account yet, and saying
     * otherwise would be the swallowed failure NFR-303 forbids, dressed up as success.
     *
     * It carries its own undo window: a queued save is as undoable as a written one, and for
     * the user it is the same act.
     */
    data class Queued(val undo: UndoWindow? = null) : SaveState

    /**
     * FR-803, and §7.2's third row: nothing was written.
     *
     * Reached either because this message has been saved before, or because it is new text
     * naming the date the item already sits on. Both are "already saved" to the user,
     * because in both there is nothing for a write to do.
     */
    data object AlreadySaved : SaveState

    /**
     * FR-804: this capture looks like a reschedule, and the user has to say.
     *
     * **Nothing has been written when this state is reached** — the offer comes before the
     * write, not after it, because the requirement forbids a silent update and an update
     * already performed cannot be un-asked. The two answers are [CaptureSaver.updateExisting]
     * and [CaptureSaver.createNewInstead]; closing the screen takes neither and writes
     * nothing.
     *
     * [existing] and [proposed] are dates, not sentences. NFR-402 keeps the phrasing in the
     * app, and the weekday shown beside each must be **computed from the date** rather than
     * carried over from the captured text, which may name a weekday that contradicts it.
     */
    data class RescheduleOffered(
        val title: String,
        val existing: ItemDates,
        val proposed: ItemDates,
    ) : SaveState

    data class Failed(val reason: SaveFailure) : SaveState

    /** FR-807: the deletes are in flight. */
    data object Undoing : SaveState

    /** FR-807: every item of the chain is gone. */
    data object Undone : SaveState

    /**
     * NFR-303: an undo that did not fully succeed says so, and says how far it got.
     *
     * [removed] of [total] is not decoration. The recourse for a failed undo is to remove
     * the item in Google by hand, and a user cannot do that without being told how many are
     * still there.
     */
    data class UndoFailed(val removed: Int, val total: Int) : SaveState
}

/**
 * FR-807's offer if it is still open, null otherwise.
 *
 * Pure, and the only thing that decides whether undo is available — the screen, the activity
 * and [CaptureSaver.undo] all ask this rather than each reading the state their own way.
 * Same arrangement as [saveBlocker], and for the same reason.
 */
fun undoOffer(state: SaveState, now: Instant): UndoWindow? = when (state) {
    is SaveState.Saved -> state.undo
    is SaveState.Queued -> state.undo
    else -> null
}?.takeIf { it.isOpen(now) }

/**
 * Whether the Save button belongs on screen at all.
 *
 * Every state that has already written something, or removed it again, is terminal for this
 * capture: the screen is a confirmation, not an editor. Re-saving after an undo is possible
 * as far as Google is concerned — a deleted item is excluded from both FR-803 searches — but
 * offering it here would make the window a thing to be worked in rather than answered.
 */
fun saveIsOffered(state: SaveState): Boolean = when (state) {
    SaveState.Idle, SaveState.Saving, is SaveState.Failed -> true
    // FR-804: the question on screen is now update-or-create, and Save is not one of the
    // two answers. Leaving it up would offer a third door that writes without answering.
    is SaveState.RescheduleOffered,
    is SaveState.Saved,
    is SaveState.Queued,
    SaveState.AlreadySaved,
    SaveState.Undoing,
    SaveState.Undone,
    is SaveState.UndoFailed,
    -> false
}

/**
 * Performs a save, and outlives the screen that asked for it.
 *
 * Held by `LatchApplication`, not by `CaptureActivity`, and deliberately. The capture window
 * is a floating dialog with `windowCloseOnTouchOutside` set and is excluded from recents — a
 * stray tap anywhere outside it dismisses the activity. A write that has already left the
 * device must still finish and still be reported; scoping it to the Activity would abandon it
 * halfway, having perhaps already created the event.
 *
 * Single-slot: one save at a time, the same shape as `AuthResolutionBridge`.
 */
class CaptureSaver(
    private val defaultsStore: AccountDefaultsStore,
    private val calendarApi: CalendarApi,
    private val tasksApi: TasksApi,
    private val writeQueue: WriteQueue,
    /**
     * FR-806: asks for a drain. A function rather than the `WorkManager` itself, so this
     * class stays free of Android — everything else it touches is a `:data` contract, and
     * `CaptureSaverTest` runs on the JVM.
     */
    private val requestDrain: () -> Unit,
    private val scope: CoroutineScope,
    /**
     * FR-805's link back to the source, as a format string taking the source application.
     * Passed in because it is user-visible text and NFR-402 keeps that in `strings.xml` —
     * the same arrangement as the Latch calendar's name in `SetupCoordinator`.
     */
    private val sourceLinkTemplate: String,
) {
    private val _state = MutableStateFlow<SaveState>(SaveState.Idle)
    val state: StateFlow<SaveState> = _state.asStateFlow()

    /**
     * The write held back while an FR-804 offer stands.
     *
     * Kept here rather than inside [SaveState.RescheduleOffered] because the state is what
     * the screen renders and none of this is its business. It also means an answer can only
     * be acted on while an offer is genuinely open: [reset] clears this, so a tap belonging
     * to a capture that has since been replaced finds nothing to do.
     */
    private var offer: PendingOffer? = null

    /**
     * @param selected FR-511's answer: the candidates left ticked, by index. Defaults to all
     *   of them, which is what the checkboxes start as.
     */
    fun save(
        captured: CapturedText,
        result: ParseResult,
        context: ParseContext,
        selected: Set<Int> = result.candidates.indices.toSet(),
    ) {
        if (_state.value == SaveState.Saving) return
        _state.value = SaveState.Saving
        scope.launch { perform(captured, result, context, selected) }
    }

    /**
     * FR-807: take back everything the last save wrote.
     *
     * Silently does nothing where there is no open offer — a tap that lands as the window
     * lapses, or a second tap on a delete already in flight. The offer is read through
     * [undoOffer] so that the button and this agree on when it is gone.
     */
    fun undo() {
        val window = undoOffer(_state.value, Instant.now()) ?: return
        _state.value = SaveState.Undoing
        scope.launch { remove(window) }
    }

    /**
     * So a second capture in the same process does not open on the last one's outcome.
     *
     * This is also what ends an FR-807 offer that a new capture has overtaken: the previous
     * window's countdown was on a screen that no longer exists, and the delete it would have
     * done is no longer the one in front of the user. A save or an undo already in flight is
     * left alone — it has reached Google and its outcome still has to be reported.
     */
    /**
     * FR-804: the user confirmed the move. This is the first write of this save.
     *
     * Does nothing unless an offer is actually standing, so a second tap, or one that lands
     * after the screen has moved on, cannot patch the item twice.
     */
    fun updateExisting() {
        val standing = offer ?: return
        if (_state.value !is SaveState.RescheduleOffered) return
        offer = null
        _state.value = SaveState.Saving
        scope.launch { applyUpdate(standing) }
    }

    /**
     * FR-804: the user declined the move and wants a separate item. An ordinary FR-801
     * create, and the escape hatch for a match this app got wrong.
     */
    fun createNewInstead() {
        val standing = offer ?: return
        if (_state.value !is SaveState.RescheduleOffered) return
        offer = null
        _state.value = SaveState.Saving
        scope.launch { completeAsCreate(standing) }
    }

    fun reset() {
        val current = _state.value
        if (current != SaveState.Saving && current != SaveState.Undoing) {
            // An offer the user walked away from wrote nothing, and must not be answerable
            // by a tap belonging to the next capture.
            offer = null
            _state.value = SaveState.Idle
        }
    }

    private suspend fun perform(
        captured: CapturedText,
        result: ParseResult,
        context: ParseContext,
        selected: Set<Int>,
    ) {
        val defaults = try {
            defaultsStore.allAccounts().firstOrNull()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (unreadable: Exception) {
            null
        }
        if (defaults == null) {
            _state.value = SaveState.Failed(SaveFailure.NO_DESTINATION)
            return
        }

        // Minted here rather than inside the mapping, so that stays deterministic under test.
        val captureId = UUID.randomUUID().toString()
        val chainId = UUID.randomUUID().toString()
        val capturedAt = Instant.now()

        val draft = draftItems(captured, result, context, defaults, captureId, chainId, selected)
        if (draft is DraftResult.Blocked) {
            _state.value = SaveState.Failed(SaveFailure.NEEDS_A_DATE)
            return
        }
        val items = (draft as DraftResult.Ready).items

        // ocrUsed reaches FR-805b through sourceBlock below: an OCR capture's description
        // carries an extract around the dates, not everything the recogniser saw.
        val source = CaptureSource(
            layer = captured.layer,
            appId = captured.appId,
            ocrUsed = captured.ocrUsed,
        )
        val metadata = RemoteMetadata(
            // The whole capture, so every item of one save shares it and FR-803 asks
            // "was this message saved", not "was this item created".
            sourceHash = sourceHashOf(captured.text),
            // Not the title as displayed: FR-509 makes that the whole text for a short
            // capture, which would make item_key a copy of source_hash and leave FR-804
            // with nothing that survives a date change. See itemKeyTitle.
            itemKey = itemKeyOf(itemKeyTitle(captured, result)),
            chainId = chainId,
            capturedAt = capturedAt,
            sourceApp = captured.appId?.let { "android:$it" },
            // FR-600 recipes are not applied on this path yet.
            recipeId = null,
        )
        // FR-805, FR-805a for the notification layer and FR-805b for an OCR capture —
        // composed once, here, so no call site can compose it differently.
        val body = sourceBlock(
            source = source,
            sourceText = captured.text,
            // The same spans itemKeyTitle blanks. FR-805b excerpts around what §7.2 removes.
            dateSpans = dateSpans(result),
            sourceLink = captured.appId?.let { String.format(sourceLinkTemplate, it) },
        )

        val outcome = try {
            write(items, defaults, metadata, body, context)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // FR-806: offline is a delay, not a failure. Anything that will not come right
            // on its own still is one, and is reported rather than hidden in a queue the
            // user would watch never drain.
            if (isWorthRetrying(failure)) {
                queue(items, metadata, body, context)
            } else {
                SaveState.Failed(SaveFailure.WRITE_FAILED)
            }
        }
        settle(outcome)
    }

    /**
     * Publish an outcome, then wait out its FR-807 offer.
     *
     * Shared by all three paths a save can take — a create, an FR-804 update, and a create
     * the user chose over an update — so the ten seconds cannot come out differently
     * depending on which of them ran.
     *
     * The wait is in this coroutine rather than a job of its own, because the check after it
     * is what actually decides: anything that has moved the state on since (a new capture's
     * reset, or the user taking the offer) has already ended this window and finds nothing
     * to do.
     */
    private suspend fun settle(outcome: SaveState) {
        _state.value = outcome

        val window = undoWindowOf(outcome) ?: return
        delay(UNDO_WINDOW.toMillis())
        val current = _state.value
        if (undoWindowOf(current)?.chainId != window.chainId) return
        _state.value = when (current) {
            is SaveState.Saved -> current.copy(undo = null)
            is SaveState.Queued -> current.copy(undo = null)
            else -> return
        }
    }

    /**
     * FR-806: the write could not go now, so it goes later.
     *
     * The drain is asked for here rather than by the caller, so a capture cannot be put on
     * disk by one path and left there by another. WorkManager holds the request behind a
     * connectivity constraint, so this means "when there is a network", not "now".
     */
    private suspend fun queue(
        items: List<Item>,
        metadata: RemoteMetadata,
        body: String,
        context: ParseContext,
    ): SaveState {
        // One entry for the chain, not one per item: SRS §7.1 at v1.23. Per item, the second
        // entry to drain would find the first entry's item under the shared source_hash and
        // remove itself without writing.
        val queueId = writeQueue.enqueue(
            PendingWrite(
                items = items,
                metadata = metadata,
                body = body,
                timeZone = context.zone.id,
            )
        )
        requestDrain()
        return SaveState.Queued(
            undo = undoWindowFor(metadata, listOf(CreatedItem.Queued(queueId)))
        )
    }

    /**
     * FR-804's update, and the only place in the app that moves an item that already exists.
     *
     * The failure handling matches a create's exactly: a failure that waiting can fix goes
     * to the queue, anything else is reported. What it queues is an `UPDATE` carrying the
     * target and the prior state, because a queued move that could not later be undone
     * would be the defect SRS 1.16 corrected, reintroduced through the back door.
     */
    private suspend fun applyUpdate(standing: PendingOffer) {
        val outcome = try {
            val moved = when (val proposed = standing.proposed) {
                is ItemDates.Event -> {
                    calendarApi.patchEventDates(
                        calendarId = standing.defaults.destinationCalendarId,
                        eventId = standing.match.remoteId,
                        dates = proposed,
                    )
                    CreatedItem.Updated(
                        type = ItemType.EVENT,
                        containerId = standing.defaults.destinationCalendarId,
                        remoteId = standing.match.remoteId,
                        priorDates = standing.match.dates,
                    )
                }

                is ItemDates.Task -> {
                    tasksApi.patchTaskDates(
                        taskListId = standing.defaults.taskListId,
                        taskId = standing.match.remoteId,
                        dates = proposed,
                    )
                    CreatedItem.Updated(
                        type = ItemType.TASK,
                        containerId = standing.defaults.taskListId,
                        remoteId = standing.match.remoteId,
                        priorDates = standing.match.dates,
                    )
                }
            }
            SaveState.Saved(undo = undoWindowFor(standing.metadata, listOf(moved)))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (isWorthRetrying(failure)) queueUpdate(standing) else SaveState.Failed(SaveFailure.WRITE_FAILED)
        }

        settle(outcome)
    }

    private suspend fun completeAsCreate(standing: PendingOffer) {
        val outcome = try {
            create(
                items = standing.items,
                defaults = standing.defaults,
                metadata = standing.metadata,
                body = standing.body,
                context = standing.context,
                searchWasCapped = standing.duplicateWasCapped,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (isWorthRetrying(failure)) {
                queue(standing.items, standing.metadata, standing.body, standing.context)
            } else {
                SaveState.Failed(SaveFailure.WRITE_FAILED)
            }
        }

        settle(outcome)
    }

    private suspend fun queueUpdate(standing: PendingOffer): SaveState {
        val queueId = writeQueue.enqueue(
            PendingWrite(
                // An update targets one existing item, so this chain is always of one.
                items = listOf(standing.items.first()),
                metadata = standing.metadata,
                body = standing.body,
                timeZone = standing.context.zone.id,
                targetRemoteId = standing.match.remoteId,
                priorState = standing.match.dates,
            ),
            operation = WriteOperation.UPDATE,
        )
        requestDrain()
        // Undoing this drops the entry. Nothing has been moved in the account yet, so there
        // is no patch to reverse — which is why the two are separate `CreatedItem` shapes.
        return SaveState.Queued(
            undo = undoWindowFor(standing.metadata, listOf(CreatedItem.Queued(queueId))),
        )
    }

    private suspend fun remove(window: UndoWindow) {
        var removed = 0
        var failed = false

        // The loop does not stop at the first failure: a chain half in the account is worse
        // than one that is wholly there or wholly gone, so every item still gets its delete.
        for (item in window.created) {
            try {
                when (item) {
                    is CreatedItem.Written -> when (item.type) {
                        ItemType.EVENT -> calendarApi.deleteEvent(item.containerId, item.remoteId)
                        ItemType.TASK -> tasksApi.deleteTask(item.containerId, item.remoteId)
                    }

                    // FR-806: nothing was written, so there is nothing to delete — the entry
                    // stops existing. A false here would mean the worker drained it first,
                    // which `drainable` exists to prevent; if it ever happens the item is in
                    // the account and this undo did not remove it, so it counts as a failure
                    // rather than as a quiet success.
                    is CreatedItem.Queued -> check(writeQueue.drop(item.queueId)) {
                        "Queue entry was already drained"
                    }

                    // FR-807, corrected at SRS 1.16: undoing an update is a restore. The
                    // item existed before this save touched it, so deleting it would destroy
                    // something the user already had — the one thing an undo must not do.
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
                failed = true
            }
        }

        _state.value = if (failed) {
            SaveState.UndoFailed(removed = removed, total = window.created.size)
        } else {
            SaveState.Undone
        }
    }

    /** The offer, on whichever outcome carries one. */
    private fun undoWindowOf(state: SaveState): UndoWindow? = when (state) {
        is SaveState.Saved -> state.undo
        is SaveState.Queued -> state.undo
        else -> null
    }

    /**
     * The offer, starting from the moment the item exists rather than from the tap. The
     * clock has to start after the insert returns: a slow write would otherwise spend the
     * user's ten seconds before they had anything to undo.
     */
    private fun undoWindowFor(metadata: RemoteMetadata, created: List<CreatedItem>) = UndoWindow(
        chainId = metadata.chainId,
        created = created,
        expiresAt = Instant.now().plus(UNDO_WINDOW),
    )

    /**
     * FR-803, then FR-804, then FR-801. The order is the requirement.
     *
     * A `source_hash` match settles it and the item key is **not queried at all** — §7.2's
     * first row says the key is not consulted, so a duplicate can never present itself as a
     * reschedule. Only on a miss is the key searched, and only then can an offer arise.
     */
    private suspend fun write(
        items: List<Item>,
        defaults: AccountDefaults,
        metadata: RemoteMetadata,
        body: String,
        context: ParseContext,
    ): SaveState {
        // FR-803 is asked of the **message**, once, before the chain is written — §7.2 says
        // every item of a capture shares a source_hash and that the question is "has this
        // message already been saved". Asked per item, the second insert would find the
        // first and abandon the rest of the chain. The same rule holds at drain (SRS §7.1,
        // v1.23), so the two sites cannot drift.
        val leader = items.first()
        val proposed = itemDatesOf(leader, context.zone.id)
        val duplicate = findDuplicate(leader, defaults, metadata)

        // SRS 1.25: a capture producing more than one item is never offered as a reschedule.
        // An item_key covers the capture, so a re-captured multi-date message matches the
        // chain it created — and accepting the offer would patch one item and write none of
        // the others, turning four items in front of the user into one moved event and three
        // discarded silently. The query is skipped rather than its answer ignored: there is
        // nothing to ask when no answer could be acted on.
        val offerable = items.size == 1
        val reschedule = if (duplicate.found || !offerable) {
            null
        } else {
            findReschedule(leader, defaults, metadata)
        }

        return when (val decision = writeDecision(duplicate, reschedule, proposed)) {
            WriteDecision.Duplicate -> SaveState.AlreadySaved

            WriteDecision.Create ->
                create(items, defaults, metadata, body, context, duplicate.scanCapped)

            is WriteDecision.Reschedule -> {
                // Held aside rather than put in the state: the state is what the screen
                // renders, and the write's internals are not its business.
                offer = PendingOffer(
                    items = items,
                    defaults = defaults,
                    metadata = metadata,
                    body = body,
                    context = context,
                    match = decision.match,
                    proposed = proposed,
                    duplicateWasCapped = duplicate.scanCapped,
                )
                SaveState.RescheduleOffered(
                    // The **stored** item's title, not this capture's. The question is about
                    // the item already in the account, and for a short capture FR-509 makes
                    // this capture's title the whole text — which read as a contradiction:
                    // "<the new text>" is already saved for <the old date>. After a "create
                    // new" the two titles genuinely differ, and only this one names the item
                    // the offer would actually move. Falls back to the drafted title where
                    // the stored item carries none, so the sentence still has a subject.
                    title = decision.match.title.ifBlank { leader.title },
                    existing = decision.match.dates,
                    proposed = proposed,
                )
            }
        }
    }

    private suspend fun findDuplicate(
        item: Item,
        defaults: AccountDefaults,
        metadata: RemoteMetadata,
    ) = when (item.type) {
        ItemType.EVENT -> calendarApi.findEventBySourceHash(
            calendarId = defaults.destinationCalendarId,
            sourceHash = metadata.sourceHash,
        )

        ItemType.TASK -> tasksApi.findTaskBySourceHash(
            taskListId = defaults.taskListId,
            sourceHash = metadata.sourceHash,
            due = item.dueDate,
        )
    }

    /**
     * The key is derived once, by the current §7.2 derivation, and searched for once.
     *
     * There is deliberately no second query under a superseded derivation to catch items
     * written before v1.14: §7.2 declares those unmatched, and looking for an old-style key
     * would quietly restore a wire contract this project has moved.
     */
    private suspend fun findReschedule(
        item: Item,
        defaults: AccountDefaults,
        metadata: RemoteMetadata,
    ) = when (item.type) {
        ItemType.EVENT -> calendarApi.findEventByItemKey(
            calendarId = defaults.destinationCalendarId,
            itemKey = metadata.itemKey,
        )

        ItemType.TASK -> tasksApi.findTaskByItemKey(
            taskListId = defaults.taskListId,
            itemKey = metadata.itemKey,
        )
    }

    private suspend fun create(
        items: List<Item>,
        defaults: AccountDefaults,
        metadata: RemoteMetadata,
        body: String,
        context: ParseContext,
        searchWasCapped: Boolean,
    ): SaveState {
        // No duplicate check between the items of one chain: they share a source_hash by
        // design, so item two would find item one and the chain would stop there.
        val created = items.map { item -> insert(item, defaults, metadata, body, context) }

        return SaveState.Saved(
            searchWasCapped = searchWasCapped,
            undo = undoWindowFor(metadata, created),
        )
    }

    private suspend fun insert(
        item: Item,
        defaults: AccountDefaults,
        metadata: RemoteMetadata,
        body: String,
        context: ParseContext,
    ): CreatedItem {
        val created = when (item.type) {
            ItemType.EVENT -> CreatedItem.Written(
                type = ItemType.EVENT,
                containerId = defaults.destinationCalendarId,
                remoteId = calendarApi.insertEvent(
                    calendarId = defaults.destinationCalendarId,
                    event = EventWrite(
                        summary = item.title,
                        description = body,
                        location = item.location,
                        start = requireNotNull(item.start),
                        end = requireNotNull(item.end),
                        allDay = item.allDay,
                        timeZone = context.zone.id,
                        metadata = metadata,
                    ),
                ),
            )

            ItemType.TASK -> CreatedItem.Written(
                type = ItemType.TASK,
                containerId = defaults.taskListId,
                remoteId = tasksApi.insertTask(
                    taskListId = defaults.taskListId,
                    task = TaskWrite(
                        title = item.title,
                        notes = body,
                        due = item.dueDate,
                        metadata = metadata,
                    ),
                ),
            )
        }

        return created
    }
}

/**
 * Everything an FR-804 offer needs in order to be answered either way.
 *
 * Both answers are possible from here: [match] and [proposed] carry out the update, and the
 * drafted item with its metadata and body carry out the create instead. Holding one object
 * rather than two means the create branch cannot quietly differ from the one the offer replaced.
 */
private data class PendingOffer(
    val items: List<Item>,
    val defaults: AccountDefaults,
    val metadata: RemoteMetadata,
    val body: String,
    val context: ParseContext,
    val match: RescheduleMatch,
    val proposed: ItemDates,
    /** Carried through so "saved, and it might be a second copy" survives the detour. */
    val duplicateWasCapped: Boolean,
)
