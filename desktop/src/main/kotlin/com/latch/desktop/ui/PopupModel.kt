package com.latch.desktop.ui

import com.latch.core.model.ItemType
import com.latch.desktop.save.RemovalOutcome
import com.latch.google.ItemDates
import com.latch.desktop.capture.EmptyCapture
import com.latch.parser.DatedCandidate
import com.latch.parser.ParseResult
import com.latch.wire.WireCapture
import com.latch.wire.TypeChangeCost
import com.latch.wire.canOverrideTo
import com.latch.wire.candidateBlocker
import com.latch.wire.typeChangeCost
import com.latch.wire.titleFor
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** One row of the popup: FR-511's checkbox, FR-508's badge, and the date it stands for. */
data class CaptureRow(
    /** Index into [ParseResult.candidates]. Not the row's position — an unticked row is still a row. */
    val index: Int,
    val badge: String,
    val whenLine: String,
    val title: String,
    val checked: Boolean,
    /** FR-507: whether this row's type can be changed. FR-510 forbids a past date becoming an event. */
    val canOverride: Boolean,
    /** What the row would become if the badge were pressed. */
    val otherType: ItemType,
    /**
     * §8.1's cost of pressing it, **shown before the press and not after**.
     *
     * This is the one place in the app where a user action deliberately loses something they
     * wrote — a time, or the closing day of a range — so the sentence has to be readable while
     * the badge still says what it says now.
     */
    val overrideCost: String?,
    /** FR-506 row 3: a time with no day, which the row offers a way out of. */
    val needsDate: Boolean,
    val note: String?,
)

/** Everything the popup draws, decided here so the window only renders. */
data class PopupModel(
    val title: String,
    val rows: List<CaptureRow>,
    val summary: String,
    val canSave: Boolean,
    val blocker: String?,
)

/**
 * The popup's contents, as a pure function.
 *
 * The rule this project keeps relearning: a screen that decides anything is a screen no test
 * can check. Everything here — which rows exist, what each says, whether Save is possible — is
 * decided in one place that a JVM test calls, and `CaptureWindow` draws the result and nothing
 * more. It is also why the Android confirmation sheet and this popup can be compared: both
 * call `titleFor`, so both show what will actually be written.
 */
fun popupModel(
    captured: WireCapture,
    result: ParseResult,
    selected: Set<Int>,
    today: LocalDate,
    titleOverrides: Map<Int, String> = emptyMap(),
    typeOverrides: Map<Int, ItemType> = emptyMap(),
): PopupModel {
    val rows = result.candidates.mapIndexed { index, candidate ->
        val type = typeOverrides[index] ?: candidate.classification.itemType
        val other = if (type == ItemType.EVENT) ItemType.TASK else ItemType.EVENT
        CaptureRow(
            index = index,
            badge = if (type == ItemType.EVENT) DesktopStrings.BADGE_EVENT else DesktopStrings.BADGE_TASK,
            whenLine = whenLineFor(candidate),
            title = titleFor(captured, result, candidate, titleOverrides[index]),
            checked = index in selected,
            // FR-510 outranks FR-507: a past date becomes an undated follow-up, so offering to
            // make it an event would offer something the requirement forbids.
            canOverride = canOverrideTo(candidate, other),
            otherType = other,
            overrideCost = typeChangeCost(candidate, other)?.let(::overrideCostText),
            needsDate = candidate.date == null,
            note = noteFor(candidate, today),
        )
    }

    val blocker = when {
        result.candidates.isEmpty() -> DesktopStrings.NO_DATE_FOUND
        selected.isEmpty() -> DesktopStrings.NOTHING_TICKED
        selected.all { index -> result.candidates.getOrNull(index)?.let(::candidateBlocker) != null } ->
            DesktopStrings.NEEDS_A_DATE
        else -> null
    }

    return PopupModel(
        // FR-509: one title where one governs, and the row's own where FR-509a applies.
        title = result.candidates.firstOrNull()
            ?.let { titleFor(captured, result, result.primary ?: it, titleOverrides[0]) }
            ?: captured.preferredTitle?.takeIf { it.isNotBlank() } ?: result.title.value,
        rows = rows,
        summary = summaryFor(rows.size),
        canSave = blocker == null,
        blocker = blocker,
    )
}

private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM uuuu")
private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

internal fun whenLineFor(candidate: DatedCandidate): String {
    val date = candidate.date?.value ?: return DesktopStrings.NO_DAY_YET
    val day = date.format(DAY)
    val end = candidate.endDate?.value
    if (end != null && end != date) return day + " – " + end.format(DAY)
    val time = candidate.time?.value ?: return day
    return day + ", " + time.format(TIME)
}

