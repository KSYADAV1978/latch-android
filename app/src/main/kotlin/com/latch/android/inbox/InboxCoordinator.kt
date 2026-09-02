package com.latch.android.inbox

import com.latch.android.capture.CapturedText
import com.latch.android.capture.CaptureSaver
import com.latch.android.capture.withAssignedDate
import com.latch.android.capture.withTypeOverrides
import com.latch.data.CaptureInbox
import com.latch.core.model.ItemType
import com.latch.data.InboxCapture
import com.latch.parser.DateParser
import com.latch.parser.ParseContext
import com.latch.parser.ParseResult
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * FR-702: triage. Assign a date, edit, save, snooze, or discard.
 *
 * Held by `LatchApplication` rather than by the screen, for the reason `CaptureSaver` is: a
 * save started from here reaches Google and must finish and be reported even if the user backs
 * out of the list while it is in flight.
 */
class InboxCoordinator(
    private val inbox: CaptureInbox,
    private val saver: CaptureSaver,
    private val scope: CoroutineScope,
    /** The home screen's FR-704 count is now wrong. */
    private val onChanged: () -> Unit,
) {
    private val _rows = MutableStateFlow<List<InboxCapture>>(emptyList())

    /** Oldest first, snoozed rows excluded — see [CaptureInbox.due]. */
    val rows: StateFlow<List<InboxCapture>> = _rows.asStateFlow()

    fun refresh() {
        scope.launch { _rows.value = runCatching { inbox.due(Instant.now()) }.getOrDefault(emptyList()) }
    }

    /** FR-702: assign a date. Applied to the parse when the row is saved, never to the text. */
    fun assignDate(id: String, date: LocalDate) = edit(id) { it.copy(assignedDate = date) }

    /** FR-702: edit. The edited title wins over FR-509 and FR-509a, as FR-206's subject does. */
    fun editTitle(id: String, title: String) =
        edit(id) { it.copy(editedTitle = title.trim().takeIf(String::isNotEmpty)) }

    /**
     * FR-507, here as well as on the confirmation sheet.
     *
     * The requirement says the override is available "before saving", and a row is saved from
     * this list — so a control that existed only on the capture sheet would leave every routed
     * capture with the parser's classification and no way to change it.
     */
    fun overrideType(id: String, type: ItemType) = edit(id) { it.copy(typeOverride = type) }

    /**
     * FR-702: snooze. Out of the list and out of FR-704's count until it comes back.
     *
     * A week, and it is a reading rather than a constant with no thought behind it: shorter and
     * the row returns before the reason it was put off has changed, longer and FR-705's review
     * period is doing the work instead.
     */
    fun snooze(id: String, by: Duration = SNOOZE) =
        edit(id) { it.copy(snoozedUntil = Instant.now().plus(by)) }

    /** FR-702: discard. The user has decided; nothing is kept and nothing reached Google. */
    fun discard(id: String) {
        scope.launch {
            runCatching { inbox.discard(id) }
            refresh()
            onChanged()
        }
    }

    /**
     * FR-702's save, and FR-703's boundary: this is the confirmation, and the first moment
     * anything about this capture leaves the device.
     *
     * The row is discarded by the saver once the write lands, not here — a failure has to leave
     * it exactly where it was, which is the whole point of the Inbox holding it.
     */
    fun save(id: String) {
        scope.launch {
            val capture = runCatching { inbox.find(id) }.getOrNull() ?: return@launch
            val context = parseContextOf(capture)
            val result = parseOf(capture, context)
            saver.save(
                captured = capturedTextOf(capture),
                result = result,
                context = context,
                fromInboxId = capture.id,
            )
        }
    }

    private fun edit(id: String, change: (InboxCapture) -> InboxCapture) {
        scope.launch {
            val capture = runCatching { inbox.find(id) }.getOrNull() ?: return@launch
            runCatching { inbox.update(change(capture)) }
            refresh()
            onChanged()
        }
    }

    private companion object {
        val SNOOZE: Duration = Duration.ofDays(7)
    }
}

/**
 * The parse context a row is read against: **the capture's own, not today's**.
 *
 * This is the load-bearing half of storing the text rather than the parse. FR-515 makes a parse
 * a pure function of its text and its context, so replaying the stored context reproduces the
 * reading the user was shown when they captured. Parsing against today's clock would resolve
 * "kal" one day further every time the list was opened and walk "next Monday" forward a week at
 * a time — design principle 1's failure inverted: not inventing a date, but quietly moving one.
 */
fun parseContextOf(capture: InboxCapture): ParseContext = ParseContext(
    now = capture.capturedLocal,
    zone = runCatching { ZoneId.of(capture.zone) }.getOrElse { ZoneId.systemDefault() },
)

/**
 * The parse a row shows and saves, with FR-702's assigned date applied over it.
 *
 * [today] decides only whether the resulting date is in the past (FR-510), which is a fact
 * about now rather than about the capture — the one thing here that is right to read from the
 * present clock.
 */
fun parseOf(
    capture: InboxCapture,
    context: ParseContext = parseContextOf(capture),
    today: LocalDate = LocalDate.now(),
): ParseResult {
    val parsed = DateParser.parse(capture.rawText, context)
    // The same order the confirmation sheet applies them in, and for the same reason: FR-507's
    // override reclassifies against whether a date is present, so a row that has just been
    // given one is a different row.
    val dated = capture.assignedDate?.let { withAssignedDate(parsed, it, today) } ?: parsed
    val override = capture.typeOverride ?: return dated
    return withTypeOverrides(dated, dated.candidates.indices.associateWith { override })
}

/** The row as the save path sees it. The edited title wins, exactly as FR-206's subject does. */
fun capturedTextOf(capture: InboxCapture): CapturedText = CapturedText(
    text = capture.rawText,
    layer = capture.layer,
    preferredTitle = capture.editedTitle ?: capture.preferredTitle,
    appId = capture.appId,
    ocrUsed = capture.ocrUsed,
)
