package com.latch.core.model

import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate

/**
 * **This lives in `:core-model` because both clients of §4.1 read it.** FR-504's date order,
 * FR-1001's default duration and FR-512's threshold all reach `ParseContext`, and a parse that
 * two machines read differently is the drift `:wire` exists to remove — SRS 1.59 asked exactly
 * that question of `ItemKeyAcrossClientsTest` and found the date order the field most likely to
 * differ between somebody's phone and their desktop.
 *
 * **What is not here is any store.** Each client keeps this the way its platform keeps things:
 * the phone in encrypted preferences under a Keystore key, the desktop in a DPAPI file. Nor is
 * anything platform-specific: FR-302's hotkey is a Windows setting and lives in the Windows
 * client's own record, and [enabledLayers] and [monitoredPackages] describe FR-200's Android
 * layers and are simply unread on a desktop.
 */
/**
 * FR-1001's settings, as one record.
 *
 * **What is deliberately not here.** The destination calendar, the task list and the routing
 * mode live on [AccountDefaults], because FR-110 stores them *per account* and these are
 * per install. The FR-1004 webhook endpoint lives in the secret store, because NFR-203 requires
 * it — such URLs commonly embed a bearer token in the path.
 *
 * Every field has a default that is the behaviour the app had before this record existed, so a
 * missing or unreadable record is not a broken install: it is the app as it shipped.
 */
data class LatchSettings(
    /** FR-605: a six-day week is common in the target market, so this is not a Mon–Fri constant. */
    val workingDays: Set<DayOfWeek> = DEFAULT_WORKING_DAYS,
    /** FR-605: holidays the user added on top of the bundled Indian list. */
    val holidayAdditions: List<Holiday> = emptyList(),
    /** FR-605: bundled holidays the user removed. A removal is a date, not a name. */
    val holidayRemovals: Set<LocalDate> = emptySet(),
    /** FR-504: how `05/09` is read. `true` is DD/MM, the default for Indian locales. */
    val dayFirstDates: Boolean = true,
    /** FR-1001: applied where a start time is found but no end. */
    val defaultEventDuration: Duration = Duration.ofHours(1),
    /** FR-1001: minutes before an event, for a recipe step that names no reminders of its own. */
    val defaultReminderMinutes: List<Int> = emptyList(),
    /** FR-512's "configurable threshold". Below this a capture is routed to the Inbox. */
    val confidenceThreshold: Double = 0.6,
    /**
     * FR-1001: an IANA zone id, or null to follow the device.
     *
     * Null rather than the device's current zone written down, and that is the point: a user
     * who travels wants their captures to follow them unless they have said otherwise, and a
     * stored zone taken silently at setup would not.
     */
    val timeZone: String? = null,
    /**
     * FR-1003's per-layer toggles: text selection ON, share sheet ON, tile ON, notification
     * listener OFF, screenshot watcher OFF. The defaults here are that line, verbatim.
     */
    val enabledLayers: Set<CaptureLayer> = DEFAULT_LAYERS,
    /** FR-905: routing rules, under Option A. */
    val routingRules: List<RoutingRule> = emptyList(),
    /**
     * FR-1004: whether the outbound webhook is on. **Off by default**, which the requirement
     * states twice and AC-17 tests.
     *
     * The endpoint itself is **not here**. NFR-203 treats it as a secret — such URLs commonly
     * embed a bearer token in the path — so it lives in the secret store and this record holds
     * only the fact that one is configured. That also means a settings record read by anything
     * without the Keystore key discloses nothing about where a user's captures go.
     */
    val webhookEnabled: Boolean = false,
    /**
     * FR-907: how many times the user has overridden the destination for each source app.
     *
     * Kept per source *application*, because that is what FR-905's rule would match on — a
     * count kept per destination instead would offer a rule that could not be expressed. The
     * requirement's other half is that the app "shall not create the rule automatically", so
     * this is only ever an input to an offer.
     */
    val destinationOverrideCounts: Map<String, Int> = emptyMap(),
    /**
     * FR-212: which applications the notification listener monitors.
     *
     * **Empty by default, which means none**, and that is deliberate rather than an oversight:
     * FR-209 has the layer off by default, and a layer switched on that immediately began
     * reading every messaging app on the phone would be a second decision the user never made.
     * They pick.
     */
    val monitoredPackages: Set<String> = emptySet(),
    /**
     * The applications the listener has seen a notification from, so FR-212's picker has
     * something to offer.
     *
     * **A package name is not notification content**, which is what NFR-206 governs, and this is
     * the only way to populate that list without `QUERY_ALL_PACKAGES` — a restricted permission
     * this app will not request in order to fill a settings screen. Recorded as a reading in the
     * SRS rather than left as an inference from what is stored here.
     */
    val seenNotificationPackages: Set<String> = emptySet(),
) {
    /** FR-605's list as the calculator wants it: bundled, plus additions, minus removals. */
    fun holidays(bundled: List<Holiday>): List<Holiday> =
        bundled.filterNot { it.date in holidayRemovals } + holidayAdditions
}

/** FR-1003, and the reading behind the default working week: Monday to Friday. */
val DEFAULT_WORKING_DAYS: Set<DayOfWeek> = setOf(
    DayOfWeek.MONDAY,
    DayOfWeek.TUESDAY,
    DayOfWeek.WEDNESDAY,
    DayOfWeek.THURSDAY,
    DayOfWeek.FRIDAY,
)

/** FR-1003, verbatim: the two that are off are the two that carry a disclosure obligation. */
val DEFAULT_LAYERS: Set<CaptureLayer> = setOf(
    CaptureLayer.TEXT_SELECTION,
    CaptureLayer.SHARE_SHEET,
    CaptureLayer.QUICK_TILE,
)
