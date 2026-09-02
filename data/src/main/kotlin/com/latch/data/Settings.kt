package com.latch.data

import android.content.Context
import android.content.SharedPreferences
import com.latch.core.model.CaptureLayer
import com.latch.core.model.Holiday
import com.latch.core.model.HolidaySource
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

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
    val routingRules: List<com.latch.core.model.RoutingRule> = emptyList(),
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

interface SettingsStore {
    suspend fun read(): LatchSettings
    suspend fun write(settings: LatchSettings)
}

/**
 * [SettingsStore] over app-private preferences, encrypted by [KeystoreCipher] — the same
 * mechanism as [EncryptedAccountDefaultsStore] and the write queue, for the reason NFR-203's
 * recorded reading gives: one scheme, not three.
 *
 * **An unreadable record decodes to the defaults rather than being deleted**, and that is the
 * opposite of what the account-defaults store does with one. The difference is what is lost.
 * Losing account defaults means the app does not know where to write, so running setup again is
 * the only honest recovery; losing settings means the app behaves as it shipped, which is a
 * working app with some preferences forgotten. Sending a user back through setup because their
 * working week could not be read would be the disproportionate answer.
 */
class EncryptedSettingsStore(context: Context) : SettingsStore {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    private val cipher = KeystoreCipher(KEY_ALIAS)

    override suspend fun read(): LatchSettings = withContext(Dispatchers.IO) {
        prefs.getString(RECORD_KEY, null)
            ?.let(cipher::decrypt)
            ?.let(::decodeSettings)
            ?: LatchSettings()
    }

    override suspend fun write(settings: LatchSettings) {
        withContext(Dispatchers.IO) {
            val written = prefs.edit()
                .putString(RECORD_KEY, cipher.encrypt(encodeSettings(settings)))
                .commit()
            check(written) { "Settings were not written to disk." }
        }
    }

    private companion object {
        const val PREFS_FILE = "settings"
        const val KEY_ALIAS = "latch.settings.v1"
        const val RECORD_KEY = "settings"
    }
}

// ---------------------------------------------------------------------------------------
// The record format. Internal and pure, so it is JVM-tested; the preferences are not.
// ---------------------------------------------------------------------------------------

internal const val SETTINGS_RECORD_VERSION = 1

internal fun encodeSettings(settings: LatchSettings): String {
    val json = JSONObject()
        .put("v", SETTINGS_RECORD_VERSION)
        .put("working_days", JSONArray(settings.workingDays.map { it.name }))
        .put("holiday_removals", JSONArray(settings.holidayRemovals.map { it.toString() }))
        .put("day_first", settings.dayFirstDates)
        .put("default_duration_minutes", settings.defaultEventDuration.toMinutes())
        .put("default_reminders", JSONArray(settings.defaultReminderMinutes))
        .put("confidence_threshold", settings.confidenceThreshold)
        .putOpt("time_zone", settings.timeZone)
        .put("layers", JSONArray(settings.enabledLayers.map { it.name }))
        .put("webhook_enabled", settings.webhookEnabled)
        .put("override_counts", JSONObject(settings.destinationOverrideCounts as Map<*, *>))

    val additions = JSONArray()
    settings.holidayAdditions.forEach {
        additions.put(JSONObject().put("date", it.date.toString()).put("name", it.name))
    }
    json.put("holiday_additions", additions)

    val rules = JSONArray()
    settings.routingRules.forEach {
        rules.put(
            JSONObject()
                .put("id", it.id)
                .put("match_type", it.matchType.name)
                .put("match_value", it.matchValue)
                .put("calendar_id", it.calendarId)
                .put("priority", it.priority)
                .put("calendar_name", it.calendarName)
                .put("calendar_colour", it.calendarColour)
        )
    }
    json.put("routing_rules", rules)

    return json.toString()
}

/**
 * Null for anything unreadable, which the store turns into the defaults.
 *
 * Field by field rather than all-or-nothing where a *value* is unrecognised: a working day or a
 * capture layer a later version introduced is dropped and the rest of the record is kept,
 * because losing one preference is better than losing all of them. The record **version** is
 * still all-or-nothing, for the reason every other record here is: a layout this code does not
 * know cannot be read one field at a time either.
 */
internal fun decodeSettings(record: String): LatchSettings? = try {
    val json = JSONObject(record)
    if (json.optInt("v") != SETTINGS_RECORD_VERSION) {
        null
    } else {
        val defaults = LatchSettings()
        LatchSettings(
            workingDays = json.stringsAt("working_days")
                .mapNotNull { name -> DayOfWeek.entries.firstOrNull { it.name == name } }
                .toSet()
                // A working week with no working days never terminates — `WorkingWeek`'s own
                // init says so. A record that somehow held one falls back rather than hanging.
                .ifEmpty { defaults.workingDays },
            holidayAdditions = json.optJSONArray("holiday_additions")?.let { array ->
                (0 until array.length()).mapNotNull { index ->
                    val entry = array.optJSONObject(index) ?: return@mapNotNull null
                    val date = runCatching { LocalDate.parse(entry.getString("date")) }.getOrNull()
                    date?.let { Holiday(it, entry.optString("name"), HolidaySource.USER) }
                }
            }.orEmpty(),
            holidayRemovals = json.stringsAt("holiday_removals")
                .mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }
                .toSet(),
            dayFirstDates = json.optBoolean("day_first", defaults.dayFirstDates),
            defaultEventDuration = json.optLong("default_duration_minutes")
                .takeIf { it > 0 }
                ?.let(Duration::ofMinutes)
                ?: defaults.defaultEventDuration,
            defaultReminderMinutes = json.optJSONArray("default_reminders")
                ?.let { array -> (0 until array.length()).map(array::getInt) }
                .orEmpty(),
            confidenceThreshold = json.optDouble("confidence_threshold", defaults.confidenceThreshold)
                .takeIf { it in 0.0..1.0 } ?: defaults.confidenceThreshold,
            timeZone = json.optString("time_zone").takeIf { it.isNotBlank() },
            enabledLayers = json.stringsAt("layers")
                .mapNotNull { name -> CaptureLayer.entries.firstOrNull { it.name == name } }
                .toSet(),
            routingRules = json.optJSONArray("routing_rules")?.let { array ->
                (0 until array.length()).mapNotNull { index ->
                    val entry = array.optJSONObject(index) ?: return@mapNotNull null
                    val type = com.latch.core.model.MatchType.entries
                        .firstOrNull { it.name == entry.optString("match_type") }
                        ?: return@mapNotNull null
                    com.latch.core.model.RoutingRule(
                        id = entry.getString("id"),
                        matchType = type,
                        matchValue = entry.optString("match_value"),
                        calendarId = entry.optString("calendar_id"),
                        priority = entry.optInt("priority"),
                        calendarName = entry.optString("calendar_name"),
                        calendarColour = entry.optString("calendar_colour"),
                    )
                }
            }.orEmpty(),
            // Defaults to false where a record predates the field, which is FR-1004's own
            // default and the safe direction: a webhook that turned itself on would be the
            // one failure this requirement's whole shape is built to prevent.
            webhookEnabled = json.optBoolean("webhook_enabled", false),
            destinationOverrideCounts = json.optJSONObject("override_counts")?.let { counts ->
                counts.keys().asSequence().associateWith { counts.optInt(it) }
            }.orEmpty(),
        )
    }
} catch (malformed: JSONException) {
    null
}

private fun JSONObject.stringsAt(key: String): List<String> =
    optJSONArray(key)?.let { array -> (0 until array.length()).map(array::getString) }.orEmpty()
