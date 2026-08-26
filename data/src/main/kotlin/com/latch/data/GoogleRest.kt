package com.latch.data

import java.net.URLEncoder
import java.security.MessageDigest
import java.util.TimeZone
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject

private const val CALENDAR_V3 = "https://www.googleapis.com/calendar/v3"
private const val TASKS_V1 = "https://tasks.googleapis.com/tasks/v1"

/**
 * A page count that only a bug can reach. `calendarList.list` caps at 250 entries per page,
 * so ten pages is 2,500 calendars; a token that never clears would otherwise spin forever
 * behind a spinner the user cannot cancel.
 */
private const val MAX_PAGES = 10

/**
 * The way `:app` gets these. The clients and the HTTP layer stay internal so that
 * [ALLOWED_HOSTS] cannot be bypassed by constructing a client with something else underneath
 * — the AC-17 guarantee is only worth as much as the number of ways around it.
 */
fun googleCalendarApi(tokens: TokenProvider): CalendarApi = GoogleCalendarApi(GoogleHttp(tokens))

fun googleTasksApi(tokens: TokenProvider): TasksApi = GoogleTasksApi(GoogleHttp(tokens))

/** FR-901, FR-902, FR-903, FR-105, FR-104 — the calendar half of setup. */
internal class GoogleCalendarApi(private val http: GoogleHttp) : CalendarApi {

    override suspend fun listWritableCalendars(): List<WritableCalendar> {
        val calendars = mutableListOf<WritableCalendar>()
        var pageToken: String? = null
        var pages = 0

        do {
            currentCoroutineContext().ensureActive()
            val url = buildString {
                append("$CALENDAR_V3/users/me/calendarList?maxResults=250")
                pageToken?.let { append("&pageToken=").append(encodeQuery(it)) }
            }
            val page = http.get(url)
            calendars += writableCalendarsFrom(page)
            pageToken = nextPageTokenFrom(page)
            pages++
        } while (pageToken != null && pages < MAX_PAGES)

        return calendars
    }

    override suspend fun createLatchCalendar(summary: String, description: String): String {
        val body = JSONObject()
            .put("summary", summary)
            .put("description", description)
            // Without this the calendar takes the account's default zone, which need not be
            // the one the device is in — and every date this app writes is resolved against
            // the device zone (FR-515).
            .put("timeZone", TimeZone.getDefault().id)

        val created = http.post("$CALENDAR_V3/calendars", body)
        return created.optString("id").takeIf { it.isNotBlank() }
            ?: throw GoogleUnreadable("calendars.insert returned no id")
    }

    override suspend fun setColourAndVisibility(calendarId: String, colorId: String, visible: Boolean) {
        val body = JSONObject()
            .put("colorId", colorId)
            .put("selected", visible)
        // colorRgbFormat is deliberately not set: it exists to make backgroundColor and
        // foregroundColor writable, and using it would oblige us to supply both. Setting
        // colorId alone needs nothing.
        http.patch("$CALENDAR_V3/users/me/calendarList/${encodePath(calendarId)}", body)
    }

    override suspend fun makeVisible(calendarId: String) {
        // `selected` alone. This is one of the user's own calendars (FR-903, AC-09).
        val body = JSONObject().put("selected", true)
        http.patch("$CALENDAR_V3/users/me/calendarList/${encodePath(calendarId)}", body)
    }
}

/** FR-106 — the task list half. */
internal class GoogleTasksApi(private val http: GoogleHttp) : TasksApi {

    override suspend fun listTaskLists(): List<TaskList> {
        // A TaskList carries no "this is the default" flag and the list order is not
        // documented, so the default is asked for by name. FR-108's "no taps" rests on the
        // right list being pre-selected, and matching on the title would break the moment
        // the account is not in English.
        val defaultId = runCatching {
            http.get("$TASKS_V1/users/@me/lists/@default").optString("id").takeIf { it.isNotBlank() }
        }.getOrNull()

        val lists = mutableListOf<TaskList>()
        var pageToken: String? = null
        var pages = 0

        do {
            currentCoroutineContext().ensureActive()
            val url = buildString {
                append("$TASKS_V1/users/@me/lists?maxResults=1000")
                pageToken?.let { append("&pageToken=").append(encodeQuery(it)) }
            }
            val page = http.get(url)
            lists += taskListsFrom(page, defaultId)
            pageToken = nextPageTokenFrom(page)
            pages++
        } while (pageToken != null && pages < MAX_PAGES)

        // If @default could not be read, the first list is the honest guess — better than
        // no pre-selection, which would cost the tap FR-108 is counting.
        return if (lists.none { it.isDefault } && lists.isNotEmpty()) {
            lists.mapIndexed { index, list -> list.copy(isDefault = index == 0) }
        } else {
            lists
        }
    }
}

