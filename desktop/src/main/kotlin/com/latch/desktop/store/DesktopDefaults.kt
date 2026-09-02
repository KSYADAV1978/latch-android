package com.latch.desktop.store

import com.latch.google.json.JSONObject

/**
 * Where this machine sends a capture, once setup has chosen.
 *
 * The Android equivalent is `AccountDefaults`, and this is deliberately smaller. That record
 * carries a routing mode, FR-905's rules, FR-903's hidden-calendar badge and the colour the
 * chip is drawn in, because the phone has screens for all of it. This client has a tray and a
 * popup, so it carries what a write needs and what the tray shows.
 *
 * **A leading version, and an unreadable record is dropped rather than guessed at**, which is
 * the discipline every record format in this project follows. Dropping means setup runs again,
 * which costs a sign-in and loses nothing: no capture is stored in here.
 */
data class DesktopDefaults(
    val email: String,
    val calendarId: String,
    val calendarName: String,
    val taskListId: String,
) {
    fun encode(): String = JSONObject()
        .put("v", RECORD_VERSION)
        .put("email", email)
        .put("calendar_id", calendarId)
        .put("calendar_name", calendarName)
        .put("task_list_id", taskListId)
        .toString()

    companion object {
        const val RECORD_VERSION: Int = 1

        /** The key this record is stored under, in the same DPAPI file as the refresh token. */
        const val KEY: String = "account.defaults"

        /**
         * Null where the record is absent, from a version this build does not know, or missing
         * a field a write needs.
         *
         * The last of those is the one worth spelling out: a record with no `calendar_id` would
         * otherwise become a write to a calendar called the empty string, which Google answers
         * with a 404 the user cannot interpret. Setup running again is the better failure.
         */
        fun decode(json: String?): DesktopDefaults? {
            if (json.isNullOrBlank()) return null
            val record = runCatching { JSONObject(json) }.getOrNull() ?: return null
            if (record.optInt("v", -1) != RECORD_VERSION) return null

            val calendarId = record.optString("calendar_id")
            val taskListId = record.optString("task_list_id")
            if (calendarId.isBlank() || taskListId.isBlank()) return null

            return DesktopDefaults(
                email = record.optString("email"),
                calendarId = calendarId,
                calendarName = record.optString("calendar_name").ifBlank { calendarId },
                taskListId = taskListId,
            )
        }
    }
}

/** Reads and writes [DesktopDefaults] through the same DPAPI file as the refresh token. */
class DefaultsStore(private val secrets: SecretFile) {

    /**
     * **Encrypted, though a calendar id is not obviously a secret.**
     *
     * The email address is, in the sense NFR-206 cares about — it names the person — and the
     * Android client hashes it rather than storing it for exactly that reason. Putting the
     * record in the file that already exists costs nothing and means there is one place on
     * this machine holding anything about the user, which is also one place for NFR-205 to
     * delete.
     */
    fun read(): DesktopDefaults? = DesktopDefaults.decode(secrets.get(DesktopDefaults.KEY))

    fun write(defaults: DesktopDefaults) = secrets.put(DesktopDefaults.KEY, defaults.encode())

    fun clear() = secrets.remove(DesktopDefaults.KEY)
}
