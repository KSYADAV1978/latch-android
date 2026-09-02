package com.latch.data

import com.latch.core.model.CaptureSource

/**
 * FR-805, FR-805a and FR-805b, composed in one place.
 *
 * FR-805 requires the source text and a link back to it in every item's description or notes.
 * FR-805a excludes the source text for captures from the notification listener, because a
 * Google item is persistent storage synchronised to every device on the account and NFR-206
 * forbids notification content reaching it. FR-805b caps the source text to an extract for
 * OCR-derived captures, because what a recogniser returns from a screenshot is everything
 * that was on the screen.
 *
 * **The rules are structural, not a matter of care.** Call sites do not assemble this block
 * themselves and cannot forget one, in the same way that `SetupEffect.Commit` being the only
 * path to `calendars.insert` is what makes FR-105 hold. AC-22 — confirm a notification
 * capture, find its text nowhere in the created item — is a unit test over this function, and
 * FR-805b's extract is tested the same way.
 *
 * The **link** survives for every layer. It is provenance, not content: the source
 * application and the capture timestamp, which the item already carries under §7.2 anyway.
 *
 * @param dateSpans where the parser matched dates and times in [sourceText]. Used only under
 *   FR-805b, to decide what an extract is taken *around*; ignored for a text capture, which
 *   stores its source whole.
 */
fun sourceBlock(
    source: CaptureSource,
    sourceText: String,
    dateSpans: List<IntRange> = emptyList(),
    sourceLink: String? = null,
): String =
    buildList {
        when {
            !source.storesSourceText -> Unit // FR-805a: nothing, for this layer.
            source.storesWholeSourceText -> sourceText.trim().takeIf(String::isNotEmpty)?.let(::add)
            // FR-805b.
            else -> sourceExcerpt(sourceText, dateSpans).takeIf(String::isNotEmpty)?.let(::add)
        }
        sourceLink?.trim()?.takeIf(String::isNotEmpty)?.let(::add)
    }.joinToString("\n\n")

/**
 * FR-510: the past date, recorded above the FR-805 body.
 *
 * A separate function rather than a parameter of [sourceBlock] because the two have different
 * scopes and always will: FR-805's body is composed once per capture, and this note belongs to
 * **one item** — the follow-up — while the rest of a chain may be perfectly ordinary future
 * items sharing the same body. Composing it here keeps the FR-805a and FR-805b rules exactly
 * where they were, which is what makes them structural.
 *
 * The note leads. It is the reason this item exists and is not a date it carries, so a reader
 * who sees only the first line of a task's notes has still been told the thing that matters.
 */
fun bodyWithNote(note: String?, body: String): String =
    listOfNotNull(note?.trim()?.takeIf(String::isNotEmpty), body.takeIf(String::isNotEmpty))
        .joinToString("\n\n")

/**
 * FR-805b's extract: the **rows** of [text] that carry a date, plus one either side.
 *
 * **The unit is a row of the image, not a distance in characters**, and that is the whole of
 * this function's design. §7.2 requires OCR text to be assembled in geometric reading order,
 * one image row per line, so a line here is a message in a chat or a line of a document —
 * the unit the writer actually composed in.
 *
 * **The character radius this replaces was measured failing on a device and is superseded.**
 * It kept 160 characters either side of each date; on a chat screenshot, which recognises to
 * roughly 270 characters with its dates spread through it, the merged windows covered the
 * whole text and the note written into the user's account was the entire screen — every
 * message in the thread, the amount paid, the sender's name. Shrinking the radius would not
 * have fixed it: a character count does not know where a message begins or ends, so it
 * swallowed a short capture whole while cutting a long one off mid-word. Both failures are
 * the same mistake about what the bound should be measured in.
 *
 * [budget] is retained as an **outer bound rather than the mechanism**. A capture whose rows
 * are individually enormous still needs a ceiling, and a rendered PDF page is exactly that: it
 * arrives as one very long line.
 */
fun sourceExcerpt(
    text: String,
    dateSpans: List<IntRange>,
    neighbours: Int = EXCERPT_NEIGHBOUR_ROWS,
    budget: Int = EXCERPT_BUDGET,
): String {
    val whole = text.trim()
    if (whole.isEmpty()) return ""

    // No date matched: there is nothing to window around, so the opening of the text is the
    // extract. The item is undated under design principle 1 and still needs provenance.
    if (dateSpans.isEmpty()) {
        return if (whole.length <= budget) whole else whole.take(budget).trimEnd() + ELLIPSIS
    }

    val lines = whole.lines()
    val bounds = lineBounds(whole, lines)
    val carriesDate = lines.indices.filter { index ->
        dateSpans.any { span -> span.first <= bounds[index].last && span.last >= bounds[index].first }
    }.toSet()
    if (carriesDate.isEmpty()) {
        return if (whole.length <= budget) whole else whole.take(budget).trimEnd() + ELLIPSIS
    }

    val kept = carriesDate.flatMap { index ->
        (index - neighbours).coerceAtLeast(0)..(index + neighbours).coerceAtMost(lines.lastIndex)
    }.toSortedSet()

    // Consecutive kept lines form a run; a gap between runs becomes one ellipsis.
    val runs = ArrayList<MutableList<String>>()
    var previous = -2
    kept.forEach { index ->
        if (index == previous + 1) runs.last() += lines[index] else runs += mutableListOf(lines[index])
        previous = index
    }

    val body = runs.joinToString("\n$ELLIPSIS\n") { run ->
        run.map(String::trim).filter(String::isNotEmpty).joinToString("\n")
    }
    val openedMidText = kept.first() > 0
    val closedMidText = kept.last() < lines.lastIndex

    val assembled = buildString {
        if (openedMidText) append("$ELLIPSIS\n")
        append(body)
        if (closedMidText) append("\n$ELLIPSIS")
    }
    return if (assembled.length <= budget) assembled else assembled.take(budget).trimEnd() + ELLIPSIS
}

/** Where each line of [whole] starts and ends, so a date span can be mapped to its line. */
private fun lineBounds(whole: String, lines: List<String>): List<IntRange> {
    val bounds = ArrayList<IntRange>(lines.size)
    var start = 0
    lines.forEach { line ->
        bounds += start..(start + line.length)
        start += line.length + 1 // the newline the split consumed
    }
    return bounds
}

/**
 * Rows kept either side of the one carrying a date. One is enough to say who was speaking and
 * what about; two begins to carry the thread again, which is what FR-805b exists to stop.
 */
const val EXCERPT_NEIGHBOUR_ROWS: Int = 1

/** The outer bound, ellipses included — not the mechanism. See [sourceExcerpt]. */
const val EXCERPT_BUDGET: Int = 600

private const val ELLIPSIS = "…"
