package com.latch.parser.rule

import com.latch.parser.Confidence
import com.latch.parser.ParseContext
import java.time.LocalDate
import java.time.LocalTime

/**
 * A date one rule believes it found, before candidates are assembled and de-duplicated.
 */
internal data class RawDate(
    val date: LocalDate,
    val span: IntRange,
    val confidence: Confidence,
    val source: DateSource,
    val ambiguousOrder: Boolean = false,
    val ambiguousRelative: Boolean = false,
    /**
     * Where this date is first committed to in the text, which is not always where [span]
     * points.
     *
     * One date can be written twice — "from August 31 … Monday, August 31, 2026" — and the
     * mention that wins on confidence need not be the one that came first. [span] follows the
     * strongest mention, because that is the text the user is shown and the text `item_key`
     * blanks; this follows the earliest, because that is the order the capture reads in.
     * Defaults to the start of [span], which is correct for every date mentioned once.
     */
    val firstMentionAt: Int = span.first,
)

/**
 * Which kind of rule produced a [RawDate]. The assembler uses this to settle conflicts:
 * in "PTM on Friday 12 September" the weekday is the writer corroborating their own date,
 * not a second commitment, so it must not become a second item under FR-511.
 */
internal enum class DateSource { EXPLICIT, RELATIVE, WEEKDAY, OFFSET }

internal data class RawTime(
    val time: LocalTime,
    val span: IntRange,
    val confidence: Confidence,
)

/**
 * Rules see normalised text (lower-cased, same length as the original) and never the clock —
 * [ParseContext.now] is the only "today" they are allowed to know about.
 */
internal interface DateRule {
    fun find(text: String, ctx: ParseContext): List<RawDate>
}

internal infix fun IntRange.overlaps(other: IntRange): Boolean =
    first <= other.last && other.first <= last

/** The smallest range covering this one and every one of [others]. */
internal fun IntRange.spanning(others: List<IntRange>): IntRange {
    var from = first
    var to = last
    others.forEach { other ->
        if (other.first < from) from = other.first
        if (other.last > to) to = other.last
    }
    return from..to
}

/** Distance between two spans, 0 if they touch or overlap. */
internal fun IntRange.gapTo(other: IntRange): Int = when {
    this overlaps other -> 0
    last < other.first -> other.first - last
    else -> first - other.last
}
