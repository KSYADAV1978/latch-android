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
    ): Classification = when {
        // "Time only, or ambiguous relative reference" — the date picker opens with chips.
        ambiguousRelative -> Classification.EVENT_INCOMPLETE
        hasDate && hasTime -> Classification.EVENT
        hasDate -> Classification.TASK_WITH_DUE_DATE
        hasTime -> Classification.EVENT_INCOMPLETE
        else -> Classification.TASK_UNDATED
    }
}
