package com.latch.data

import java.net.HttpURLConnection
import java.net.URLEncoder
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
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

    override suspend fun setColourAndVisibility(
        calendarId: String,
        colorId: String,
        visible: Boolean,
    ): String? {
        val body = JSONObject()
            .put("colorId", colorId)
            .put("selected", visible)
        // colorRgbFormat is deliberately not set: it exists to make backgroundColor and
        // foregroundColor writable, and using it would oblige us to supply both. Setting
        // colorId alone needs nothing.
        val patched = http.patch("$CALENDAR_V3/users/me/calendarList/${encodePath(calendarId)}", body)
        // The response is a CalendarListEntry, which — unlike the Calendar resource that
        // calendars.insert returns — does carry the colour.
        return patched.optString("backgroundColor").takeIf { it.isNotBlank() }
    }

    override suspend fun makeVisible(calendarId: String) {
        // `selected` alone. This is one of the user's own calendars (FR-903, AC-09).
        val body = JSONObject().put("selected", true)
        http.patch("$CALENDAR_V3/users/me/calendarList/${encodePath(calendarId)}", body)
    }

    override suspend fun insertEvent(calendarId: String, event: EventWrite): String {
        val created = http.post(
            "$CALENDAR_V3/calendars/${encodePath(calendarId)}/events",
            eventRequestBody(event),
        )
        return created.optString("id").takeIf { it.isNotBlank() }
            ?: throw GoogleUnreadable("events.insert returned no id")
    }

    override suspend fun findEventBySourceHash(calendarId: String, sourceHash: String): DuplicateSearch {
        val page = http.get(eventDedupUrl(calendarId, sourceHash))
        // Never capped: Google did the matching, so an empty result means there is none,
        // not that we stopped looking.
        return DuplicateSearch(firstEventId(page))
    }

    override suspend fun deleteEvent(calendarId: String, eventId: String) {
        http.deleteWhateverIsThere(eventDeleteUrl(calendarId, eventId))
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

    override suspend fun insertTask(taskListId: String, task: TaskWrite): String {
        val created = http.post(
            "$TASKS_V1/lists/${encodePath(taskListId)}/tasks",
            taskRequestBody(task),
        )
        return created.optString("id").takeIf { it.isNotBlank() }
            ?: throw GoogleUnreadable("tasks.insert returned no id")
    }

    override suspend fun findTaskBySourceHash(
        taskListId: String,
        sourceHash: String,
        due: LocalDate?,
    ): DuplicateSearch {
        var pageToken: String? = null
        var pages = 0

        do {
            currentCoroutineContext().ensureActive()
            val page = http.get(taskDedupUrl(taskListId, due, pageToken))
            matchingTaskId(page, sourceHash)?.let { return DuplicateSearch(it) }
            pageToken = nextPageTokenFrom(page)
            pages++
        } while (pageToken != null && pages < MAX_DEDUP_PAGES)

        // A token still in hand means there were more tasks than we were willing to read.
        // Reported rather than logged, so a caller cannot mistake it for "no duplicate".
        return DuplicateSearch(existingId = null, scanCapped = pageToken != null)
    }

    override suspend fun deleteTask(taskListId: String, taskId: String) {
        http.deleteWhateverIsThere(taskDeleteUrl(taskListId, taskId))
    }
}

/**
 * A delete whose only obligation is that the item is not there afterwards (FR-807).
 *
 * Shared by both transports because the reasoning is the same on each, and it is the one
 * place the [alreadyGone] rule is applied.
 */
