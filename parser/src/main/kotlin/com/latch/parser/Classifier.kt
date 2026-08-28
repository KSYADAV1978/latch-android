package com.latch.parser

/**
 * FR-506, as a lookup rather than a nest of branches, so the table in the SRS and the code
 * can be compared line by line.
 *
 * The user can override the outcome with one control before saving (FR-507); nothing here
 * is final.
 */
object Classifier {

    fun classify(
        hasDate: Boolean,
        hasTime: Boolean,
        ambiguousRelative: Boolean = false,
        isRange: Boolean = false,
    ): Classification = when {
        // "Time only, or ambiguous relative reference" — the date picker opens with chips.
        ambiguousRelative -> Classification.EVENT_INCOMPLETE
        // SRS 1.23, an explicit exception to the "date only means task" row below. A range
        // has a start and an end, and a Google task carries one due date and no end — so
        // calling it a task would silently discard the end the writer took care to give.
        isRange -> Classification.EVENT
        hasDate && hasTime -> Classification.EVENT
        hasDate -> Classification.TASK_WITH_DUE_DATE
        hasTime -> Classification.EVENT_INCOMPLETE
        else -> Classification.TASK_UNDATED
    }
}
