package com.latch.android.capture

import com.latch.core.model.CaptureSource
import com.latch.data.InboxReason
import com.latch.parser.Confidence
import com.latch.parser.ParseResult
import com.latch.wire.candidateBlocker

/**
 * Where a confirmed capture goes: to Google, or to the Capture Inbox.
 *
 * **This retires FR-512's interim reading**, which v1.10 recorded as being in force "only until
 * the FR-700 Capture Inbox exists". It existed because there was nowhere to route to, and
 * refusing to save would have lost the capture entirely — worse than saving something doubtful
 * that the user had read. There is somewhere to route to now.
 */
sealed interface SaveRoute {
    /** FR-801: straight to the user's account, which is what most captures do. */
    data object Google : SaveRoute

    /** FR-512, FR-506 rows 3 and 4, AC-03. Local only until the user confirms it (FR-703). */
    data class Inbox(val reason: InboxReason) : SaveRoute
}

/**
 * The single decision about where a save goes. Pure, so the screen and the saver cannot drift
 * apart — the same arrangement `saveBlocker` and `writeDecision` already have, and for the same
 * reason: the button has to say what is about to happen.
 *
 * The order of the tests is the order the reasons matter in, and each is a requirement rather
 * than a preference.
 *
 * 1. **A notification capture is never routed** (NFR-206, FR-210). The Inbox is persistent
 *    storage and that layer's content may not reach it. FR-512's interim behaviour therefore
 *    survives here permanently — the confidence is shown and the save stands — because the
 *    alternative is refusing to save, which loses the capture.
 * 2. **Nothing dateable at all** is FR-506's fourth row, verbatim: "Neither → Task, undated →
 *    Routed to Capture Inbox". AC-03 is this line.
 * 3. **Nothing that can be completed** is FR-506's third row. A time with no day needs the
 *    date picker that row describes; until then the Inbox is where it waits, which is better
 *    than the refusal that preceded it and does not invent a date (design principle 1).
 * 4. **Below the threshold** is FR-512 itself.
 *
 * A capture with one blocked row beside a saveable one is **not** routed: SRS 1.23 makes such a
 * candidate block itself and not its neighbours, so the saveable rows go to Google and the
 * blocked one is left unticked on screen with its reason showing.
 */
fun saveRoute(
    result: ParseResult,
    selected: Set<Int>,
    threshold: Confidence,
    source: CaptureSource,
): SaveRoute {
    if (!source.routableToInbox) return SaveRoute.Google

    val chosen = result.candidates.filterIndexed { index, _ -> index in selected }
    // Nothing ticked is the user having emptied the list; there is nothing to route and
    // nothing to write, and `saveBlocker` has already disabled the button.
    if (chosen.isEmpty()) return SaveRoute.Google

    if (chosen.all { it.date == null && it.time == null }) return SaveRoute.Inbox(InboxReason.UNDATED)
    if (chosen.all { candidateBlocker(it) != null }) return SaveRoute.Inbox(InboxReason.INCOMPLETE)
    if (result.overallConfidence < threshold) return SaveRoute.Inbox(InboxReason.LOW_CONFIDENCE)

    return SaveRoute.Google
}
