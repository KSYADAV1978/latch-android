package com.latch.android.capture

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.latch.android.LatchApplication
import com.latch.android.ui.CaptureScreen
import com.latch.android.ui.LatchTheme
import com.latch.parser.DateParser
import com.latch.parser.ParseContext
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The confirmation screen behind every capture layer (§5.2).
 *
 * The parse is synchronous and touches no I/O, which is what keeps NFR-101's 800 ms budget
 * from gesture to confirmation achievable — and why the FR-904 destination is read from
 * stored defaults rather than fetched.
 */
class CaptureActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as LatchApplication

        // A previous capture in this process may have left an outcome on screen.
        app.captureSaver.reset()
        // Setup may have completed in another task since this process read its defaults.
        app.refreshAccounts()

        val captured = intent.toCapturedText(this)?.copy(appId = referrerPackage())
        val context = ParseContext(now = LocalDateTime.now(), zone = ZoneId.systemDefault())
        val result = captured?.let { DateParser.parse(it.text, context) }

        setContent {
            LatchTheme {
                val destinations by app.configuredAccounts.collectAsState()
                val saveState by app.captureSaver.state.collectAsState()

                HoldWindowOpenForUndo(saveState)

                // FR-511: every date starts ticked, and the choice belongs to this screen
                // rather than to the saver — nothing is written until Save, so there is
                // nothing for the saver to hold. Keyed on the parse so a capture arriving
                // over this one starts ticked again rather than inheriting a stale set.
                var selected by remember(result) {
                    mutableStateOf(result?.candidates?.indices?.toSet() ?: emptySet())
                }

                CaptureScreen(
                    captured = captured,
                    result = result,
                    onDismiss = { finish() },
                    // Three states, not a nullable value: not-yet-read and none-configured
                    // are opposite facts, and only the second is worth telling the user.
                    destination = when (val accounts = destinations) {
                        null -> DestinationState.Loading
                        else -> accounts.firstOrNull()
                            ?.let(DestinationState::Ready)
                            ?: DestinationState.None
                    },
                    saveState = saveState,
                    // FR-512, interim: shown rather than blocking the save, until the
                    // Capture Inbox exists to send it to instead.
                    lowConfidence = result != null &&
                        result.overallConfidence < context.confidenceThreshold,
                    selected = selected,
                    onToggleCandidate = { index ->
                        selected = if (index in selected) selected - index else selected + index
                    },
                    onSave = {
                        if (captured != null && result != null) {
                            app.captureSaver.save(captured, result, context, selected)
                        }
                    },
                    onUndo = { app.captureSaver.undo() },
                    // FR-804: the two answers to the offer. Neither is a default, and the
                    // saver ignores both unless an offer is genuinely standing.
                    onUpdateExisting = { app.captureSaver.updateExisting() },
                    onCreateNew = { app.captureSaver.createNewInstead() },
                    fromEmptyClipboard = intent.getBooleanExtra(EXTRA_READ_CLIPBOARD, false),
                )
            }
        }
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
     * FR-802's source application, for `latch.source_app`. `getReferrer()` is the only
     * source of it here — neither `ACTION_PROCESS_TEXT` nor `ACTION_SEND` carries the
     * sender's package, and `getCallingActivity()` is null because both arrive via
     * `startActivity` rather than for a result. Often null, which the schema reads as
     * unknown rather than as an error.
     */
    private fun referrerPackage(): String? = referrer?.host?.takeIf { it.isNotBlank() }
}
