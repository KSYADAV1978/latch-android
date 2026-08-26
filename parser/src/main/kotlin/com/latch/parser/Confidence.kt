package com.latch.parser

/**
 * FR-505: every extracted field carries a confidence value.
 *
 * The scale is arbitrary but the ordering is not: FR-512 routes anything below the
 * configured threshold to the Capture Inbox, so these numbers decide what the user is
 * asked to confirm.
 */
@JvmInline
value class Confidence(val value: Double) : Comparable<Confidence> {
    init {
        require(value in 0.0..1.0) { "Confidence must be in 0..1, was $value" }
    }

    operator fun times(factor: Double) = Confidence((value * factor).coerceIn(0.0, 1.0))

    override fun compareTo(other: Confidence): Int = value.compareTo(other.value)

    companion object {
        /** An unambiguous, fully specified match: "12 September 2026". */
        val CERTAIN = Confidence(0.98)

        /** A clear match with one assumption, e.g. a year the user did not write. */
        val HIGH = Confidence(0.9)

        /** A clear pattern carrying a documented heuristic, e.g. "4 baje" read as 16:00. */
        val MEDIUM = Confidence(0.75)

        /** Recognised, but the reading could reasonably be something else. */
        val LOW = Confidence(0.5)

        val NONE = Confidence(0.0)
    }
}

/**
 * One extracted field, with the span of the source text it came from so the UI can show
 * the user what it read (FR-504's "the resolved interpretation shall be displayed").
 */
data class Field<out T>(
    val value: T,
    val confidence: Confidence,
    val span: IntRange? = null,
)
