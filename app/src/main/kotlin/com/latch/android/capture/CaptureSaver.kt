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
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
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

sealed interface SaveState {
    data object Idle : SaveState
    data object Saving : SaveState

    /**
     * [searchWasCapped] carries `DuplicateSearch.scanCapped` up to the UI rather than
     * discarding it: the item was written, but the duplicate check gave up before it had
     * read everything, so this is "saved, and it might be a second copy".
     */
    data class Saved(val searchWasCapped: Boolean = false) : SaveState

    /** FR-803: an item with this source hash is already in the account. Nothing was written. */
    data object AlreadySaved : SaveState

    data class Failed(val reason: SaveFailure) : SaveState
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

    /** So a second capture in the same process does not open on the last one's outcome. */
    fun reset() {
        if (_state.value != SaveState.Saving) _state.value = SaveState.Idle
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
            itemKey = itemKeyOf(items.first().title),
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

        _state.value = try {
            write(items.first(), defaults, metadata, body, context)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            SaveState.Failed(SaveFailure.WRITE_FAILED)
        }
    }

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
                calendarApi.insertEvent(
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
                SaveState.Saved(searchWasCapped = existing.scanCapped)
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
                tasksApi.insertTask(
                    taskListId = defaults.taskListId,
                    task = TaskWrite(
                        title = item.title,
                        notes = body,
                        due = item.dueDate,
                        metadata = metadata,
                    ),
                )
                SaveState.Saved(searchWasCapped = existing.scanCapped)
            }
        }
    }
}
