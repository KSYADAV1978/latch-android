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
    /**
      * The candidate the confirmation UI opens on, and the one a save writes. The others are
      * additional checkboxes once FR-511 is built.
      *
      * Chosen by [PRIMARY_RANKING] rather than by position — see the reading recorded against
      * FR-505 in the SRS. [candidates] stays in document order, so the list still reads in the
      * order the capture reads.
      */
     val primary: DatedCandidate get() = candidates.minWith(PRIMARY_RANKING)

    /** FR-512: what the app compares against [ParseContext.confidenceThreshold]. */
    val overallConfidence: Confidence
        get() = minOf(title.confidence, primary.confidence)
}

/**
 * Which of several dates in one capture the app should act on first (a reading of FR-505,
 * recorded in the SRS).
 *
 * Best first, so `minWith` picks the winner. In order:
 *
 * 1. **A date with a time beats one without.** A writer who gave a clock time has told us the
 *    most about the commitment, and under FR-506 it is the difference between an event and a
 *    task with a due date.
 * 2. **An explicitly written date beats a relative or inferred one.** "August 31" is a
 *    statement; "Monday" or "in 3 days" is a calculation this app performed, and design
 *    principle 1 says the app's own arithmetic should never outrank what the user wrote.
 * 3. **Otherwise the earliest mention wins.** Not a key here: [candidates] is already in
 *    document order and `minWith` keeps the first of equal elements, so position is the
 *    tiebreak by construction.
 *
 * Position used to be the *only* rule, and that is what let "from August 31 to September 6,
 * 2026 / Date: Monday, August 31, 2026 at 21:30" open on 6 September with no time.
 *
 * **FR-505's confidence is deliberately not a key, and that was not the first answer.** Ranking
 * on it above position reads well until a date range meets it: people write a range's year once,
 * at the end — "from August 31 to September 6, 2026" — so only the closing date carries one, and
 * a year is exactly what separates [Confidence.CERTAIN] from [Confidence.HIGH]. Confidence then
 * makes the *end* of every such range the primary, which is the same wrong answer this ranking
 * was written to fix, arrived at down a different road. Below position it can never fire at all,
 * because no two candidates share a first mention — so it is left out rather than kept as a key
 * that cannot decide anything. The corpus row for "from August 31 to September 6, 2026" is what
 * holds this in place.
 */
val PRIMARY_RANKING: Comparator<DatedCandidate> =
    compareBy<DatedCandidate> { if (it.time != null) 0 else 1 }
        .thenBy { if (it.isExplicit) 0 else 1 }

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
    /**
     * True where the writer wrote the date out — "31 August", "05/09" — rather than the app
     * having worked it out from "Monday", "tomorrow" or "in 3 days". Read by [PRIMARY_RANKING].
     */
    val isExplicit: Boolean = false,
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
