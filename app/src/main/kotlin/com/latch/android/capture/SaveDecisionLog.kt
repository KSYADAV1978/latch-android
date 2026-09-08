package com.latch.android.capture

import com.latch.google.WriteBasis
import com.latch.google.WriteDecision
import com.latch.google.basisSafeName

/**
 * The one diagnostic line a save leaves behind: **which query answered, and what it decided.**
 *
 * It exists because the screen cannot tell you. `SaveState.AlreadySaved` is reached by two
 * quite different routes — §7.2's row 1, where `latch.source_hash` matched, and row 3, where
 * the hash missed and `latch.item_key` matched a date the item already sits on — and both
 * render the identical sentence, "Already saved. Nothing was written again." On 1 Sep 2026
 * that identity hid a real defect for two days of diagnosis; on 3 Sep 2026 it left AC-07's
 * reverse direction passing on its outcome and unclosable on its mechanism.
 *
 * **It carries no capture content, and that is structural rather than careful.** The only
 * arguments are an enum, a decision whose name is taken through [basisSafeName], and a
 * boolean. There is no text, no title and no date here to leak — `WriteDecision.Reschedule`
 * holds the title stored on the user's item and is deliberately never printed whole. NFR-202's
 * instinct applies to a log as much as to an analytics SDK, and the phone's logcat is readable
 * by anyone with the device in their hand.
 *
 * Pure, so what the line says is asserted by a JVM test rather than read off a device.
 */
internal fun saveDecisionLine(
    basis: WriteBasis,
    decision: WriteDecision,
    scanCapped: Boolean,
): String = buildString {
    append("save decision=")
    append(decision.basisSafeName)
    append(" basis=")
    append(basis.name)
    // `DuplicateSearch.scanCapped` is the Tasks API admitting it read ten pages and stopped,
    // and SRS §5.8 forbids reading that as "no duplicate". A NO_MATCH beside a capped scan is
    // a different fact from a NO_MATCH beside a complete one, so the line says which.
    if (scanCapped) append(" scan=capped")
}

/**
 * What an undo did, counted by act (SRS 1.193).
 *
 * **Three acts end at one sentence on screen.** FR-807's undo deletes what was created, drops
 * what was queued and *restores* what was updated (SRS 1.16), and all three leave the user
 * looking at "Removed. Nothing was left in your Google account." That is the same identity
 * problem `WriteBasis` was built for on the save side: two routes, one sentence, and no way to
 * tell from outside the phone which one ran.
 *
 * [failed] is carried rather than derived so the line is complete on its own face. A reader
 * grepping one line should not have to know the total to see that something did not happen.
 */
data class UndoTally(
    val deleted: Int = 0,
    val restored: Int = 0,
    val dropped: Int = 0,
    val failed: Int = 0,
) {
    val attempted: Int get() = deleted + restored + dropped + failed
}

/**
 * The one diagnostic line an undo leaves behind, on either pillar.
 *
 * The gap SRS 1.189 recorded on the card side: FR-1232's restore passed **on the account**
 * rather than on the phone, because nothing the phone emitted said the undo had restored
 * anything. That row's wording — *the undo logs no decision line of its own* — is corrected
 * by SRS 1.193 in one respect: `undoCardCreated` has emitted a *transport* line since 6 Sep
 * 2026, saying whether Google accepted the request. What was missing, and is what a device
 * pass actually needs, is the line above it: **which act this undo was, and how far it got.**
 *
 * **Every count is printed even at zero**, which is deliberate. An absent `restored=` cannot
 * be told from a `restored=0`, and for a diagnostic the difference between "it did not restore
 * anything" and "this build does not report restores" is the whole value of the line.
 *
 * **It carries no capture content**, for the reason [saveDecisionLine] does not: the arguments
 * are four integers and a subject the *call site* supplies as a literal. There is no title, no
 * date and no resource name here — a resource name is not content but it is an identifier into
 * the user's account, and a log readable by anyone holding the phone is not the place for one.
 *
 * Pure, so what the line says is asserted by a JVM test rather than read off a device.
 */
internal fun undoDecisionLine(subject: String, tally: UndoTally): String = buildString {
    append(subject)
    append(" decision=")
    // Named for the outcome rather than the act, because "did all of it happen" is the first
    // question and the counts beside it answer the second.
    append(if (tally.failed == 0) "Undone" else "UndoFailed")
    append(" deleted=").append(tally.deleted)
    append(" restored=").append(tally.restored)
    append(" dropped=").append(tally.dropped)
    append(" failed=").append(tally.failed)
}