/** §8.1's costs, in words. NFR-402 keeps the phrasing here and the facts in `:wire`. */
internal fun overrideCostText(cost: TypeChangeCost): String = when (cost) {
    TypeChangeCost.LOSES_TIME -> DesktopStrings.COST_TIME
    TypeChangeCost.LOSES_END_DATE -> DesktopStrings.COST_END_DATE
    TypeChangeCost.LOSES_TIME_AND_END_DATE -> DesktopStrings.COST_BOTH
}

internal fun noteFor(candidate: DatedCandidate, today: LocalDate): String? {
    val date = candidate.date?.value ?: return DesktopStrings.PICK_A_DAY
    // FR-510, and it outranks FR-507: a past date becomes an undated follow-up, never an event.
    if (date.isBefore(today)) return DesktopStrings.PAST_DATE
    return null
}

internal fun summaryFor(count: Int): String = when (count) {
    0 -> DesktopStrings.NO_DATE_FOUND
    1 -> DesktopStrings.ONE_DATE
    else -> DesktopStrings.MANY_DATES.replace("%d", count.toString())
}

/**
 * FR-804's offer, as the sentence the user reads before they choose.
 *
 * Three things about it are the Android reading followed verbatim rather than re-decided.
 *
 * **The title is quoted from the item that already exists**, not from the text just captured.
 * The user is being asked about something in their calendar, and naming it by the new wording
 * would describe a thing they cannot go and look at.
 *
 * **The weekdays are computed from the dates**, never taken from the captured text. A message
 * may name a weekday that contradicts the date beside it — §7.2's own example is "PTM on Friday
 * 12 September", where the 12th is a Saturday — and that contradiction is absorbed into the
 * date rather than repeated back at the user as though the app believed it.
 *
 * **A task with no due date reads "no date"**, not an empty gap. An undated to-do is a real
 * state on this path, and FR-510's follow-up produces one.
 */
fun rescheduleOfferText(storedTitle: String, existing: ItemDates, proposed: ItemDates): String =
    "This looks like a reschedule. \"" + storedTitle + "\" is already saved for " +
        describeDates(existing) + ". Move it to " + describeDates(proposed) + "?"

internal fun describeDates(dates: ItemDates): String = when (dates) {
    is ItemDates.Event ->
        if (dates.allDay) dates.start.toLocalDate().format(OFFER_DATE)
        else dates.start.format(OFFER_DATE_TIME)

    is ItemDates.Task -> dates.due?.format(OFFER_DATE) ?: DesktopStrings.NO_DATE
}

private val OFFER_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM yyyy")
private val OFFER_DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm")

/**
 * FR-807's countdown, as it reads on the button.
 *
 * The seconds are shown rather than a bare "Undo", because the whole point of the offer is that
 * it expires: a button that gave no sign of running out would leave the user discovering the
 * limit by missing it. `CLAUDE.md` records that happening twice on the phone.
 */
fun undoLabel(secondsLeft: Int): String = "Undo (" + secondsLeft + ")"

/** NFR-303: a partial undo has to say how far it got, because the rest is a manual job. */
fun undoOutcomeText(outcome: RemovalOutcome, wasUpdate: Boolean): String = when {
    outcome.complete && wasUpdate -> "Put back where it was."
    outcome.complete && outcome.total == 1 -> "Removed."
    outcome.complete -> "Removed all " + outcome.total + "."
    outcome.removed == 0 -> "Could not undo. Nothing was changed back; remove it in Google."
    else -> "Undid " + outcome.removed + " of " + outcome.total +
        ". Remove the rest in Google Calendar or Tasks."
}

/** What to say when a hotkey press found nothing worth capturing. */
fun messageFor(reason: EmptyCapture): String = when (reason) {
    EmptyCapture.NOTHING_COPIED -> DesktopStrings.NOTHING_COPIED
    EmptyCapture.NO_TEXT_IN_IMAGE -> DesktopStrings.NO_TEXT_IN_IMAGE
    EmptyCapture.NO_RECOGNISER -> DesktopStrings.NO_RECOGNISER
    EmptyCapture.RECOGNITION_FAILED -> DesktopStrings.RECOGNITION_FAILED
}

/**
 * Every user-facing string in this client, in one file.
 *
 * NFR-402 names `strings.xml`, which is Android's answer to the same requirement; the point of
 * it is that the pure modules return named facts and one place turns them into words. That
 * holds here. NFR-403's Hindi would make this a `ResourceBundle`, which is a change of
 * mechanism and not of arrangement — nothing outside this file would move.
 */
object DesktopStrings {
    const val BADGE_EVENT = "EVENT"
    const val BADGE_TASK = "TO-DO"