/**
 * Who is signed in. Standalone rather than a method on [CalendarApi], because the auth client
 * needs it and the auth client is what supplies [CalendarApi]'s token — putting it on the
 * contract would make the two impossible to construct without a knot.
 *
 * The primary calendar's id **is** the account's email address. `AuthorizationClient` returns
 * authorization, not identity, and this is the identity the app can read without a second
 * sign-in library. It uses the `calendar.calendars` scope already held, so FR-002 is unchanged.
 */
suspend fun fetchPrimaryAccount(accessToken: String): GoogleAccount {
    val http = GoogleHttp(FixedToken(accessToken))
    val primary = http.get("$CALENDAR_V3/calendars/primary")
    val email = primary.optString("id").takeIf { it.isNotBlank() }
        ?: throw GoogleUnreadable("calendars/primary returned no id")
    return GoogleAccount(
        id = accountIdFor(email),
        email = email,
        // A primary calendar's summary is the email address again, not a person's name.
        // Leaving this null is honest; nothing renders it.
        displayName = null,
    )
}

private class FixedToken(private val token: String) : TokenProvider {
    override suspend fun accessToken() = token

    // Nothing to invalidate: this token was handed in, and the one call it makes happens
    // immediately after the grant that produced it.
    override suspend fun invalidate(token: String) = Unit
}

// ---------------------------------------------------------------------------------------
// Mapping. Kept separate from the I/O above, and internal rather than private, so the whole
// of it is exercised by JVM tests against captured response bodies — the same arrangement
// as the record format in EncryptedPreferences.kt.
// ---------------------------------------------------------------------------------------

/**
 * The account key. A SHA-256 of the lowercased email rather than the email itself, because
 * [EncryptedAccountDefaultsStore] puts the account id in a preference key **in the clear**
 * and keeps everything with content inside the ciphertext. Storing the address there would
 * quietly reverse that, and it is written down as a decision.
 */
internal fun accountIdFor(email: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(email.lowercase().toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

/** FR-901: owner and writer only. Anything else is a calendar the user cannot write to. */
private val WRITABLE_ROLES = setOf("owner", "writer")

internal fun writableCalendarsFrom(page: JSONObject): List<WritableCalendar> {
    val items = page.optJSONArray("items") ?: return emptyList()
    return (0 until items.length()).mapNotNull { index ->
        val entry = items.optJSONObject(index) ?: return@mapNotNull null

        // showDeleted defaults to false, so this is belt and braces — but a deleted entry
        // offered as a destination would be a write that vanishes.
        if (entry.optBoolean("deleted", false)) return@mapNotNull null

        // FR-901 names owner and writer exactly. Filtered here rather than with the
        // minAccessRole parameter so the code says what the requirement says — the role
        // enum has grown a writerWithoutPrivateAccess that a server-side filter would let in.
        val role = entry.optString("accessRole")
        if (role !in WRITABLE_ROLES) return@mapNotNull null

        val id = entry.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null

        WritableCalendar(
            id = id,
            // A calendar the user renamed in their own list carries summaryOverride, and
            // that is the name they will look for. FR-902's point is that the picker looks
            // like Google.
            summary = entry.optString("summaryOverride").takeIf { it.isNotBlank() }
                ?: entry.optString("summary"),
            // Absent for some entries. The UI already renders an unparseable colour as grey,
            // which is a better answer than inventing a plausible one.
            backgroundColor = entry.optString("backgroundColor"),
            // Booleans are omitted entirely when false, so optBoolean is required — getBoolean
            // would throw on the common case.
            isPrimary = entry.optBoolean("primary", false),
            // FR-903. Note that `selected` (ticked in the UI) and `hidden` (removed from the
            // list) are different properties; this is the one FR-903 names.
            visible = entry.optBoolean("selected", false),
            latchCreated = entry.optString("description").contains(LATCH_CALENDAR_MARKER),
        )
    }
}

internal fun taskListsFrom(page: JSONObject, defaultListId: String?): List<TaskList> {
    val items = page.optJSONArray("items") ?: return emptyList()
    return (0 until items.length()).mapNotNull { index ->
        val entry = items.optJSONObject(index) ?: return@mapNotNull null
        val id = entry.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
        TaskList(
            id = id,
            title = entry.optString("title"),
            isDefault = defaultListId != null && id == defaultListId,
        )
    }
}

internal fun nextPageTokenFrom(page: JSONObject): String? =
    page.optString("nextPageToken").takeIf { it.isNotBlank() }

/**
 * Percent-encoding for a path segment. `URLEncoder` is form-encoding, which turns a space
 * into `+` — harmless for the calendar ids seen here, which are email-shaped, but wrong in a
 * path, so it is corrected rather than relied upon.
 */
private fun encodePath(segment: String): String =
    URLEncoder.encode(segment, "UTF-8").replace("+", "%20")

private fun encodeQuery(value: String): String = URLEncoder.encode(value, "UTF-8")
