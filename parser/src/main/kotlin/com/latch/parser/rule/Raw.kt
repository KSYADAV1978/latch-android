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

/** Distance between two spans, 0 if they touch or overlap. */
internal fun IntRange.gapTo(other: IntRange): Int = when {
    this overlaps other -> 0
    last < other.first -> other.first - last
    else -> first - other.last
}
