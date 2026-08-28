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
 * FR-805b's extract: a capped window of [text] around each of [dateSpans].
 *
 * The parameters are the readings recorded in the SRS against FR-805b, and are movable by a
 * future revision. They are named constants rather than literals so that the requirement and
 * the code can be read against one another.
 *
 * The shape is: a window either side of every matched date, windows merged where they nearly
 * touch, joined with an ellipsis where they do not, and the whole capped. An ellipsis at
 * either end says that text was dropped there, so a reader can see they are looking at an
 * extract rather than at a short capture.
 *
 * **The window decides, not the total length**, and this is the correction that matters. An
 * earlier version returned any text already inside [budget] whole, on the reasoning that the
 * rule was a cap rather than an omission. That is wrong twice over. FR-805b says the source
 * text "shall never be the whole recognised text", without qualification; and a phone
 * screenshot of a chat is *typically* five hundred characters, so the carve-out would have
 * exempted the exact case the requirement was written for — the balance and the card number
 * beside the message would have been stored after all. The budget is a second, independent
 * bound on the worst case, not the thing that decides whether to excerpt.
 *
 * Where a window happens to cover the whole text, the whole text is kept. That is the window
 * saying every character is context for the date, which for a short note it is.
 */
fun sourceExcerpt(
    text: String,
    dateSpans: List<IntRange>,
    radius: Int = EXCERPT_RADIUS,
    mergeGap: Int = EXCERPT_MERGE_GAP,
    budget: Int = EXCERPT_BUDGET,
): String {
    val whole = text.trim()
    if (whole.isEmpty()) return ""

    // No date matched: there is nothing to window around, so the opening of the text is the
    // extract. The item is undated under design principle 1 and still needs provenance.
    val windows = if (dateSpans.isEmpty()) {
        if (whole.length <= budget) return whole
        listOf(0 until budget.coerceAtMost(whole.length))
    } else {
        dateSpans
            .map { span ->
                (span.first - radius).coerceAtLeast(0)..(span.last + radius).coerceAtMost(whole.lastIndex)
            }
            .filter { !it.isEmpty() }
            .sortedBy { it.first }
            .merged(mergeGap)
    }
    if (windows.isEmpty()) return whole.take(budget).trimEnd() + ELLIPSIS

    val pieces = ArrayList<String>(windows.size)
    var remaining = budget
    var droppedTail = false
    for (window in windows) {
        if (remaining <= 0) {
            droppedTail = true
            break
        }
        val slice = whole.substring(window.first, window.last + 1)
        if (slice.length > remaining) {
            pieces += slice.take(remaining).trimEnd()
            remaining = 0
            droppedTail = true
        } else {
            pieces += slice.trim()
            remaining -= slice.length
        }
    }

    val openedMidText = windows.first().first > 0
    val closedMidText = droppedTail || windows.last().last < whole.lastIndex

    return buildString {
        if (openedMidText) append(ELLIPSIS)
        append(pieces.joinToString(" $ELLIPSIS "))
        if (closedMidText) append(ELLIPSIS)
    }
}

/** Windows that overlap or sit within [gap] characters become one. Input must be sorted. */
private fun List<IntRange>.merged(gap: Int): List<IntRange> {
    if (isEmpty()) return this
    val merged = ArrayList<IntRange>(size)
    var current = first()
    for (next in drop(1)) {
        current = if (next.first - current.last <= gap) {
            current.first..maxOf(current.last, next.last)
        } else {
            merged += current
            next
        }
    }
    merged += current
    return merged
}

/**
 * Characters kept either side of a matched date. Enough to carry the clause the date sits in
 * — who, and what for — without carrying the rest of the screen.
 */
const val EXCERPT_RADIUS: Int = 160

/**
 * Two windows closer than this are joined rather than separated by an ellipsis. An ellipsis
 * standing in for a handful of characters costs more to read than the characters would.
 */
const val EXCERPT_MERGE_GAP: Int = 40

/** The whole extract, ellipses included. Google's task notes cap is 8192; this is a privacy bound, not a platform one. */
const val EXCERPT_BUDGET: Int = 600

private const val ELLIPSIS = "…"
