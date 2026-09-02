package com.latch.android.capture

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.latch.android.BuildConfig
import com.latch.android.LatchApplication
import com.latch.android.R
import com.latch.android.ui.CaptureScreen
import com.latch.android.ui.LatchTheme
import com.latch.ocr.OcrFailure
import com.latch.ocr.OcrResult
import com.latch.ocr.PageProgress
import com.latch.core.model.CaptureLayer
import com.latch.core.model.CaptureSource
import com.latch.android.ui.RuleOffer
import com.latch.parser.Confidence
import com.latch.parser.DateOrder
import com.latch.parser.DateParser
import com.latch.parser.ParseContext
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * What this screen has to show, which since FR-215 is not always known when it opens.
 *
 * NFR-102 requires the confirmation UI to render before the content is ready rather than
 * delaying display, and this type is what lets it: [Extracting] is a state the screen can
 * draw, where before there were only a capture and its parse.
 */
sealed interface CaptureContent {
    /**
     * FR-215: an image or PDF is being recognised. Only ever reached by an OCR capture.
     *
     * [pages] is null for an image, which is one piece of work with nothing to count, and
     * present for a PDF once the first page starts — NFR-101a records a 10-page document at
     * about five seconds, which is long enough that "something is happening" stops being
     * enough to say.
     */
    data class Extracting(val pages: PageProgress? = null) : CaptureContent

    /** Everything that is known. [captured] is null where nothing usable arrived. */
    data class Ready(val captured: CapturedText?) : CaptureContent

    /** FR-215: recognition ran and produced nothing usable. */
    data class Failed(val reason: OcrFailure) : CaptureContent
}

/**
 * The confirmation screen behind every capture layer (§5.2).
 *
 * **A text capture is parsed synchronously, exactly as it was before FR-215.** The parse
 * touches no I/O, which is what keeps NFR-101's 800 ms budget from gesture to confirmation
 * achievable — and why the FR-904 destination is read from stored defaults rather than
 * fetched. An image or a PDF cannot be: NFR-101 allows it 2.5 s, which is a wait the user
 * watches rather than sits behind a blank window, so it renders first and fills in.
 *
 * The gating is deliberate and is recorded against NFR-102 in the SRS. The risk of adding an
 * asynchronous path is that it quietly captures the synchronous one, which is why a device
 * pass re-verifies an ordinary text capture end to end whenever this file is touched.
 */
class CaptureActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as LatchApplication

        val request = intent.toCaptureRequest(this, app.notificationCaptures::take)

        // A previous capture in this process may have left an outcome on screen — but a
        // *recreation* of this one has not. Keying the reset on what was captured is what
        // stops a rotation inside FR-807's ten seconds silently ending the offer, which is the
        // requirement met on paper and not in the hand. See `CaptureSaver.reset`.
        app.captureSaver.reset(captureKeyOf(request))
        // Setup may have completed in another task since this process read its defaults.
        app.refreshAccounts()

        val openedAt = SystemClock.elapsedRealtime()
        // Read once, here. Everything below derives from it, so a settings change arriving
        // mid-composition cannot move the capture's own `now` — FR-515 makes a parse a pure
        // function of its text and its context, and a context that moved under it would make
        // the same capture read differently on successive frames.
        val capturedAt = Instant.now()
        val referrer = referrerPackage()

        // FR-1003: a capture arriving through a layer the user has switched off. Evaluated
        // against whatever settings are loaded, which on a cold start is briefly the defaults —
        // see the note on `parseContext` below.
        val layer = request.layerOf()

        // Text is resolved before the first frame, as it always was. Only an OCR capture
        // starts as Extracting and arrives later.
        val content = MutableStateFlow<CaptureContent>(
            when (request) {
                is CaptureRequest.Ready -> CaptureContent.Ready(request.captured.copy(appId = referrer))
                is CaptureRequest.Nothing -> CaptureContent.Ready(null)
                is CaptureRequest.Image, is CaptureRequest.Pdf -> CaptureContent.Extracting()
            }
        )
        if (request is CaptureRequest.Image || request is CaptureRequest.Pdf) {
            extract(request, referrer, content, openedAt)
        } else {
            logContentReady(openedAt, content.value)
        }

        setContent {
            LatchTheme {
                val destinations by app.configuredAccounts.collectAsState()
                val saveState by app.captureSaver.state.collectAsState()
                val captureContent by content.collectAsState()
                val settings by app.settings.collectAsState()

                /*
                 * FR-1001 reaches the parser here: the date order (FR-504), the default event
                 * duration, the FR-512 threshold and the time zone.
                 *
                 * **On a cold start the first frames use the shipped defaults**, because the
                 * settings record is read asynchronously and a capture may be the first thing
                 * in the process. The sheet re-parses when it arrives, which is NFR-102's own
                 * pattern; Save is blocked meanwhile by the destination read, which is a round
                 * trip to the same encrypted store and lands at the same time. Recorded rather
                 * than left to be found: the alternative is a blocking read on the main thread
                 * or an asynchronous text path, and NFR-102's note is explicit that the risk of
                 * adding one is that it quietly captures the synchronous one.
                 */
                val parseContext = remember(settings) {
                    val zone = settings.timeZone
                        ?.let { runCatching { ZoneId.of(it) }.getOrNull() }
                        ?: ZoneId.systemDefault()
                    ParseContext(
                        now = capturedAt.atZone(zone).toLocalDateTime(),
                        zone = zone,
                        dateOrder = if (settings.dayFirstDates) DateOrder.DAY_FIRST else DateOrder.MONTH_FIRST,
                        defaultEventDuration = settings.defaultEventDuration,
                        confidenceThreshold = Confidence(settings.confidenceThreshold),
                    )
                }
                val today = parseContext.now.toLocalDate()
                val layerDisabled = layer != null && layer !in settings.enabledLayers

                HoldWindowOpenForUndo(saveState)

                val captured = (captureContent as? CaptureContent.Ready)?.captured
                // Parsed here rather than beside the extraction so that both paths reach the
                // parser by exactly one route. `remember` keyed on the text keeps a
                // recomposition from re-parsing; it is cheap, but it is not free and the
                // FR-807 countdown recomposes this screen every 200 ms.
                val parsed = remember(captured?.text) {
                    captured?.let { DateParser.parse(it.text, parseContext) }
                }

                // FR-506 row 3 and FR-507: what the user has changed on this sheet. Held here
                // rather than by the screen because the saver has to write exactly what the
                // screen showed, and the way to guarantee that is for both to read one value.
                // Keyed on the parse so a capture arriving over this one starts clean.
                var edits by remember(parsed) { mutableStateOf(SheetEdits()) }

                // Applied in one place, so the badge, the checkbox, the blocker and the write
                // all see the same rows. A screen that applied them itself would eventually
                // show one thing and save another — the divergence a confirmation screen
                // exists to make impossible.
                val result = remember(parsed, edits, today) {
                    parsed?.withEdits(edits, today)
                }

                // FR-904, FR-905, FR-906: where this capture goes, decided the same way the
                // saver decides it — one pure function over the same inputs, so the chip the
                // user reads and the calendar the write reaches cannot differ.
                var chosenDestination by remember(parsed) { mutableStateOf<Destination?>(null) }
                var showingDestinations by remember(parsed) { mutableStateOf(false) }
                var ruleOffer by remember(parsed) { mutableStateOf<RuleOffer?>(null) }

                val account = destinations?.firstOrNull()
                val routed = remember(account, settings, captured, chosenDestination) {
                    chosenDestination ?: account?.let {
                        destinationFor(
                            defaults = it,
                            settings = settings,
                            sourceApp = captured?.appId,
                            recipeId = null,
                            captureText = captured?.text.orEmpty(),
                        )
                    }
                }

                // FR-511: every date starts ticked, and the choice belongs to this screen
                // rather than to the saver — nothing is written until Save, so there is
                // nothing for the saver to hold. Keyed on the parse so a capture arriving
                // over this one starts ticked again rather than inheriting a stale set.
                var selected by remember(result) {
                    mutableStateOf(result?.candidates?.indices?.toSet() ?: emptySet())
                }

                // FR-601: the recipe the user picked, and the chain it produces. Held here
                // beside the other sheet edits, and keyed on the parse so a capture arriving
                // over this one does not inherit the previous one's chain.
                val recipes by app.recipes.collectAsState()
                var appliedRecipeId by remember(parsed) { mutableStateOf<String?>(null) }

                // One chain id for the life of this expansion, so the ids the screen shows and
                // the ids a save writes are the same ones. The saver mints its own for the
                // metadata; this one only groups the display.
                val expansionChainId = remember(parsed) { UUID.randomUUID().toString() }

                val recipeSteps = remember(result, appliedRecipeId, settings) {
                    val recipe = recipes.firstOrNull { it.id == appliedRecipeId }
                    if (recipe == null || result == null) {
                        emptyList()
                    } else {
                        expandRecipe(
                            recipe = recipe,
                            result = result,
                            settings = settings,
                            bundledHolidays = app.bundledHolidays(today),
                            chainId = expansionChainId,
                        ).orEmpty()
                    }
                }

                // FR-608: every step ticked to begin with, the same as FR-511's dates.
                var recipeSelection by remember(recipeSteps) {
                    mutableStateOf(recipeSteps.indices.toSet())
                }

                // FR-512: where this capture is going, decided once and read by the button,
                // the destination chip and the saver alike. A screen that worked it out its own
                // way would eventually offer Save and perform something else.
                val route = remember(result, selected, captured) {
                    if (captured == null || result == null) {
                        SaveRoute.Google
                    } else {
                        saveRoute(
                            result = result,
                            selected = selected,
                            threshold = parseContext.confidenceThreshold,
                            source = CaptureSource(captured.layer, captured.appId, captured.ocrUsed),
                        )
                    }
                }

                CaptureScreen(
                    captured = captured,
                    result = result,
                    route = route,
                    onDismiss = { finish() },
                    // Three states, not a nullable value: not-yet-read and none-configured
                    // are opposite facts, and only the second is worth telling the user.
                    destination = when {
                        destinations == null -> DestinationState.Loading
                        account == null -> DestinationState.None
                        // The routed destination, not the raw account default: FR-906 requires
                        // the calendar shown to be the calendar written to.
                        else -> DestinationState.Ready(
                            account.copy(
                                destinationCalendarId = routed?.calendarId ?: account.destinationCalendarId,
                                destinationCalendarName = routed?.calendarName ?: account.destinationCalendarName,
                                destinationCalendarColour = routed?.calendarColour ?: account.destinationCalendarColour,
                            )
                        )
                    },
                    saveState = saveState,
                    // FR-512, interim: shown rather than blocking the save, until the
                    // Capture Inbox exists to send it to instead.
                    lowConfidence = result != null &&
                        result.overallConfidence < parseContext.confidenceThreshold,
                    selected = selected,
                    onToggleCandidate = { index ->
                        selected = if (index in selected) selected - index else selected + index
                    },
                    // FR-506 row 3. The row becomes tickable the moment it has a day, and is
                    // ticked here rather than waiting for a second tap: the user has just
                    // finished it, and leaving it unticked would make the picker feel inert.
                    onAssignDate = { index, date ->
                        edits = edits.copy(assignedDates = edits.assignedDates + (index to date))
                        selected = selected + index
                    },
                    // FR-507.
                    onOverrideType = { index, type ->
                        edits = edits.copy(typeOverrides = edits.typeOverrides + (index to type))
                    },
                    // FR-509b. A null index is the header title, which governs every item this
                    // capture creates, so it is applied to every candidate — which is what the
                    // screen is already showing. An index is one FR-509a row.
                    onEditTitle = { index, text ->
                        val overrides = if (index == null) {
                            result?.candidates?.indices?.associateWith { text }.orEmpty()
                        } else {
                            edits.titleOverrides + (index to text)
                        }
                        edits = edits.copy(titleOverrides = overrides)
                    },
                    titleOverrides = edits.titleOverrides,
                    today = today,
                    layerDisabled = layerDisabled,
                    // FR-1005. Drafted through exactly the same path a save uses, so an
                    // exported file and a written item cannot describe different things.
                    onExportIcs = onExport@{
                        val text = captured ?: return@onExport
                        val parsedResult = result ?: return@onExport
                        val account = destinations?.firstOrNull() ?: return@onExport
                        val items = if (appliedRecipeId != null) {
                            recipeItems(
                                planned = recipeSteps,
                                selected = recipeSelection,
                                captureId = "export",
                                chainId = "export",
                                defaults = account,
                                context = parseContext,
                            )
                        } else {
                            (draftItems(
                                captured = text,
                                result = parsedResult,
                                context = parseContext,
                                defaults = account,
                                captureId = "export",
                                chainId = "export",
                                selected = selected,
                                pastDateNoteTemplate = getString(R.string.capture_past_date_note),
                            ) as? DraftResult.Ready)?.items.orEmpty()
                        }
                        shareAsIcs(this@CaptureActivity, items, parseContext.zone.id)?.let {
                            startActivity(
                                Intent.createChooser(it, getString(R.string.ics_share_title))
                            )
                        }
                    },
                    destinationChoices = if (showingDestinations) {
                        app.settingsCoordinator.destinations.collectAsState().value.calendars
                    } else {
                        null
                    },
                    onChangeDestination = {
                        app.settingsCoordinator.loadDestinations()
                        showingDestinations = true
                    },
                    onChooseDestination = { calendar ->
                        showingDestinations = false
                        chosenDestination = Destination(
                            calendarId = calendar.id,
                            calendarName = calendar.summary,
                            calendarColour = calendar.backgroundColor,
                            taskListId = account?.taskListId.orEmpty(),
                        )
                        // FR-907: counted, and only offered — nothing is written by counting.
                        val app_id = captured?.appId
                        app.countDestinationOverride(app_id)
                        if (account != null &&
                            shouldOfferRule(settings.destinationOverrideCounts, app_id, settings, account)
                        ) {
                            ruleOffer = RuleOffer(app_id.orEmpty(), calendar.summary)
                        }
                    },
                    ruleOffer = ruleOffer,
                    onAcceptRule = {
                        chosenDestination?.let { app.makeRoutingRule(captured?.appId, it) }
                        ruleOffer = null
                    },
                    onDeclineRule = { ruleOffer = null },
                    recipes = recipes,
                    appliedRecipeId = appliedRecipeId,
                    onApplyRecipe = { appliedRecipeId = it },
                    recipeSteps = recipeSteps,
                    recipeSelection = recipeSelection,
                    onToggleRecipeStep = { index ->
                        recipeSelection =
                            if (index in recipeSelection) recipeSelection - index
                            else recipeSelection + index
                    },
                    onSave = {
                        if (captured != null && result != null) {
                            app.captureSaver.save(
                                captured = captured,
                                result = result,
                                context = parseContext,
                                selected = selected,
                                recipe = appliedRecipeId?.let { id ->
                                    RecipeApplication(id, recipeSteps, recipeSelection)
                                },
                                destination = routed,
                                titleOverrides = edits.titleOverrides,
                            )
                        }
                    },
                    onUndo = { app.captureSaver.undo() },
                    // FR-804: the two answers to the offer. Neither is a default, and the
                    // saver ignores both unless an offer is genuinely standing.
                    onUpdateExisting = { app.captureSaver.updateExisting() },
                    onCreateNew = { app.captureSaver.createNewInstead() },
                    fromEmptyClipboard = (request as? CaptureRequest.Nothing)?.fromEmptyClipboard == true,
                    fromLapsedOffer = (request as? CaptureRequest.Nothing)?.fromLapsedOffer == true,
                    // NFR-102: the screen draws these rather than waiting on them.
                    extracting = captureContent is CaptureContent.Extracting,
                    // FR-207: "Reading page N of M…" while a document is read.
                    extractingPages = (captureContent as? CaptureContent.Extracting)?.pages,
                    ocrFailure = (captureContent as? CaptureContent.Failed)?.reason,
                )
            }
        }
    }

    /**
     * FR-215 and FR-207, off the main thread.
     *
     * In `lifecycleScope`, so a capture the user closes stops being recognised rather than
     * finishing into a window that has gone. That is the opposite of the choice `CaptureSaver`
     * makes — a *write* in flight is held by the application precisely so it survives this
     * window — and the two differ because they have opposite failure modes. An abandoned
     * write leaves an item in the user's account that nothing will report; an abandoned read
     * leaves nothing anywhere, so cancelling it costs only the work.
     *
     * **A rotation re-runs the recognition**, because the activity is recreated and this
     * starts again. That is accepted rather than solved: recognition is idempotent, the URI
     * grant survives in the redelivered intent, and holding the result on the application
     * would mean a second object with a lifecycle to reason about for a case measured in
     * seconds. It is recorded here so that it is recognised rather than diagnosed.
     */
    private fun extract(
        request: CaptureRequest,
        referrer: String?,
        content: MutableStateFlow<CaptureContent>,
        openedAt: Long,
    ) {
        lifecycleScope.launch {
            val app = application as LatchApplication
            val (result, layer, title) = when (request) {
                is CaptureRequest.Image ->
                    Triple(app.ocrReader.readImage(request.uri), request.layer, request.preferredTitle)

                is CaptureRequest.Pdf -> Triple(
                    // NFR-102: the page count reaches the screen as it happens, so a document
                    // is a wait with a number on it rather than an indefinite one. Publishing
                    // to the same flow the result lands on means the progress cannot outlive
                    // the result — whichever arrives last is what the screen shows.
                    app.ocrReader.readPdf(request.uri) { page ->
                        content.value = CaptureContent.Extracting(page)
                    },
                    request.layer,
                    request.preferredTitle,
                )

                else -> return@launch
            }

            val next = when (result) {
                is OcrResult.Failed -> CaptureContent.Failed(result.reason)
                is OcrResult.Text -> CaptureContent.Ready(
                    CapturedText(
                        text = result.value,
                        layer = layer,
                        // FR-206 still applies: a shared PDF from a mail client carries the
                        // subject, and it names the thing better than its first recognised line.
                        preferredTitle = title,
                        appId = referrer,
                        ocrUsed = true,
                        pages = result.pages,
                    )
                )
            }
            // Before publishing, so the logged duration is the recognition and not whatever
            // recomposition the new state kicks off.
            logContentReady(openedAt, next)
            content.value = next
        }
    }

    /**
     * NFR-101's clock stop, on debug builds only.
     *
     * The requirement budgets "time from capture gesture to confirmation UI **displayed**" —
     * 800 ms for text, 2.5 s for OCR of a full-screen image — and since NFR-102's progressive
     * render landed, that moment is no longer one the platform logs. `ActivityTaskManager`'s
     * own `Displayed` line marks the **first frame**, which for an OCR capture is the spinner:
     * taking it for NFR-101 would let NFR-102 satisfy NFR-101 by drawing nothing, which is
     * plainly not what either requirement means. So the confirmation UI having its content is
     * marked here.
     *
     * Read against `ActivityTaskManager`'s `START` line for the same capture — both carry
     * logcat wall-clock timestamps, so NFR-101 is the gap between them:
     *
     *     adb logcat -d -v time | grep -E "ActivityTaskManager.*START.*latch|LatchTiming"
     *
     * Debug-only, the same guard and for the same reason as `GoogleAuthClient`'s status-code
     * logging: a release build has no one reading logcat, and `BuildConfig.DEBUG` is a
     * compile-time constant there, so R8 removes the branch and the strings with it. It logs
     * **no capture content** — a duration, which path ran, and how many characters came back.
     * NFR-202's instinct applies to a log as much as to an analytics SDK.
     */
    private fun logContentReady(openedAt: Long, content: CaptureContent) {
        if (!BuildConfig.DEBUG) return
        val elapsed = SystemClock.elapsedRealtime() - openedAt
        val outcome = when (content) {
            is CaptureContent.Extracting -> "extracting"
            is CaptureContent.Failed -> "failed=${content.reason}"
            is CaptureContent.Ready -> content.captured?.let { captured ->
                val pages = captured.pages?.let { ", pages=${it.read}/${it.total}" }.orEmpty()
                "ready, ocr=${captured.ocrUsed}, chars=${captured.text.length}$pages"
            } ?: "ready, nothing captured"
        }
        Log.i("LatchTiming", "content $outcome in ${elapsed}ms")
    }

    /**
     * What makes FR-807's ten seconds real, given the window this screen lives in.
     *
     * The capture window is a floating dialog with `windowCloseOnTouchOutside` set, so a
     * stray tap anywhere outside it finishes the activity. An undo offer that lived only on
     * this screen could therefore be gone in well under a second, through no deliberate act
     * of the user — "shall offer an undo for a period of not less than 10 seconds" would be
     * met on paper and not in the hand. So the dismissal is suppressed while the offer
     * stands. The Close button and the back gesture still work: those are the user declining
     * the offer, which is a different thing from brushing it away.
     *
     * When the offer lapses untaken the save stands and this window has nothing further to
     * say, so it closes itself. Only on a lapse: an offer the user *took* moves the state to
     * `Undoing`, and the outcome of that has to stay on screen (NFR-303).
     *
     * The offer itself lives in `CaptureSaver`, which is held by the application — so it
     * also survives the rotation or the recreate that destroys this activity.
     */
    @Composable
    private fun HoldWindowOpenForUndo(saveState: SaveState) {
        val offerIsOpen = (saveState as? SaveState.Saved)?.undo != null

        // FR-804's offer is held open for the same reason, and it is the stronger case: this
        // is a question the app asked rather than an action the user took, and a stray tap
        // would answer it by discarding the capture entirely. Nothing has been written at
        // this point, so the tap costs the whole save and not just the undo.
        val askingAboutReschedule = saveState is SaveState.RescheduleOffered

        var offerWasOpen by remember { mutableStateOf(false) }

        LaunchedEffect(saveState) {
            setFinishOnTouchOutside(!offerIsOpen && !askingAboutReschedule)
            when {
                offerIsOpen -> offerWasOpen = true
                offerWasOpen && saveState is SaveState.Saved -> finish()
            }
        }
    }

    /**
     * `launchMode` is `singleTop`, so a second capture arriving while this one is showing is
     * delivered here rather than to a new instance. Without this the screen would keep the
     * previous capture's text — and, now that there is a Save button under it, would offer
     * to save the wrong thing.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recreate()
    }

    /**
     * FR-1003: which capture layer delivered this, where the request names one.
     *
     * Null for a request that carries nothing usable — there is no layer to switch off, and
     * "nothing was shared" is a better thing to say than "that way of capturing is off".
     */
    private fun CaptureRequest.layerOf(): CaptureLayer? = when (this) {
        is CaptureRequest.Ready -> captured.layer
        is CaptureRequest.Image -> layer
        is CaptureRequest.Pdf -> layer
        is CaptureRequest.Nothing -> null
    }

    /**
     * What this capture *is*, so a recreation of it can be told from a new one.
     *
     * Not a hash of the text — the capture may still be extracting, and an image capture's
     * text does not exist yet. The intent's own content is what identifies it, and that is
     * available at `onCreate` on both paths.
     */
    private fun captureKeyOf(request: CaptureRequest): String = when (request) {
        is CaptureRequest.Ready -> "text:" + request.captured.text.hashCode()
        is CaptureRequest.Image -> "image:" + request.uri
        is CaptureRequest.Pdf -> "pdf:" + request.uri
        // Two empty captures in a row are indistinguishable and there is nothing to preserve
        // for either, so they may as well be the same one.
        is CaptureRequest.Nothing -> "nothing"
    }

    /**
     * FR-802's source application, for `latch.source_app`. `getReferrer()` is the only
     * source of it here — neither `ACTION_PROCESS_TEXT` nor `ACTION_SEND` carries the
     * sender's package, and `getCallingActivity()` is null because both arrive via
     * `startActivity` rather than for a result. Often null, which the schema reads as
     * unknown rather than as an error.
     */
    private fun referrerPackage(): String? = referrer?.host?.takeIf { it.isNotBlank() }
}
