package com.latch.android.capture

import com.latch.core.model.Item
import com.latch.core.model.ItemType
import com.latch.data.AccountDefaults
import com.latch.wire.dateSpans
import com.latch.wire.titleFor
import com.latch.parser.DatedCandidate
import com.latch.parser.ParseContext
import com.latch.parser.ParseResult
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Turns what the parser read into the items a save will create.
 *
 * This lives in `:app` because it is the only module that sees both `:parser` and
 * `:core-model` — the parser deliberately depends on the domain types and not the reverse.
 * It is pure Kotlin and reads no clock, so it is tested on the JVM beside the setup reducer.
 *
 * `Classification` already carries the FR-506 type mapping; what it does not carry is how a
 * candidate's date and time become a start, an end or a due date, because §5.6's KDoc puts
 * that on the app: "the parser's job ends at saying which row applies."
 */

/** Why a capture cannot be saved, where it cannot. Named, not phrased — NFR-402. */
enum class DraftBlocker {
    /**
     * FR-506 row 3: a time with no date, and no date given for it yet.
     *
     * The requirement's answer is a date picker with suggestion chips, and the confirmation
     * sheet has one — so this is now a state the user can leave rather than a wall. It is also
     * left by FR-507's override (a task needs no day) and by the Inbox.
     */
    NEEDS_A_DATE,
}

/** Either the items a save would create, or the reason it cannot. */
sealed interface DraftResult {
    data class Ready(val items: List<Item>) : DraftResult
    data class Blocked(val reason: DraftBlocker) : DraftResult
}

/**
 * [captureId] and [chainId] are the caller's to mint, so this stays clock-free and
 * deterministic under test. Every item of one save shares the chain id — §2.4's chain is the
 * items produced by one save, which is what FR-807 undo groups by.
 *
 * [selected] is FR-511's answer: the candidates the user left ticked, by index into
 * [ParseResult.candidates]. Defaults to all of them, which is what the requirement asks the
 * checkboxes to start as, and is also what a caller with no list of its own should get.
 *
 * Blocked only where **nothing** is left to write. A candidate that cannot be completed —
 * FR-506 row 3, a time with no day — is dropped from the chain and its neighbours are still
 * saved (SRS 1.23); it is the caller's job to have said so on screen, and [candidateBlocker]
 * is what it asks.
 */
fun draftItems(
    captured: CapturedText,
    result: ParseResult,
    context: ParseContext,
    defaults: AccountDefaults,
    captureId: String,
    chainId: String,
    selected: Set<Int> = result.candidates.indices.toSet(),
    /**
     * FR-510: how the past date is worded in the follow-up's notes, as a format string taking
     * the date. Passed in because it is user-facing text and NFR-402 keeps that in
     * `strings.xml` — the same arrangement as `CaptureSaver`'s source-link template.
     */
    pastDateNoteTemplate: String = "",
    /**
     * FR-509b: titles the user corrected, by **candidate** index — which is not the same as the
     * position in the chain, since unticked and blocked rows are dropped before drafting. The
     * two are kept apart below for exactly that reason.
     */
    titleOverrides: Map<Int, String> = emptyMap(),
): DraftResult {
    val drafted = result.candidates
        .withIndex()
        .filter { (index, _) -> index in selected }
        .filter { (_, candidate) -> candidateBlocker(candidate) == null }

    if (drafted.isEmpty()) {
        // Everything the user chose is unsaveable, or they chose nothing. The first reason a
        // candidate could not be drafted is the one worth reporting; with an empty selection
        // there is none, and NEEDS_A_DATE is still the only blocker there is.
        val reason = result.candidates
            .filterIndexed { index, _ -> index in selected }
            .firstNotNullOfOrNull(::candidateBlocker)
            ?: DraftBlocker.NEEDS_A_DATE
        return DraftResult.Blocked(reason)
    }

    val items = drafted.mapIndexed { index, indexed ->
        val (candidateIndex, candidate) = indexed
        val title = titleFor(captured, result, candidate, titleOverrides[candidateIndex])

        // FR-510, and it outranks the classification rather than sitting beside it: "where the
        // only date found is in the past, the app shall **not** create a dated item. It shall
        // offer instead to create a follow-up, with the past date recorded in the notes."
        //
        // Applied per candidate rather than per capture (SRS 1.44). The requirement's "the only
        // date found" was written when a capture produced one item; with FR-511 a capture can
        // hold a past date beside a future one, and writing the past one as a dated item because
        // it was not the *only* date would be the outcome this exists to prevent.
        //
        // The follow-up is undated, because a follow-up needs a date only if the app picks one
        // and picking one is what design principle 1 forbids. It also outranks FR-507's
        // override: an Event is a dated item, so a past row cannot be made one.
        if (candidate.isPast && candidate.date != null) {
            return@mapIndexed Item(
                id = itemId(chainId, index),
                captureId = captureId,
                chainId = chainId,
                type = ItemType.TASK,
                title = title,
                dueDate = null,
                location = result.location?.value,
                notes = pastDateNoteTemplate
                    .takeIf { it.isNotBlank() }
                    ?.format(candidate.date!!.value.format(PAST_DATE_FORMAT)),
                taskListId = defaults.taskListId,
            )
        }

        when (candidate.classification.itemType) {
            ItemType.EVENT -> eventItem(
                candidate = candidate,
                date = requireNotNull(candidate.date).value,
                title = title,
                location = result.location?.value,
                context = context,
                defaults = defaults,
                captureId = captureId,
                chainId = chainId,
                index = index,
            )

            ItemType.TASK -> Item(
                id = itemId(chainId, index),
                captureId = captureId,
                chainId = chainId,
                type = ItemType.TASK,
                title = title,
                // A task never carries a start: Google Tasks discards the time (§9.1), and
                // Item's own init rejects one.
                dueDate = candidate.date?.value,
                location = result.location?.value,
                taskListId = defaults.taskListId,
            )
        }
    }

    return DraftResult.Ready(items)
}