    const val NO_DAY_YET = "No day for this time yet"
    const val PICK_A_DAY = "Pick a day for this."
    const val PAST_DATE = "This date has passed, so it becomes a to-do with no date."

    const val NO_DATE_FOUND = "No date found in what you copied."
    const val NOTHING_TICKED = "Tick at least one date to save."
    const val NEEDS_A_DATE = "Nothing here can be saved yet."
    const val ONE_DATE = "1 date found."
    const val MANY_DATES = "%d dates found in this capture."

    const val NOTHING_COPIED = "Nothing was copied. Select some text first, or copy an image."
    const val NO_TEXT_IN_IMAGE = "No text was found in that image."
    const val NO_RECOGNISER =
        "Windows has no text-recognition language installed. Add one under " +
            "Settings, Time & language, Language & region."
    const val RECOGNITION_FAILED = "That image could not be read."

    const val SAVE = "Save"
    const val CLOSE = "Close"
    const val EXPORT = "Export .ics"
    const val UPDATE = "Update"
    const val CREATE_NEW = "Create new"
    const val NO_DATE = "no date"
    const val COST_TIME =
        "As a to-do this keeps the day but not the time — Google Tasks has no time of day."
    const val COST_END_DATE = "As a to-do this keeps the first day but not the last."
    const val COST_BOTH =
        "As a to-do this keeps only the first day — not the time, and not the last day."
    const val PAST_CANNOT_BE_EVENT = "A past date cannot be an event."
    const val TODAY = "Today"
    const val TOMORROW = "Tomorrow"
    const val IN_A_WEEK = "In a week"
    const val OTHER_DATE = "Use"
    const val SAVED = "Saved to Latch."
    const val UPDATED = "Moved. The existing item now sits on the new date."
    const val HELD = "No connection. Held on this machine, and it will be written when there is one."
    const val ALREADY_SAVED = "Already saved. Nothing was written again."
    const val EXPORTED = "Saved a calendar file to your Downloads folder."
    const val EXPORT_FAILED = "Latch could not write the calendar file."
    const val EDIT_TITLE = "Edit"
    const val OVERRIDE_HINT = "Press Space on a badge to switch between event and to-do."
    const val NOT_SIGNED_IN = "Sign in to Google from the Latch tray icon first."

    // ---- FR-700, the Capture Inbox ---------------------------------------------------------

    const val INBOX_TITLE = "Latch Inbox"
    const val INBOX_EMPTY = "Nothing waiting. Captures Latch is unsure about will appear here."
    // FR-703, said rather than assumed: this is the one screen in the client where the
    // difference between "held" and "saved" is the whole point.
    const val INBOX_LOCAL_ONLY =
        "Everything here is on this PC only. Nothing reaches Google until you save it."
    const val INBOX_NO_DATE_YET = "No date yet"
    const val INBOX_CAPTURED_ON = "Captured %s"
    // FR-705: surfaced for review, never deleted automatically.
    const val INBOX_AGED = "Waiting since %s. Still want it?"
    const val INBOX_ONE_WAITING = "1 waiting in the Inbox"
    const val INBOX_MANY_WAITING = "%d waiting in the Inbox"
    const val INBOX_UNREADABLE =
        "%d held captures could not be read by this version of Latch. " +
            "They are still on this PC and nothing has been deleted."
    const val INBOX_ADD = "Add to Inbox"
    const val INBOX_ADDED = "Held in the Latch Inbox on this PC. Nothing was written to Google."
    const val INBOX_ADD_FAILED = "Latch could not hold that capture. Nothing was saved."
    // FR-702's five actions, in the requirement's own order.
    const val INBOX_SET_DATE = "Set a date"
    const val INBOX_TITLE_FIELD = "Title"
    const val INBOX_SAVE = "Save to Google"
    const val INBOX_SNOOZE = "Snooze a week"
    const val INBOX_DISCARD = "Discard"
    const val INBOX_SAVING = "Saving…"
    const val INBOX_SAVE_FAILED = "Could not save that to Google. It is still here — try again."
    const val INBOX_NEEDS_DATE = "Give this a date before saving it."
    const val INBOX_REFRESH = "Refresh"

    // ---- FR-1000, Settings -------------------------------------------------------------------

    const val SETTINGS_TITLE = "Latch Settings"
    const val SETTINGS_SAVE = "Save"
    const val SETTINGS_CLOSE = "Close"
    const val SETTINGS_SAVED = "Saved."
    const val SETTINGS_WRITE_FAILED = "Latch could not store those settings on this PC."

