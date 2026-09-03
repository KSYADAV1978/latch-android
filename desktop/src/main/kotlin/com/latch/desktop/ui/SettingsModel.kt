package com.latch.desktop.ui

import com.latch.core.model.LatchSettings
import com.latch.desktop.capture.HotkeyParse
import com.latch.desktop.capture.HotkeyProblem
import com.latch.desktop.capture.parseHotkey
import com.latch.desktop.store.DesktopSettings
import com.latch.webhook.WebhookDelivery
import com.latch.webhook.WebhookResult
import com.latch.webhook.maskedEndpoint
import java.time.DayOfWeek
import java.time.Duration
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
    /**
     * FR-1004, and it is **only the switch**.
     *
     * The endpoint is not on this form and never round-trips through it: NFR-203 requires it
     * masked once saved, so a field holding the real value would be one render from showing it.
     * It has its own control and its own act — FR-1004 asks for the URL to be entered
     * explicitly *and* for the feature to be enabled, and the screen keeps those as two things.
     */
    val webhookEnabled: Boolean = false,
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

    /**
     * FR-1004: enabling delivery with nowhere to deliver to.
     *
     * Refused rather than accepted-and-inert. `webhookEligible` would decline to send anyway,
     * so nothing would leak — but a switch that is on and does nothing is the same class of
     * defect SRS 1.65 found in this client's badge: it reads as though the work were done.
     */
    WEBHOOK_NO_ENDPOINT,
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
    webhookEnabled = settings.shared.webhookEnabled,
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
fun applyForm(
    current: DesktopSettings,
    form: SettingsForm,
    /** FR-1004: whether an endpoint is stored. Passed in, because it lives in the secret store. */
    hasEndpoint: Boolean = false,
): SettingsApplied {
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

    if (form.webhookEnabled && !hasEndpoint) problems += SettingsProblem.WEBHOOK_NO_ENDPOINT

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
                webhookEnabled = form.webhookEnabled,
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
    SettingsProblem.WEBHOOK_NO_ENDPOINT ->
        "Enter an endpoint before switching the webhook on. Latch will not send anything " +
            "without one."
}

/**
 * FR-1004b's passive report, in words.
 *
 * "Passively" is the requirement's own word and it governs the phrasing as much as the place:
 * this is a line on a screen the user chose to open, not an alert. A success is reported as
 * well as a failure, because a webhook that silently works and a webhook that silently does
 * nothing look identical, and the second is the state a user actually needs to notice.
 */
fun deliveryText(delivery: WebhookDelivery?, at: ZoneId = ZoneId.systemDefault()): String? {
    if (delivery == null) return null
    val when_ = DELIVERY_TIME.format(delivery.at.atZone(at))
    return when (delivery.result) {
        WebhookResult.DELIVERED -> DesktopStrings.WEBHOOK_LAST_OK.replace("%s", when_)
        WebhookResult.ENDPOINT_REFUSED -> DesktopStrings.WEBHOOK_LAST_REFUSED
            .replace("%s", when_)
            .replace("%d", delivery.status?.toString() ?: "?")
        WebhookResult.UNREACHABLE -> DesktopStrings.WEBHOOK_LAST_UNREACHABLE.replace("%s", when_)
        WebhookResult.REFUSED_BEFORE_SENDING ->
            DesktopStrings.WEBHOOK_LAST_NOT_SENT.replace("%s", when_)
    }
}

private val DELIVERY_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM, HH:mm")

/** NFR-203: what the endpoint field shows once something is stored. */
fun endpointLine(endpoint: String?): String =
    endpoint?.let(::maskedEndpoint) ?: DesktopStrings.WEBHOOK_NONE

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
