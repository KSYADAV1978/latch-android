package com.latch.android.capture

import com.latch.core.model.ItemType
import com.latch.parser.Classification
import com.latch.parser.Classifier
import com.latch.parser.Confidence
import com.latch.parser.DatedCandidate
import com.latch.parser.Field
import com.latch.parser.ParseResult
import com.latch.wire.titleFor
import java.time.LocalDate

/**
 * What the user may change on the confirmation screen before saving: FR-506 row 3's date,
 * FR-507's Event/Task override.
 *
 * All of it is applied to the **parse**, not to the text, and all of it is pure — so the screen
 * and the saver reach the same items from the same edits, which is what a confirmation screen
 * has to guarantee. It is also what makes every case below testable without a device.
 *
 * The edits compose in one direction and it matters: a date is assigned first, because FR-507's
 * override reclassifies against whether a date is present, and a row that has just been given
 * one is a different row.
 */
data class SheetEdits(
    /** FR-506 row 3: a date the user chose for a row that had none, by candidate index. */
    val assignedDates: Map<Int, LocalDate> = emptyMap(),
    /** FR-507: the type the user chose for a row, by candidate index. */
    val typeOverrides: Map<Int, ItemType> = emptyMap(),
    /**
     * FR-509b: a title the user corrected, by candidate index.
     *
     * **Not part of [ParseResult], and that is the point.** The other two edits are applied to
     * the parse — a date and a classification are things the parser had an opinion about. A
     * title correction is not: §7.2 derives `latch.item_key` from the captured text with date
     * spans blanked, and if an edit reached the parse it would reach the key with it. A
     * corrected OCR error would then make the item permanently unmatchable by FR-804, and two
     * users correcting one garbled capture differently would derive different identities for the
     * same message. So this travels separately and is consumed by `titleFor` alone.
     */
    val titleOverrides: Map<Int, String> = emptyMap(),
) {
    val isEmpty: Boolean
        get() = assignedDates.isEmpty() && typeOverrides.isEmpty() && titleOverrides.isEmpty()
}

/** Applies [edits] to [result], in the order they have to be applied in. */
fun ParseResult.withEdits(edits: SheetEdits, today: LocalDate): ParseResult =
    withAssignedDates(this, edits.assignedDates, today)
        .let { withTypeOverrides(it, edits.typeOverrides) }

/**
 * FR-506 row 3, and FR-702's assigned date: a date the **user** supplied, applied to the parse.
 *
 * The date arrives at [Confidence.CERTAIN] and marked explicit, because it is not an inference
 * at all — design principle 1's objection is to the app inventing a date, not to the user
 * giving one. FR-506 is then re-applied through [Classification] rather than patched, so a row
 * that was `EVENT_INCOMPLETE` for want of a day becomes an ordinary `EVENT` and one that was
 * `TASK_UNDATED` becomes a task with a due date.
 *
 * The new [Field] carries **no span**, which matters more than it looks: §7.2 derives
 * `latch.item_key` by blanking every matched span out of the captured text, and a date that was
 * never in that text has nothing to blank. Giving it one would blank characters that mean
 * something else, and silently move an identity that cannot be corrected once written.
 *
 * A candidate that already has a date is left alone. Overwriting one the writer actually wrote
 * would be a different feature, and a destructive one.
 */
fun withAssignedDates(
    result: ParseResult,
    dates: Map<Int, LocalDate>,
    today: LocalDate,
): ParseResult {
    if (dates.isEmpty()) return result
    return result.copy(
        candidates = result.candidates.mapIndexed { index, candidate ->
            val date = dates[index]
            if (date == null || candidate.date != null) candidate else candidate.dated(date, today)
        },
    )
}

/** FR-702: the Inbox assigns one date to every row that has none. */
fun withAssignedDate(result: ParseResult, date: LocalDate, today: LocalDate): ParseResult =
    withAssignedDates(
        result,
        result.candidates.indices.filter { result.candidates[it].date == null }.associateWith { date },
        today,
    )

private fun DatedCandidate.dated(date: LocalDate, today: LocalDate) = copy(
    date = Field(date, Confidence.CERTAIN, span = null),
    isExplicit = true,
    classification = Classifier.classify(hasDate = true, hasTime = time != null, isRange = endDate != null),
    // The user answered the question this flag exists to ask.
    ambiguousRelative = false,
    isPast = date.isBefore(today),
)

