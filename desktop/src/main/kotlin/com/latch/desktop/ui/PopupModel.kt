package com.latch.desktop.ui

import com.latch.core.model.ItemType
import com.latch.desktop.capture.EmptyCapture
import com.latch.parser.DatedCandidate
import com.latch.parser.ParseResult
import com.latch.wire.WireCapture
import com.latch.wire.candidateBlocker
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
        CaptureRow(
            index = index,
            badge = if (type == ItemType.EVENT) DesktopStrings.BADGE_EVENT else DesktopStrings.BADGE_TASK,
            whenLine = whenLineFor(candidate),
            title = titleFor(captured, result, candidate, titleOverrides[index]),
            checked = index in selected,
            canOverride = candidate.date?.value?.isBefore(today) != true,
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
    const val EDIT_TITLE = "Edit"
    const val OVERRIDE_HINT = "Press Space on a badge to switch between event and to-do."
    const val NOT_SIGNED_IN = "Sign in to Google from the Latch tray icon first."
    const val NOT_CONFIGURED =
        "This copy of Latch has no Google client configured, so it cannot sign in."
}
