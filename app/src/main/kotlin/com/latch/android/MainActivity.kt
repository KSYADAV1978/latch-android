package com.latch.android

import android.os.Bundle
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
import com.latch.android.recipes.newRecipe
import com.latch.android.ui.InboxScreen
import com.latch.android.ui.RecipesScreen
import com.latch.android.ui.LatchTheme
import com.latch.android.ui.SetupFlow
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

                                HomeScreen.HOME -> Home(
                                    queue = app.queueStatus.collectAsState().value,
                                    inboxCount = app.inboxCount.collectAsState().value,
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
    /** FR-704: pending Inbox items, drawn only when there are some. */
    inboxCount: Int = 0,
    /** FR-807: an offer that outlived the window that made it. */
    undoOffer: StoredUndoOffer? = null,
    onSignIn: () -> Unit = {},
    onRetryQueue: () -> Unit = {},
    onOpenInbox: () -> Unit = {},
    /** FR-602/FR-603: the recipe list, which is also where a user's own are made. */
    onOpenRecipes: () -> Unit = {},
    onUndo: () -> Unit = {},
) {
    Column(
        modifier = Modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.home_headline), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.home_setup_pending), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.home_try_it), style = MaterialTheme.typography.bodyMedium)

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
        if (queue.needsSignIn) {
            Text(
                text = stringResource(R.string.home_queue_needs_sign_in),
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
private enum class HomeScreen { HOME, INBOX, RECIPES }
