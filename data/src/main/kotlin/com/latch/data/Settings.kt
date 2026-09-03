package com.latch.data

import android.content.Context
import android.content.SharedPreferences
import com.latch.core.model.CaptureLayer
import com.latch.core.model.DEFAULT_LAYERS
import com.latch.core.model.DEFAULT_WORKING_DAYS
import com.latch.core.model.Holiday
import com.latch.core.model.HolidaySource
import com.latch.core.model.LatchSettings
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

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
        .put("monitored_packages", JSONArray(settings.monitoredPackages.toList()))
        .put("seen_packages", JSONArray(settings.seenNotificationPackages.toList()))

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
            monitoredPackages = json.stringsAt("monitored_packages").toSet(),
            seenNotificationPackages = json.stringsAt("seen_packages").toSet(),
        )
    }
} catch (malformed: JSONException) {
    null
}

private fun JSONObject.stringsAt(key: String): List<String> =
    optJSONArray(key)?.let { array -> (0 until array.length()).map(array::getString) }.orEmpty()
