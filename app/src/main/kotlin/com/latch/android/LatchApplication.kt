package com.latch.android

import android.app.Application
import android.content.ComponentName
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import com.latch.android.capture.CaptureSaver
import com.latch.android.capture.Destination
import com.latch.android.capture.DrainTrigger
import com.latch.android.capture.CaptureTileService
import com.latch.android.capture.WriteQueueWorker
import com.latch.android.capture.countingOverride
import com.latch.android.capture.removeCreated
import com.latch.android.capture.ruleFromOverride
import com.latch.android.capture.shouldDrainNow
import com.latch.android.inbox.InboxCoordinator
import com.latch.android.recipes.RecipeCoordinator
import com.latch.android.settings.SettingsCoordinator
import com.latch.android.setup.AuthResolutionBridge
import com.latch.android.setup.GoogleAuthClient
import com.latch.android.setup.SetupCoordinator
import com.latch.data.AccountDefaults
import com.latch.data.CaptureInbox
import com.latch.data.EncryptedAccountDefaultsStore
import com.latch.data.EncryptedSecretStore
import com.latch.data.EncryptedSettingsStore
import com.latch.data.LatchSettings
import com.latch.data.RecipeStore
import com.latch.data.SecretStore
import com.latch.data.SettingsStore
import com.latch.data.SqliteRecipeStore
import com.latch.data.recipesFor
import com.latch.data.EncryptedWriteQueueStore
import com.latch.data.LocalItemIndex
import com.latch.data.QueueStatus
import com.latch.data.SqliteCaptureInbox
import com.latch.data.SqliteItemIndex
import com.latch.data.SqliteUndoOfferStore
import com.latch.data.StoredUndoOffer
import com.latch.data.UndoOfferStore
import com.latch.data.WriteQueue
import com.latch.data.googleCalendarApi
import com.latch.data.googleTasksApi
import com.latch.core.model.CaptureLayer
import com.latch.core.model.Holiday
import com.latch.core.model.Recipe
import com.latch.ocr.MlKitOcrReader
import com.latch.ocr.OcrReader
import com.latch.recipes.BuiltInRecipes
import com.latch.recipes.IndianHolidays
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * NFR-104: no persistent background service other than the optional notification listener.
 * Nothing is started here, and nothing should be — capture layers are entry points that the
 * system brings up on demand.
 */
class LatchApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob())

    /**
     * `by lazy`, not a property initializer. A property initializer runs inside the
     * Application *constructor*, which is before `attachBaseContext`, so `this` has no base
     * context yet and any `getApplicationContext` off it throws. Every context-touching
     * field here has to be deferred to first use or to `onCreate` for the same reason.
     */
    val accountDefaults by lazy { EncryptedAccountDefaultsStore(this) }

    private val _configuredAccounts = MutableStateFlow<List<AccountDefaults>?>(null)

    /**
     * FR-101: what decides whether setup runs. Null while the read is in flight, which is
     * a state the UI has to have — an empty list and "not read yet" mean opposite things,
     * and treating them alike would show setup to a user who has already finished it.
     *
     * The store moves itself to `Dispatchers.IO`, so where a refresh is launched from
     * carries no obligation.
     */
    val configuredAccounts: StateFlow<List<AccountDefaults>?> = _configuredAccounts.asStateFlow()

    /**
     * Re-read the stored defaults.
     *
     * This used to happen once, in `onCreate`, and that was a defect: completing setup
     * writes to the store but cannot reach this flow, so it stayed at whatever it held when
     * the process started — empty, for the run that has just finished setting up. MainActivity
     * did not notice, because it also consults the setup outcome. The capture path has no
     * such second opinion, so it saw no destination and disabled Save for the life of the
     * process. Anything that writes defaults must call this.
     */
    fun refreshAccounts() {
        appScope.launch { _configuredAccounts.value = accountDefaults.allAccounts() }
    }

    override fun onCreate() {
        super.onCreate()
        // TODO(FR-908): this is where the stored destination first comes back into the app,
        //  and so where re-validation belongs once CalendarApi is real. FR-908 requires the
        //  calendar list to be refreshed on launch, and a `destinationCalendarId` that has
        //  gone missing or lost write access to fall back to the primary calendar with the
        //  user told. Nothing reads the id yet, which is the only reason this is a comment
        //  rather than a defect: StubCalendarApi mints a fresh latch-N id every process, so
        //  a stored id already fails to name anything the next launch can see.
        refreshAccounts()
        refreshQueueStatus()
        refreshInboxCount()
        refreshUndoOffer()
        refreshSettings()
        refreshRecipes()
        // Every save moves this, wherever it was started from — the capture sheet, or the
        // Inbox's own Save button — and every save can change all three counts: a row leaves
        // the Inbox, an entry joins the queue, an FR-807 offer opens. Subscribing once here is
        // what keeps the two screens from each having to remember to ask.
        appScope.launch {
            captureSaver.state.collect {
                refreshInboxCount()
                refreshQueueStatus()
                refreshUndoOffer()
                inboxCoordinator.refresh()
            }
        }
        // Registered here rather than by a screen: the queue drains whether or not anything is
        // on screen, and a capture made from the share sheet may never bring one up at all.
        runCatching {
            getSystemService(ConnectivityManager::class.java)
                ?.registerDefaultNetworkCallback(connectivity)
        }
        // FR-806: a queue that outlived the process — killed mid-drain, or the device
        // restarted — has nothing scheduled to drain it. WorkManager keeps its own record of
        // unfinished work, but an entry queued by a process that died before it could
        // schedule anything would otherwise wait for the next capture to be noticed.
        WriteQueueWorker.schedule(this)
    }

    /**
     * Held here, not on MainActivity, because a consent screen must survive the rotation
     * that destroys the Activity which launched it. MainActivity attaches and detaches.
     */
    val authResolution = AuthResolutionBridge()

    /**
     * The OAuth grant and the token supply are the same object: the REST clients draw tokens
     * from the grant setup obtained. Nothing persists a token — see [GoogleAuthClient].
     */
    internal val authClient by lazy { GoogleAuthClient(this, authResolution) }

    /**
     * Internal rather than private: `WriteQueueWorker` is constructed by WorkManager, not by
     * this class, so it has nowhere to be handed its dependencies from and reaches back here
     * for them. That is the whole reason these are visible.
     */
    internal val calendarApi by lazy { googleCalendarApi(authClient) }
    internal val tasksApi by lazy { googleTasksApi(authClient) }

    /** FR-806, NFR-302. See [EncryptedWriteQueueStore] for why the payload lives here. */
    val writeQueue: WriteQueue by lazy { EncryptedWriteQueueStore(this) }

    /** FR-701 to FR-705. Local only; nothing here has reached Google (FR-703). */
    val inbox: CaptureInbox by lazy { SqliteCaptureInbox(this) }

    /** FR-803's local index — see [LocalItemIndex] for the one question it is allowed to answer. */
    val itemIndex: LocalItemIndex by lazy { SqliteItemIndex(this) }

    /** FR-807's offer, written down so it outlives the process that made it. */
    val undoOffers: UndoOfferStore by lazy { SqliteUndoOfferStore(this) }

    /** FR-1001. The UI over it is FR-1000's; what is here is the record and its defaults. */
    val settingsStore: SettingsStore by lazy { EncryptedSettingsStore(this) }

    /** FR-603: the user's own recipes. FR-602's eight built-ins are code, in `:recipes`. */
    val recipeStore: RecipeStore by lazy { SqliteRecipeStore(this) }

    /**
     * NFR-203's secret store. Holds the FR-1004 endpoint and, deliberately, no OAuth token —
     * there is none at rest to protect, which is stronger than protecting one.
     */
    val secrets: SecretStore by lazy { EncryptedSecretStore(this) }

    private val _settings = MutableStateFlow(LatchSettings())

    /**
     * FR-1001, read once at start and again whenever Settings changes it.
     *
     * A value rather than a nullable one, unlike [configuredAccounts]: an unread record and a
     * default record produce the same behaviour, because the defaults *are* the behaviour the
     * app had before the record existed. There is nothing a screen would do differently while
     * it waited.
     */
    val settings: StateFlow<LatchSettings> = _settings.asStateFlow()

    fun refreshSettings() {
        appScope.launch {
            _settings.value = runCatching { settingsStore.read() }.getOrDefault(LatchSettings())
            applyTileSetting(_settings.value)
        }
    }

    /**
     * FR-1003, for the one layer whose toggle can be honoured by the system rather than by this
     * app declining a capture.
     *
     * The Quick Settings tile is its own manifest component, so switching it off disables the
     * component and the tile disappears from the shade. Text selection and the share sheet are
     * three intent filters on **one** activity, so disabling it would remove all three at once —
     * their toggles are enforced when the capture arrives instead, which means Latch still
     * appears in the share sheet and declines with a reason. Splitting the activity per layer is
     * the cure and is recorded as owed rather than pretended away.
     *
     * `DONT_KILL_APP`, because the alternative is the process being killed out from under a
     * capture that may be mid-write.
     */
    private fun applyTileSetting(settings: LatchSettings) {
        val enabled = CaptureLayer.QUICK_TILE in settings.enabledLayers
        runCatching {
            packageManager.setComponentEnabledSetting(
                ComponentName(this, CaptureTileService::class.java),
                if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
    }

    fun updateSettings(change: (LatchSettings) -> LatchSettings) {
        appScope.launch {
            val updated = change(_settings.value)
            _settings.value = updated
            runCatching { settingsStore.write(updated) }
            applyTileSetting(updated)
        }
    }

    private val _recipes = MutableStateFlow(BuiltInRecipes.all)

    /**
     * FR-602's eight, with any the user has edited shadowing them, and the user's own appended.
     *
     * `recipesFor` is what decides that, and it is pure — a built-in is never mutated, so
     * "edit a built-in" is expressed as a stored recipe carrying its id. That keeps the shipped
     * set stable across upgrades without inventing a second concept for a customised one.
     */
    val recipes: StateFlow<List<Recipe>> = _recipes.asStateFlow()

    fun refreshRecipes() {
        appScope.launch {
            val stored = runCatching { recipeStore.all() }.getOrDefault(emptyList())
            _recipes.value = recipesFor(BuiltInRecipes.all, stored)
        }
    }

    /**
     * FR-605's bundled list, over the years a capture could plausibly reach.
     *
     * A window rather than every year, because the calculator asks about specific dates and a
     * list is cheap only while it is bounded. Three years back and five forward covers a
     * follow-up on an old capture and a renewal several years out, which are the two ends this
     * app actually sees.
     */
    fun bundledHolidays(today: java.time.LocalDate = java.time.LocalDate.now()): List<Holiday> =
        ((today.year - 3)..(today.year + 5)).flatMap(IndianHolidays::fixedDateHolidays)

    /**
     * FR-215, FR-207. Held here rather than by `CaptureActivity` because loading ML Kit's
     * native pipeline is not free and NFR-101 allows 2.5 s for the whole capture — a reader
     * built per capture would pay that cost every time.
     *
     * `by lazy` twice over. It is the rule this whole class follows (a property initializer
     * runs before `attachBaseContext`), and it also means the great majority of captures —
     * text, which never reaches OCR — never construct one. `MlKitOcrReader` defers the
     * recogniser itself a second time for the same reason, so holding this costs nothing
     * until an image actually arrives.
     *
     * Deliberately not closed in [onTerminate]: that callback is not called on a real device,
     * and a recogniser released while a capture is still reading would fail the capture. The
     * process ending releases it, which is the only lifecycle this object has.
     */
    val ocrReader: OcrReader by lazy { MlKitOcrReader(this) }

    private val _queueStatus = MutableStateFlow(QueueStatus(waiting = 0, givenUp = 0))

    /**
     * FR-806's "visible to the user". Null is not a state here, unlike
     * [configuredAccounts]: an unread queue and an empty one both mean "nothing to say",
     * and the home screen draws nothing either way.
     */
    val queueStatus: StateFlow<QueueStatus> = _queueStatus.asStateFlow()

    /**
     * Anything that adds to or drains the queue must call this. The count is on the home
     * screen, and a queue that empties without the screen hearing about it would sit there
     * claiming captures are still waiting.
     */
    fun refreshQueueStatus() {
        appScope.launch { _queueStatus.value = writeQueue.status() }
    }

    private val _inboxCount = MutableStateFlow(0)

    /**
     * FR-704: an unobtrusive count of pending Inbox items, and **it shall not nag**. The home
     * screen draws nothing at all when it is zero, for the same reason the queue count does.
     *
     * Snoozed rows are excluded (FR-702): a capture the user has put off is not pending on
     * them, and counting it would be the nagging the requirement names.
     */
    val inboxCount: StateFlow<Int> = _inboxCount.asStateFlow()

    fun refreshInboxCount() {
        appScope.launch { _inboxCount.value = inbox.pendingCount(Instant.now()) }
    }

    private val _pendingUndo = MutableStateFlow<StoredUndoOffer?>(null)

    /**
     * FR-807's offer where the screen that made it has gone.
     *
     * The capture window is a floating dialog the system may kill, and until FR-701's storage
     * existed a process death inside the ten seconds simply lost the offer and the save stood.
     * It is read here and shown on the home screen, which is the only surface left once that
     * window is closed.
     */
    val pendingUndo: StateFlow<StoredUndoOffer?> = _pendingUndo.asStateFlow()

    fun refreshUndoOffer() {
        appScope.launch { _pendingUndo.value = undoOffers.open(Instant.now()) }
    }

    /**
     * FR-807, taken from the home screen rather than from the capture sheet.
     *
     * The same [removeCreated] the sheet uses — one implementation of a destructive operation,
     * because two would eventually differ and the one that differed would be the one nobody
     * watched.
     */
    fun undoPendingOffer() {
        appScope.launch {
            val offer = _pendingUndo.value ?: return@launch
            _pendingUndo.value = null
            removeCreated(offer.created, calendarApi, tasksApi, writeQueue, itemIndex)
            undoOffers.forget(offer.chainId)
            refreshQueueStatus()
            refreshUndoOffer()
        }
    }

    private var lastImmediateDrain: Instant? = null

    /**
     * FR-806's immediate drain: throw away a backoff the worker is sitting out, where something
     * has happened that makes it no longer the right wait.
     *
     * `shouldDrainNow` holds the policy and is pure; this is the part that needs a `Context`.
     * The rate-limit clock lives here rather than in the decision so the decision stays
     * testable without one.
     */
    fun drainNow(trigger: DrainTrigger) {
        appScope.launch {
            val now = Instant.now()
            val entries = runCatching { writeQueue.pending() }.getOrDefault(emptyList())
            if (!shouldDrainNow(entries, trigger, lastImmediateDrain, now)) return@launch
            lastImmediateDrain = now
            WriteQueueWorker.schedule(this@LatchApplication, replaceExisting = true)
            refreshQueueStatus()
        }
    }

    /**
     * FR-806's "Retry now": a given-up entry goes back in the queue and a drain is asked for.
     *
     * FR-806's own note records that such an entry "stays in the queue and is shown as stuck,
     * but has no manual retry or dismissal, because the screen that would offer one is Settings
     * (FR-1000) and that is not built". This is that retry, on the screen that exists.
     */
    fun retryQueueNow() {
        appScope.launch {
            runCatching { writeQueue.reviveGivenUp() }
            drainNow(DrainTrigger.USER_ASKED)
        }
    }

    /**
     * The OS reporting a usable network again (FR-806).
     *
     * **Not a service, and NFR-104 is why that matters.** A `NetworkCallback` registered by the
     * application object lives exactly as long as the process does and starts nothing; it is
     * the same standing WorkManager's own connectivity constraint has. What it adds is the one
     * thing that constraint cannot: a drain that is not waiting on connectivity but on a
     * backoff timer does not notice the network coming back, and `ExistingWorkPolicy.KEEP`
     * correctly refuses to reset that timer for an ordinary request.
     */
    private val connectivity = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            drainNow(DrainTrigger.CONNECTIVITY_RESTORED)
        }
    }

    /**
     * Held here rather than by `CaptureActivity`, because the capture window is a floating
     * dialog that closes on a tap outside it. A write already on its way to Google must
     * still finish and still be reported — see [CaptureSaver].
     */
    val captureSaver: CaptureSaver by lazy {
        CaptureSaver(
            defaultsStore = accountDefaults,
            calendarApi = calendarApi,
            tasksApi = tasksApi,
            writeQueue = writeQueue,
            inbox = inbox,
            index = itemIndex,
            undoOffers = undoOffers,
            requestDrain = {
                WriteQueueWorker.schedule(this)
                refreshQueueStatus()
                refreshUndoOffer()
            },
            onGoogleReached = { drainNow(DrainTrigger.FOREGROUND_REQUEST_SUCCEEDED) },
            scope = appScope,
            sourceLinkTemplate = getString(R.string.capture_source_link),
            pastDateNoteTemplate = getString(R.string.capture_past_date_note),
            // FR-1001, read at write time rather than captured at launch: Settings can change
            // while this process lives, and a saver holding a snapshot would keep writing the
            // settings the app started with.
            settings = { _settings.value },
            // FR-1004. A suspending read rather than a field, so the endpoint — which NFR-203
            // treats as a secret — is not held in memory for the life of the process whether or
            // not a webhook is ever sent.
            webhookEndpoint = {
                runCatching { secrets.get(EncryptedSecretStore.KEY_WEBHOOK_ENDPOINT) }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }
            },
        )
    }

    /**
     * FR-702's triage, held here rather than by the screen for the reason [captureSaver] is: a
     * save started from the Inbox reaches Google and has to finish and be reported even if the
     * user backs out of the list while it is in flight.
     */
    val inboxCoordinator: InboxCoordinator by lazy {
        InboxCoordinator(
            inbox = inbox,
            saver = captureSaver,
            scope = appScope,
            onChanged = {
                refreshInboxCount()
                refreshQueueStatus()
            },
        )
    }

    /**
     * FR-1000, held here for the reason every coordinator is — and for one more: the settings
     * it edits are read by the capture path, which is a different process entry point
     * altogether. A change made here has to reach a screen that may not exist yet.
     */
    val settingsCoordinator: SettingsCoordinator by lazy {
        SettingsCoordinator(
            settingsStore = settingsStore,
            defaultsStore = accountDefaults,
            secrets = secrets,
            calendarApi = calendarApi,
            tasksApi = tasksApi,
            scope = appScope,
            onChanged = {
                refreshSettings()
                refreshAccounts()
            },
        )
    }

    /**
     * FR-907: one more destination override from this source.
     *
     * Counting only. The requirement is explicit that the app "shall **not** create the rule
     * automatically", so nothing here writes a rule — `shouldOfferRule` reads the count and the
     * user answers the offer.
     */
    fun countDestinationOverride(sourceApp: String?) {
        if (sourceApp.isNullOrBlank()) return
        updateSettings {
            it.copy(destinationOverrideCounts = countingOverride(it.destinationOverrideCounts, sourceApp))
        }
    }

    /** FR-907's answer, taken. The rule the user just agreed to. */
    fun makeRoutingRule(sourceApp: String?, destination: Destination) {
        val app = sourceApp?.takeIf { it.isNotBlank() } ?: return
        updateSettings {
            it.copy(
                routingRules = it.routingRules +
                    ruleFromOverride("rule." + java.util.UUID.randomUUID(), app, destination),
            )
        }
    }

    /** FR-603, held here for the reason every coordinator is: it outlives the screen. */
    val recipeCoordinator: RecipeCoordinator by lazy {
        RecipeCoordinator(store = recipeStore, scope = appScope, onChanged = ::refreshRecipes)
    }

    /**
     * FR-105: setup state is held here, for the life of the process and nowhere else, so
     * that abandoning setup — or losing the process partway through it — leaves nothing in
     * the user's Google account and nothing on disk (AC-16).
     *
     * The coordinator outlives MainActivity on purpose. A rotation partway through setup
     * must not restart it, and this is the way to get that without a retained ViewModel,
     * which would mean a dependency the app does not carry yet (NFR-501).
     */
    val setupCoordinator: SetupCoordinator by lazy {
        SetupCoordinator(
            auth = authClient,
            calendarApi = calendarApi,
            tasksApi = tasksApi,
            defaultsStore = accountDefaults,
            scope = appScope,
            latchCalendarSummary = getString(R.string.latch_calendar_summary),
            latchCalendarDescription = getString(R.string.latch_calendar_description),
        )
    }

    /**
     * FR-806a. Re-runs the grant interactively, from the one screen that can present a consent
     * dialog, and then asks for a drain so the held captures go straight out.
     *
     * The capture path deliberately cannot do this — that is the requirement — so this button
     * is the whole of the user's route out of a queue held for a sign-in.
     */
    fun reauthorize() {
        appScope.launch {
            runCatching { authClient.signIn() }
                .onSuccess {
                    WriteQueueWorker.schedule(this@LatchApplication)
                    refreshQueueStatus()
                }
                .onFailure { refreshQueueStatus() }
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        appScope.cancel()
    }
}
