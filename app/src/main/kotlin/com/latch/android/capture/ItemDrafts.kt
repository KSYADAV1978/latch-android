package com.latch.android.capture

import com.latch.core.model.Item
import com.latch.core.model.ItemType
import com.latch.data.AccountDefaults
import com.latch.parser.DatedCandidate
import com.latch.parser.ParseContext
import com.latch.parser.ParseResult
import com.latch.parser.TitleExtractor
import java.time.LocalDate
import java.time.LocalDateTime

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
     * FR-506 row 3: a time with no date. The requirement's answer is a date picker with
     * suggestions, which is not built, and there is no start to be had without one.
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
): DraftResult {
    // FR-206: a mail client's subject line beats the first line of the body.
    val title = captured.preferredTitle?.takeIf { it.isNotBlank() } ?: result.title.value

    val drafted = result.candidates
        .withIndex()
        .filter { (index, _) -> index in selected }
        .filter { (_, candidate) -> candidateBlocker(candidate) == null }
        .map { (_, candidate) -> candidate }

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

    val items = drafted.mapIndexed { index, candidate ->
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

/** Derived from the chain id so a redraft of the same save produces the same ids. */
private fun itemId(chainId: String, index: Int) = "$chainId#$index"

/**
 * The item's title with every date and time the parser matched cut out of it — the input to
 * §7.2's `latch.item_key`.
 *
 * FR-804 detects a reschedule by "matching title and identifiers, different date", so the
 * identity it matches on has to survive the date changing. Hashing the title as displayed
 * does not: `TitleExtractor` implements FR-509, "use the selection verbatim if under 60
 * characters", so for a short capture the title **is** the whole text, dates included, and
 * `item_key` comes out byte-identical to `source_hash`. Both then change together on a
 * reschedule and FR-804 has nothing to match. That is what this removes.
 *
 * The spans are blanked rather than deleted. `Normalizer` is length-preserving exactly so
 * that a rule's reported span still indexes into the original text, and blanking keeps every
 * later span valid where deleting would shift them all — it also stops "sync 31 August at"
 * collapsing into "syncat". The hash normalisation collapses the leftover whitespace.
 *
 * A subject line (FR-206) is hashed as it stands: the spans index into the body, so they do
 * not apply to it. A subject carrying its own date is the residual case, and it is rare
 * enough to accept — a mail subject is normally the standing name of the thing.
 */
fun itemKeyTitle(captured: CapturedText, result: ParseResult): String {
    captured.preferredTitle?.takeIf { it.isNotBlank() }?.let { return it }

    // §7.2 step 2 blanks **every** date, time and end-time span, not merely the primary's.
    // This blanked only the primary's until SRS 1.23, which was invisible while a capture
    // produced one item and wrong the moment it held two dates: a neighbour's date left
    // standing in the title moves the key whenever any date in the message changes, which is
    // the failure item_key exists to prevent. One capture, one key — so every item of a
    // chain shares it, and FR-804 identifies what the message is about rather than which
    // occurrence of it.
    val spans = result.candidates.flatMap { candidate ->
        listOfNotNull(
            candidate.date?.span,
            candidate.time?.span,
            candidate.endTime?.span,
            candidate.endDate?.span,
        )
    }
    return TitleExtractor.extract(captured.text.blankOut(spans)).value
}

private fun String.blankOut(spans: List<IntRange>): String {
    if (spans.isEmpty()) return this
    val characters = toCharArray()
    spans.forEach { span ->
        val from = span.first.coerceAtLeast(0)
        val to = span.last.coerceAtMost(characters.lastIndex)
        for (index in from..to) characters[index] = ' '
    }
    return String(characters)
}

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
 * Per candidate rather than per capture since SRS 1.23. FR-506 row 3 — a time with no day —
 * needs the date picker that requirement describes, which is not built; until it is, such a
 * row is shown with its reason and left untickable while the rest of the capture saves.
 */
fun candidateBlocker(candidate: DatedCandidate): DraftBlocker? {
    val needsDate = candidate.classification.itemType == ItemType.EVENT && candidate.date == null
    return if (needsDate) DraftBlocker.NEEDS_A_DATE else null
}
