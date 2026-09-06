package com.latch.android.capture

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.latch.android.BuildConfig
import com.latch.android.cards.CardDismiss
import com.latch.android.cards.cardDismiss
import com.latch.android.cards.CardCreated
import com.latch.android.cards.undoCardCreated
import com.latch.android.cards.CardOffer
import com.latch.android.cards.CardPhotoCoverage
import com.latch.android.cards.CardPhotoEncoder
import com.latch.android.cards.CardPhotoStep
import com.latch.android.cards.canAddCardPhoto
import com.latch.android.cards.cardLinesOf
import com.latch.android.cards.CardPreview
import com.latch.android.cards.cardPhotoFiles
import com.latch.android.cards.unsavedDates
import com.latch.android.cards.cardPhotoStep
import com.latch.android.cards.clearCardPhotos
import com.latch.android.cards.nextCardPhotoFile
import com.latch.android.cards.CardSaveResult
import com.latch.android.cards.CardSheetState
import com.latch.android.cards.cardOffer
import com.latch.android.cards.soleContactPayload
import com.latch.android.ui.CardChooser
import com.latch.android.ui.CardScreen
import com.latch.cards.CardParse
import com.latch.android.cards.CardReading
import com.latch.cards.classifyCard
import com.latch.cards.holdsContact
import com.latch.cards.parseCard
import com.latch.android.LatchApplication
import com.latch.android.R
import com.latch.android.ui.CaptureScreen
import com.latch.android.ui.LatchTheme
import com.latch.android.ui.RuleOffer
import com.latch.core.model.CaptureLayer
import com.latch.core.model.CaptureSource
import com.latch.data.toWireDestination
import com.latch.ocr.OcrFailure
import com.latch.ocr.OcrResult
import com.latch.ocr.PageProgress
import com.latch.parser.Confidence
import com.latch.parser.DateOrder
import com.latch.parser.DateParser
import com.latch.parser.ParseContext
import com.latch.wire.DraftResult
import com.latch.wire.RecipeApplication
import com.latch.wire.expandRecipe
import com.latch.wire.recipeItems
import com.latch.wire.parseContextFor
import com.latch.wire.SaveRoute
import com.latch.wire.saveRoute
import com.latch.wire.SheetEdits
import com.latch.wire.draftItems
import com.latch.wire.withEdits
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import com.latch.android.cards.cardPersonWarning
import com.latch.wire.cardPersonKeys
import androidx.compose.ui.graphics.asImageBitmap

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

    /**
     * FR-1203's decode result, held apart from the recognition it runs beside.
     *
     * **Null is "not decoded yet", and the two-valued flow could not say it** (SRS 1.119). An
     * empty list meant both "still decoding" and "no code in this image", which was harmless
     * while a human tap read it — the offer simply firmed up from AVAILABLE to DETECTED — and is
     * not harmless now that FR-1201a's camera path opens the sheet on its own.
     *
     * **A field rather than a local** since FR-1225: another photograph arrives at an Activity
     * result callback, which is outside `onCreate`'s scope and has to be able to publish into it.
     */
    private val cardPayloads = MutableStateFlow<List<String>?>(null)

    /** What this screen has to show. See [CaptureContent]; a field for [cardPayloads]' reason. */
    private val content = MutableStateFlow<CaptureContent>(CaptureContent.Ready(null))

    /** FR-1225: how many photographs this capture holds, and how many of them yielded text. */
    private val cardCoverage = MutableStateFlow<CardPhotoCoverage?>(null)

    /**
     * FR-1229: the photograph as the reader saw it, for the sheet to show.
     *
     * The **first** photograph, which is FR-1226's rule for the same reason — it is the front, and
     * a strip of every side would cost the space the fields need.
     */
    private val cardPreviews = MutableStateFlow<List<CardPreview>>(emptyList())

    /**
     * FR-1225: a photograph is being recognised into the capture in hand.
     *
     * Blocks the save while it stands, which is correctness rather than politeness — a save that
     * raced the back of the card would write a contact missing it, silently, against a card that
     * is no longer in the user's hand.
     */
    private val readingCardPhoto = MutableStateFlow(false)

    /**
     * FR-1225: take another photograph into the capture already open.
     *
     * Registered as a field, which `ComponentActivity` handles before STARTED and gives a stable
     * key — the same reason `MainActivity`'s three launchers are fields. A composition-scoped one
     * would key itself off a composition that changes shape between the capture sheet and the
     * card sheet, and a key that moves is a result delivered to no one.
     */
    private val addCardPhoto = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { taken ->
        if (taken) {
            // Everything is re-read from disk, this photograph included: the set is enumerated
            // rather than remembered, so there is nothing here to append to.
            extractCardPhotos(SystemClock.elapsedRealtime())
        } else {
            // Abandoned in the camera. No file was written, so the next attempt reuses the index
            // and nothing needs cleaning up.
            readingCardPhoto.value = false
        }
    }

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
        content.value = when (request) {
            is CaptureRequest.Ready -> CaptureContent.Ready(request.captured.copy(appId = referrer))
            is CaptureRequest.Nothing -> CaptureContent.Ready(null)
            is CaptureRequest.Image, is CaptureRequest.Pdf -> CaptureContent.Extracting()
        }
        when {
            // FR-1201a and FR-1225. Its own path, because the card capture reads *every*
            // photograph on disk rather than the one URI an intent carries — which is also what
            // makes a rotation cost a re-recognition instead of every side but the first.
            request is CaptureRequest.Image && request.cardPath -> extractCardPhotos(openedAt)
            request is CaptureRequest.Image || request is CaptureRequest.Pdf ->
                extract(request, referrer, content, cardPayloads, openedAt)
            else -> logContentReady(openedAt, content.value)
        }

        setContent {
            LatchTheme {
                val destinations by app.configuredAccounts.collectAsState()
                val saveState by app.captureSaver.state.collectAsState()
                val captureContent by content.collectAsState()
                val coverage by cardCoverage.collectAsState()
                val previewShots by cardPreviews.collectAsState()
                val readingPhoto by readingCardPhoto.collectAsState()
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
                // Derived in `:wire` rather than here, so this client and the desktop cannot
                // read one settings record into two different parses — FR-504's date order
                // being the field most likely to differ between a phone and a desktop.
                val parseContext = remember(settings) { parseContextFor(settings, capturedAt) }
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

                // FR-1202. The offer is a function of what the capture is and what the decoder
                // found — computed here and passed down, so the screen renders it and decides
                // nothing.
                val payloads by cardPayloads.collectAsState()
                // FR-1230. **Detected, and detection is why the button is not permanent.** The
                // action row already carries Close, Export .ics and Save; a fourth control on
                // every date capture, for a case that arises rarely, is the misplaced caution
                // SRS 1.113 records twice. `holdsContact` is a name or an email address and
                // nothing else — measured on NFR-502's 113 real strings, where the wider rule
                // fired eleven times and every one of the eleven read a numeric date as a
                // telephone number (SRS 1.151).
                //
                // Computed only for a text capture: an image already offers the action, and an
                // image's recognised text arrives late, so classifying it here would put a
                // classification on NFR-101's critical path for an answer nothing reads.
                val textHoldsContact = remember(captured?.text, request) {
                    request !is CaptureRequest.Image && request !is CaptureRequest.Pdf &&
                        captured?.text?.let { holdsContact(classifyCard(it.lines())) } == true
                }
                val offer = cardOffer(
                    isImage = request is CaptureRequest.Image,
                    decodedContactPayloads = payloads?.size ?: 0,
                    textHoldsContact = textHoldsContact,
                )
                var cardState by remember { mutableStateOf<CardSheetState?>(null) }
                var cardSave by remember { mutableStateOf<CardSaveResult>(CardSaveResult.Idle) }
                // FR-1210: when the save landed, so the ten seconds are counted from the save
                // and not from a recomposition.
                var cardSavedAt by remember { mutableStateOf<Instant?>(null) }
                var cardUndoing by remember { mutableStateOf(false) }
                // The window must not close on a stray tap while an undo is on offer.
                LaunchedEffect(cardSave, cardState) {
                    cardOfferStanding = cardState != null && when (val r = cardSave) {
                        is CardSaveResult.Saved, is CardSaveResult.Held -> true
                        // **A failed undo holds the window too** (SRS 1.125), and only a failed
                        // one: the contact is still in the user's account and the sentence saying
                        // so is the only place they will learn it, so a stray tap outside must not
                        // take it away. A successful undo has nothing left to act on, and letting
                        // the window close on a tap there costs the user nothing.
                        is CardSaveResult.Undone -> !r.removed
                        else -> false
                    }
                }
                var choosing by remember { mutableStateOf(false) }

                // FR-1227's answer for the sheet currently open, or null while nothing has been
                // asked. Keyed on the *key* rather than on the draft, so typing a job title does
                // not re-scan 3,000 contacts and correcting a name does.
                var personMatch by remember { mutableStateOf<String?>(null) }

                // FR-1203: one payload opens; several ask; none means the user is telling us this
                // image is a card the decoder could not read, which is Phase B's path and not
                // built, so it says so rather than opening an empty sheet.
                // **A tap must never do nothing** (SRS 1.100). The first version fell through
                // silently when there was no payload, or when one was found and would not parse —
                // and a control that does nothing is indistinguishable from a broken one, which
                // is how rows 3 and 4 of the device pass failed for a reason that had nothing to
                // do with what they were testing.
                var cardMessage by remember { mutableStateOf<Int?>(null) }
                val openCard = {
                    cardMessage = null
                    val decoded = payloads.orEmpty()
                    val sole = soleContactPayload(decoded)
                    when {
                        decoded.size > 1 -> choosing = true
                        sole != null -> {
                            val state = cardStateFor(sole)
                            if (state == null) cardMessage = R.string.card_unreadable
                            else cardState = state
                        }
                        // FR-1220: no code, so read the card off the photograph instead. The
                        // recognised text is already here — `:ocr` ran it for the dates — so this
                        // costs nothing beyond the classification, and reusing it is the reason
                        // FR-1220 says to use `:ocr` unchanged.
                        //
                        // FR-1230 arrives at the same call with `TEXT`: an email signature is
                        // lines, exactly as a photographed card is, and one classification serves
                        // both. What differs is what the sheet then says about how far to trust
                        // it, and the message where nothing could be read — a text selection that
                        // yields no contact is not "no code in this image".
                        else -> {
                            val text = captured?.text.orEmpty()
                            val isImageLike = request is CaptureRequest.Image ||
                                request is CaptureRequest.Pdf
                            val reading =
                                if (isImageLike) CardReading.PHOTO else CardReading.TEXT
                            val state = classifiedCardState(text, coverage, reading)
                            if (state == null) {
                                cardMessage = if (isImageLike) R.string.card_no_code
                                else R.string.card_no_contact_in_text
                            } else cardState = state
                        }
                    }
                }

                // FR-1201a. A photograph taken from Latch's own card button has already been
                // declared a card, so the sheet opens without asking again — the button *was*
                // FR-1202's choice. `cardPhotoStep` decides, and waits for both readers; its
                // note says why that ordering is required here and was wrong in SRS 1.100.
                val fromCamera = (request as? CaptureRequest.Image)?.cardPath == true
                var cardPhotoOpened by remember { mutableStateOf(false) }
                if (fromCamera) {
                    LaunchedEffect(captureContent, payloads) {
                        if (cardPhotoOpened) return@LaunchedEffect
                        when (
                            cardPhotoStep(
                                recognitionSettled = captureContent !is CaptureContent.Extracting,
                                decodeSettled = payloads != null,
                                hasText = !captured?.text.isNullOrBlank(),
                                payloads = payloads?.size ?: 0,
                            )
                        ) {
                            CardPhotoStep.WAITING -> Unit
                            CardPhotoStep.OPEN -> {
                                // Once per composition. Dismissing the sheet must return the user
                                // to the capture rather than to the sheet they have just backed
                                // out of.
                                //
                                // **A rotation reopens it**, this flag going with the composition
                                // that held it — which is right for the ordinary case, where the
                                // sheet was on screen and the user expects it back, and is a
                                // shrug in the one case where they had dismissed it first. It is
                                // the same standing this file already gives the recognition a
                                // rotation re-runs: recorded so it is recognised rather than
                                // diagnosed.
                                cardPhotoOpened = true
                                openCard()
                            }
                            // Nothing to open a sheet with. The capture screen stays, saying so,
                            // with whatever the photograph did yield — a date on a flyer, say —
                            // rather than the capture being thrown away.
                            CardPhotoStep.NOTHING_READ -> {
                                cardPhotoOpened = true
                                cardMessage = R.string.card_photo_unreadable
                            }
                        }
                    }
                }

                // FR-1225: another photograph re-classifies the **whole** capture and keeps the
                // user's corrections. Never two drafts merged — that would apply every FR-1221
                // rule twice and then need a conflict policy for each scalar field.
                //
                // **`CardSheetState` separating `parsed` from `edits` is what makes this safe**,
                // and this is the first thing to use that separation for something other than
                // display: without it, adding the back of a card would silently discard every
                // field the user had just corrected.
                LaunchedEffect(captured?.text, coverage) {
                    val open = cardState
                    // **Only where something actually changed**, which was worth finding: without
                    // the guard this fired for the very text that had just opened the sheet, so
                    // every capture classified twice and the debug diagnostic printed the card's
                    // contents twice with it. Both keys are tested, not just the text — a side
                    // that yields nothing leaves the text identical and moves the coverage, and
                    // that is exactly the case FR-1225's report exists for.
                    if (open != null && open.fromPhoto && captured != null &&
                        (open.payload != captured.text || open.photos != coverage)
                    ) {
                        classifiedCardState(captured.text, coverage)?.let { rebuilt ->
                            // FR-1226's tick travels with FR-1205's edits, and for the same
                            // reason: adding the back of a card must not silently untick a box the
                            // user set, which would send no photograph while the sheet said it
                            // would. It is a user answer, not a parse.
                            cardState = rebuilt.copy(
                                edits = open.edits,
                                attachPhoto = open.attachPhoto,
                            )
                        }
                    }
                }

                // FR-1227: ask, once per distinct key, and never block on the answer.
                //
                // **Before the save and not during it**, which is the requirement: a warning that
                // arrived with the outcome would be telling the user about a decision they had
                // already taken. It runs on the application's scope for `CaptureSaver`'s reason —
                // this window closes on a tap outside it — and its failure is silence, because an
                // inexact key that cannot be checked has nothing to say.
                val personKeys = remember(cardState?.edited) {
                    cardState?.edited?.let { cardPersonKeys(it) } ?: emptyList()
                }
                LaunchedEffect(personKeys) {
                    personMatch = null
                    if (personKeys.isNotEmpty()) {
                        personMatch = runCatching {
                            // A blank hash asks the inexact question alone — there is no capture
                            // hash to ask about before a save, and `findContactByHashPaged`
                            // supports it explicitly rather than by accident.
                            app.contactsApi.findContactBySourceHash(
                                sourceHash = "",
                                personKeys = personKeys,
                            ).probablePersonResourceName
                        }.getOrNull()
                    }
                }

                val sheet = cardState
                if (sheet != null) {
                    CardScreen(
                        state = sheet,
                        personWarning = cardPersonWarning(
                            personKeys = personKeys,
                            matched = personMatch,
                            exactAlreadySaved = cardSave is CardSaveResult.AlreadySaved,
                        ),
                        // FR-1225. Offered on a photographed card only, and never on one decoded
                        // from a grammar: a vCard payload is a complete record, and merging a
                        // second side's recognised lines into it would need exactly the conflict
                        // policy this requirement exists to avoid. Withdrawn at the cap rather
                        // than taking a photograph and then discarding it.
                        onAddPhoto = if (
                            fromCamera && sheet.fromPhoto && canAddCardPhoto(coverage?.added ?: 0)
                        ) ({ addAnotherCardPhoto() }) else null,
                        readingPhoto = readingPhoto,
                        // FR-1226. Offered only where there is an image to attach, which is the
                        // photographed path — a card decoded from a QR grammar has no photograph,
                        // and drawing a tick that could do nothing would be a control that lies.
                        onAttachPhoto = if (fromCamera && sheet.fromPhoto) {
                            { on -> cardState = sheet.copy(attachPhoto = on) }
                        } else null,
                        // FR-1212's consequence, said before the save rather than after it.
                        photoWouldBeHeld = destinations == null,
                        // FR-1229: what the reader saw, not what the camera showed.
                        previews = previewShots,
                        accountLabel = account?.let { if (destinations == null) null else "Google" },
                        onEdit = { cardState = sheet.copy(edits = it) },
                        saveResult = cardSave,
                        savedAt = cardSavedAt,
                        undoing = cardUndoing,
                        // FR-1210. What undo does is chosen by what the save did: a written
                        // contact is deleted, a **held** one has its entry dropped — a delete
                        // there would ask Google to remove something that was never created,
                        // succeed against a 404, and leave the card to arrive minutes later.
                        onUndo = {
                            cardUndoing = true
                            app.appScope.launch {
                                val created = when (val r = cardSave) {
                                    is CardSaveResult.Saved -> CardCreated.Written(r.resourceName)
                                    is CardSaveResult.Held -> CardCreated.Queued(r.entryId)
                                    else -> null
                                }
                                val removal = if (created == null) null else {
                                    undoCardCreated(
                                        created = created,
                                        contacts = app.contactsApi,
                                        dropQueued = { id -> app.cardQueue.drop(id) },
                                        log = { line -> if (BuildConfig.DEBUG) Log.i("LatchTiming", line) },
                                    )
                                }
                                cardUndoing = false
                                cardSavedAt = null
                                // **The answer is kept and the window stays** (SRS 1.125). This
                                // used to discard `removal` and `finish()` unconditionally, so a
                                // delete that failed against Google was indistinguishable from one
                                // that worked: the window closed either way and the contact stayed.
                                // NFR-303 names that a defect, and the date side has always left an
                                // undo's outcome on screen.
                                cardSave = CardSaveResult.Undone(
                                    removed = removal != null && removal.removed == removal.attempted
                                )
                            }
                        },
                        onSave = {
                            cardSave = CardSaveResult.Saving
                            // On the application's scope, not this Activity's: the capture window
                            // closes on a tap outside it and a write in flight must still finish.
                            // `CaptureSaver` is held for the same reason.
                            app.appScope.launch {
                                // FR-1226. Encoded here rather than in the saver, which keeps its
                                // promise of no `android.*` import — and encoded **now** rather
                                // than when the box was ticked, because the photographs are still
                                // on disk until this capture closes and doing it on the tick would
                                // spend a second of the user's time on a save they may not make.
                                //
                                // **The first photograph Latch could read**, where FR-1225
                                // supplied several. It was the first *file* until SRS 1.146, and
                                // the two differ exactly where it matters: a first shot that gave
                                // no text is one the recogniser could make nothing of, and
                                // attaching it would put the blurred frame on the contact — with
                                // no thumbnail on the sheet, since FR-1229 previews what was read.
                                // Now the label, the thumbnail and the attachment name one
                                // photograph between them. Choosing a different one is a question
                                // no requirement asks.
                                //
                                // **And it is composed from the preview's own numbers** (SRS
                                // 1.148), so the picture in the account is the picture on the
                                // sheet: same side, same turn, same crop.
                                val shown = cardPreviews.value.firstOrNull()
                                val jpeg = if (!sheet.attachPhoto) null else {
                                    cardPhotoFiles(cacheDir)
                                        .getOrNull((shown?.side ?: 1) - 1)?.let { file ->
                                            runCatching {
                                                CardPhotoEncoder(this@CaptureActivity).squareJpeg(
                                                    uri = cardPhotoUriFor(this@CaptureActivity, file),
                                                    textAngle = shown?.textAngle ?: 0.0,
                                                    region = shown?.region,
                                                )
                                            }.getOrNull()
                                        }
                                }
                                cardSave = app.cardSaver.save(
                                    draft = sheet.edited,
                                    payload = sheet.payload,
                                    layer = captured?.layer?.name ?: "SHARED_IMAGE",
                                    photoJpeg = jpeg,
                                )
                                cardSavedAt = Instant.now()
                            }
                        },
                        // SRS 1.104. Backing out returned to the capture sheet unconditionally,
                        // so a user who had just saved a contact from a QR with no dates in it
                        // was dropped onto an empty capture — reading as though nothing had
                        // happened. What decides is what the capture still *holds*.
                        onDismiss = {
                            val dismiss = cardDismiss(
                                // A held card is finished from the user's point of view: it is
                                // safe on the phone and the sheet has said so. Sending them back
                                // to a capture they have dealt with would read as a failure.
                                saved = cardSave is CardSaveResult.Saved ||
                                    cardSave is CardSaveResult.AlreadySaved ||
                                    cardSave is CardSaveResult.Held,
                                // **Dates, not candidates** (SRS 1.147). `candidates.size`
                                // counted the single `TASK_UNDATED` a capture with no date in it
                                // always yields, so every photographed card claimed to hold one
                                // unsaved date and SRS 1.104's rule inverted on the path it was
                                // written for.
                                unsavedDateCandidates = if (saveState is SaveState.Saved) 0
                                else unsavedDates(result?.candidates.orEmpty()),
                            )
                            when (dismiss) {
                                CardDismiss.CLOSE_CAPTURE -> finish()
                                CardDismiss.BACK_TO_CAPTURE -> {
                                    cardState = null
                                    cardSave = CardSaveResult.Idle
                                }
                            }
                        },
                    )
                } else if (choosing) {
                    CardChooser(
                        payloads = payloads.orEmpty(),
                        onChoose = { chosen -> choosing = false; cardState = cardStateFor(chosen) },
                        onDismiss = { choosing = false },
                    )
                } else {
                CaptureScreen(
                    cardOffer = offer,
                    onSaveAsContact = openCard,
                    cardMessage = cardMessage,
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
                                destination = account.toWireDestination(),
                                context = parseContext,
                            )
                        } else {
                            (draftItems(
                                captured = text,
                                result = parsedResult,
                                context = parseContext,
                                destination = account.toWireDestination(),
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
                    // FR-1201a: the same wait, over a photograph the user just took of a card.
                    // "Reading the text" would be true and would describe the wrong thing.
                    extractingCard = fromCamera,
                    // FR-207: "Reading page N of M…" while a document is read.
                    extractingPages = (captureContent as? CaptureContent.Extracting)?.pages,
                    ocrFailure = (captureContent as? CaptureContent.Failed)?.reason,
                )
                }
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
        /** FR-1203's decode result, independent of [content] — see the Image branch. */
        cardPayloads: MutableStateFlow<List<String>?>,
        openedAt: Long,
    ) {
        lifecycleScope.launch {
            val app = application as LatchApplication
            val (result, layer, title) = when (request) {
                is CaptureRequest.Image -> {
                    // FR-1203, off NFR-101's critical path.
                    //
                    // **Its own flow, not a field on `CaptureContent.Ready`** (SRS 1.100). The
                    // first version folded the payloads into whatever `content` held when the
                    // decode finished — and a QR decode of a card is *quicker* than ML Kit on the
                    // same image, so it finished while `content` was still `Extracting` and the
                    // `as?` dropped the result on the floor. Every card was then offered as
                    // AVAILABLE with nothing behind it. The ordering is removed rather than
                    // guarded: two independent results, neither waiting on the other.
                    lifecycleScope.launch {
                        val codes = runCatching { app.qrReader.readCodes(request.uri) }.getOrNull()
                        // Assigned on every outcome, a throw included: the flow is now tri-state
                        // and a decode that failed while leaving it null would hold FR-1201a's
                        // automatic open open for ever, waiting on a reader that had finished.
                        cardPayloads.value = codes?.payloads.orEmpty()
                            .filter { looksLikeContactPayload(it) }
                    }
                    Triple(app.ocrReader.readImage(request.uri), request.layer, request.preferredTitle)
                }

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
    /**
     * FR-1210 on the card sheet.
     *
     * Held on the Activity rather than passed down, because the card sheet and the window's
     * touch-outside rule live in different composable scopes and the rule is the Activity's.
     */
    private var cardOfferStanding by mutableStateOf(false)

    @Composable
    private fun HoldWindowOpenForUndo(saveState: SaveState) {
        val offerIsOpen = (saveState as? SaveState.Saved)?.undo != null

        // FR-804's offer is held open for the same reason, and it is the stronger case: this
        // is a question the app asked rather than an action the user took, and a stray tap
        // would answer it by discarding the capture entirely. Nothing has been written at
        // this point, so the tap costs the whole save and not just the undo.
        val askingAboutReschedule = saveState is SaveState.RescheduleOffered

        // FR-1210 on the card sheet, and it is the same reason: the window closes on a tap
        // outside it, so an undo offer the user cannot reach is not an offer. Reported from the
        // device pass, where a stray tap lost the sheet mid-row.
        val cardOfferOpen = cardOfferStanding

        var offerWasOpen by remember { mutableStateOf(false) }

        LaunchedEffect(saveState, cardOfferOpen) {
            setFinishOnTouchOutside(!offerIsOpen && !askingAboutReschedule && !cardOfferOpen)
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
     * FR-1220 into FR-1205: recognised lines become an editable draft, or nothing.
     *
     * **One classification over every photograph's lines**, which is FR-1225's own sentence and
     * the reason this is a function rather than two call sites: it is used when the sheet first
     * opens and again each time a side is added, and the two producing different readings of one
     * capture is precisely the drift this pillar keeps finding.
     */
    private fun classifiedCardState(
        text: String,
        photos: CardPhotoCoverage?,
        /** FR-1224/FR-1230: how far the sheet should ask the user to trust this, and why. */
        reading: CardReading = CardReading.PHOTO,
    ): CardSheetState? {
        val recognised = text.lines()
        val classified = classifyCard(recognised)
        // **A diagnostic that deliberately prints card content** (SRS 1.108), which nothing else
        // in this application does: `LatchTiming` carries an enum and a boolean and structurally
        // cannot leak. It exists to build FR-1222's corpus from real cards, it is debug-only, and
        // it is recorded so that its removal is a decision rather than an oversight. It is item 0
        // on `docs/RELEASE.md`'s gate.
        //
        // **Confined to the photographed path** (SRS 1.151). FR-1230 reaches this function with a
        // text selection, which is not a card and cannot enter FR-1222's corpus — so logging it
        // would print the user's own selected text for no purpose the diagnostic exists for.
        if (BuildConfig.DEBUG && reading == CardReading.PHOTO) {
            Log.i("LatchCardOcr", "--- recognised ${recognised.size} line(s)")
            recognised.forEachIndexed { i, l -> Log.i("LatchCardOcr", "  [$i] $l") }
            Log.i("LatchCardOcr", "--- classified")
            Log.i("LatchCardOcr", "  name=${classified.draft.displayName}")
            Log.i("LatchCardOcr", "  title=${classified.draft.jobTitle}")
            Log.i("LatchCardOcr", "  org=${classified.draft.organisation}")
            classified.draft.phones.forEach { Log.i("LatchCardOcr", "  phone=${it.number} type=${it.type}") }
            classified.draft.emails.forEach { Log.i("LatchCardOcr", "  email=${it.address}") }
            classified.draft.urls.forEach { Log.i("LatchCardOcr", "  url=$it") }
            // Logged because its absence was mistaken for a missing address on a real card: the
            // lines were neither in the draft nor in unplaced, and the instrument was what could
            // not see them.
            classified.draft.addresses.forEach { Log.i("LatchCardOcr", "  address=$it") }
            classified.unplaced.forEach { Log.i("LatchCardOcr", "  unplaced=$it") }
        }
        if (classified.draft.isEmpty) return null
        return CardSheetState(
            // FR-1223's lines become the note, so what could not be placed is carried rather than
            // shown and then dropped (SRS 1.113). The classifier stays pure: it reports what it
            // could not place, and this decides what to do about it.
            parsed = classified.draft.copy(
                note = classified.unplaced
                    .joinToString(System.lineSeparator())
                    .takeIf { it.isNotBlank() },
            ),
            // No payload: FR-1208's hash comes from the recognised text for this path, which
            // SRS 1.30 records as wobblier than a decoded one and SRS 1.123 records as weaker
            // still once a camera is involved — every shutter press is a new image.
            // On the text path this is the selection itself, so FR-1208's hash is exact and
            // deterministic rather than a re-rendering away from differing — the wobble SRS 1.30
            // and 1.123 record belongs to the recognisers, not to this path.
            payload = text,
            unplaced = classified.unplaced,
            photos = photos,
            readBy = reading,
        )
    }

    /**
     * FR-1225: the camera again, into the capture already open.
     *
     * The file is named before the camera is asked, so the photograph the camera returns is the
     * only thing this has to find afterwards — and it is never swept here, which is the whole
     * difference from starting a capture.
     */
    private fun addAnotherCardPhoto() {
        val file = nextCardPhotoFile(cacheDir) ?: return
        val uri = runCatching { cardPhotoUriFor(this, file) }.getOrNull() ?: return
        readingCardPhoto.value = true
        // `launch` throws ActivityNotFoundException where nothing handles ACTION_IMAGE_CAPTURE.
        // It cannot here — the capture only exists because a camera answered once — but the
        // flag has to come back down either way or the save stays blocked for ever.
        if (runCatching { addCardPhoto.launch(uri) }.isFailure) readingCardPhoto.value = false
    }

    /**
     * FR-1201a and FR-1225: every photograph of the capture in hand, recognised and decoded.
     *
     * **Enumerated from disk, not appended to** (SRS 1.124). A rotation recreates this Activity
     * and runs this again, which costs a second and loses nothing; state remembered in the
     * composition would have kept the first photograph and dropped every side added after it,
     * which loses work rather than repeating it. FR-1211 already puts them on disk, so disk was
     * the source of truth and anything else would have been a second copy of it.
     *
     * The decode runs beside the recognition here rather than racing it, which is safe on this
     * path and is not the shared-image path's arrangement: `cardPhotoStep` waits for both before
     * the sheet opens anyway, so there is nothing for a race to win. SRS 1.100's fix is left
     * exactly where it was, on the path that needs it.
     */
    private fun extractCardPhotos(openedAt: Long) {
        lifecycleScope.launch {
            val app = application as LatchApplication
            val referrer = referrerPackage()
            val files = cardPhotoFiles(cacheDir)
            cardPreviews.value = emptyList()
            val previews = mutableListOf<CardPreview>()
            val texts = mutableListOf<String>()
            val payloads = mutableListOf<String>()

            for ((index, file) in files.withIndex()) {
                val uri = runCatching { cardPhotoUriFor(this@CaptureActivity, file) }.getOrNull()
                    ?: continue
                runCatching { app.qrReader.readCodes(uri) }.getOrNull()
                    ?.payloads
                    .orEmpty()
                    .filterTo(payloads) { looksLikeContactPayload(it) }
                when (val result = app.ocrReader.readImage(uri)) {
                    // A side that gave nothing is still a photograph the user took, so it is
                    // counted rather than forgotten — that difference is the whole of what
                    // FR-1225's coverage line reports.
                    is OcrResult.Failed -> Unit
                    is OcrResult.Text -> {
                        texts += result.value
                        // FR-1229, and **every photograph rather than the first** (SRS 1.145).
                        // Showing only the first was worse than showing none: a second side left
                        // the thumbnail unchanged, so a control whose entire job is to say what
                        // was read was reporting on a picture that was no longer the one just
                        // taken. Each is rendered exactly as its own side was read — decoded
                        // small, turned by the angle that side's reader turned it, cropped to the
                        // region that side's second pass read.
                        runCatching {
                            CardPhotoEncoder(this@CaptureActivity)
                                .preview(uri, result.textAngle, result.readRegion)
                        }.getOrNull()?.let {
                            previews += CardPreview(
                                side = index + 1,
                                image = it,
                                textAngle = result.textAngle,
                                region = result.readRegion,
                            )
                        }
                        // **Published as each side finishes**, not at the end. The sheet is on
                        // screen throughout the read, and a preview that appears only once every
                        // side is done leaves the longest gap exactly where the doubt is.
                        cardPreviews.value = previews.toList()
                    }
                }
            }

            cardPayloads.value = payloads
            cardCoverage.value = CardPhotoCoverage(read = texts.size, added = files.size)

            val lines = cardLinesOf(texts)
            val next = if (lines.isEmpty()) {
                CaptureContent.Ready(null)
            } else {
                CaptureContent.Ready(
                    CapturedText(
                        text = lines.joinToString(System.lineSeparator()),
                        layer = CaptureLayer.CAMERA,
                        appId = referrer,
                        ocrUsed = true,
                    )
                )
            }
            logContentReady(openedAt, next)
            content.value = next
            readingCardPhoto.value = false
        }
    }

    /**
     * FR-1211: the photograph does not outlive the capture.
     *
     * **Guarded on `isFinishing`, and that guard is the requirement rather than tidiness.** A
     * rotation destroys this activity too, and the recreation re-runs the recognition against the
     * same URI — so deleting unconditionally would lose a card capture to a quarter-turn of the
     * phone. What this cannot cover is a killed process; `newCardPhotoFile` sweeps the directory
     * before each photograph, which is the other half.
     *
     * Run for every capture, not only a camera one: the directory is empty for the rest, and a
     * sweep that happens on a path nobody thought about is worth more than one that is exact.
     */
    override fun onDestroy() {
        if (isFinishing) clearCardPhotos(cacheDir)
        super.onDestroy()
    }

    /**
     * FR-1003: which capture layer delivered this, where the request names one.
     *
     * Null for a request that carries nothing usable — there is no layer to switch off, and
     * "nothing was shared" is a better thing to say than "that way of capturing is off".
     */
    private fun CaptureRequest.layerOf(): CaptureLayer? = when (this) {
        is CaptureRequest.Ready -> captured.layer
        // FR-1201a's photograph reports no layer, deliberately (SRS 1.119). FR-1003's toggles
        // govern §5.2's layers — the ways content arrives from somewhere else — and a button
        // inside Latch is not one of them, so there is nothing here to switch off. Returning
        // `CAMERA` would also have refused every existing install: their stored `enabled_layers`
        // record was written before the value existed and cannot contain it.
        is CaptureRequest.Image -> if (cardPath) null else layer
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

/**
 * FR-1201a: a card photograph's file, as a URI the camera application can be granted.
 *
 * **One definition, used by both Activities**: `MainActivity` starts a capture and
 * `CaptureActivity` adds a side to one, and two copies of an authority string is how the two
 * eventually stop naming the same provider.
 *
 * The authority is named for FR-1005's exports and serves both cache roots — a `FileProvider` has
 * one authority and as many declared paths as it needs. Renaming it would be a migration of a
 * published component for no gain.
 */
internal fun cardPhotoUriFor(context: Context, file: File): Uri =
    FileProvider.getUriForFile(context, "${context.packageName}.exports", file)

/**
 * FR-1202: is this decoded payload a contact grammar?
 *
 * A QR code on printed material is as likely to be a URL or a Wi-Fi credential as a card, and
 * offering the card path for a Wi-Fi password would be the app guessing. `parseCard` is the
 * authority on what parses; this is the cheap prefix test that decides whether to ask it.
 */
internal fun looksLikeContactPayload(payload: String): Boolean {
    val text = payload.trimStart()
    return text.startsWith("BEGIN:VCARD", ignoreCase = true) ||
        text.startsWith("MECARD:", ignoreCase = true)
}

/**
 * FR-1204 into FR-1205: a decoded payload becomes a sheet, or nothing.
 *
 * A payload that does not parse produces no sheet at all rather than an empty one — the
 * requirement's "reported unreadable, not partially accepted", at the seam where it would
 * otherwise be tempting to open the screen and let the user fill it in.
 */
internal fun cardStateFor(payload: String): CardSheetState? =
    (parseCard(payload) as? CardParse.Parsed)?.let { CardSheetState(parsed = it.draft, payload = payload) }