/**
 * FR-507: the user's Event/Task override, applied to the classification.
 *
 * **What an override to a Task costs is real and is disclosed on screen rather than performed
 * quietly.** §8.1: the Tasks API records only the date portion of a due date, so a time is
 * discarded — and a date range loses its end, which is the same limitation that made SRS 1.23
 * classify a range as an Event to begin with. This is the one place in the app where a user
 * action deliberately loses something they wrote, so [typeChangeCost] exists to say so *before*
 * the tap.
 *
 * **The classification is replaced, not the fields.** The time and the end date stay on the
 * candidate, so an override tapped twice returns the row to exactly where it was; it is
 * `draftItems` that declines to carry them onto a task, which is where §8.1 belongs.
 *
 * **A past row cannot be overridden into an Event.** FR-510 says the app shall not create a
 * dated item for a past date, and an Event is a dated item — so FR-510 outranks this, and the
 * override is refused rather than accepted and then quietly undone by the drafting step.
 */
fun withTypeOverrides(result: ParseResult, overrides: Map<Int, ItemType>): ParseResult {
    if (overrides.isEmpty()) return result
    return result.copy(
        candidates = result.candidates.mapIndexed { index, candidate ->
            val wanted = overrides[index]
            when {
                wanted == null || wanted == candidate.classification.itemType -> candidate
                !canOverrideTo(candidate, wanted) -> candidate
                else -> candidate.copy(classification = classificationFor(candidate, wanted))
            }
        },
    )
}

/**
 * Whether FR-507's override may be taken on this row.
 *
 * One refusal, and it is FR-510's rather than this requirement's: a past date may not become an
 * Event, because an Event is a dated item and FR-510 forbids creating one. The screen shows the
 * badge as fixed for such a row and says why, rather than offering a control that does nothing.
 */
fun canOverrideTo(candidate: DatedCandidate, type: ItemType): Boolean =
    !(type == ItemType.EVENT && candidate.isPast)

private fun classificationFor(candidate: DatedCandidate, type: ItemType): Classification = when (type) {
    ItemType.EVENT ->
        // An event with no date is FR-506's third row again; with one, it is an ordinary event,
        // all-day where there is no time.
        if (candidate.date == null) Classification.EVENT_INCOMPLETE else Classification.EVENT

    ItemType.TASK ->
        // A task keeps only a date. A row that had a time and no day therefore becomes an
        // undated task — which is a second, honest way out of FR-506's third row.
        if (candidate.date == null) Classification.TASK_UNDATED else Classification.TASK_WITH_DUE_DATE
}

/**
 * What the user would lose by taking FR-507's override on this row, so the screen can say it
 * **before** the tap.
 *
 * Nothing at all is the common case, and the screen shows nothing for it. NFR-402: named here,
 * phrased in `strings.xml`.
 */
enum class TypeChangeCost {
    /** §8.1: the Tasks API has no time of day, so the clock time the writer gave is discarded. */
    LOSES_TIME,

    /** A task carries one due date and no end, so the closing day of a range is discarded. */
    LOSES_END_DATE,

    /** Both, which a timed range does. */
    LOSES_TIME_AND_END_DATE,
}

fun typeChangeCost(candidate: DatedCandidate, to: ItemType): TypeChangeCost? {
    if (to != ItemType.TASK) return null
    val losesTime = candidate.time != null
    val losesEnd = candidate.endDate != null
    return when {
        losesTime && losesEnd -> TypeChangeCost.LOSES_TIME_AND_END_DATE
        losesTime -> TypeChangeCost.LOSES_TIME
        losesEnd -> TypeChangeCost.LOSES_END_DATE
        else -> null
    }
}

/**
 * FR-506 row 3's suggestion chips.
 *
 * Three, and deliberately not a guess: the requirement asks for suggestions beside the picker,
 * and a suggestion the user taps is the user choosing a date. Design principle 1 forbids the
 * *app* choosing one, which is why none of these is pre-selected and why the row stays unticked
 * until one is taken.
 */
enum class DateSuggestion { TODAY, TOMORROW, IN_A_WEEK }

fun DateSuggestion.dateFrom(today: LocalDate): LocalDate = when (this) {
    DateSuggestion.TODAY -> today
    DateSuggestion.TOMORROW -> today.plusDays(1)
    DateSuggestion.IN_A_WEEK -> today.plusWeeks(1)
}
