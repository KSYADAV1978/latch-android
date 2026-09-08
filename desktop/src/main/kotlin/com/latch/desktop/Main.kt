package com.latch.desktop

import com.latch.desktop.auth.ClientConfig
import com.latch.desktop.auth.DesktopAuth
import com.latch.desktop.auth.SignInFailure
import com.latch.desktop.auth.SignInOutcome
import com.latch.desktop.capture.CaptureOutcome
import com.latch.desktop.capture.ClipboardCapture
import com.latch.desktop.capture.GlobalHotkey
import com.latch.desktop.capture.HotkeyEvent
import com.latch.desktop.capture.HotkeyParse
import com.latch.desktop.capture.HotkeySpec
import com.latch.desktop.capture.buildCapture
import com.latch.desktop.capture.desktopSource
import com.latch.desktop.capture.parseHotkey
import com.latch.desktop.capture.writeForRecognition
import com.latch.desktop.inbox.DesktopInbox
import com.latch.desktop.ocr.WindowsOcr
import com.latch.desktop.queue.DrainTrigger
import com.latch.desktop.queue.QueueRunner
import com.latch.desktop.queue.WriteQueue
import com.latch.desktop.save.CreatedItem
import com.latch.desktop.save.DesktopSaver
import com.latch.desktop.save.RemovalOutcome
import com.latch.desktop.save.undoCreated
import com.latch.desktop.save.IcsFile
import com.latch.desktop.save.DesktopSetup
import com.latch.desktop.save.PendingWrite
import com.latch.desktop.save.SaveFailure
import com.latch.desktop.save.SaveResult
import com.latch.desktop.save.SetupResult
import com.latch.desktop.save.toWireDestination
import com.latch.desktop.store.DefaultsStore
import com.latch.desktop.store.DesktopSettings
import com.latch.desktop.store.DesktopSettingsStore
import com.latch.desktop.store.RecipeFile
import com.latch.desktop.store.WebhookSecrets
import com.latch.desktop.store.SecretFile
import com.latch.desktop.store.latchDataDirectory
import com.latch.desktop.ui.CaptureWindow
import com.latch.desktop.ui.DesktopStrings
import com.latch.desktop.ui.InboxWindow
import com.latch.desktop.ui.SettingsForm
import com.latch.desktop.ui.RecipeForm
import com.latch.desktop.ui.RecipesWindow
import com.latch.desktop.ui.SettingsWindow
import com.latch.desktop.ui.applyRecipe
import com.latch.desktop.ui.applyRecipeForm
import com.latch.desktop.ui.recipeChainModel
import com.latch.desktop.ui.recipeChooser
import com.latch.desktop.ui.recipeProblemText
import com.latch.desktop.ui.applyForm
import com.latch.desktop.ui.deliveryText
import com.latch.desktop.ui.endpointLine
import com.latch.desktop.ui.hotkeyChanged
import com.latch.desktop.ui.inboxModel
import com.latch.desktop.ui.settingsProblemText
import com.latch.webhook.EndpointRefusal
import com.latch.webhook.WebhookSender
import com.latch.webhook.validateEndpoint
import com.latch.webhook.webhookEligible
import com.latch.webhook.webhookPayloadForChain
import com.latch.desktop.ui.LatchTray
import com.latch.desktop.ui.TrayAction
import com.latch.desktop.ui.TrayModel
import com.latch.desktop.ui.messageFor
import com.latch.desktop.ui.rescheduleOfferText
import com.latch.desktop.ui.undoOutcomeText
import com.latch.desktop.ui.popupModel
import com.latch.google.TokenProvider
import com.latch.google.fetchPrimaryAccount
import com.latch.google.googleCalendarApi
import com.latch.google.googleTasksApi
import com.latch.parser.DateParser
import com.latch.parser.ParseContext
import com.latch.parser.ParseResult
import com.latch.core.model.InboxCapture
import com.latch.core.model.LatchSettings
import com.latch.core.model.InboxReason
import com.latch.core.model.ItemType
import com.latch.wire.DraftResult
import com.latch.wire.SaveRoute
import com.latch.wire.parseContextOf
import com.latch.wire.parseOf
import com.latch.core.model.Recipe
import com.latch.recipes.BuiltInRecipes
import com.latch.recipes.duplicateOf
import com.latch.recipes.editableCopyOf
import com.latch.recipes.newRecipe
import com.latch.recipes.recipesFor
import com.latch.wire.RecipeApplication
import com.latch.wire.recipeItems
import com.latch.wire.parseContextFor
import com.latch.wire.saveRoute
import com.latch.wire.titleOverridesOf
import com.latch.wire.SheetEdits
import com.latch.wire.draftItems
import com.latch.wire.withEdits
import kotlinx.coroutines.runBlocking
import java.awt.Desktop
import java.awt.TrayIcon
import java.io.File
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import javax.swing.SwingUtilities
import javax.swing.UIManager
import kotlin.system.exitProcess

/**
 * FR-301: the Windows client, as a tray application.
 *
 * This file is wiring and nothing else. Every decision it appears to make is made by a pure
 * function somewhere a test can reach: `parseHotkey`, `buildCapture`, `popupModel`, `trayMenu`.
 * That is deliberate and is the same arrangement `:app` uses — `CLAUDE.md` records that twice
 * in this project the untested thing turned out to be the load-bearing thing, both times
 * because it was inside something a test could not construct.
 */
object Latch {
    private val secrets by lazy { SecretFile(File(latchDataDirectory(), "secrets.dat")) }
    private val auth by lazy { DesktopAuth(secrets, ClientConfig.load()) }
    private val defaultsStore by lazy { DefaultsStore(secrets) }

    /**
     * FR-1001, and it is read on the capture path — so it is cached after the first read.
     * NFR-101 budgets a capture 800 ms and the DPAPI bridge costs most of a second.
     */
    private val settingsStore by lazy { DesktopSettingsStore(secrets) }

    /** FR-1004's endpoint (NFR-203) and FR-1004b's passive report. */
    private val webhookSecrets by lazy { WebhookSecrets(secrets) }
    private val webhooks = WebhookSender()
    private val queue by lazy { WriteQueue(File(latchDataDirectory(), "queue.dat")) }

    /** FR-701. Local only (FR-703); nothing in it has reached the user's Google account. */
    private val inbox by lazy { DesktopInbox(File(latchDataDirectory(), "inbox.dat")) }

    /** FR-603. Only the user's own; FR-602's eight ship as code and are shadowed, never stored. */
    private val recipeStore by lazy { RecipeFile(File(latchDataDirectory(), "recipes.dat")) }

    /**
     * FR-806's drain.
     *
     * The APIs are passed as functions rather than values because they cannot be built
     * before there is a sign-in, and the queue outlives not having one: a capture made
     * while signed out is still a capture, and the runner simply finds nothing to write
     * with until there is.
     */
    private val runner by lazy {
        QueueRunner(
            queue = queue,
            calendar = { if (auth.isSignedIn) calendarApi else null },
            tasks = { if (auth.isSignedIn) tasksApi else null },
            onChanged = { SwingUtilities.invokeLater { tray?.update(model()) } },
        )
    }