private fun eventItem(
    candidate: DatedCandidate,
    date: LocalDate,
    title: String,
    location: String?,
    context: ParseContext,
    defaults: AccountDefaults,
    captureId: String,
    chainId: String,
    index: Int,
): Item {
    val time = candidate.time?.value

    // An EVENT_INCOMPLETE can still carry a date: classify() tests ambiguousRelative before
    // it tests for a date and a time, so "next week at 3" lands here with both. Without a
    // time there is nothing to put on a clock, and an all-day event is the honest shape.
    val allDay = time == null

    // SRS 1.23: a range carries its closing day, inclusive as the writer wrote it. Turning
    // that into what Google wants is this step's job, not the parser's.
    val spanEnd = candidate.endDate?.value

    val start = if (allDay) date.atStartOfDay() else LocalDateTime.of(date, time)
    val end = when {
        // Google reads an all-day end date as exclusive: a single day on the 5th ends on the
        // 6th, and 12–14 September ends on the 15th. EventWrite passes end through untouched
        // by design, so the day is added here — the one place it is added, for both shapes.
        allDay -> (spanEnd ?: date).plusDays(1).atStartOfDay()
        // A second time in the text wins over the default. An end at or before the start
        // means the range crossed midnight — "from 11 pm to 1 am" is one sitting, not an
        // event that finishes two hours before it begins. Only the day rolls; the clock time
        // is what the writer wrote, so nothing is invented (design principle 1).
        candidate.endTime != null -> {
            val endTime = candidate.endTime!!.value
            // A range of days says which day it ends on; only a bare time has to infer it.
            val endDay = spanEnd ?: if (endTime > time) date else date.plusDays(1)
            LocalDateTime.of(endDay, endTime)
        }
        // A span of days carrying a start time but no end time: it ends on the closing day,
        // at the time it started. Guessing a different clock time would be inventing one.
        spanEnd != null -> LocalDateTime.of(spanEnd, time)
        // FR-1001's default duration. Declared on ParseContext and, until now, read by
        // nothing — its KDoc says "applied when a start time is found but no end", and this
        // is the downstream that was meant to apply it.
        else -> start.plus(context.defaultEventDuration)
    }

    return Item(
        id = itemId(chainId, index),
        captureId = captureId,
        chainId = chainId,
        type = ItemType.EVENT,
        title = title,
        start = start,
        end = end,
        allDay = allDay,
        location = location,
        calendarId = defaults.destinationCalendarId,
    )
}

/**
 * FR-510's date, written the way a person reads one.
 *
 * ISO would be unambiguous and is the wrong choice here: this string goes into a note the user
 * reads in Google Tasks, not into a wire format, and §4.1's other clients never parse it back.
 */
private val PAST_DATE_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)

/** Derived from the chain id so a redraft of the same save produces the same ids. */
private fun itemId(chainId: String, index: Int) = "$chainId#$index"


/**
 * Whether this capture can be saved at all, and why not where it cannot.
 *
 * Exposed so the confirmation screen can disable Save for the same reason the draft would
 * have refused it. One source of truth: a screen that decided separately would eventually
 * disagree with the mapping.
 */
fun draftBlocker(result: ParseResult): DraftBlocker? =
    // The capture as a whole is blocked only where every candidate is. One unsaveable date
    // among several stops itself and not its neighbours (SRS 1.23), and a Save button
    // disabled because of one row would be the all-or-nothing behaviour that reading ends.
    result.candidates.map(::candidateBlocker).let { blockers ->
        if (blockers.all { it != null }) blockers.firstOrNull() else null
    }

/**
 * Why this one candidate cannot be written, or null where it can.
 *
 * Per candidate rather than per capture since SRS 1.23: such a row is shown with its reason and
 * left untickable while the rest of the capture saves. Since v1.44 the sheet also offers the
 * date picker FR-506 row 3 describes, so the row can be completed in place rather than only
 * explained.
 */
fun candidateBlocker(candidate: DatedCandidate): DraftBlocker? {
    // FR-510: a past row is written as an undated follow-up whatever its classification says,
    // so it is never waiting on a date and must not be reported as blocked.
    if (candidate.isPast && candidate.date != null) return null
    val needsDate = candidate.classification.itemType == ItemType.EVENT && candidate.date == null
    return if (needsDate) DraftBlocker.NEEDS_A_DATE else null
}

