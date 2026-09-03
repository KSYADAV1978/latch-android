package com.latch.desktop.ui

import com.latch.core.model.LatchSettings
import com.latch.desktop.capture.HotkeyParse
import com.latch.desktop.capture.HotkeyProblem
import com.latch.desktop.capture.parseHotkey
import com.latch.desktop.store.DesktopSettings
import java.time.DayOfWeek
import java.time.Duration
import java.time.ZoneId

/**
 * What the user typed into the Settings window, before any of it is believed.
 *
 * Strings rather than parsed values, deliberately: the window's job is to collect characters and
 * this file's job is to decide what they mean. A form that parsed as it went would have to
 * decide what to do with a half-typed number on every keystroke, and the answer is always "wait
 * until Save".
 */
data class SettingsForm(
    /** FR-302, as `Ctrl+Shift+K`. */
    val hotkey: String,
    /** FR-504: true is DD/MM. */
    val dayFirst: Boolean,
    /** FR-1001: minutes, applied where a start time is found but no end. */
    val defaultDurationMinutes: String,
    /** FR-1001: minutes before an event, comma-separated. Blank means Google's own defaults. */
    val defaultReminderMinutes: String,
    /** FR-512: whole percent. */
    val confidencePercent: String,
    /** FR-605: a six-day week is common in the target market, so this is a set and not a flag. */
    val workingDays: Set<DayOfWeek>,
    /** FR-1001: an IANA zone id, or blank to follow the machine. */
    val timeZone: String,
)

/** Why a form could not be saved. Named, so this file's own strings phrase it (NFR-402). */
enum class SettingsProblem {
    HOTKEY_EMPTY,
    HOTKEY_NO_KEY,
    HOTKEY_UNKNOWN_KEY,
    HOTKEY_NO_MODIFIER,
    HOTKEY_TOO_MANY_KEYS,
    DURATION,
    REMINDERS,
    THRESHOLD,
    WORKING_WEEK,
    TIME_ZONE,
}

/**
 * Either the settings a form means, or every reason it means none.
 *
 * **All the problems, not the first one.** A form that reported one error at a time would make
 * a user with two typos press Save twice to find out about the second.
 */
data class SettingsApplied(
    val settings: DesktopSettings?,
    val problems: List<SettingsProblem>,
) {
    val ok: Boolean get() = settings != null
}

/** The form a stored record produces, so the window opens showing what is actually in force. */
fun formOf(settings: DesktopSettings): SettingsForm = SettingsForm(
    hotkey = settings.hotkey,
    dayFirst = settings.shared.dayFirstDates,
    defaultDurationMinutes = settings.shared.defaultEventDuration.toMinutes().toString(),
    defaultReminderMinutes = settings.shared.defaultReminderMinutes.joinToString(", "),
    confidencePercent = (settings.shared.confidenceThreshold * 100).toInt().toString(),
    workingDays = settings.shared.workingDays,
    timeZone = settings.shared.timeZone.orEmpty(),
)

/**
 * FR-1001, as a pure function: what the typed form means, or why it means nothing.
 *
 * **Nothing is saved unless every field is good.** A partial save is the failure mode a
 * settings screen can least afford — the user pressed Save, some of it took and some did not,
 * and the screen looks the same either way.
 *
 * **The record is changed rather than rebuilt**, so a field this client does not offer keeps
 * whatever it holds. FR-905's routing rules and FR-1003's layer toggles are in `LatchSettings`
 * because the phone needs them, and a desktop Save that reconstructed the record from the form
 * would silently clear them.
 */
fun applyForm(current: DesktopSettings, form: SettingsForm): SettingsApplied {
    val problems = mutableListOf<SettingsProblem>()

    val hotkey = when (val parsed = parseHotkey(form.hotkey)) {
        is HotkeyParse.Parsed -> parsed.spec.display
        is HotkeyParse.Rejected -> {
            problems += hotkeyProblem(parsed.problem)
            null
        }
    }

    // Minutes, and a duration of zero is refused rather than accepted: an event that ends when
    // it starts is not what anybody means by a default length, and Google shows it as a point.
    val duration = form.defaultDurationMinutes.trim().toLongOrNull()
        ?.takeIf { it in 1..(24 * 60) }
        ?.let(Duration::ofMinutes)
    if (duration == null) problems += SettingsProblem.DURATION

    val reminders = remindersOf(form.defaultReminderMinutes)
    if (reminders == null) problems += SettingsProblem.REMINDERS

    val threshold = form.confidencePercent.trim().toIntOrNull()?.takeIf { it in 0..100 }
    if (threshold == null) problems += SettingsProblem.THRESHOLD

    // FR-605: `WorkingWeek` refuses an empty week on construction — one with no working days
    // never terminates — so this is refused here, where the user can read why.
    if (form.workingDays.isEmpty()) problems += SettingsProblem.WORKING_WEEK

    val zoneText = form.timeZone.trim()
    val zone = if (zoneText.isEmpty()) null else {
        runCatching { ZoneId.of(zoneText) }.getOrNull()?.id
            .also { if (it == null) problems += SettingsProblem.TIME_ZONE }
    }

    if (problems.isNotEmpty()) return SettingsApplied(null, problems)

    return SettingsApplied(
        current.copy(
            hotkey = hotkey!!,
            shared = current.shared.copy(
                workingDays = form.workingDays,
                dayFirstDates = form.dayFirst,
                defaultEventDuration = duration!!,
                defaultReminderMinutes = reminders!!,
                confidenceThreshold = threshold!! / 100.0,
                timeZone = zone,
            ),
        ),
        emptyList(),
    )
}

