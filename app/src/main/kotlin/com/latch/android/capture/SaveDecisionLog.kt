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
