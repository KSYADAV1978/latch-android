package com.latch.android.capture

import com.latch.core.model.CaptureSource
import com.latch.core.model.Item
import com.latch.core.model.ItemType
import com.latch.data.AccountDefaults
import com.latch.data.AccountDefaultsStore
import com.latch.data.CalendarApi
import com.latch.data.EventWrite
import com.latch.data.RemoteMetadata
import com.latch.data.TaskWrite
import com.latch.data.TasksApi
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
 * One item a save created, and the whole of what is needed to remove it again.
 *
 * [containerId] is the calendar id for an event and the task list id for a task — both APIs
 * address an item by its container and its own id, and neither can be deleted by id alone.
 */
data class CreatedItem(
    val type: ItemType,
    val containerId: String,
    val remoteId: String,
)

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

    /** FR-803: an item with this source hash is already in the account. Nothing was written. */
    data object AlreadySaved : SaveState

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
fun undoOffer(state: SaveState, now: Instant): UndoWindow? =
    (state as? SaveState.Saved)?.undo?.takeIf { it.isOpen(now) }

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
    is SaveState.Saved,
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

    fun save(captured: CapturedText, result: ParseResult, context: ParseContext) {
        if (_state.value == SaveState.Saving) return
        _state.value = SaveState.Saving
        scope.launch { perform(captured, result, context) }
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
    fun reset() {
        val current = _state.value
        if (current != SaveState.Saving && current != SaveState.Undoing) {
            _state.value = SaveState.Idle
        }
    }

    private suspend fun perform(captured: CapturedText, result: ParseResult, context: ParseContext) {
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

        val draft = draftItems(captured, result, context, defaults, captureId, chainId)
        if (draft is DraftResult.Blocked) {
            _state.value = SaveState.Failed(SaveFailure.NEEDS_A_DATE)
            return
        }
        val items = (draft as DraftResult.Ready).items

        val source = CaptureSource(layer = captured.layer, appId = captured.appId)
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
        // FR-805, and FR-805a for the notification layer — composed once, here, so no call
        // site can compose it differently.
        val body = sourceBlock(
            source = source,
            sourceText = captured.text,
            sourceLink = captured.appId?.let { String.format(sourceLinkTemplate, it) },
        )

        val outcome = try {
            write(items.first(), defaults, metadata, body, context)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            SaveState.Failed(SaveFailure.WRITE_FAILED)
        }
        _state.value = outcome

        // FR-807: the offer closes on its own after ten seconds. Waited out in this
        // coroutine rather than a job of its own, because the check below is what actually
        // decides — anything that has moved the state on since (a new capture's reset, or
        // the user taking the offer) has already ended this window, and finds nothing to do.
        val window = (outcome as? SaveState.Saved)?.undo ?: return
        delay(UNDO_WINDOW.toMillis())
        val current = _state.value
        if (current is SaveState.Saved && current.undo?.chainId == window.chainId) {
            _state.value = current.copy(undo = null)
        }
    }

    private suspend fun remove(window: UndoWindow) {
        var removed = 0
        var failed = false

        // The loop does not stop at the first failure: a chain half in the account is worse
        // than one that is wholly there or wholly gone, so every item still gets its delete.
        for (item in window.created) {
            try {
                when (item.type) {
                    ItemType.EVENT -> calendarApi.deleteEvent(item.containerId, item.remoteId)
                    ItemType.TASK -> tasksApi.deleteTask(item.containerId, item.remoteId)
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

    /** FR-803 first, then FR-801. The order is the requirement: check before writing. */
    private suspend fun write(
        item: Item,
        defaults: AccountDefaults,
        metadata: RemoteMetadata,
        body: String,
        context: ParseContext,
    ): SaveState = when (item.type) {
        ItemType.EVENT -> {
            val existing = calendarApi.findEventBySourceHash(
                calendarId = defaults.destinationCalendarId,
                sourceHash = metadata.sourceHash,
            )
            if (existing.found) {
                SaveState.AlreadySaved
            } else {
                val remoteId = calendarApi.insertEvent(
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
                )
                SaveState.Saved(
                    searchWasCapped = existing.scanCapped,
                    undo = undoWindowFor(
                        metadata,
                        listOf(
                            CreatedItem(
                                type = ItemType.EVENT,
                                containerId = defaults.destinationCalendarId,
                                remoteId = remoteId,
                            )
                        ),
                    ),
                )
            }
        }

        ItemType.TASK -> {
            val existing = tasksApi.findTaskBySourceHash(
                taskListId = defaults.taskListId,
                sourceHash = metadata.sourceHash,
                due = item.dueDate,
            )
            if (existing.found) {
                SaveState.AlreadySaved
            } else {
                val remoteId = tasksApi.insertTask(
                    taskListId = defaults.taskListId,
                    task = TaskWrite(
                        title = item.title,
                        notes = body,
                        due = item.dueDate,
                        metadata = metadata,
                    ),
                )
                SaveState.Saved(
                    searchWasCapped = existing.scanCapped,
                    undo = undoWindowFor(
                        metadata,
                        listOf(
                            CreatedItem(
                                type = ItemType.TASK,
                                containerId = defaults.taskListId,
                                remoteId = remoteId,
                            )
                        ),
                    ),
                )
            }
        }
    }
}