/**
 * Blank is **no override**, which is not the same as no reminders at all.
 *
 * `:wire` omits the reminder overrides entirely where the list is empty, so Google's own
 * calendar defaults stay in force — an empty override list would mean "no reminders", which is
 * a different instruction and one this app has never taken on a user's behalf.
 */
internal fun remindersOf(text: String): List<Int>? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return emptyList()
    val parts = trimmed.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    val numbers = parts.map { it.toIntOrNull() ?: return null }
    if (numbers.any { it < 0 || it > 40320 }) return null
    return numbers.distinct().sorted()
}

private fun hotkeyProblem(problem: HotkeyProblem) = when (problem) {
    HotkeyProblem.EMPTY -> SettingsProblem.HOTKEY_EMPTY
    HotkeyProblem.NO_KEY -> SettingsProblem.HOTKEY_NO_KEY
    HotkeyProblem.UNKNOWN_KEY -> SettingsProblem.HOTKEY_UNKNOWN_KEY
    HotkeyProblem.NO_MODIFIER -> SettingsProblem.HOTKEY_NO_MODIFIER
    HotkeyProblem.TOO_MANY_KEYS -> SettingsProblem.HOTKEY_TOO_MANY_KEYS
}

/** NFR-402: the facts are named above and phrased here. */
fun settingsProblemText(problem: SettingsProblem): String = when (problem) {
    SettingsProblem.HOTKEY_EMPTY -> "Type a shortcut, such as Ctrl+Shift+K."
    SettingsProblem.HOTKEY_NO_KEY -> "A shortcut needs a key as well as Ctrl, Shift or Alt."
    SettingsProblem.HOTKEY_UNKNOWN_KEY ->
        "Latch does not know that key. Use a letter, a number, F1 to F24, Space, Insert, " +
            "Delete, Home, End, Page Up or Page Down."
    // Not a limitation of Windows, which would register a bare K happily — and then the letter
    // K would stop working everywhere else on the machine.
    SettingsProblem.HOTKEY_NO_MODIFIER -> "A shortcut needs Ctrl, Shift, Alt or Win with it."
    SettingsProblem.HOTKEY_TOO_MANY_KEYS -> "A shortcut can have only one key besides Ctrl, Shift and Alt."
    SettingsProblem.DURATION -> "The default length must be between 1 and 1440 minutes."
    SettingsProblem.REMINDERS ->
        "Reminders must be minutes, separated by commas — for example 30, 1440. " +
            "Leave it empty to use your calendar's own reminders."
    SettingsProblem.THRESHOLD -> "The confidence threshold must be between 0 and 100."
    SettingsProblem.WORKING_WEEK -> "Choose at least one working day."
    SettingsProblem.TIME_ZONE ->
        "That is not a time zone Latch knows. Use a name like Asia/Kolkata, or leave it empty " +
            "to follow this PC."
}

/**
 * Whether the FR-302 registration has to be redone.
 *
 * Asked here rather than by re-registering on every Save, because re-registering means stopping
 * a sidecar and starting another — and if the new combination is already held by another
 * application the user is left with **no** working shortcut, having changed something unrelated.
 */
fun hotkeyChanged(before: DesktopSettings, after: DesktopSettings): Boolean =
    before.hotkey != after.hotkey

/**
 * What Settings says about the things this client does not have yet.
 *
 * **Named on screen rather than absent**, which is SRS 1.65's finding applied before it can
 * happen again: a capability whose control is missing reads as though the work were done, and
 * the next person to look would reasonably believe it. Each line says which requirement is owed.
 */
fun settingsGaps(): List<String> = listOf(
    // FR-1002 is about switching between Option A and Option B without moving anything already
    // saved. There is no switch here because there is no picker to switch between — FR-900's
    // destination chooser is owed on this client, and a mode with one destination is not a mode.
    DesktopStrings.SETTINGS_GAP_DESTINATION,
    DesktopStrings.SETTINGS_GAP_LAYERS,
    DesktopStrings.SETTINGS_GAP_RECIPES,
)

/** FR-605's days, in the order a week is read. */
val WEEK_IN_ORDER: List<DayOfWeek> = listOf(
    DayOfWeek.MONDAY,
    DayOfWeek.TUESDAY,
    DayOfWeek.WEDNESDAY,
    DayOfWeek.THURSDAY,
    DayOfWeek.FRIDAY,
    DayOfWeek.SATURDAY,
    DayOfWeek.SUNDAY,
)

/** The shared record, for the callers that only want that half. */
val DesktopSettings.parserSettings: LatchSettings get() = shared
