package com.latch.wire

import com.latch.core.model.InboxCapture
import com.latch.parser.DateParser
import com.latch.parser.ParseContext
import com.latch.parser.ParseResult
import java.time.LocalDate
import java.time.ZoneId

/**
 * How an Inbox row is read back (FR-702, FR-515), shared by every client that holds one.
 *
 * These three were `:app`'s until the Windows client grew an Inbox of its own. They are here
 * for the reason `saveRoute` is: replaying a stored capture is a *derivation*, and two
 * derivations of one row would eventually disagree about which day "kal" meant — which is
 * exactly the failure the stored context exists to prevent, arriving from the other direction.
 */

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

/** FR-509b, from the Inbox: one edited title applies to every item the row produces. */
fun titleOverridesOf(capture: InboxCapture, result: ParseResult): Map<Int, String> =
    capture.editedTitle?.takeIf { it.isNotBlank() }
        ?.let { edited -> result.candidates.indices.associateWith { edited } }
        .orEmpty()
