package com.latch.google

import com.latch.core.model.Item
import com.latch.core.model.ItemType

/**
 * What a save should do about what is already in the account: SRS §7.2's decision table,
 * as one pure function.
 *
 * | `source_hash` | `item_key` | dates | |
 * |---|---|---|---|
 * | matches | not consulted | not consulted | [Duplicate] |
 * | differs | matches | differ | [Reschedule] |
 * | differs | matches | same | [Duplicate] |
 * | differs | differs | either | [Create] |
 *
 * Pure for the same reason `saveBlocker` and `undoOffer` are: it is the whole of the rule,
 * every branch is reachable from a table-driven test, and the screen cannot arrive at a
 * different answer from the saver by reading the same two searches its own way.
 */
sealed interface WriteDecision {
    /**
     * FR-803. Nothing is written and no offer is made.
     *
     * Two quite different situations reach it. The message has been saved before, which is
     * the first row; or the message is new text naming a date the item already sits on,
     * which is the third. Both are answered "already saved", because in both there is
     * nothing for a write to do.
     */
    data object Duplicate : WriteDecision

    /** FR-801: nothing in the account matches, so this is a new item. */
    data object Create : WriteDecision

    /** FR-804: same identity, different dates. The user is asked; nothing is written yet. */
    data class Reschedule(val match: RescheduleMatch) : WriteDecision
}

/**
 * @param duplicate the FR-803 search on `latch.source_hash`.
 * @param reschedule the FR-804 search on `latch.item_key`, or **null where it was not run**
 *   because [duplicate] had already settled the matter. Null is the honest encoding of the
 *   table's "not consulted": passing an empty result instead would say the query ran and
 *   found nothing, which is a different fact.
 * @param proposed the dates this capture would write.
 */
fun writeDecision(
    duplicate: DuplicateSearch,
    reschedule: RescheduleSearch?,
    proposed: ItemDates,
): WriteDecision {
    // Row 1. The key is not consulted, so a duplicate can never present as a reschedule.
    if (duplicate.found) return WriteDecision.Duplicate

    // Row 4. Includes a capped task scan that found nothing: SRS §5.8 requires that to fall
    // through to a create rather than be read as "not a reschedule".
    val match = reschedule?.match ?: return WriteDecision.Create

    // Row 3. A message restated — forwarded, quoted back, sent again with a different
    // greeting — is different text naming the same date. Offering to move an item to where
    // it already is would be the app misreading a restatement as a change.
    if (movesNothing(match.dates, proposed)) return WriteDecision.Duplicate

    // Row 2.
    return WriteDecision.Reschedule(match)
}

/**
 * **Which row of §7.2's table answered**, for a diagnostic line and nothing else.
 *
 * [WriteDecision] says what a save will do; this says *why*, and the difference is the whole
 * reason it exists. `Duplicate` is reached by two quite different routes — the source hash
 * matched (row 1), or the hash missed and the key matched a date the item already sits on
 * (row 3) — and **they produce the identical sentence on screen**. On 1 Sep 2026 an "Already
 * saved" on Android was traced to the second while FR-803's own query was returning `items=0`
 * against a hash carried by two events in that very calendar, and on 3 Sep 2026 AC-07's
 * reverse direction passed on its outcome and could not be closed on its mechanism for exactly
 * the same reason.
 *
 * It carries **no content**: which query answered, and nothing about what was captured. That is
 * a property of the type rather than of its callers — there is no field here that could leak
 * one, which is what makes it safe to put in a log.
 *
 * Derived from the same two searches `writeDecision` reads, so the two cannot disagree about
 * what happened; a diagnostic that reported a different story from the code it describes would
 * be worse than none.
 */
enum class WriteBasis {
    /** Row 1: `latch.source_hash` matched. FR-803 answered and the key was never consulted. */
    SOURCE_HASH,

    /** Row 3: the hash missed, `latch.item_key` matched, and an update would move nothing. */
    ITEM_KEY_SAME_DATE,

    /** Row 2: the hash missed, the key matched, and the dates differ. FR-804 has something to ask. */
    ITEM_KEY_DIFFERENT_DATE,

    /** Row 4: the key query ran and matched nothing. */
    NO_MATCH,

    /**
     * Row 4, reached without asking.
     *
     * Told apart from [NO_MATCH] because they are different facts about the account: one says
     * nothing matched, the other says nothing was looked for. SRS 1.25 skips the key query for
     * a capture producing more than one item, and a diagnosis that could not see the difference
     * would read a deliberate narrowing as an empty account.
     */
    KEY_NOT_CONSULTED,
}

/** @see WriteBasis */
fun writeBasis(
    duplicate: DuplicateSearch,
    reschedule: RescheduleSearch?,
    proposed: ItemDates,
): WriteBasis {
    if (duplicate.found) return WriteBasis.SOURCE_HASH
    if (reschedule == null) return WriteBasis.KEY_NOT_CONSULTED
    val match = reschedule.match ?: return WriteBasis.NO_MATCH
    return if (movesNothing(match.dates, proposed)) {
        WriteBasis.ITEM_KEY_SAME_DATE
    } else {
        WriteBasis.ITEM_KEY_DIFFERENT_DATE
    }
}

/**
 * The decision's name, with **nothing of the match in it**.
 *
 * `WriteDecision.Reschedule` is a data class holding a `RescheduleMatch`, whose own
 * `toString()` carries the **title stored on the user's item**. Logging the decision object
 * would therefore put a line of their calendar into logcat, which NFR-202's instinct forbids as
 * firmly of a log as of an analytics SDK. This is the safe spelling, and it exists so that no
 * call site has to remember why the obvious one is wrong.
 */
val WriteDecision.basisSafeName: String
    get() = when (this) {
        WriteDecision.Duplicate -> "Duplicate"
        WriteDecision.Create -> "Create"
        is WriteDecision.Reschedule -> "Reschedule"
    }

/**
 * Whether an update from [existing] to [proposed] would change nothing.
 *
 * The comparison is over exactly the fields an update may modify, which is the precise form
 * of §7.2's "same resolved date": if applying the update would leave the item as it is,
 * there is nothing to move and this is a duplicate. It follows that a same-day change of
 * *time* is a reschedule, because an update would move something.
 *
 * The time zone is deliberately not compared. Google returns none for an event using its
 * calendar's default, so comparing it would make an unchanged item look changed and offer a
 * reschedule that moved nothing — the very thing this function exists to prevent. Nothing on
 * this path reschedules across zones: the zone comes from the device, not from the text.
 */
fun movesNothing(existing: ItemDates, proposed: ItemDates): Boolean = when {
    existing is ItemDates.Event && proposed is ItemDates.Event ->
        existing.start == proposed.start &&
            existing.end == proposed.end &&
            existing.allDay == proposed.allDay

    existing is ItemDates.Task && proposed is ItemDates.Task -> existing.due == proposed.due

    // An item that changed transport is not the same item to move. It cannot arise today —
    // the key is derived from the title and the type follows the parse — but answering
    // "false" here would offer a patch of one kind against an item of the other.
    else -> false
}

/** The dates a drafted item would write, in the shape FR-804 compares and patches. */
fun itemDatesOf(item: Item, timeZone: String): ItemDates = when (item.type) {
    ItemType.EVENT -> ItemDates.Event(
        start = requireNotNull(item.start) { "An event draft has no start" },
        end = requireNotNull(item.end) { "An event draft has no end" },
        allDay = item.allDay,
        timeZone = timeZone,
    )

    ItemType.TASK -> ItemDates.Task(item.dueDate)
}
