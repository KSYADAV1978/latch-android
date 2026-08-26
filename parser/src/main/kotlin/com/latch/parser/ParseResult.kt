package com.latch.parser

import com.latch.core.model.Direction
import com.latch.core.model.ItemType
import com.latch.core.model.OffsetUnit
import java.time.LocalDate
import java.time.LocalTime

/**
 * What the parser extracted from one capture (FR-502).
 *
 * [candidates] may hold more than one entry: FR-511 requires multiple dates in a single
 * capture to be presented as a multi-select list, so the parser returns all of them rather
 * than picking a winner. There is always at least one candidate; where nothing dateable was
 * found it is an undated one, because design principle 1 forbids guessing a date and FR-506
 * still needs a row to apply.
 */
data class ParseResult(
    val title: Field<String>,
    val candidates: List<DatedCandidate>,
    val location: Field<String>? = null,
    val recurrence: Field<String>? = null,
    /**
     * A "3 working days before" style offset with no anchor of its own (FR-503).
     * The parser does not resolve these: working-day arithmetic needs the working week and
     * the holiday list, which live in :recipes. Left here for that module to apply.
     */
    val pendingOffset: Field<PendingOffset>? = null,
) {
    /** The candidate the confirmation UI opens on. Later ones are additional checkboxes. */
    val primary: DatedCandidate get() = candidates.first()

    /** FR-512: what the app compares against [ParseContext.confidenceThreshold]. */
    val overallConfidence: Confidence
        get() = minOf(title.confidence, primary.confidence)
}

/**
 * One date found in the capture, with the time that belongs to it if there was one.
 */
data class DatedCandidate(
    val date: Field<LocalDate>?,
    val time: Field<LocalTime>? = null,
    val endTime: Field<LocalTime>? = null,
    val classification: Classification,
    /** FR-510: the only handling a past date gets in the parser is being flagged. */
    val isPast: Boolean = false,
    /** FR-504: true for `05/09`-style input, where the read order is a setting, not a fact. */
    val ambiguousOrder: Boolean = false,
    /** FR-506 row 3: "next week" resolves to a date, but not to one worth saving unconfirmed. */
    val ambiguousRelative: Boolean = false,
) {
    val confidence: Confidence
        get() = listOfNotNull(date?.confidence, time?.confidence).minOrNull() ?: Confidence.NONE
}

/**
 * FR-506, verbatim as a type. The behaviour column belongs to the app; the parser's job
 * ends at saying which row applies.
 */
enum class Classification(val itemType: ItemType) {
    /** Date and time: fields pre-filled, one action to save. */
    EVENT(ItemType.EVENT),

    /** Date only: saved as a task with a due date. */
    TASK_WITH_DUE_DATE(ItemType.TASK),

    /** Time only, or an ambiguous relative reference: date picker opens with suggestions. */
    EVENT_INCOMPLETE(ItemType.EVENT),

    /** Neither: undated task, routed to the Capture Inbox. */
    TASK_UNDATED(ItemType.TASK),
}

/** An offset awaiting an anchor date — see [ParseResult.pendingOffset]. */
data class PendingOffset(
    val value: Int,
    val unit: OffsetUnit,
    val direction: Direction,
)
