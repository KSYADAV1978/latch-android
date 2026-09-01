package com.latch.android

import android.app.Application
import com.latch.android.capture.CaptureSaver
import com.latch.android.capture.WriteQueueWorker
import com.latch.android.setup.AuthResolutionBridge
import com.latch.android.setup.GoogleAuthClient
import com.latch.android.setup.SetupCoordinator
import com.latch.data.AccountDefaults
import com.latch.data.EncryptedAccountDefaultsStore
import com.latch.data.EncryptedWriteQueueStore
import com.latch.data.QueueStatus
import com.latch.data.WriteQueue
import com.latch.data.googleCalendarApi
import com.latch.data.googleTasksApi
import com.latch.ocr.MlKitOcrReader
import com.latch.ocr.OcrReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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
            requestDrain = {
                WriteQueueWorker.schedule(this)
                refreshQueueStatus()
            },
            scope = appScope,
            sourceLinkTemplate = getString(R.string.capture_source_link),
        )
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
