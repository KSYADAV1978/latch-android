package com.latch.android.cards

import java.time.Instant

/**
 * FR-1231's offer, and FR-1210's undo, across an Activity recreation (SRS 1.183).
 *
 * **Reported from real use: rotating the phone with an update offer standing dismissed it, and
 * rotating back showed the ordinary sheet with `Save contact`.** The harm is not the lost
 * question, it is the button that replaces it — the user is returned without warning to the
 * control that writes a *second* contact for somebody the offer had just established is already
 * in their address book, which is the duplicate FR-1231 exists to prevent.
 *
 * **This is the FR-807 arrangement, one API over.** `CaptureSaver` keeps the date side's undo
 * offer at application scope and keys `reset` on what was captured, precisely so that a
 * recreation of the same capture keeps it while a genuinely new capture ends it. The card side
 * held its offer in the composition's own `remember`, which the recreation destroys, so the same
 * requirement was met on paper and not in the hand.
 *
 * **The four values travel together because they are one answer.** `accepted`, `values` and
 * `flipped` are the user's edits *to* the offer; restoring the offer without them would put the
 * question back and silently discard the replies, which reads as the app having forgotten rather
 * than as an offer preserved.
 *
 * `CardSaveResult.Saving` is deliberately **not** kept. Its coroutine belonged to the destroyed
 * Activity, so remembering it would restore a spinner nothing will ever complete; the sheet
 * falls back to Idle instead. That a save in flight can be lost to a rotation at all is a
 * separate hazard, recorded rather than fixed here.
 *
 * No `android.*` import, for `CardSaver`'s reason: this is reachable from a JVM test.
 */
class CardOfferMemory {

    data class Remembered(
        val result: CardSaveResult,
        val savedAt: Instant?,
        val accepted: Set<Int>,
        val values: Map<Int, String>,
        val flipped: Set<Int>,
    )

    private var key: String? = null
    private var held: Remembered? = null

    /** What to put back on screen for [captureKey], or null where there is nothing to restore. */
    fun restore(captureKey: String): Remembered? = held?.takeIf { key == captureKey }

    /**
     * Hold the current answer against [captureKey].
     *
     * A state with nothing to preserve clears rather than lingers, so a sheet that has gone back
     * to Idle cannot be resurrected by a later recreation.
     */
    fun keep(captureKey: String, remembered: Remembered) {
        if (!worthKeeping(remembered.result)) {
            if (key == captureKey) held = null
            return
        }
        key = captureKey
        held = remembered
    }

    /**
     * A genuinely new capture ends the offer; a recreation of the same one keeps it.
     *
     * Same key, same capture: exactly `CaptureSaver.reset`'s rule, and for the same reason.
     */
    fun reset(captureKey: String?) {
        if (captureKey != null && captureKey == key) return
        key = captureKey
        held = null
    }

    private fun worthKeeping(result: CardSaveResult): Boolean = when (result) {
        // The standing question, and the answered-but-undoable outcomes.
        is CardSaveResult.UpdateOffered,
        is CardSaveResult.Saved,
        is CardSaveResult.Updated,
        is CardSaveResult.Held,
        is CardSaveResult.Undone,
        is CardSaveResult.AlreadySaved,
        is CardSaveResult.Failed,
        -> true
        // Idle has nothing to say, and Saving would restore a spinner with no coroutine behind it.
        else -> false
    }
}