    /**
     * The bridge from this client's sign-in to the shared Google client.
     *
     * `invalidate` drops the cached access token so the next call refreshes. It does not
     * discard the refresh token: only Google saying the grant is gone does that, and
     * `DesktopAuth` is where that decision lives.
     */
    private val tokens = object : TokenProvider {
        // The throwing form, deliberately. `accessToken()` answering null cannot say whether
        // the network is down or the grant is gone, and FR-806 needs to know: the first is
        // held, the second is reported. Reporting the first loses the capture.
        override suspend fun accessToken(): String = auth.accessTokenOrThrow()

        override suspend fun invalidate(token: String) = auth.dropCachedAccessToken()
    }

    private val calendarApi by lazy { googleCalendarApi(tokens) }
    private val tasksApi by lazy { googleTasksApi(tokens) }
    private val clipboard = ClipboardCapture()
    private val recogniser = WindowsOcr()

    private var tray: LatchTray? = null
    private var hotkey: GlobalHotkey? = null
    /**
     * The capture the open window is about.
     *
     * Held because FR-1004's eligibility is a question about the *capture* (FR-210a) and the
     * save result carries the write rather than what it came from. Cleared with the window.
     */
    private var lastCaptured: com.latch.desktop.capture.DesktopCapture? = null
    private var window: CaptureWindow? = null
    private var inboxWindow: InboxWindow? = null
    private var settingsWindow: SettingsWindow? = null
    private var recipesWindow: RecipesWindow? = null

    /**
     * FR-601: the chain the open capture window is showing, what it came from, and the parse it
     * was expanded over.
     *
     * The parse is kept because it is the **edited** one — FR-506 row 3's assigned date is what
     * can make a capture expandable at all — and §7.2's `item_key` and FR-805's date spans are
     * derived from whatever reaches the saver. Re-deriving from the original would be one more
     * place for the sheet and the write to disagree.
     */
    private var applied: Triple<Recipe, RecipeApplication, ParseResult>? = null

    /**
     * FR-302's combination, from FR-1001's record.
     *
     * **A stored value that will not parse falls back to the shipped default rather than
     * leaving the client with no shortcut at all.** `applyForm` refuses to store an unparseable
     * one, so this can only happen to a record written by another version — and a client whose
     * only way in silently stopped working would be the least diagnosable failure this
     * application has.
     */
    private fun specOf(text: String): HotkeySpec = when (val parsed = parseHotkey(text)) {
        is HotkeyParse.Parsed -> parsed.spec
        is HotkeyParse.Rejected -> (parseHotkey(HotkeySpec.DEFAULT) as HotkeyParse.Parsed).spec
    }

    private var spec: HotkeySpec = specOf(HotkeySpec.DEFAULT)

    fun start() {
        runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }

        spec = specOf(runCatching { settingsStore.read().hotkey }.getOrNull() ?: HotkeySpec.DEFAULT)

        val trayIcon = LatchTray(::onTrayAction)
        if (!trayIcon.install(model())) {
            System.err.println("This desktop has no system tray, so Latch has nowhere to live.")
            exitProcess(1)
        }
        tray = trayIcon

        registerHotkey(spec)
        runner.start()

