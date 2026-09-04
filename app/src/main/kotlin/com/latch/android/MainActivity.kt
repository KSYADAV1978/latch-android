package com.latch.android

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.latch.android.setup.SetupEvent
import com.latch.android.setup.SetupOutcome
import com.latch.recipes.newRecipe
import com.latch.android.ui.InboxScreen
import com.latch.android.ui.RecipesScreen
import com.latch.android.ui.NotificationAccessScreen
import com.latch.android.ui.SettingsScreen
import com.latch.android.ui.LatchTheme
import com.latch.android.ui.SetupFlow
import com.latch.core.model.CaptureLayer
import com.latch.android.settings.SignInPrompt
import com.latch.android.settings.signInPrompt
import com.latch.data.QueueStatus
import com.latch.data.StoredUndoOffer
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.delay

/**
 * FR-101: first launch runs setup. It is not "first launch" that is tested, but whether an
 * account has been configured — which is what makes an abandoned setup indistinguishable
 * from never having run one (AC-16), and what now makes a completed one survive the
 * process that ran it.
 *
 * The Capture Inbox (FR-701) and Settings (FR-1001) still belong here and are not built.
 */
class MainActivity : ComponentActivity() {

    private val latchApplication get() = application as LatchApplication

    /**
     * The consent screen's result. Registered as a field, which `ComponentActivity` handles
     * before STARTED and gives a stable key. The composition-scoped
     * `rememberLauncherForActivityResult` would key itself off a composition that changes
     * shape below — the `when` that swaps between setup, Home and nothing — and a key that
     * moves is a result delivered to no one. The wait it would strand has no retry on screen.
     */
    private val consentResult = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        // Null data covers a dismissed consent screen; the bridge reads that as cancelled.
        latchApplication.authResolution.deliver(result.data)
    }

    /**
     * FR-211's `POST_NOTIFICATIONS`.
     *
     * Registered as a field for the reason the consent launcher is: `ComponentActivity` handles
     * a field registration before STARTED and gives it a stable key, where a composition-scoped
     * one would key itself off a composition that changes shape as the user moves between
     * screens — and a key that moves is a result delivered to no one.
     */
    private val postNotificationsResult = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> canPostNotifications = granted }

    private var canPostNotifications by mutableStateOf(true)

    private fun refreshPostPermission() {
        canPostNotifications = android.os.Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    override fun onStart() {
        super.onStart()
        latchApplication.authResolution.attach(consentResult)
        // FR-806: the queue may have drained while this screen was away — the worker runs
        // whether or not anything is on screen — so the count is re-read rather than trusted
        // to be whatever it was when the process started.
        latchApplication.refreshQueueStatus()
        // FR-704 and FR-807: a capture may have been added to the Inbox, or a save undone,
        // from a window that is not this one. Both are read again rather than remembered.
        latchApplication.refreshInboxCount()
        latchApplication.refreshUndoOffer()
        latchApplication.inboxCoordinator.refresh()
        // Asked of the platform each time rather than remembered: the user can revoke it in
        // Android's settings and this app is not told.
        refreshPostPermission()
        // FR-806b. Silent: it presents nothing and cannot, `grantNeedsConsent()` answering a
        // boolean and keeping the consent intent to itself. Rate-limited inside, because
        // onStart fires on every return to the app.
        //
        // **This is MainActivity and not the capture path**, which is the other half of why it
        // is safe: NFR-101 budgets a capture 800 ms and `CaptureActivity` never runs this.
        latchApplication.checkGrantOnForeground()
    }

    override fun onStop() {
        latchApplication.authResolution.detach(consentResult)
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = latchApplication
        val coordinator = app.setupCoordinator

        setContent {
            LatchTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val outcome by coordinator.outcome.collectAsState()
                    val setupState by coordinator.state.collectAsState()
                    val configured by app.configuredAccounts.collectAsState()

                    LaunchedEffect(outcome) {
                        // Backing out of step 1 leaves the app rather than stranding the
                        // user on a screen they have just declined.
                        if (outcome == SetupOutcome.ABANDONED) finish()
                        // Setup has just written defaults that this process read as absent
                        // at launch. Without this the capture path finds no destination
                        // until the process restarts.
                        if (outcome == SetupOutcome.COMPLETED) app.refreshAccounts()
                    }

                    // The one piece of navigation this app has. A `when` over a screen value
                    // rather than a navigation library: two destinations do not justify a
                    // dependency (NFR-501), and the Inbox is reached from exactly one place.
                    // Three destinations now, and still a `when` rather than a navigation
                    // library: each is reached from exactly one place and none takes arguments,
                    // which is a long way short of what a dependency would buy (NFR-501).
                    var screen by remember { mutableStateOf(HomeScreen.HOME) }

                    when {
                        // Stored defaults have not been read yet. This lasts a frame or
                        // two, and drawing nothing through it is the only option that
                        // cannot flash setup at a configured user.
                        configured == null -> Unit

                        // The outcome covers the account configured moments ago in this
                        // process; the stored list covers every earlier launch.
                        outcome == SetupOutcome.COMPLETED || configured?.isNotEmpty() == true ->
                            when (screen) {
                                HomeScreen.INBOX -> InboxScreen(
                                    rows = app.inboxCoordinator.rows.collectAsState().value,
                                    onBack = { screen = HomeScreen.HOME },
                                    onAssignDate = app.inboxCoordinator::assignDate,
                                    onEditTitle = app.inboxCoordinator::editTitle,
                                    onOverrideType = app.inboxCoordinator::overrideType,
                                    onSave = app.inboxCoordinator::save,
                                    onSnooze = { app.inboxCoordinator.snooze(it) },
                                    onDiscard = app.inboxCoordinator::discard,
                                    saveState = app.captureSaver.state.collectAsState().value,
                                    unreadable = app.inboxStatus.collectAsState().value.unreadable,
                                )

                                HomeScreen.RECIPES -> RecipesScreen(
                                    recipes = app.recipes.collectAsState().value,
                                    onBack = { screen = HomeScreen.HOME },
                                    onSave = app.recipeCoordinator::save,
                                    onDuplicate = {
                                        app.recipeCoordinator.duplicate(it, getString(R.string.recipes_copy_suffix))
                                    },
                                    onDelete = app.recipeCoordinator::delete,
                                    onCreate = {
                                        app.recipeCoordinator.save(
                                            newRecipe(
                                                name = getString(R.string.recipes_new_name),
                                                stepTitle = getString(R.string.recipes_new_step_title),
                                            )
                                        )
                                    },
                                )

                                HomeScreen.SETTINGS -> SettingsScreen(
                                    // FR-1007. The same interactive grant the home
                                    // screen's button runs, reachable on purpose rather
                                    // than only after a capture has been stranded.
                                    grantNeedsConsent = app.grantNeedsConsent.collectAsState().value,
                                    onSignInAgain = { app.reauthorize() },
                                    settings = app.settings.collectAsState().value,
                                    account = configured?.firstOrNull(),
                                    destinations = app.settingsCoordinator.destinations.collectAsState().value,
                                    bundledHolidays = app.bundledHolidays(),
                                    endpointMask = app.settingsCoordinator.endpointMask.collectAsState().value,
                                    endpointRefusal = app.settingsCoordinator.endpointRefusal.collectAsState().value,
                                    lastDelivery = app.settingsCoordinator.lastDelivery.collectAsState().value,
                                    onBack = { screen = HomeScreen.HOME },
                                    onReloadDestinations = app.settingsCoordinator::loadDestinations,
                                    onChooseCalendar = app.settingsCoordinator::chooseCalendar,
                                    onChooseTaskList = app.settingsCoordinator::chooseTaskList,
                                    onChooseMode = app.settingsCoordinator::chooseMode,
                                    onAddRule = app.settingsCoordinator::addRule,
                                    onRemoveRule = app.settingsCoordinator::removeRule,
                                    onUpdate = app.settingsCoordinator::update,
                                    onAddHoliday = app.settingsCoordinator::addHoliday,
                                    onRemoveHoliday = app.settingsCoordinator::removeHoliday,
                                    onSetEndpoint = app.settingsCoordinator::setEndpoint,
                                    onClearEndpoint = app.settingsCoordinator::clearEndpoint,
                                    onSetWebhookEnabled = app.settingsCoordinator::setWebhookEnabled,
                                    onRevokeAndDelete = { app.revokeAndDeleteEverything() },
                                    revokeOutcome = app.revokeOutcome.collectAsState().value,
                                    onOpenNotificationAccess = { screen = HomeScreen.NOTIFICATIONS },
                                )

                                // FR-209: the listener is enabled here and nowhere else, behind
                                // the disclosure the requirement makes a precondition.
                                HomeScreen.NOTIFICATIONS -> NotificationAccessScreen(
                                    settings = app.settings.collectAsState().value,
                                    accessGranted = app.notificationAccessGranted(),
                                    canPostNotifications = canPostNotifications,
                                    onRequestPostPermission = {
                                        if (android.os.Build.VERSION.SDK_INT >= 33) {
                                            postNotificationsResult.launch(
                                                android.Manifest.permission.POST_NOTIFICATIONS
                                            )
                                        }
                                    },
                                    onBack = { screen = HomeScreen.SETTINGS },
                                    onOpenSystemSettings = {
                                        // Latch cannot grant this; only Android's own screen can.
                                        runCatching {
                                            startActivity(
                                                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                                            )
                                        }
                                    },
                                    onSetEnabled = { on ->
                                        app.settingsCoordinator.setLayerEnabled(
                                            CaptureLayer.NOTIFICATION,
                                            on,
                                        )
                                    },
                                    onToggleMonitored = { app.toggleMonitoredPackage(it) },
                                )

                                HomeScreen.HOME -> Home(
                                    queue = app.queueStatus.collectAsState().value,
                                    grantNeedsConsent = app.grantNeedsConsent.collectAsState().value,
                                    fellBackFrom = app.destinationFellBack.collectAsState().value,
                                    onAcknowledgeFallback = { app.acknowledgeFallback() },
                                    inboxCount = app.inboxStatus.collectAsState().value.due,
                                    undoOffer = app.pendingUndo.collectAsState().value,
                                    // FR-806a. The consent screen can only go out from here —
                                    // this is the Activity that attaches the resolution bridge —
                                    // which is precisely why a capture cannot ask for one and
                                    // why the queue routes the user to this button instead.
                                    onSignIn = { app.reauthorize() },
                                    onRetryQueue = { app.retryQueueNow() },
                                    onOpenInbox = {
                                        app.inboxCoordinator.refresh()
                                        screen = HomeScreen.INBOX
                                    },
                                    onOpenRecipes = {
                                        app.refreshRecipes()
                                        screen = HomeScreen.RECIPES
                                    },
                                    onOpenSettings = {
                                        app.settingsCoordinator.open()
                                        screen = HomeScreen.SETTINGS
                                    },
                                    onUndo = { app.undoPendingOffer() },
                                )
                            }

                        else -> {
                            BackHandler { coordinator.dispatch(SetupEvent.BackRequested) }
                            SetupFlow(state = setupState, onEvent = coordinator::dispatch)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Home(
    queue: QueueStatus,
    /**
     * FR-806b: the grant no longer covers the scopes this build asks for.
     *
     * Separate from [QueueStatus.needsSignIn] because it is a different fact — that one is
     * "entries are held and this is why", this one is "your next capture will be". They coincide
     * often; the case that separates them is an empty queue and a stale grant, which before
     * SRS 1.87 put nothing on screen at all.
     */
    grantNeedsConsent: Boolean = false,
    /** FR-704: pending Inbox items, drawn only when there are some. */
    inboxCount: Int = 0,
    /** FR-807: an offer that outlived the window that made it. */
    undoOffer: StoredUndoOffer? = null,
    /** FR-908: the destination that has gone, so this says where captures go now. */
    fellBackFrom: String? = null,
    onAcknowledgeFallback: () -> Unit = {},
    onSignIn: () -> Unit = {},
    onRetryQueue: () -> Unit = {},
    onOpenInbox: () -> Unit = {},
    /** FR-602/FR-603: the recipe list, which is also where a user's own are made. */
    onOpenRecipes: () -> Unit = {},
    /** FR-1000. FR-109 promised at setup that every choice would be changeable here. */
    onOpenSettings: () -> Unit = {},
    onUndo: () -> Unit = {},
) {
    Column(
        modifier = Modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.home_headline), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.home_setup_pending), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.home_try_it), style = MaterialTheme.typography.bodyMedium)

        // FR-908: "shall fall back to the primary calendar and **inform the user**". Their
        // captures are about to start landing somewhere they did not choose, which is the one
        // thing on this screen worth an acknowledgement rather than a passive line.
        if (fellBackFrom != null) {
            Text(
                text = stringResource(R.string.home_destination_fell_back, fellBackFrom),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            TextButton(onClick = onAcknowledgeFallback) {
                Text(stringResource(R.string.home_destination_fell_back_ok))
            }
        }

        // FR-807, where the capture window has gone. The countdown is read here rather than
        // trusted to a timer, and the offer disappears of its own accord when it lapses —
        // the store sweeps it, and this stops drawing it a fraction earlier.
        if (undoOffer != null) {
            val remaining by produceState(undoOffer.secondsLeft(Instant.now()), undoOffer) {
                while (value > 0) {
                    delay(250)
                    value = undoOffer.secondsLeft(Instant.now())
                }
            }
            if (remaining > 0) {
                Text(
                    text = stringResource(R.string.home_undo_offer),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = onUndo) {
                    Text(stringResource(R.string.home_undo, remaining))
                }
            }
        }

        // FR-704: an unobtrusive count, and nothing at all when there is none. The second half
        // of that requirement — "and shall not nag" — is why this is a line of text and a text
        // button rather than a badge, a colour or a notification.
        if (inboxCount > 0) {
            Text(
                text = pluralStringResource(R.plurals.home_inbox_pending, inboxCount, inboxCount),
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = onOpenInbox) {
                Text(stringResource(R.string.home_open_inbox))
            }
        }

        // FR-806's "visible to the user", and the whole of it for now — this is the only
        // screen the app has until Settings (FR-1000) lands. Nothing is drawn when the queue
        // is empty: a permanent "0 waiting" is noise, and NFR-104's habit of not nagging
        // applies to a count as much as to a notification.
        if (queue.waiting > 0) {
            Text(
                text = pluralStringResource(R.plurals.home_queue_waiting, queue.waiting, queue.waiting),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        // FR-806a. Beside the count, not instead of it: the captures are safe and still
        // waiting, and only the reason they are not moving has changed. This is the one stuck
        // state a user can clear, so it says what to do rather than what went wrong.
        // FR-806a and FR-806b reach one button by two routes and do not say the same thing.
        // `signInPrompt` chooses, so the screen cannot show "to save these" over an empty queue.
        val prompt = signInPrompt(
            queueNeedsSignIn = queue.needsSignIn,
            queueWaiting = queue.waiting,
            grantNeedsConsent = grantNeedsConsent,
        )
        if (prompt != SignInPrompt.NONE) {
            Text(
                text = stringResource(
                    when (prompt) {
                        SignInPrompt.QUEUE_HELD -> R.string.home_queue_needs_sign_in
                        // FR-806b: nothing has been lost yet, and the sentence says so rather
                        // than referring to captures that do not exist.
                        else -> R.string.home_grant_needs_sign_in
                    }
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Button(onClick = onSignIn) {
                Text(stringResource(R.string.home_sign_in))
            }
        }
        if (queue.givenUp > 0) {
            Text(
                text = pluralStringResource(R.plurals.home_queue_given_up, queue.givenUp, queue.givenUp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        // FR-602/FR-603. Always available, unlike the counts above: a recipe list with
        // nothing in it is impossible — eight ship — so there is no empty state to hide.
        TextButton(onClick = onOpenRecipes) {
            Text(stringResource(R.string.recipes_open))
        }

        // FR-1000, and FR-109's promise kept: "Every choice made during setup shall be
        // changeable afterwards in Settings, and the setup screens shall say so."
        TextButton(onClick = onOpenSettings) {
            Text(stringResource(R.string.settings_open))
        }

        // FR-806's manual retry, which its own note recorded as absent until Settings existed.
        // Offered whenever anything is in the queue at all, given-up entries included: those
        // are revived by the tap, because the user has usually done something between the
        // failure and pressing it and refusing to try is worse than trying and saying so.
        if (!queue.isEmpty) {
            TextButton(onClick = onRetryQueue) {
                Text(stringResource(R.string.home_queue_retry))
            }
        }
    }
}

/**
 * FR-807's countdown, on the home screen. Counts 10, 9, … 1 and never a visible zero, exactly
 * as the capture sheet's does — the same offer seen from the other surface.
 */
private fun StoredUndoOffer.secondsLeft(now: Instant): Int {
    val millis = Duration.between(now, expiresAt).toMillis()
    return if (millis <= 0) 0 else ((millis + 999) / 1000).toInt()
}

/** The three places this app can be. See the note at the `when` that switches between them. */
private enum class HomeScreen { HOME, INBOX, RECIPES, SETTINGS, NOTIFICATIONS }
