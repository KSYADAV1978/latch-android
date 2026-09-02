package com.latch.android.capture

import com.latch.core.model.CaptureSource
import com.latch.core.model.Item
import com.latch.core.model.ItemType
import com.latch.data.AccountDefaults
import com.latch.data.AccountDefaultsStore
import com.latch.data.CalendarApi
import com.latch.data.CaptureInbox
import com.latch.data.CreatedItem
import com.latch.data.DuplicateSearch
import com.latch.data.EventWrite
import com.latch.data.InboxCapture
import com.latch.data.InboxReason
import com.latch.data.ItemDates
import com.latch.data.LatchSettings
import com.latch.data.LocalItemIndex
import com.latch.data.RemoteMetadata
import com.latch.data.RescheduleMatch
import com.latch.data.StoredUndoOffer
import com.latch.data.TaskWrite
import com.latch.data.PendingWrite
import com.latch.data.TasksApi
import com.latch.data.UndoOfferStore
import com.latch.data.WriteOperation
import com.latch.data.WriteQueue
import com.latch.data.WebhookSender
import com.latch.data.WrittenItem
import com.latch.data.bodyWithNote
import com.latch.data.webhookEligible
import com.latch.data.webhookPayloadForChain
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

    /**
     * FR-506 row 3: a time with no day, and the row has not been given one.
     *
     * Reachable only where **every** ticked row is in that state; SRS 1.23 makes such a
     * candidate block itself and not its neighbours. The sheet's own picker (FR-506 row 3) and
     * FR-507's override are both ways out of it, and the Inbox is the third.
     */
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

    /** FR-506 row 3: a time with no day, and no row has been given one. */
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
    /**
     * FR-512: where the capture is going. An Inbox route needs no destination and cannot be
     * blocked for want of a date — the Inbox is precisely where a capture with no date goes
     * (FR-506 row 4, AC-03), and refusing to put it there would lose it.
     */
    route: SaveRoute = SaveRoute.Google,
): SaveBlocker? = when {
    result == null -> SaveBlocker.NEEDS_A_DATE
    saveState !is SaveState.Idle -> SaveBlocker.NOT_IDLE
    route is SaveRoute.Inbox -> null
    destination is DestinationState.Loading -> SaveBlocker.READING_DESTINATION
    destination is DestinationState.None -> SaveBlocker.NO_DESTINATION
    draftBlocker(result) == DraftBlocker.NEEDS_A_DATE -> SaveBlocker.NEEDS_A_DATE
    else -> null
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

    /**
     * FR-512, FR-506 rows 3 and 4, AC-03: the capture is in the Capture Inbox and **nothing
     * was written to Google** (FR-703).
     *
     * Deliberately not a [Saved], for the reason [Queued] is not one either: saying "saved"
     * about something that is not in the user's account is the swallowed failure NFR-303
     * forbids, dressed as success. It carries no undo window — nothing left the device, and
     * the Inbox's own Discard is the way back.
     */
    data class SentToInbox(val reason: InboxReason) : SaveState

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
    is SaveState.SentToInbox,
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
    /** FR-701: where a capture goes when FR-512 says it is not to be written yet. */
    private val inbox: CaptureInbox,
    /**
     * FR-803's local index. Consulted **after** Google, never instead of it — see
     * [LocalItemIndex], and [findDuplicate] below for the two places it actually answers.
     */
    private val index: LocalItemIndex,
    /** FR-807: the offer, written down so it survives the process that made it. */
    private val undoOffers: UndoOfferStore,
    /**
     * FR-806: asks for a drain. A function rather than the `WorkManager` itself, so this
     * class stays free of Android — everything else it touches is a `:data` contract, and
     * `CaptureSaverTest` runs on the JVM.
     */
    private val requestDrain: () -> Unit,
    /**
     * FR-806: a foreground request to Google just succeeded, so the transport demonstrably
     * works. Any queue entry backing off after a transport failure could go now, and
     * `shouldDrainNow` is what decides whether that is worth acting on.
     */
    private val onGoogleReached: () -> Unit = {},
    private val scope: CoroutineScope,
    /**
     * FR-805's link back to the source, as a format string taking the source application.
     * Passed in because it is user-visible text and NFR-402 keeps that in `strings.xml` —
     * the same arrangement as the Latch calendar's name in `SetupCoordinator`.
     */
    private val sourceLinkTemplate: String,
    /**
     * FR-510's note, as a format string taking the past date. Here for the same reason
     * [sourceLinkTemplate] is: it is text the user reads, and NFR-402 keeps that in
     * `strings.xml` rather than in a module that has no access to resources.
     */
    private val pastDateNoteTemplate: String = "",
    /**
     * FR-1001. A function rather than a value because Settings can change while the process
     * lives, and a saver holding a snapshot from launch would keep writing the old settings.
     */
    private val settings: () -> LatchSettings = { LatchSettings() },
    /**
     * FR-1004, and it is a function for a stronger reason than the settings are: the endpoint is
     * a secret read from the Keystore, and holding it in a field would keep it in memory for
     * the life of the process whether or not a webhook was ever sent.
     */
    private val webhookEndpoint: suspend () -> String? = { null },
    /** FR-1004b: one attempt, best-effort, never blocking the Google write. */
    private val webhooks: WebhookSender = WebhookSender(),
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
    /**
     * @param fromInboxId FR-702/FR-703: this save is the user confirming a row that was
     *   already in the Inbox. It forces the route to Google — routing it back to the Inbox it
     *   came from would be a loop with a button on it — and the row is discarded once the
     *   write lands, because the item now exists in the account and the Inbox holds only what
     *   has not been confirmed.
     */
    fun save(
        captured: CapturedText,
        result: ParseResult,
        context: ParseContext,
        selected: Set<Int> = result.candidates.indices.toSet(),
        fromInboxId: String? = null,
        /**
         * FR-601: the recipe the user applied, and the steps they left ticked (FR-608).
         *
         * Where one is present the chain **replaces** the FR-511 candidate list rather than
         * adding to it — a recipe expands one captured date (`recipeBlocker` refuses the rest),
         * so there is one date and its expansion is what gets written.
         */
        recipe: RecipeApplication? = null,
        /**
         * FR-904: the destination the user chose on the sheet, where they changed it. Null is
         * "whatever FR-905's rules and the account defaults resolve to", which is every capture
         * they did not touch.
         */
        destination: Destination? = null,
    ) {
        if (_state.value == SaveState.Saving) return
        _state.value = SaveState.Saving
        scope.launch { perform(captured, result, context, selected, fromInboxId, recipe, destination) }
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

    /**
     * So a second capture in the same process does not open on the last one's outcome.
     *
     * **[captureKey] is what tells a new capture from the same one arriving again**, and it is
     * load-bearing rather than tidy. `CaptureActivity` is recreated on rotation and on its own
     * `recreate()`, and an unconditional reset here meant a rotation inside FR-807's ten
     * seconds silently ended the offer — the requirement met on paper and not in the hand, in
     * exactly the way the touch-outside suppression exists to prevent. Same key, same capture:
     * the state stands.
     *
     * A genuinely new capture still ends the offer, which is FR-807's own recorded limit: the
     * countdown belonged to a save the user has moved on from. The **stored** offer is dropped
     * with it, so the home screen does not go on offering an undo for a capture that has been
     * replaced.
     *
     * A save or an undo already in flight is left alone — it has reached Google and its outcome
     * still has to be reported.
     */
    fun reset(captureKey: String? = null) {
        val current = _state.value
        if (current == SaveState.Saving || current == SaveState.Undoing) return
        if (captureKey != null && captureKey == lastCaptureKey) return

        undoWindowOf(current)?.let { window ->
            scope.launch { undoOffers.forget(window.chainId) }
        }
        lastCaptureKey = captureKey
        // An offer the user walked away from wrote nothing, and must not be answerable
        // by a tap belonging to the next capture.
        offer = null
        _state.value = SaveState.Idle
    }

    private var lastCaptureKey: String? = null

    private suspend fun perform(
        captured: CapturedText,
        result: ParseResult,
        context: ParseContext,
        selected: Set<Int>,
        fromInboxId: String? = null,
        recipe: RecipeApplication? = null,
        destination: Destination? = null,
    ) {
        val source = CaptureSource(
            layer = captured.layer,
            appId = captured.appId,
            ocrUsed = captured.ocrUsed,
        )

        // FR-512, and the end of its interim reading. A capture already confirmed out of the
        // Inbox is not routed back into it.
        val route = when {
            fromInboxId != null -> SaveRoute.Google
            // FR-601: applying a recipe is a stronger confirmation than the FR-512 threshold is
            // testing for — the user picked a template against a date they can see on screen —
            // and routing it to the Inbox would discard the chain they chose along the way.
            recipe != null -> SaveRoute.Google
            else -> saveRoute(result, selected, context.confidenceThreshold, source)
        }

        if (route is SaveRoute.Inbox) {
            sendToInbox(captured, result, context, route.reason)
            return
        }

        val configured = try {
            defaultsStore.allAccounts().firstOrNull()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (unreadable: Exception) {
            null
        }
        if (configured == null) {
            _state.value = SaveState.Failed(SaveFailure.NO_DESTINATION)
            return
        }

        // FR-905, FR-906: the destination this capture actually goes to, decided the same way
        // the confirmation screen decided it — one pure function, so the chip the user saw and
        // the calendar the write reaches cannot differ. An override the user made on the sheet
        // wins over any rule, which is what "changeable in one action" means.
        val routed = destination ?: destinationFor(
            defaults = configured,
            settings = settings(),
            sourceApp = captured.appId,
            recipeId = recipe?.recipeId,
            captureText = captured.text,
        )
        val defaults = configured.copy(
            destinationCalendarId = routed.calendarId,
            destinationCalendarName = routed.calendarName,
            destinationCalendarColour = routed.calendarColour,
            taskListId = routed.taskListId,
        )

        // Minted here rather than inside the mapping, so that stays deterministic under test.
        val captureId = UUID.randomUUID().toString()
        val chainId = UUID.randomUUID().toString()
        val capturedAt = Instant.now()

        val items = if (recipe != null) {
            // FR-601/FR-607/FR-608. The chain replaces the candidate list; `recipeItems` is the
            // one place a chain's destination is chosen, which is what makes FR-607 structural.
            recipeItems(
                planned = recipe.planned,
                selected = recipe.selected,
                captureId = captureId,
                chainId = chainId,
                defaults = defaults,
                context = context,
                defaultReminderMinutes = settings().defaultReminderMinutes,
            ).ifEmpty {
                // FR-608 with everything unticked. Nothing to write, and reporting it is better
                // than a save that appears to succeed and creates nothing.
                _state.value = SaveState.Failed(SaveFailure.NEEDS_A_DATE)
                return
            }
        } else {
            val draft = draftItems(
                captured = captured,
                result = result,
                context = context,
                defaults = defaults,
                captureId = captureId,
                chainId = chainId,
                selected = selected,
                pastDateNoteTemplate = pastDateNoteTemplate,
            )
            if (draft is DraftResult.Blocked) {
                _state.value = SaveState.Failed(SaveFailure.NEEDS_A_DATE)
                return
            }
            (draft as DraftResult.Ready).items
        }

        // ocrUsed reaches FR-805b through sourceBlock below: an OCR capture's description
        // carries an extract around the dates, not everything the recogniser saw.
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
            // §7.2's `latch.recipe`, at last written by something. Absent where no recipe was
            // applied, never empty — the rule §7.2 applies to every optional key.
            recipeId = recipe?.recipeId,
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
            write(items, defaults, metadata, body, context).also { onGoogleReached() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // FR-806: offline is a delay, not a failure. Anything that will not come right
            // on its own still is one, and is reported rather than hidden in a queue the
            // user would watch never drain.
            if (isWorthRetrying(failure)) {
                offlineFallback(captured, result, context, items, metadata, body)
            } else {
                SaveState.Failed(SaveFailure.WRITE_FAILED)
            }
        }

        // FR-703: the Inbox holds what has not been confirmed. Once the write has landed — or
        // is queued to land, which is a promise the queue keeps under NFR-302 — the row has
        // done its job. A failure leaves it exactly where it was, which is the point of it.
        if (fromInboxId != null && (outcome is SaveState.Saved || outcome is SaveState.Queued)) {
            inbox.discard(fromInboxId)
        }

        // FR-1004b, every clause of it. **After** the write and never awaited, so it cannot
        // block or delay the Google write; a single attempt with no retry; not in FR-806's
        // queue, which it structurally could not enter — everything there drains through the
        // `ALLOWED_HOSTS` guard and would be refused for a non-Google host. A failure is not a
        // failed API write under NFR-303 and is not reported as one.
        //
        // AC-21's consequence follows from where this sits: a capture saved offline is queued
        // and reaches Google later, and **no webhook is ever sent for it**, because the one
        // attempt happened here and failed.
        if (outcome is SaveState.Saved) {
            deliverWebhook(items, metadata, body, source)
        }

        settle(outcome)
    }

    /**
     * FR-1004, FR-1004a, FR-1004b and FR-210a.
     *
     * Launched rather than awaited, in the application scope the saver already runs in, so the
     * capture's outcome is published without waiting on someone else's server. FR-210a's
     * suppression is inside [webhookEligible] rather than here, so a second call site could not
     * forget it — the same reason `sourceBlock` composes every description.
     */
    private fun deliverWebhook(
        items: List<Item>,
        metadata: RemoteMetadata,
        body: String,
        source: CaptureSource,
    ) {
        scope.launch {
            val endpoint = runCatching { webhookEndpoint() }.getOrNull()
            if (!webhookEligible(source, settings().webhookEnabled, endpoint)) return@launch
            runCatching {
                webhooks.deliver(
                    endpoint = requireNotNull(endpoint),
                    payload = webhookPayloadForChain(items, metadata, body),
                )
            }
        }
    }

    /**
     * FR-701, FR-512: the capture goes to the Inbox and nothing is written (FR-703).
     *
     * The capture's own `now` and zone travel with it, and that is the load-bearing part. The
     * Inbox re-parses when the row is opened, and re-parsing against *today's* clock would
     * resolve "kal" one day further every time the user looked — design principle 1's failure
     * inverted, not inventing a date but quietly moving one. FR-515 makes a parse a pure
     * function of its text and its context, so carrying the context is what makes the reading
     * reproducible.
     */
    private suspend fun sendToInbox(
        captured: CapturedText,
        result: ParseResult,
        context: ParseContext,
        reason: InboxReason,
    ) {
        val outcome = try {
            inbox.add(
                InboxCapture(
                    id = UUID.randomUUID().toString(),
                    rawText = captured.text,
                    layer = captured.layer,
                    appId = captured.appId,
                    preferredTitle = captured.preferredTitle,
                    ocrUsed = captured.ocrUsed,
                    capturedAt = Instant.now(),
                    capturedLocal = context.now,
                    zone = context.zone.id,
                    confidence = result.overallConfidence.value,
                    reason = reason,
                )
            )
            SaveState.SentToInbox(reason)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // NFR-303. There is no queue for this — the Inbox *is* the local store — so a
            // failure here means the capture is nowhere, and saying so is the whole of what
            // can be done about it.
            SaveState.Failed(SaveFailure.WRITE_FAILED)
        }
        settle(outcome)
    }

    /**
     * What to do with a write that could not reach Google, now that there is somewhere other
     * than the queue to put it.
     *
     * **FR-804's offline limitation gets the cure its own note names.** That note records: "an
     * offline reschedule therefore becomes a second item, and the recourse is to remove one by
     * hand… **The Inbox is the cure**, and this should be revisited when FR-701 lands." It has
     * landed. Where the local index says an item with this `latch.item_key` already stands at a
     * different date, queueing a create would write the second item that note describes — so
     * the capture waits in the Inbox instead, and the question is asked when it can be answered.
     *
     * **The index decides only whether there is a question worth asking.** The answer, and the
     * prior state an undo would need, are read from Google at match time when the row is
     * confirmed — SRS 1.19 requires that, and an index row records what Latch wrote rather than
     * what the item holds now.
     *
     * Confined to a single-candidate capture, because SRS 1.25 confines FR-804's offer to one.
     * A multi-date capture is queued as it always was.
     */
    private suspend fun offlineFallback(
        captured: CapturedText,
        result: ParseResult,
        context: ParseContext,
        items: List<Item>,
        metadata: RemoteMetadata,
        body: String,
    ): SaveState {
        val source = CaptureSource(captured.layer, captured.appId, captured.ocrUsed)
        if (items.size == 1 && source.routableToInbox && looksLikeOfflineReschedule(items.first(), metadata, context)) {
            sendToInbox(captured, result, context, InboxReason.RESCHEDULE_UNRESOLVED)
            // sendToInbox has already settled the state; returning it keeps the caller's
            // single `settle` harmless — it publishes the same value a second time.
            return _state.value
        }
        return queue(items, metadata, body, context)
    }

    private suspend fun looksLikeOfflineReschedule(
        leader: Item,
        metadata: RemoteMetadata,
        context: ParseContext,
    ): Boolean {
        val proposed = itemDatesOf(leader, context.zone.id)
        val known = try {
            index.byItemKey(metadata.itemKey)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (unreadable: Exception) {
            // An index that cannot be read is an index that knows nothing, which is the
            // answer it gives for an unseen capture anyway. The queue is the fallback either
            // way, and losing the capture is not on the table.
            emptyList()
        }
        return known.any { it.type == leader.type && !movesNothing(it.dates, proposed) }
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
        // FR-807, and the limit its own note recorded as needing FR-701's storage: written
        // down before the wait, so a process death inside the ten seconds no longer loses the
        // offer. The home screen picks it up where the capture window has gone.
        undoOffers.remember(StoredUndoOffer(window.chainId, window.expiresAt, window.created))

        delay(UNDO_WINDOW.toMillis())
        val current = _state.value
        if (undoWindowOf(current)?.chainId != window.chainId) return
        // Lapsed untaken: the save stands and there is nothing left to offer, here or on the
        // home screen. The store's own sweep would catch it, but leaving it to that would mean
        // a stale offer visible for as long as nothing looked.
        undoOffers.forget(window.chainId)
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
        val outcome = removeCreated(window.created, calendarApi, tasksApi, writeQueue, index)
        // The offer is spent either way. A partial removal leaves items in the account, and
        // re-offering an undo that would try the same deletes again is not the recourse —
        // NFR-303's message says to remove the rest in Google by hand, which is.
        undoOffers.forget(window.chainId)

        _state.value = if (outcome.complete) {
            SaveState.Undone
        } else {
            SaveState.UndoFailed(removed = outcome.removed, total = outcome.total)
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
        // SRS 1.25, and a recipe chain is covered by it for the same reason a multi-date
        // capture is: accepting a reschedule offer would patch one item and write none of the
        // others. A chain of one — a recipe with a single step — is still offerable, which is
        // correct: there is nothing else to discard.
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

    /**
     * FR-803, asked of Google first and of the local index only where Google could not answer.
     *
     * **The order is the whole of the design.** Google sees what every client of §4.1 wrote;
     * the index sees what this device wrote. Consulting the index first would be faster and
     * would answer "already saved" about an item the user had since deleted by hand in Google
     * Calendar — leaving them unable to capture it again, with no recourse and nothing on
     * screen to explain it. FR-807's own note is careful about the mirror-image case: "an item
     * the user undid does not prevent them capturing it again."
     *
     * So the index answers exactly one question, and it is the one Google cannot: **the scan
     * stopped looking.** `DuplicateSearch.scanCapped` is the Tasks API's honest admission that
     * it read ten pages and gave up, and FR-803's own note names the cure — "a local index of
     * source hashes… deferred because it needs local storage that does not yet exist; it
     * should be revisited when the Capture Inbox (FR-701) brings that storage with it."
     *
     * A capped scan that the index *can* answer is upgraded from "may be a duplicate" to
     * "is one". A capped scan the index cannot answer stays capped, and the user still gets
     * the "could not check every task" warning rather than a false all-clear.
     */
    private suspend fun findDuplicate(
        item: Item,
        defaults: AccountDefaults,
        metadata: RemoteMetadata,
    ): DuplicateSearch {
        val remote = when (item.type) {
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
        if (remote.found || !remote.scanCapped) return remote

        val known = try {
            index.bySourceHash(metadata.sourceHash)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (unreadable: Exception) {
            null
        }
        // Still capped where the index knows nothing: the search did stop looking, and saying
        // otherwise would be the false negative `scanCapped` exists to refuse.
        return known?.let { DuplicateSearch(existingId = it.remoteId, scanCapped = false) } ?: remote
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
                        // FR-510's past date leads the FR-805 body where there is one. An event
                        // never carries it today — a follow-up is a task — but the composition
                        // is the same on both transports so it cannot drift.
                        description = bodyWithNote(item.notes, body),
                        location = item.location,
                        start = requireNotNull(item.start),
                        end = requireNotNull(item.end),
                        allDay = item.allDay,
                        timeZone = context.zone.id,
                        reminderMinutes = item.reminderMinutes,
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
                        // FR-510: "with the past date recorded in the notes".
                        notes = bodyWithNote(item.notes, body),
                        due = item.dueDate,
                        metadata = metadata,
                    ),
                ),
            )
        }

        // FR-803's index, after the insert and never before it: a row here asserts that an item
        // exists in the user's account, and one written speculatively would suppress a save
        // that never happened. Failing to remember costs a fast path, so it is swallowed —
        // failing the save over a cache would be the tail wagging the dog.
        runCatching {
            index.remember(
                WrittenItem(
                    remoteId = created.remoteId,
                    containerId = created.containerId,
                    type = item.type,
                    sourceHash = metadata.sourceHash,
                    itemKey = metadata.itemKey,
                    dates = itemDatesOf(item, context.zone.id),
                    writtenAt = Instant.now(),
                )
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