private suspend fun GoogleHttp.deleteWhateverIsThere(url: String) {
    try {
        delete(url)
    } catch (rejected: GoogleRejected) {
        if (!alreadyGone(rejected)) throw rejected
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

// ---------------------------------------------------------------------------------------
// Writing (FR-801, FR-802) and the FR-803 check that precedes it.
// ---------------------------------------------------------------------------------------

/** How many pages of tasks a dedup scan will read before giving up. See [DuplicateSearch]. */
private const val MAX_DEDUP_PAGES = 10

internal fun eventRequestBody(event: EventWrite): JSONObject {
    val body = JSONObject().put("summary", event.summary)

    // Omitted rather than sent empty: FR-805a leaves a notification-sourced item with no
    // description at all, and an empty string is a value where absence is the truth.
    event.description.takeIf { it.isNotBlank() }?.let { body.put("description", it) }
    event.location?.takeIf { it.isNotBlank() }?.let { body.put("location", it) }

    body.put("start", eventTimePoint(event.start, event.allDay, event.timeZone))
    body.put("end", eventTimePoint(event.end, event.allDay, event.timeZone))

    // §7.2. Private, never shared: shared properties are visible to every attendee of the
    // event, and this is the user's own provenance data.
    body.put(
        "extendedProperties",
        JSONObject().put("private", JSONObject(event.metadata.toEventProperties() as Map<*, *>)),
    )
    return body
}

private fun eventTimePoint(at: LocalDateTime, allDay: Boolean, timeZone: String): JSONObject =
    if (allDay) {
        JSONObject().put("date", at.toLocalDate().toString())
    } else {
        // Seconds are written explicitly: ISO_LOCAL_DATE_TIME drops them at zero, and RFC
        // 3339 — which is what the API documents — requires them.
        JSONObject()
            .put("dateTime", at.format(EVENT_DATE_TIME))
            .put("timeZone", timeZone)
    }

private val EVENT_DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

internal fun taskRequestBody(task: TaskWrite): JSONObject {
    val body = JSONObject().put("title", task.title)
    // The metadata line is appended here, not by the caller, so a task cannot be written
    // without it (§7.2).
    body.put("notes", task.metadata.toTaskNotes(task.notes))
    // Google records only the date and discards the time (§9.1), but the field is a
    // timestamp, so midnight UTC is the unambiguous way to say "this day".
    task.due?.let { body.put("due", "${it}T00:00:00.000Z") }
    return body
}

internal fun eventDedupUrl(calendarId: String, sourceHash: String): String =
    "$CALENDAR_V3/calendars/${encodePath(calendarId)}/events" +
        "?privateExtendedProperty=${encodeQuery("$KEY_SOURCE_HASH=$sourceHash")}" +
        "&maxResults=1"

/**
 * `showCompleted` and `showHidden` are on deliberately: a duplicate the user has already
 * ticked off still exists, and FR-803 asks whether the message was saved, not whether it is
 * outstanding. `showDeleted` stays at its default of false, matching events — an item the
 * user deleted must not block them capturing it again.
 */
internal fun taskDedupUrl(taskListId: String, due: LocalDate?, pageToken: String?): String =
    buildString {
        append("$TASKS_V1/lists/${encodePath(taskListId)}/tasks")
        append("?maxResults=100&showCompleted=true&showHidden=true")
        if (due != null) {
            append("&dueMin=").append(encodeQuery("${due.minusDays(1)}T00:00:00.000Z"))
            append("&dueMax=").append(encodeQuery("${due.plusDays(1)}T23:59:59.999Z"))
        }
        pageToken?.let { append("&pageToken=").append(encodeQuery(it)) }
    }

internal fun eventDeleteUrl(calendarId: String, eventId: String): String =
    "$CALENDAR_V3/calendars/${encodePath(calendarId)}/events/${encodePath(eventId)}"

internal fun taskDeleteUrl(taskListId: String, taskId: String): String =
    "$TASKS_V1/lists/${encodePath(taskListId)}/tasks/${encodePath(taskId)}"

/**
 * Whether a rejected delete has nonetheless left the account in the state FR-807 asked for.
 *
 * 404 is an id the API no longer knows; 410 is one deleted since. Both mean the item is not
 * there, which is the whole of what undo promised. Anything else — a lost network, an
 * expired grant, a 403 — leaves the item in place and must reach the user, because the
 * recourse is to go and remove it in Google by hand.
 */
internal fun alreadyGone(rejected: GoogleRejected): Boolean =
    rejected.status == HttpURLConnection.HTTP_NOT_FOUND ||
        rejected.status == HttpURLConnection.HTTP_GONE

internal fun firstEventId(page: JSONObject): String? =
    page.optJSONArray("items")
        ?.optJSONObject(0)
        ?.optString("id")
        ?.takeIf { it.isNotBlank() }

internal fun matchingTaskId(page: JSONObject, sourceHash: String): String? {
    val items = page.optJSONArray("items") ?: return null
    for (index in 0 until items.length()) {
        val entry = items.optJSONObject(index) ?: continue
        // A task whose notes the user has edited into nonsense parses to null and is simply
        // not a match — it must not take out the write.
        val metadata = remoteMetadataFromTaskNotes(entry.optString("notes")) ?: continue
        if (metadata.sourceHash == sourceHash) {
            return entry.optString("id").takeIf { it.isNotBlank() }
        }
    }
    return null
}

/**
 * Percent-encoding for a path segment. `URLEncoder` is form-encoding, which turns a space
 * into `+` — harmless for the calendar ids seen here, which are email-shaped, but wrong in a
 * path, so it is corrected rather than relied upon.
 */
private fun encodePath(segment: String): String =
    URLEncoder.encode(segment, "UTF-8").replace("+", "%20")

private fun encodeQuery(value: String): String = URLEncoder.encode(value, "UTF-8")