        Runtime.getRuntime().addShutdownHook(Thread { shutDown() })
    }

    /**
     * FR-302's registration, and the one operation Settings can make worse.
     *
     * Re-registering means stopping a sidecar and starting another, so a combination another
     * application already holds leaves the user with **no** shortcut. [onRefused] is how the
     * caller puts the old one back rather than reporting a failure and stopping there.
     */
    private fun registerHotkey(wanted: HotkeySpec, onRefused: ((Int) -> Unit)? = null) {
        hotkey?.close()
        spec = wanted
        val listener = GlobalHotkey(wanted)
        listener.start { event ->
            when (event) {
                HotkeyEvent.Pressed -> SwingUtilities.invokeLater { capture(copySelectionFirst = true) }
                HotkeyEvent.Ready -> Unit
                is HotkeyEvent.Refused -> SwingUtilities.invokeLater {
                    if (onRefused != null) onRefused(event.code)
                    else tray?.say(
                        "Latch",
                        "Another application already uses " + wanted.display +
                            ". Choose a different shortcut in Settings.",
                        TrayIcon.MessageType.WARNING,
                    )
                }
                is HotkeyEvent.Broken -> SwingUtilities.invokeLater {
                    tray?.say(
                        "Latch", "The keyboard shortcut stopped working.", TrayIcon.MessageType.ERROR,
                    )
                }
            }
        }
        hotkey = listener
        tray?.update(model())
    }

    private fun model(): TrayModel {
        val status = runCatching { queue.status() }.getOrNull()
        val held = runCatching { inbox.status(Instant.now()) }.getOrNull()
        return TrayModel(
            hotkeyLabel = spec.display,
            signedInAs = if (auth.isSignedIn) defaultsStore.read()?.email ?: "your Google account" else null,
            configured = auth.isConfigured,
            pending = status?.waiting ?: 0,
            givenUp = status?.givenUp ?: 0,
            queueNeedsSignIn = status?.needsSignIn ?: false,
            inbox = held?.due ?: 0,
        )
    }

    private fun onTrayAction(action: TrayAction) = when (action) {
        TrayAction.CAPTURE -> SwingUtilities.invokeLater { capture(copySelectionFirst = false) }
        TrayAction.INBOX -> SwingUtilities.invokeLater(::openInbox)
        TrayAction.RECIPES -> SwingUtilities.invokeLater(::openRecipes)
        TrayAction.SIGN_IN -> signIn()
        TrayAction.SIGN_OUT -> {
            // NFR-205's local half only: this forgets the sign-in on this machine and
            // leaves everything already in the user's Google account alone. Revoking the
            // grant is a different action and is owed.
            auth.forget()
            defaultsStore.clear()
            // The Inbox is deliberately **not** cleared, for the same reason the queue is not:
            // its rows are captures that exist nowhere else (FR-703), and leaving an account is
            // not a request to throw away work. NFR-205's disconnect is, and it is owed.
            // The queue is deliberately **not** cleared. Its entries are captures that
            // exist nowhere else, and signing out of an account is not a request to throw
            // away work — NFR-205's disconnect is, and that is a different action.
            tray?.update(model())
        }
        TrayAction.RETRY -> {
            runner.nudge(DrainTrigger.USER_ASKED)
            tray?.say("Latch", "Trying again now.") ?: Unit
        }
        TrayAction.SETTINGS -> SwingUtilities.invokeLater(::openSettings)
        TrayAction.QUIT -> {
            shutDown()
            exitProcess(0)
        }
    }

    /**
     * FR-302's whole path: copy, read, recognise where it is a picture, parse, show.
     *
     * The recognition and the parse happen off the event thread. NFR-101 budgets a capture
     * 800 ms for text and 2.5 s for an image, and the bridge costs about 570 ms of that on
     * its own — doing it on the event thread would freeze whatever the user was looking at.
     */
    /**
     * FR-302's capture, and FR-213's reading beside it (SRS 1.82).
     *
     * **[copySelectionFirst] is the whole difference between the two entry points.** The hotkey
     * fires while the user's own window is in front, so synthesising a copy is what reaches the
     * text they have selected — that is FR-302 as written, and it is verified. A tray row is
     * pressed *after* that window has lost focus, so the same synthesised Ctrl+C would land
     * somewhere with no selection, and in a terminal it is not a copy at all. So the tray reads
     * the clipboard and nothing else, which is exactly FR-213's supported path on Android: the
     * user copies, then invokes.
     */
    private fun capture(copySelectionFirst: Boolean = true) {
        if (copySelectionFirst) clipboard.copySelection()
        val clip = clipboard.read()

        Thread({
            val outcome = buildCapture(clip) { image ->
                val file = writeForRecognition(image)
                try {
                    recogniser.recognise(file)
                } finally {
                    // A capture is often a screenshot of a private message. Leaving it in the
                    // temp directory would put on disk, in the clear, the content NFR-201
                    // keeps off the network.
                    file.delete()
                }
            }
            SwingUtilities.invokeLater { present(outcome) }
        }, "latch-capture").apply { isDaemon = true }.start()
    }

    private fun present(outcome: CaptureOutcome) {
        when (outcome) {
            is CaptureOutcome.Nothing -> tray?.say("Latch", messageFor(outcome.reason))
            is CaptureOutcome.Ready -> {
                // FR-1001, through the same function `:app` calls. FR-504's date order, the
                // default duration and FR-512's threshold all reach the parse from here, and a
                // client that filled them differently would read `05/09` the other way round
                // from the phone.
                val context = parseContextFor(
                    runCatching { settingsStore.read().shared }.getOrElse { LatchSettings() },
                    Instant.now(),
                )
                val result = DateParser.parse(outcome.capture.text, context)
                val selected = result.candidates.indices.toSet()

                window?.close()
                lastCaptured = outcome.capture
                applied = null
                val today = LocalDate.now()
                val opened = CaptureWindow(
                    onSave = { ticked, edits ->
                        save(outcome.capture, result, context, ticked, edits, today)
                    },
                    onExport = { ticked, edits ->
                        export(outcome.capture, result, context, ticked, edits, today)
                    },
                    onChooseRecipe = { recipe, edits -> chooseRecipe(recipe, result, today, edits) },
                    onSaveChain = { ticked -> saveChain(outcome.capture, context, ticked) },
                    onClose = {
                        window = null
                        // Cleared with the window, so a later save cannot ask FR-210a's
                        // question about a capture that is no longer on screen.
                        lastCaptured = null
                        applied = null
                    },
                )
                window = opened
                // The sheet re-derives from the edits on every change, so the badge, the date
                // line, the blocker and the write all read the same rows — `:app` does the same
                // with one `parsed.withEdits(edits, today)`, and for the same reason.
                opened.show(selected) { edits, ticked ->
                    val edited = result.withEdits(edits, today)
                    popupModel(
                        outcome.capture, edited, ticked, today,
                        edits.titleOverrides, edits.typeOverrides,
                    )
                }
                // FR-601's chooser arrives a moment after the sheet does. The recipe list
                // crosses the DPAPI bridge and NFR-101 budgets a capture 800 ms; the dates the
                // user came for are on screen first, and the chooser follows.
                offerRecipes(opened, result, today)
            }
        }
    }

    /**
     * FR-801 to FR-803, on the client `:app` uses.
     *
     * **What is deliberately not here, and is named rather than silently missing**:
     * FR-806's queue, so a save made offline is reported instead of held; FR-804's
     * reschedule offer, which needs a surface to ask on; and FR-807's undo. Each is owed
     * and each is in the backlog. A Save that quietly did nothing would be worse than one
     * that explains itself.
     */
    private fun save(
        captured: com.latch.desktop.capture.DesktopCapture,
        parsed: ParseResult,
        context: ParseContext,
        selected: Set<Int>,
        edits: SheetEdits,
        today: LocalDate,
    ) {
        // FR-506 row 3, FR-507 and FR-509b applied in one place, exactly as the sheet renders
        // them — so what was confirmed and what is written cannot differ.
        val result = parsed.withEdits(edits, today)
        val titleOverrides = edits.titleOverrides

        // FR-512, FR-506 rows 3 and 4, AC-03 — through the same compiled function the phone
        // calls, which is why it moved into `:wire`. **This retires SRS 1.55's interim reading
        // for this client**: an undated capture was saved as an undated to-do here because
        // there was nowhere to hold it, and now there is.
        val route = saveRoute(result, selected, context.confidenceThreshold, desktopSource(captured))
        if (route is SaveRoute.Inbox) {
            hold(captured, result, context, route.reason)
            return
        }

        if (!auth.isConfigured) {
            window?.showOutcome(DesktopStrings.NOT_CONFIGURED)
            return
        }
        if (!auth.isSignedIn) {
            window?.showOutcome(DesktopStrings.NOT_SIGNED_IN)
            return
        }

        Thread({
            val outcome = runCatching {
                runBlocking {
                    val defaults = defaultsStore.read() ?: return@runBlocking null
                    val chainId = java.util.UUID.randomUUID().toString()
                    val draft = draftItems(
                        captured = captured,
                        result = result,
                        context = context,
                        destination = defaults.toWireDestination(),
                        captureId = chainId,
                        chainId = chainId,
                        selected = selected,
                        titleOverrides = titleOverrides,
                    )
                    if (draft !is DraftResult.Ready) {
                        return@runBlocking SaveResult.Failed(SaveFailure.NOTHING_TO_WRITE)
                    }
                    saver(context.zone.id).save(captured, result, draft.items, defaults, chainId)
                }
            }.getOrElse { SaveResult.Failed(SaveFailure.REFUSED, it.message.orEmpty()) }

            SwingUtilities.invokeLater { present(outcome, context) }
        }, "latch-save").apply { isDaemon = true }.start()
    }

    private fun saver(zone: String) =
        DesktopSaver(
            calendarApi,
            tasksApi,
            zone,
            queue,
            movedNoteTemplate = DesktopStrings.MOVED_NOTE,
        )

    /**
     * What the window shows once a save has answered.
     *
     * FR-804's offer and FR-807's undo both live on the capture window rather than in a tray
     * balloon, because both are questions and a balloon cannot be answered. The window closes
     * itself when the undo window lapses — the window *is* the offer, so outliving it would
     * leave a dead thing on screen.
     */
    private fun present(outcome: SaveResult?, context: ParseContext) {
        val open = window ?: return
        when (outcome) {
            null -> open.showOutcome("No calendar is chosen yet. Sign in again to set one up.")

            is SaveResult.Written -> {
                // FR-806: a request that just succeeded says the network is back more reliably
                // than any interface check, so anything held gets a chance now.
                runner.nudge(DrainTrigger.REQUEST_SUCCEEDED)
                tray?.update(model())
                // FR-1004, after the write and before the undo offer goes up — so it can
                // neither delay the write nor hold up the offer.
                outcome.pending?.let { deliverWebhook(it, lastCaptured ?: return@let) }
                offerUndo(
                    open,
                    if (outcome.count == 1) DesktopStrings.SAVED
                    else outcome.count.toString() + " items saved to Latch.",
                    outcome.created,
                    wasUpdate = false,
                )
            }

            // SRS 1.191. Not routed through the `Written` branch above and that is the point:
            // no webhook is delivered for a save that partly did not happen, and the sentence
            // says which half did. The undo is still offered, over what landed.
            is SaveResult.PartlyWritten -> {
                tray?.update(model())
                offerUndo(
                    open,
                    String.format(
                        DesktopStrings.SAVED_PARTLY,
                        outcome.written,
                        outcome.total,
                        outcome.total - outcome.written,
                    ),
                    outcome.created,
                    wasUpdate = false,
                )
            }

            is SaveResult.Updated -> {
                runner.nudge(DrainTrigger.REQUEST_SUCCEEDED)
                offerUndo(open, DesktopStrings.UPDATED, listOf(outcome.created), wasUpdate = true)
            }

            SaveResult.AlreadySaved -> open.showOutcome(DesktopStrings.ALREADY_SAVED)

            is SaveResult.Queued -> {
                tray?.update(model())
                val created = outcome.queueId?.let { listOf(CreatedItem.Queued(it)) }.orEmpty()
                // FR-806a (SRS 1.192): why it is held, not merely that it is. "No connection"
                // over a live network with an expired grant names the wrong cure.
                val held =
                    if (outcome.needsSignIn) DesktopStrings.HELD_NEEDS_SIGN_IN
                    else DesktopStrings.HELD
                if (created.isEmpty()) open.showOutcome(held)
                else offerUndo(open, held, created, wasUpdate = false)
            }

            is SaveResult.RescheduleOffered -> open.showRescheduleOffer(
                text = rescheduleOfferText(outcome.storedTitle, outcome.match.dates, outcome.proposed),
                onUpdate = { answerOffer(outcome, update = true, context = context) },
                onCreateNew = { answerOffer(outcome, update = false, context = context) },
            )

            is SaveResult.Failed -> open.showOutcome(saveMessage(outcome))
        }
    }

    /** FR-804's two answers. Nothing was written until one of them was chosen. */
    private fun answerOffer(
        offer: SaveResult.RescheduleOffered,
        update: Boolean,
        context: ParseContext,
    ) {
        Thread({
            val outcome = runCatching {
                runBlocking {
                    val worker = saver(offer.pending.timeZone)
                    if (update) worker.applyReschedule(offer) else worker.createAnyway(offer)
                }
            }.getOrElse { SaveResult.Failed(SaveFailure.REFUSED, it.message.orEmpty()) }
            SwingUtilities.invokeLater { present(outcome, context) }
        }, "latch-reschedule").apply { isDaemon = true }.start()
    }

    private fun offerUndo(
        open: CaptureWindow,
        text: String,
        created: List<CreatedItem>,
        wasUpdate: Boolean,
    ) {
        if (created.isEmpty()) {
            open.showOutcome(text)
            return
        }
        open.showSaved(
            text = text,
            savedAt = java.time.Instant.now(),
            onUndo = {
                Thread({
                    val outcome = runCatching {
                        runBlocking { undoCreated(created, calendarApi, tasksApi, queue) }
                    }.getOrElse { RemovalOutcome(0, created.size) }
                    SwingUtilities.invokeLater {
                        tray?.update(model())
                        window?.showOutcome(undoOutcomeText(outcome, wasUpdate))
                    }
                }, "latch-undo").apply { isDaemon = true }.start()
            },
            onLapse = { tray?.update(model()) },
        )
    }

    // ---------------------------------------------------------------- FR-600, recipes

    /** FR-602's eight with any the user has shadowed replaced, and their own appended. */
    private fun allRecipes(): List<Recipe> =
        recipesFor(BuiltInRecipes.all, runCatching { recipeStore.all() }.getOrDefault(emptyList()))

    private fun offerRecipes(open: CaptureWindow, result: ParseResult, today: LocalDate) {
        Thread({
            // Read once — it crosses the DPAPI bridge — and closed over, so FR-601's blocker can
            // be re-asked on every edit without paying for the list again.
            val recipes = allRecipes()
            SwingUtilities.invokeLater {
                if (window === open) {
                    open.offerRecipes { edits -> recipeChooser(result.withEdits(edits, today), recipes) }
                }
            }
        }, "latch-recipes-offer").apply { isDaemon = true }.start()
    }

    /**
     * FR-601: the chosen recipe expanded into a chain, or the capture put back as it was.
     *
     * Null is "just this one", and it exists because applying a recipe must not be a one-way
     * gesture on a floating window: the user is looking at a popup they may be about to dismiss,
     * and a chooser with no way back would make a mis-click cost them the capture.
     */
    private fun chooseRecipe(
        recipe: Recipe?,
        parsed: ParseResult,
        today: LocalDate,
        edits: SheetEdits,
    ) {
        val open = window ?: return
        // FR-506 row 3's assigned date is what makes an otherwise unexpandable capture
        // expandable, so the recipe is applied to the edited parse and not to the original.
        val result = parsed.withEdits(edits, today)
        if (recipe == null) {
            applied = null
            open.renderChain(null)
            return
        }
        Thread({
            val settings = runCatching { settingsStore.read().shared }.getOrElse { LatchSettings() }
            val chainId = java.util.UUID.randomUUID().toString()
            val chain = applyRecipe(recipe, result, settings, chainId, today)
            SwingUtilities.invokeLater {
                if (window !== open) return@invokeLater
                if (chain == null) {
                    open.showOutcome(DesktopStrings.RECIPE_NO_DATE)
                    return@invokeLater
                }
                applied = Triple(recipe, chain, result)
                // A function of the ticks, not a snapshot of them: the window re-asks on every
                // change, which is what makes FR-608's deselection stick.
                open.renderChain({ ticked -> recipeChainModel(recipe, chain.planned, ticked) }, chain.selected)
            }
        }, "latch-recipe-expand").apply { isDaemon = true }.start()
    }

    /**
     * FR-601, FR-607, FR-608: the chain, written.
     *
     * **`recipeItems` is the one place a chain's destination is chosen**, which is what makes
     * FR-607 structural rather than a rule a call site could forget. And FR-512's threshold is
     * deliberately not consulted: picking a template against a date the user can see is a
     * stronger confirmation than the threshold is testing for, and routing it to the Inbox would
     * discard the chain they chose along the way. That is the phone's reading, followed.
     */
    private fun saveChain(
        captured: com.latch.desktop.capture.DesktopCapture,
        context: ParseContext,
        ticked: Set<Int>,
    ) {
        val (recipe, chain, result) = applied ?: return
        if (!auth.isConfigured) {
            window?.showOutcome(DesktopStrings.NOT_CONFIGURED)
            return
        }
        if (!auth.isSignedIn) {
            window?.showOutcome(DesktopStrings.NOT_SIGNED_IN)
            return
        }

        Thread({
            val outcome = runCatching {
                runBlocking {
                    val defaults = defaultsStore.read() ?: return@runBlocking null
                    val settings = runCatching { settingsStore.read().shared }.getOrElse { LatchSettings() }
                    val chainId = java.util.UUID.randomUUID().toString()
                    val items = recipeItems(
                        planned = chain.planned,
                        selected = ticked,
                        captureId = chainId,
                        chainId = chainId,
                        destination = defaults.toWireDestination(),
                        context = context,
                        // FR-1001's default lead time, applied only where a step names none of
                        // its own — a step's reminders are a property of the recipe.
                        defaultReminderMinutes = settings.defaultReminderMinutes,
                    )
                    if (items.isEmpty()) {
                        return@runBlocking SaveResult.Failed(SaveFailure.NOTHING_TO_WRITE)
                    }
                    saver(context.zone.id)
                        .save(captured, result, items, defaults, chainId, recipeId = recipe.id)
                }
            }.getOrElse { SaveResult.Failed(SaveFailure.REFUSED, it.message.orEmpty()) }
            SwingUtilities.invokeLater { present(outcome, context) }
        }, "latch-recipe-save").apply { isDaemon = true }.start()
    }

    /** FR-603's four verbs, on one window. */
    private fun openRecipes() {
        val opened = recipesWindow ?: RecipesWindow(
            onSave = ::saveRecipe,
            onDuplicate = { recipe ->
                storeRecipe(duplicateOf(recipe, DesktopStrings.RECIPE_COPY_SUFFIX))
            },
            onDelete = { recipe -> deleteRecipe(recipe.id) },
            onNew = {
                val fresh = newRecipe(DesktopStrings.RECIPE_NEW_NAME, DesktopStrings.RECIPE_NEW_STEP)
                storeRecipe(fresh) { recipesWindow?.edit(fresh) }
            },
            onClose = { recipesWindow = null },
        ).also { recipesWindow = it }
        refreshRecipes(opened) { all, own -> opened.show(all, own) }
    }

    private fun refreshRecipes(
        open: RecipesWindow,
        then: (List<Recipe>, List<Recipe>) -> Unit = { all, own -> open.render(all, own) },
    ) {
        Thread({
            val own = runCatching { recipeStore.all() }.getOrDefault(emptyList())
            val all = recipesFor(BuiltInRecipes.all, own)
            SwingUtilities.invokeLater { then(all, own) }
        }, "latch-recipes-read").apply { isDaemon = true }.start()
    }

    private fun saveRecipe(form: RecipeForm) {
        val open = recipesWindow ?: return
        val applied = applyRecipeForm(form)
        val recipe = applied.recipe
        if (recipe == null) {
            open.say(applied.problems.joinToString(" ") { recipeProblemText(it) })
            return
        }
        storeRecipe(recipe)
    }

    /**
     * FR-603's store, and the shadowing rule in one line.
     *
     * `editableCopyOf` keeps a built-in's id and clears its `builtIn` flag, so a stored copy
     * *shadows* the shipped one rather than replacing it — which is what makes deleting the copy
     * a restore. Neither client re-decides that; `:recipes` owns it.
     */
    private fun storeRecipe(recipe: Recipe, then: () -> Unit = {}) {
        Thread({
            val stored = runCatching { recipeStore.save(editableCopyOf(recipe)) }.isSuccess
            SwingUtilities.invokeLater {
                recipesWindow?.let { open ->
                    open.say(if (stored) DesktopStrings.RECIPE_SAVED else DesktopStrings.RECIPE_WRITE_FAILED)
                    refreshRecipes(open)
                }
                then()
            }
        }, "latch-recipes-save").apply { isDaemon = true }.start()
    }

    private fun deleteRecipe(recipeId: String) {
        Thread({
            runCatching { recipeStore.delete(recipeId) }
            SwingUtilities.invokeLater { recipesWindow?.let { refreshRecipes(it) } }
        }, "latch-recipes-delete").apply { isDaemon = true }.start()
    }

    // ---------------------------------------------------------------- FR-1000, Settings

    private fun openSettings() {
        val opened = settingsWindow ?: SettingsWindow(
            onSave = ::saveSettings,
            onSetEndpoint = ::setEndpoint,
            onClearEndpoint = ::clearEndpoint,
            onClose = { settingsWindow = null },
        ).also { settingsWindow = it }
        opened.show(runCatching { settingsStore.read() }.getOrElse { DesktopSettings() })
        refreshWebhook(opened)
    }

    /**
     * FR-1004, NFR-203 and FR-1004b's passive report, read off the secret store.
     *
     * Off the event thread because it crosses the DPAPI bridge twice; the window is already up
     * with everything else on it, so the mask arrives a moment later rather than the whole
     * screen arriving a moment later.
     */
    private fun refreshWebhook(window: SettingsWindow) {
        Thread({
            val mask = endpointLine(runCatching { webhookSecrets.endpoint() }.getOrNull())
            val last = deliveryText(runCatching { webhookSecrets.lastDelivery() }.getOrNull())
            SwingUtilities.invokeLater { window.fillWebhook(mask, last) }
        }, "latch-webhook-read").apply { isDaemon = true }.start()
    }

    /**
     * FR-1004: the endpoint, entered explicitly and validated before it is stored.
     *
     * **Saving one does not start sending.** The requirement asks for the URL to be entered
     * explicitly *and* for the feature to be enabled, and this client keeps those as two acts:
     * the switch is on the main form and takes effect on Save.
     */
    private fun setEndpoint(raw: String) {
        val window = settingsWindow ?: return
        val refusal = validateEndpoint(raw)
        if (refusal != null) {
            window.say(endpointRefusalText(refusal))
            return
        }
        Thread({
            val stored = runCatching { webhookSecrets.setEndpoint(raw) }.isSuccess
            SwingUtilities.invokeLater {
                settingsWindow?.let {
                    it.say(if (stored) DesktopStrings.WEBHOOK_SAVED else DesktopStrings.SETTINGS_WRITE_FAILED)
                    if (stored) it.clearEndpointEntry()
                    refreshWebhook(it)
                }
            }
        }, "latch-webhook-set").apply { isDaemon = true }.start()
    }

    /** FR-1004: with no endpoint there is nowhere to send, so the switch goes off with it. */
    private fun clearEndpoint() {
        Thread({
            runCatching { webhookSecrets.clearEndpoint() }
            runCatching {
                val current = settingsStore.read()
                settingsStore.write(current.copy(shared = current.shared.copy(webhookEnabled = false)))
            }
            SwingUtilities.invokeLater {
                settingsWindow?.let {
                    it.say(DesktopStrings.WEBHOOK_CLEARED)
                    it.clearEndpointEntry()
                    it.fill(com.latch.desktop.ui.formOf(settingsStore.read()))
                    refreshWebhook(it)
                }
            }
        }, "latch-webhook-clear").apply { isDaemon = true }.start()
    }

    private fun endpointRefusalText(refusal: EndpointRefusal) = when (refusal) {
        EndpointRefusal.MALFORMED -> DesktopStrings.ENDPOINT_MALFORMED
        EndpointRefusal.NOT_HTTPS -> DesktopStrings.ENDPOINT_NOT_HTTPS
        EndpointRefusal.CARRIES_CREDENTIALS -> DesktopStrings.ENDPOINT_CREDENTIALS
    }

    /**
     * FR-1004, FR-1004a, FR-1004b and FR-210a.
     *
     * **Fired after the write and never awaited**, which is every clause of FR-1004b that is
     * about ordering: it cannot block or delay the Google write because the write has already
     * happened, and it cannot block FR-807's undo because the undo offer is put on screen by
     * the caller before this thread has done anything.
     *
     * **One attempt.** There is no loop and no queue — and it could not enter the FR-806 queue
     * even by mistake, because everything in there drains through the Google client's
     * `ALLOWED_HOSTS` guard, which refuses any non-Google host.
     *
     * **Only for a save that actually wrote.** A queued capture reaches Google later and sends
     * no webhook at all (AC-21), which the configuration screen states rather than leaving to
     * be discovered; and an FR-804 update sends none either, matching the phone — the payload
     * describes items a save created, and an update creates none.
     */
    private fun deliverWebhook(pending: PendingWrite, captured: com.latch.wire.WireCapture) {
        Thread({
            val endpoint = runCatching { webhookSecrets.endpoint() }.getOrNull()
            val enabled = runCatching { settingsStore.read().shared.webhookEnabled }.getOrDefault(false)
            // FR-210a lives inside `webhookEligible` rather than here, so a second call site
            // could not forget it. On this client the layer is never NOTIFICATION, and asking
            // anyway is what keeps the rule structural rather than incidental.
            if (!webhookEligible(desktopSource(captured), enabled, endpoint)) return@Thread

            val delivery = runCatching {
                runBlocking {
                    webhooks.deliver(
                        endpoint = requireNotNull(endpoint),
                        payload = webhookPayloadForChain(pending.items, pending.metadata, pending.body),
                    )
                }
            }.getOrNull() ?: return@Thread

            // FR-1004b: reported passively in Settings, never as a blocking error and never on
            // the capture window, which by now is showing an undo the user may be about to take.
            runCatching { webhookSecrets.recordDelivery(delivery) }
            SwingUtilities.invokeLater { settingsWindow?.let(::refreshWebhook) }
        }, "latch-webhook").apply { isDaemon = true }.start()
    }

    // ---------------------------------------------------------------- FR-700, the Inbox

    /**
     * FR-1001. Nothing is stored unless the whole form is good, and the hotkey is re-registered
     * only if it actually changed.
     *
     * **A refused combination puts the old one back**, which is the difference between a
     * setting that failed and a client with no way in. `RegisterHotKey` is system-wide, so
     * "already held by another application" is a normal answer and not an error.
     */
    private fun saveSettings(form: SettingsForm) {
        val window = settingsWindow ?: return
        val current = runCatching { settingsStore.read() }.getOrElse { DesktopSettings() }
        val hasEndpoint = runCatching { webhookSecrets.endpoint() != null }.getOrDefault(false)
        val applied = applyForm(current, form, hasEndpoint)
        val wanted = applied.settings
        if (wanted == null) {
            window.say(applied.problems.joinToString(" ") { settingsProblemText(it) })
            return
        }

        val stored = runCatching { settingsStore.write(wanted) }.isSuccess
        if (!stored) {
            window.say(DesktopStrings.SETTINGS_WRITE_FAILED)
            return
        }
        window.fill(com.latch.desktop.ui.formOf(wanted))

        if (!hotkeyChanged(current, wanted)) {
            window.say(DesktopStrings.SETTINGS_SAVED)
            return
        }

        val previous = current
        registerHotkey(specOf(wanted.hotkey)) {
            // Put back what was working. The user changed a shortcut and must not be left
            // without one because the new combination belongs to something else.
            runCatching { settingsStore.write(previous) }
            registerHotkey(specOf(previous.hotkey))
            settingsWindow?.let {
                it.fill(com.latch.desktop.ui.formOf(previous))
                it.say(DesktopStrings.SETTINGS_HOTKEY_REFUSED.replace("%s", wanted.hotkey))
            }
        }
        window.say(DesktopStrings.SETTINGS_HOTKEY_RETAKEN.replace("%s", spec.display))
    }

    // ---------------------------------------------------------------- FR-700, the Inbox

    /**
     * FR-701: the capture is held on this machine and **nothing reaches Google** (FR-703).
     *
     * The row stores the text and the capture own `now` and zone, never the parse — FR-515
     * makes a parse a function of the instant it was made at, so replaying the stored context is
     * what reproduces the reading the user was shown rather than a fresh one against today
     * clock.
     *
     * Off the event thread because the store crosses the DPAPI bridge, which costs most of a
     * second: doing it inline would freeze the popup at the moment the user pressed Save.
     */
    private fun hold(
        captured: com.latch.desktop.capture.DesktopCapture,
        result: ParseResult,
        context: ParseContext,
        reason: InboxReason,
    ) {
        Thread({
            val held = runCatching {
                inbox.add(
                    InboxCapture(
                        id = java.util.UUID.randomUUID().toString(),
                        rawText = captured.text,
                        layer = desktopSource(captured).layer,
                        appId = null,
                        preferredTitle = captured.preferredTitle,
                        ocrUsed = captured.ocrUsed,
                        capturedAt = Instant.now(),
                        capturedLocal = context.now,
                        zone = context.zone.id,
                        confidence = result.overallConfidence.value,
                        reason = reason,
                    )
                )
            }.isSuccess
            SwingUtilities.invokeLater {
                tray?.update(model())
                inboxWindow?.let { refreshInbox(it) }
                // NFR-303. There is no queue behind this — the Inbox *is* the local store — so
                // a failure here means the capture is nowhere, and saying so is the whole of
                // what can be done about it.
                window?.showOutcome(
                    if (held) DesktopStrings.INBOX_ADDED else DesktopStrings.INBOX_ADD_FAILED,
                )
            }
        }, "latch-inbox-hold").apply { isDaemon = true }.start()
    }

    /** FR-702 triage surface. One window, reopened rather than stacked. */
    private fun openInbox() {
        val opened = inboxWindow ?: InboxWindow(
            onAssignDate = { id, date -> edit(id) { it.copy(assignedDate = date) } },
            onEditTitle = { id, title ->
                edit(id) { it.copy(editedTitle = title.trim().takeIf(String::isNotEmpty)) }
            },
            onOverrideType = { id, type -> edit(id) { it.copy(typeOverride = type) } },
            onSave = ::saveHeld,
            // A week, and it is the phone reading rather than a fresh one: shorter and the row
            // returns before the reason it was put off has changed, longer and FR-705 review
            // period is doing the work instead.
            onSnooze = { id -> edit(id) { it.copy(snoozedUntil = Instant.now().plus(SNOOZE)) } },
            onDiscard = ::discardHeld,
            onClose = { inboxWindow = null },
        ).also { inboxWindow = it }

        Thread({
            val model = readInbox()
            SwingUtilities.invokeLater { opened.show(model) }
        }, "latch-inbox-open").apply { isDaemon = true }.start()
    }

    private fun readInbox() = runCatching {
        val now = Instant.now()
        inboxModel(
            captures = inbox.due(now),
            now = now,
            unreadable = inbox.status(now).unreadable,
        )
    }.getOrElse { inboxModel(emptyList(), Instant.now()) }

    private fun refreshInbox(window: InboxWindow) {
        Thread({
            val model = readInbox()
            SwingUtilities.invokeLater { window.render(model) }
        }, "latch-inbox-refresh").apply { isDaemon = true }.start()
    }

    private fun edit(id: String, change: (InboxCapture) -> InboxCapture) {
        Thread({
            runCatching { inbox.find(id)?.let { inbox.update(change(it)) } }
            SwingUtilities.invokeLater {
                tray?.update(model())
                inboxWindow?.let { refreshInbox(it) }
            }
        }, "latch-inbox-edit").apply { isDaemon = true }.start()
    }

    /** FR-702: discard. The user has decided; nothing is kept and nothing reached Google. */
    private fun discardHeld(id: String) {
        Thread({
            runCatching { inbox.discard(id) }
            SwingUtilities.invokeLater {
                tray?.update(model())
                inboxWindow?.let { refreshInbox(it) }
            }
        }, "latch-inbox-discard").apply { isDaemon = true }.start()
    }

    /**
     * FR-702 save, and FR-703 boundary: this is the confirmation, and the first moment
     * anything about this capture leaves this machine.
     *
     * **The row is not routed back into the Inbox**, which is `CaptureSaver` rule on the phone
     * and is the same one here: a capture the user has confirmed out of the Inbox has already
     * passed the only judgement FR-512 threshold was standing in for.
     *
     * **The row is discarded only once the write lands.** A failure has to leave it exactly
     * where it was, which is the whole point of the Inbox holding it — and a "Queued" answer is
     * *not* a landing, so the row stays until the queue has actually written it.
     */
    private fun saveHeld(id: String) {
        if (!auth.isConfigured) {
            inboxWindow?.say(DesktopStrings.NOT_CONFIGURED)
            return
        }
        if (!auth.isSignedIn) {
            inboxWindow?.say(DesktopStrings.NOT_SIGNED_IN)
            return
        }
        inboxWindow?.say(DesktopStrings.INBOX_SAVING)

        var heldCapture: com.latch.desktop.capture.DesktopCapture? = null
        Thread({
            val outcome = runCatching {
                runBlocking {
                    val capture = inbox.find(id) ?: return@runBlocking null
                    val defaults = defaultsStore.read() ?: return@runBlocking null
                    val context = parseContextOf(capture)
                    val today = LocalDate.now()
                    val result = parseOf(capture, context, today)
                    val wire = com.latch.desktop.capture.DesktopCapture(
                        text = capture.rawText,
                        // FR-702 edited title deliberately does **not** travel here: §7.2
                        // returns `preferredTitle` verbatim as `item_key` input, so a user
                        // correction routed through it would silently move the item identity
                        // and make it permanently unmatchable by FR-804. It goes as FR-509b
                        // title override instead.
                        preferredTitle = capture.preferredTitle,
                        ocrUsed = capture.ocrUsed,
                    ).also { heldCapture = it }
                    val chainId = java.util.UUID.randomUUID().toString()
                    val draft = draftItems(
                        captured = wire,
                        result = result,
                        context = context,
                        destination = defaults.toWireDestination(),
                        captureId = chainId,
                        chainId = chainId,
                        titleOverrides = titleOverridesOf(capture, result),
                    )
                    if (draft !is DraftResult.Ready) {
                        return@runBlocking SaveResult.Failed(SaveFailure.NOTHING_TO_WRITE)
                    }
                    saver(context.zone.id).save(wire, result, draft.items, defaults, chainId)
                }
            }.getOrElse { SaveResult.Failed(SaveFailure.REFUSED, it.message.orEmpty()) }

            // FR-1004. A row confirmed out of the Inbox is a save like any other, which is
            // the phone's reading too — `CaptureSaver` delivers for an Inbox save on the same
            // path as a fresh capture.
            (outcome as? SaveResult.Written)?.pending?.let { pending ->
                heldCapture?.let { deliverWebhook(pending, it) }
            }

            // Written, or already there — either way the message is in the account and the row
            // has done its job. A queued or failed save leaves it standing, and so does a
            // partly written one (SRS 1.191): the row holds text that exists nowhere else for
            // the items that were refused, and discarding it to tidy up would lose them.
            val settled = outcome is SaveResult.Written ||
                outcome is SaveResult.Updated ||
                outcome == SaveResult.AlreadySaved
            if (settled) runCatching { inbox.discard(id) }

            SwingUtilities.invokeLater {
                tray?.update(model())
                inboxWindow?.let { open ->
                    refreshInbox(open)
                    open.say(heldSaveMessage(outcome))
                }
            }
        }, "latch-inbox-save").apply { isDaemon = true }.start()
    }

    private fun heldSaveMessage(outcome: SaveResult?): String = when (outcome) {
        null -> DesktopStrings.INBOX_SAVE_FAILED
        is SaveResult.Written -> DesktopStrings.SAVED
        // SRS 1.191, and the row deliberately stays (see `settled` below): part of this
        // capture is in the account and the rest is not, which is not a state the Inbox has
        // an action for.
        is SaveResult.PartlyWritten -> String.format(
            DesktopStrings.SAVED_PARTLY,
            outcome.written,
            outcome.total,
            outcome.total - outcome.written,
        )
        is SaveResult.Updated -> DesktopStrings.UPDATED
        SaveResult.AlreadySaved -> DesktopStrings.ALREADY_SAVED
        is SaveResult.Queued ->
            if (outcome.needsSignIn) DesktopStrings.HELD_NEEDS_SIGN_IN else DesktopStrings.HELD
        // FR-804 offer has no surface on this window and must not be answered blind, so the
        // save stands unwritten and the row stays. Re-capturing the text puts the question on
        // the popup, which is where it can be answered.
        is SaveResult.RescheduleOffered -> DesktopStrings.INBOX_SAVE_FAILED
        is SaveResult.Failed -> saveMessage(outcome)
    }

    /**
     * FR-1005, and it writes nothing to Google — which is the point of offering it beside Save
     * rather than after it. A user who wants the dates in their own calendar program, or who
     * has not signed in, still gets something out of a capture.
     */
    private fun export(
        captured: com.latch.desktop.capture.DesktopCapture,
        parsed: ParseResult,
        context: ParseContext,
        selected: Set<Int>,
        edits: SheetEdits,
        today: LocalDate,
    ) {
        // FR-506 row 3, FR-507 and FR-509b applied in one place, exactly as the sheet renders
        // them — so what was confirmed and what is written cannot differ.
        val result = parsed.withEdits(edits, today)
        val titleOverrides = edits.titleOverrides
        val chainId = java.util.UUID.randomUUID().toString()
        val draft = draftItems(
            captured = captured,
            result = result,
            context = context,
            // FR-1005 exports what was captured, not where it would have been filed, so these
            // ids are placeholders and `exportItems` replaces them anyway.
            destination = com.latch.wire.WireDestination("export", "export"),
            captureId = chainId,
            chainId = chainId,
            selected = selected,
            titleOverrides = titleOverrides,
        )
        val file = (draft as? DraftResult.Ready)
            ?.let { IcsFile.write(it.items, chainId, context.zone.id) }

        if (file == null) {
            window?.showOutcome(DesktopStrings.EXPORT_FAILED)
            return
        }
        window?.showOutcome(DesktopStrings.EXPORTED + " " + file.name)
    }

    /**
     * **A refusal says what Google said.**
     *
     * `SaveResult.Failed` has carried a detail since it was written and both call sites passed
     * only the enum, so a permanent rejection — the one outcome where the capture is neither
     * written nor queued, and is therefore *gone* — was reported as five words with no cause.
     * Nobody can act on that: there is no log on this client, so the sentence on screen is the
     * only instrument there is.
     *
     * The detail is Google's own status, reason and message, and it never leaves the machine.
     */
    private fun saveMessage(outcome: SaveResult.Failed) = when (outcome.reason) {
        SaveFailure.NOT_SIGNED_IN -> DesktopStrings.NOT_SIGNED_IN
        SaveFailure.NO_DESTINATION -> "No calendar is chosen yet."
        // Reached only where there is no queue to hold it, which in this build means the
        // store itself refused — a failure worth naming rather than swallowing.
        SaveFailure.OFFLINE -> "No connection, and this capture could not be held. Try again."
        SaveFailure.REFUSED -> detailed("Google refused the write.", outcome.detail)
        SaveFailure.NOTHING_TO_WRITE -> DesktopStrings.NOTHING_TICKED
    }

    private fun detailed(sentence: String, detail: String): String =
        if (detail.isBlank()) sentence else sentence + " " + detail

    private fun signIn() {
        if (!auth.isConfigured) {
            tray?.say("Latch", DesktopStrings.NOT_CONFIGURED, TrayIcon.MessageType.WARNING)
            return
        }
        Thread({
            val outcome = auth.signIn(::openInBrowser)
            SwingUtilities.invokeLater {
                tray?.update(model())
                when (outcome) {
                    SignInOutcome.Succeeded -> {
                        // FR-806a (SRS 1.192). Captures held for want of a sign-in are the
                        // reason the user may well have just done this, and waiting for the
                        // timer would leave up to half an hour of nothing happening after
                        // they did exactly what the tray asked.
                        runner.nudge(DrainTrigger.SIGNED_IN)
                        chooseDestination()
                    }
                    is SignInOutcome.Failed -> tray?.say(
                        "Latch", signInMessage(outcome.reason), TrayIcon.MessageType.WARNING,
                    )
                }
            }
        }, "latch-signin").apply { isDaemon = true }.start()
    }

    /**
     * FR-901 and FR-104, narrowed: reuse the account's Latch calendar or make one.
     *
     * Not FR-100's setup wizard, which asks which mode and which calendar. There is no
     * screen to ask on yet, so this takes the Option B default and says what it did. The
     * FR-900 destination picker is owed on this client.
     */
    private fun chooseDestination() {
        Thread({
            val outcome = runCatching {
                runBlocking {
                    val token = auth.accessToken() ?: return@runBlocking null
                    val account = fetchPrimaryAccount(token)
                    DesktopSetup(calendarApi, tasksApi).chooseDestination(account.email)
                }
            }.getOrNull()

            SwingUtilities.invokeLater {
                when (outcome) {
                    is SetupResult.Ready -> {
                        defaultsStore.write(outcome.defaults)
                        tray?.update(model())
                        tray?.say(
                            "Latch",
                            "Signed in. Captures go to your " + outcome.defaults.calendarName + " calendar.",
                        )
                    }
                    else -> tray?.say(
                        "Latch",
                        "Signed in, but Latch could not read your calendars. Try again later.",
                        TrayIcon.MessageType.WARNING,
                    )
                }
            }
        }, "latch-setup").apply { isDaemon = true }.start()
    }

    private fun signInMessage(reason: SignInFailure) = when (reason) {
        SignInFailure.NOT_CONFIGURED -> DesktopStrings.NOT_CONFIGURED
        SignInFailure.CANCELLED -> "Sign-in was cancelled. Nothing was changed."
        SignInFailure.NO_REFRESH_TOKEN ->
            "Google did not return a lasting sign-in. Try again, and allow access when asked."
        SignInFailure.NETWORK -> "Could not reach Google. Check the connection and try again."
        SignInFailure.REFUSED_BY_GOOGLE -> "Google refused the sign-in."
        SignInFailure.BROWSER -> "Latch could not open a browser to sign in."
    }

    private fun openInBrowser(url: String): Boolean = runCatching {
        if (!Desktop.isDesktopSupported()) return false
        Desktop.getDesktop().browse(URI(url))
        true
    }.getOrDefault(false)

    /** FR-702 snooze. See `openInbox` for the reading behind the week. */
    private val SNOOZE: Duration = Duration.ofDays(7)

    private fun shutDown() {
        // The hotkey registration lives as long as its child process, so this is what gives
        // the combination back to the rest of the desktop.
        runCatching { runner.close() }
        hotkey?.close()
        window?.close()
        inboxWindow?.close()
        settingsWindow?.close()
        recipesWindow?.close()
        tray?.close()
    }
}

fun main() {
    if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) {
        System.err.println("Latch for Windows needs Windows: FR-302's hotkey and FR-303's recogniser are both Win32.")
        exitProcess(1)
    }
    SwingUtilities.invokeLater { Latch.start() }
}
