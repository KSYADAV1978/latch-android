package com.latch.desktop.store

import com.latch.core.model.Holiday
import com.latch.core.model.HolidaySource
import com.latch.core.model.LatchSettings
import com.latch.core.model.DEFAULT_LAYERS
import com.latch.core.model.DEFAULT_WORKING_DAYS
import com.latch.desktop.capture.HotkeySpec
import com.latch.google.json.JSONArray
import com.latch.google.json.JSONObject
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate

/**
 * FR-1001's settings on this machine: the shared record, plus what only Windows has.
 *
 * **The split is the point.** [shared] is `:core-model`'s `LatchSettings`, which the phone also
 * holds — FR-504's date order, FR-512's threshold, FR-605's working week, FR-1001's defaults —
 * so the two clients read one capture the same way. [hotkey] is FR-302's, and FR-302 is a
 * Windows requirement: putting it in the shared record would have added a field the phone can
 * never set and never read.
 *
 * **The FR-1004 endpoint is deliberately not here**, following the phone: NFR-203 treats it as
 * a secret, such URLs commonly carry a bearer token in the path, and it lives under its own key
 * so that a settings record discloses nothing about where a user's captures go.
 */
data class DesktopSettings(
    val shared: LatchSettings = LatchSettings(),
    /** FR-302, as written for [com.latch.desktop.capture.parseHotkey]. */
    val hotkey: String = HotkeySpec.DEFAULT,
) {
    companion object {
        const val RECORD_VERSION: Int = 1

        /** The key this record is stored under, in the same DPAPI file as the refresh token. */
        const val KEY: String = "app.settings"
    }
}

fun encodeDesktopSettings(settings: DesktopSettings): String {
    val shared = settings.shared
    return JSONObject()
        .put("v", DesktopSettings.RECORD_VERSION)
        .put("hotkey", settings.hotkey)
        .put(
            "working_days",
            JSONArray().also { array -> shared.workingDays.sorted().forEach { array.put(it.name) } },
        )
        .put(
            "holiday_additions",
            JSONArray().also { array ->
                shared.holidayAdditions.forEach {
                    array.put(JSONObject().put("date", it.date.toString()).put("name", it.name))
                }
            },
        )
        .put(
            "holiday_removals",
            JSONArray().also { array -> shared.holidayRemovals.sorted().forEach { array.put(it.toString()) } },
        )
        .put("day_first_dates", shared.dayFirstDates)
        .put("default_event_minutes", shared.defaultEventDuration.toMinutes().toInt())
        .put(
            "default_reminder_minutes",
            JSONArray().also { array -> shared.defaultReminderMinutes.forEach { array.put(it) } },
        )
        .put("confidence_threshold", shared.confidenceThreshold)
        .put("time_zone", shared.timeZone)
        .put("webhook_enabled", shared.webhookEnabled)
        .toString()
}

/**
 * **An unreadable record decodes to the defaults rather than to null**, which is the opposite of
 * what [DesktopDefaults] does with one and is the phone's reading followed rather than
 * re-decided. The difference is what is lost: losing the destination means the client does not
 * know where to write, so setup running again is the only honest recovery; losing settings
 * means the client behaves as it shipped, which is a working client with some preferences
 * forgotten. Sending a user back through sign-in because their working week could not be read
 * would be the disproportionate answer.
 *
 * **What is deliberately not carried across from the phone's record is FR-905's routing rules
 * and FR-1003's layer toggles.** This client has no destination picker to make a rule against
 * and no capture layers to toggle; storing fields nothing reads would read as though the
 * capability were there. They keep their `LatchSettings` defaults, which is what they mean.
 */
fun decodeDesktopSettings(record: String?): DesktopSettings {
    val fallback = DesktopSettings()
    if (record.isNullOrBlank()) return fallback
    val json = runCatching { JSONObject(record) }.getOrNull() ?: return fallback
    if (json.optInt("v", -1) != DesktopSettings.RECORD_VERSION) return fallback

    val defaults = LatchSettings()
    val days = json.optJSONArray("working_days").toStrings()
        .mapNotNull { name -> DayOfWeek.entries.firstOrNull { it.name == name } }
        .toSet()

    return DesktopSettings(
        hotkey = json.optString("hotkey").ifBlank { HotkeySpec.DEFAULT },
        shared = LatchSettings(
            // A record naming no working day at all would make `WorkingWeek` throw on
            // construction — its own `require` says a week with no working days never
            // terminates — so an empty set reads as the default rather than as a choice.
            workingDays = days.ifEmpty { DEFAULT_WORKING_DAYS },
            holidayAdditions = json.optJSONArray("holiday_additions").toObjects().mapNotNull { entry ->
                val date = runCatching { LocalDate.parse(entry.optString("date")) }.getOrNull()
                date?.let { Holiday(it, entry.optString("name"), HolidaySource.USER) }
            },
            holidayRemovals = json.optJSONArray("holiday_removals").toStrings()
                .mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }
                .toSet(),
            dayFirstDates = json.optBoolean("day_first_dates", defaults.dayFirstDates),
            defaultEventDuration = json.optInt("default_event_minutes", -1)
                .takeIf { it > 0 }
                ?.let { Duration.ofMinutes(it.toLong()) }
                ?: defaults.defaultEventDuration,
            defaultReminderMinutes = json.optJSONArray("default_reminder_minutes").toStrings()
                .mapNotNull { it.toIntOrNull() },
            confidenceThreshold = json.optDouble("confidence_threshold", defaults.confidenceThreshold)
                .coerceIn(0.0, 1.0),
            timeZone = json.optString("time_zone").takeIf { it.isNotBlank() },
            // FR-1003 describes Android's capture layers. This client has one way in, so the
            // shipped set stands and the toggles are simply not offered here.
            enabledLayers = DEFAULT_LAYERS,
            webhookEnabled = json.optBoolean("webhook_enabled", false),
        ),
    )
}

private fun JSONArray?.toStrings(): List<String> =
    (0 until (this?.length() ?: 0)).mapNotNull { this?.optString(it) }.filter { it.isNotBlank() }

private fun JSONArray?.toObjects(): List<JSONObject> =
    (0 until (this?.length() ?: 0)).mapNotNull { this?.optJSONObject(it) }

/**
 * Reads and writes [DesktopSettings] through the same DPAPI file as the refresh token.
 *
 * **Cached in memory after the first read**, which the destination store does not need and this
 * one does: NFR-101 budgets a capture 800 ms and the DPAPI bridge costs most of a second, so a
 * settings read on the capture path would be the largest single cost in it. The cache is
 * invalidated by the only thing that can change the record, which is this client writing it.
 */
class DesktopSettingsStore(private val secrets: SecretFile) {

    @Volatile
    private var cached: DesktopSettings? = null

    fun read(): DesktopSettings = cached ?: decodeDesktopSettings(secrets.get(DesktopSettings.KEY))
        .also { cached = it }

    fun write(settings: DesktopSettings) {
        secrets.put(DesktopSettings.KEY, encodeDesktopSettings(settings))
        cached = settings
    }

    /** NFR-205, and what a failed read should not be mistaken for. */
    fun clear() {
        secrets.remove(DesktopSettings.KEY)
        cached = null
    }
}
