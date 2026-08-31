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
import com.latch.android.ui.CaptureScreen
import com.latch.android.ui.LatchTheme
import com.latch.ocr.OcrFailure
import com.latch.ocr.OcrResult
import com.latch.parser.DateParser
import com.latch.parser.ParseContext
import java.time.LocalDateTime
import java.time.ZoneId
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
    /** FR-215: an image or PDF is being recognised. Only ever reached by an OCR capture. */
    data object Extracting : CaptureContent

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

        // A previous capture in this process may have left an outcome on screen.
        app.captureSaver.reset()
        // Setup may have completed in another task since this process read its defaults.
        app.refreshAccounts()

        val openedAt = SystemClock.elapsedRealtime()
        val request = intent.toCaptureRequest(this)
        val parseContext = ParseContext(now = LocalDateTime.now(), zone = ZoneId.systemDefault())
        val referrer = referrerPackage()

        // Text is resolved before the first frame, as it always was. Only an OCR capture
        // starts as Extracting and arrives later.
        val content = MutableStateFlow<CaptureContent>(
            when (request) {
                is CaptureRequest.Ready -> CaptureContent.Ready(request.captured.copy(appId = referrer))
                is CaptureRequest.Nothing -> CaptureContent.Ready(null)
                is CaptureRequest.Image, is CaptureRequest.Pdf -> CaptureContent.Extracting
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

                HoldWindowOpenForUndo(saveState)

                val captured = (captureContent as? CaptureContent.Ready)?.captured
                // Parsed here rather than beside the extraction so that both paths reach the
                // parser by exactly one route. `remember` keyed on the text keeps a
                // recomposition from re-parsing; it is cheap, but it is not free and the
                // FR-807 countdown recomposes this screen every 200 ms.
                val result = remember(captured?.text) {
                    captured?.let { DateParser.parse(it.text, parseContext) }
                }

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
                        result.overallConfidence < parseContext.confidenceThreshold,
                    selected = selected,
                    onToggleCandidate = { index ->
                        selected = if (index in selected) selected - index else selected + index
                    },
                    onSave = {
                        if (captured != null && result != null) {
                            app.captureSaver.save(captured, result, parseContext, selected)
                        }
                    },
                    onUndo = { app.captureSaver.undo() },
                    // FR-804: the two answers to the offer. Neither is a default, and the
                    // saver ignores both unless an offer is genuinely standing.
                    onUpdateExisting = { app.captureSaver.updateExisting() },
                    onCreateNew = { app.captureSaver.createNewInstead() },
                    fromEmptyClipboard = (request as? CaptureRequest.Nothing)?.fromEmptyClipboard == true,
                    // NFR-102: the screen draws these rather than waiting on them.
                    extracting = captureContent is CaptureContent.Extracting,
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
                is CaptureRequest.Pdf ->
                    Triple(app.ocrReader.readPdf(request.uri), request.layer, request.preferredTitle)
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
     * FR-802's source application, for `latch.source_app`. `getReferrer()` is the only
     * source of it here — neither `ACTION_PROCESS_TEXT` nor `ACTION_SEND` carries the
     * sender's package, and `getCallingActivity()` is null because both arrive via
     * `startActivity` rather than for a result. Often null, which the schema reads as
     * unknown rather than as an error.
     */
    private fun referrerPackage(): String? = referrer?.host?.takeIf { it.isNotBlank() }
}
