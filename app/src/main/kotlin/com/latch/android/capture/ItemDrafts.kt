package com.latch.android.capture

import com.latch.core.model.Item
import com.latch.core.model.ItemType
import com.latch.data.AccountDefaults
import com.latch.parser.DatedCandidate
import com.latch.parser.ParseContext
import com.latch.parser.ParseResult
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
 * items produced by one save, which is what FR-807 undo will group by.
 *
 * Only [ParseResult.primary] is drafted. FR-511's other dates are shown to the user as a
 * count today and become checkboxes when that requirement is built; drafting them now would
 * write items nobody chose.
 */
fun draftItems(
    captured: CapturedText,
    result: ParseResult,
    context: ParseContext,
    defaults: AccountDefaults,
    captureId: String,
    chainId: String,
): DraftResult {
    val candidate = result.primary
    // FR-206: a mail client's subject line beats the first line of the body.
    val title = captured.preferredTitle?.takeIf { it.isNotBlank() } ?: result.title.value

    draftBlocker(result)?.let { return DraftResult.Blocked(it) }

    val item = when (candidate.classification.itemType) {
        ItemType.EVENT -> {
            val date = requireNotNull(candidate.date).value
            eventItem(candidate, date, title, result.location?.value, context, defaults, captureId, chainId)
        }

        ItemType.TASK -> Item(
            id = itemId(chainId, 0),
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

    return DraftResult.Ready(listOf(item))
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
): Item {
    val time = candidate.time?.value

    // An EVENT_INCOMPLETE can still carry a date: classify() tests ambiguousRelative before
    // it tests for a date and a time, so "next week at 3" lands here with both. Without a
    // time there is nothing to put on a clock, and an all-day event is the honest shape.
    val allDay = time == null

    val start = if (allDay) date.atStartOfDay() else LocalDateTime.of(date, time)
    val end = when {
        // Google reads an all-day end date as exclusive: a single day on the 5th ends on the
        // 6th. EventWrite passes end through untouched by design, so the day is added here.
        allDay -> date.plusDays(1).atStartOfDay()
        // A second time in the text wins over the default.
        candidate.endTime != null -> LocalDateTime.of(date, candidate.endTime!!.value)
        // FR-1001's default duration. Declared on ParseContext and, until now, read by
        // nothing — its KDoc says "applied when a start time is found but no end", and this
        // is the downstream that was meant to apply it.
        else -> start.plus(context.defaultEventDuration)
    }

    return Item(
        id = itemId(chainId, 0),
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
 * Whether this capture can be saved at all, and why not where it cannot.
 *
 * Exposed so the confirmation screen can disable Save for the same reason the draft would
 * have refused it. One source of truth: a screen that decided separately would eventually
 * disagree with the mapping.
 */
fun draftBlocker(result: ParseResult): DraftBlocker? {
    val candidate = result.primary
    val needsDate = candidate.classification.itemType == ItemType.EVENT && candidate.date == null
    return if (needsDate) DraftBlocker.NEEDS_A_DATE else null
}