    const val SETTINGS_HOTKEY = "Capture shortcut"
    const val SETTINGS_HOTKEY_HINT =
        "Type it as Ctrl+Shift+K. It works anywhere in Windows, so pick something no other " +
            "application uses."
    const val SETTINGS_HOTKEY_RETAKEN = "Shortcut changed. It is now %s."
    const val SETTINGS_HOTKEY_REFUSED =
        "Another application already uses %s. The old shortcut is back; choose a different one."

    const val SETTINGS_DATE_ORDER = "Read 05/09 as"
    const val SETTINGS_DAY_FIRST = "5 September (day first)"
    const val SETTINGS_MONTH_FIRST = "9 May (month first)"

    const val SETTINGS_DURATION = "Default event length, in minutes"
    const val SETTINGS_REMINDERS = "Default reminders, in minutes before"
    const val SETTINGS_REMINDERS_HINT =
        "Comma-separated, for example 30, 1440. Leave it empty to use your calendar's own."
    const val SETTINGS_THRESHOLD = "Hold a capture below this confidence, as a percentage"
    const val SETTINGS_THRESHOLD_HINT = "Below this, a capture waits in the Inbox instead of being saved."
    const val SETTINGS_WORKING_WEEK = "Working week"
    const val SETTINGS_TIME_ZONE = "Time zone"
    const val SETTINGS_TIME_ZONE_HINT = "A name like Asia/Kolkata. Leave it empty to follow this PC."

    // ---- FR-1004, the outbound webhook -------------------------------------------------------

    const val WEBHOOK_HEADING = "Send saved items to your own endpoint"
    const val WEBHOOK_NONE = "Not configured"
    const val WEBHOOK_ENDPOINT = "Endpoint (https only)"
    const val WEBHOOK_SET = "Save endpoint"
    const val WEBHOOK_CLEAR = "Remove endpoint"
    const val WEBHOOK_ENABLE = "Send items to this endpoint when they are saved"

    // FR-1004: "shall display a warning at the point of configuration stating that captured
    // content will be sent to that endpoint". Not a tooltip and not a footnote — the sentence
    // sits above the field it is about.
    const val WEBHOOK_WARNING =
        "What Latch saves — the title, the dates, and the text of what you captured — will be " +
            "sent to this address every time you save. It goes straight from this PC; nothing " +
            "of Latch's is involved, and Latch cannot see what happens to it afterwards. Only " +
            "use an address you control."

    // FR-1004b's consequence, which the requirement says shall be stated here alongside the
    // warning rather than left to be discovered.
    const val WEBHOOK_OFFLINE_NOTE =
        "If you save something while offline, Latch writes it to Google when the connection " +
            "comes back — but no webhook is sent for it. There is one attempt, at the moment " +
            "you save, and it is never retried."

    const val WEBHOOK_SAVED = "Endpoint saved. Tick the box above and press Save to start sending."
    const val WEBHOOK_CLEARED = "Endpoint removed, and sending is switched off."
    const val WEBHOOK_LAST_OK = "Last delivery: accepted, %s."
    const val WEBHOOK_LAST_REFUSED = "Last delivery: your endpoint answered %d, %s."
    const val WEBHOOK_LAST_UNREACHABLE = "Last delivery: could not reach your endpoint, %s."
    const val WEBHOOK_LAST_NOT_SENT =
        "Last delivery: nothing was sent, %s. The stored address is not one Latch will send to."

    const val ENDPOINT_MALFORMED = "That is not a web address."
    const val ENDPOINT_NOT_HTTPS =
        "The address must start with https. Latch will not send what you captured over " +
            "an unencrypted connection."
    const val ENDPOINT_CREDENTIALS =
        "The address must not carry a user name and password. Put the token in the path or " +
            "in a query string if your endpoint needs one."

    const val SETTINGS_NOT_YET = "Not on this PC yet"
    const val SETTINGS_GAP_DESTINATION =
        "Choosing a calendar, and the Option A / Option B switch (FR-900, FR-1002). " +
            "Captures go to the Latch calendar your phone made."
    const val SETTINGS_GAP_LAYERS =
        "Turning capture layers on and off (FR-1003). Windows has one way in — the shortcut."
    const val SETTINGS_GAP_RECIPES = "Recipes (FR-600)."

    const val REASON_UNDATED = "No date was found in this capture."
    const val REASON_LOW_CONFIDENCE = "Latch was not confident about this one."
    const val REASON_INCOMPLETE = "A time, but no day to put it on."
    const val REASON_RESCHEDULE =
        "This looks like a change to something already saved, and there was no connection to ask about it."
    const val NOT_CONFIGURED =
        "This copy of Latch has no Google client configured, so it cannot sign in."
}
