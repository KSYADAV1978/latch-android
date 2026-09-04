package com.latch.google

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * FR-804's move note: the sentence an updated item carries so a user can tell it was moved.
 *
 * **Why it exists.** An update modifies dates and nothing else, FR-509 puts the captured date
 * *into* the title verbatim, and SRS 1.18 keeps the description as provenance — so a moved item
 * ends up sitting on one date with another named in its own title and nothing anywhere saying
 * why. SRS 1.77 records that finding; this is its answer.
 *
 * **It names where the item came *from*, not where it is going.** Where it is going is the item
 * itself — visible on the face of it in any calendar — so restating that would be noise. What
 * the user cannot recover is the date it left, which after the patch exists nowhere else.
 *
 * **Composed here rather than in either client**, because two clients wording it differently
 * would put two different sentences into one account for the same act. The *words* still come
 * from the caller as a template, which is NFR-402's division — the pure modules hold facts and
 * each client holds its strings — and the same arrangement `draftItems` uses for FR-510's
 * past-date note. What is fixed here is the date formatting, which is the part that would
 * otherwise drift.
 */
fun movedFromNote(template: String, from: ItemDates, on: LocalDate): String {
    if (template.isBlank()) return ""
    return template
        .replace("%1\$s", describeMoved(from))
        .replace("%2\$s", NOTE_TODAY.format(on))
}

/**
 * The dates as the note names them.
 *
 * An all-day event and an undated task both read as a day rather than a time, for the reason
 * `describeDates` gives on the offer screen: a time this app invented would be a claim about
 * the user's calendar that the calendar does not make.
 */
internal fun describeMoved(dates: ItemDates): String = when (dates) {
    is ItemDates.Event ->
        if (dates.allDay) NOTE_DAY.format(dates.start.toLocalDate())
        else NOTE_DAY_TIME.format(dates.start)

    // An undated task has no "from" to name, and the caller is expected not to write a note at
    // all in that case — but answering with an empty string rather than throwing keeps a
    // formatting decision from becoming a failed save.
    is ItemDates.Task -> dates.due?.let(NOTE_DAY::format).orEmpty()
}

/**
 * Whether a move is worth writing a note about.
 *
 * An undated task has no previous date to name, so a note would read "Moved from  on 4 Sep",
 * which is worse than silence. `writeDecision` already refuses to offer a reschedule that
 * moves nothing, so this is the only case left.
 */
fun worthNoting(from: ItemDates): Boolean = describeMoved(from).isNotEmpty()

private val NOTE_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM yyyy")

/**
 * Today, without a weekday.
 *
 * The weekday earns its place on the date the item **moved from** — that is a scheduling fact
 * the user may be reconciling against a message. On the date the move happened it is noise, and
 * the sentence has to be read inside a calendar entry.
 */
private val NOTE_TODAY: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")
private val NOTE_DAY_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm")
