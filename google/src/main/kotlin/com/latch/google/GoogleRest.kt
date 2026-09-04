package com.latch.google

import com.latch.wire.KEY_ITEM_KEY
import com.latch.wire.KEY_SOURCE_HASH
import com.latch.wire.remoteMetadataFromTaskNotes
import com.latch.wire.toEventProperties
import com.latch.wire.toTaskNotes
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.TimeZone
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import com.latch.google.json.JSONArray
import com.latch.google.json.JSONObject

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

    /**
     * FR-803 for events. **Follows `nextPageToken` to exhaustion**, and that is the whole of
     * the requirement rather than an optimisation.
     *
     * This asked for a single page of one event until 1 Sep 2026, on the reading that Google
     * had done the matching so one hit was all that could be wanted. That reading was wrong
     * and it wrote duplicates into a real calendar. A filtered `events.list` applies
     * `privateExtendedProperty` to **a page of the scan** rather than using it to select the
     * page, so `maxResults=1` asks Google to take one arbitrary event and test it — which
     * misses whenever the calendar holds more than about one thing, and says so honestly by
     * returning an empty page **with a `nextPageToken`**. Measured on a device: the same query
     * at `maxResults=1` gave `items=0, nextPageToken=YES`, and at 250 gave `items=2`.
     *
     * It is also why AC-07 passed on 27 Aug and this was not caught: the calendar then held
     * about one event, so the single-event page was the whole calendar.
     *
     * **The fix is the loop, not the number.** Raising 1 to 250 would have made this
     * particular calendar work and left correctness a function of how much the user has in
     * theirs. Reaching the cap is therefore reported the way the task scan reports it — as
     * having stopped looking, never as having found nothing.
     */
    override suspend fun findEventBySourceHash(calendarId: String, sourceHash: String): DuplicateSearch =
        findEventPaged({ token -> eventDedupUrl(calendarId, sourceHash, token) }, get = http::get)

    override suspend fun deleteEvent(calendarId: String, eventId: String) {
        http.deleteWhateverIsThere(eventItemUrl(calendarId, eventId))
    }

    override suspend fun findEventByItemKey(calendarId: String, itemKey: String): RescheduleSearch {
        var pageToken: String? = null
        var pages = 0
        var latest: RescheduleMatch? = null

        do {
            currentCoroutineContext().ensureActive()
            val page = http.get(eventItemKeyUrl(calendarId, itemKey, pageToken))
            latest = latestEventMatch(latest, eventMatchesFrom(page))
            pageToken = nextPageTokenFrom(page)
            pages++
        } while (pageToken != null && pages < MAX_DEDUP_PAGES)

        // Google matched the key, so an empty result is genuinely none. The cap can still be
        // reached by a user who has answered "create new" a great many times, and then the
        // latest of what was read may not be the latest that exists — hence still reported.
        return RescheduleSearch(latest, scanCapped = pageToken != null)
    }

    override suspend fun patchEventDates(
        calendarId: String,
        eventId: String,
        dates: ItemDates.Event,
        description: String?,
    ) {
        http.patch(eventItemUrl(calendarId, eventId), eventDatesBody(dates, description))
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
        http.deleteWhateverIsThere(taskItemUrl(taskListId, taskId))
    }

    override suspend fun findTaskByItemKey(taskListId: String, itemKey: String): RescheduleSearch {
        var pageToken: String? = null
        var pages = 0
        var latest: RescheduleMatch? = null

        do {
            currentCoroutineContext().ensureActive()
            // No due bound, and that is the point: FR-803's D±1 window rests on a duplicate
            // sharing its date, and a reschedule differs by definition. There is nothing to
            // bound by, so this is the whole list every time.
            val page = http.get(taskItemKeyUrl(taskListId, pageToken))
            latest = latestTaskMatch(latest, taskMatchesFrom(page, itemKey))
            pageToken = nextPageTokenFrom(page)
            pages++
        } while (pageToken != null && pages < MAX_DEDUP_PAGES)

        return RescheduleSearch(latest, scanCapped = pageToken != null)
    }

    override suspend fun patchTaskDates(
        taskListId: String,
        taskId: String,
        dates: ItemDates.Task,
        notes: String?,
    ) {
        http.patch(taskItemUrl(taskListId, taskId), taskDatesBody(dates, notes))
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

/**
 * Walks a filtered `events.list` until it finds a match or runs out of pages.
 *
 * Takes the URL builder and the GET so it can be tested without a socket — the lesson from
 * the drain's own untested duplicate check, applied one layer down: what is unreachable does
 * not get tested, and this loop is the one that wrote duplicates into a real calendar.
 *
 * A page that is empty but carries a `nextPageToken` is the normal case here, not an oddity,
 * which is exactly what the old single-page read got wrong.
 */
suspend fun findEventPaged(
    urlFor: (String?) -> String,
    maxPages: Int = MAX_DEDUP_PAGES,
    get: suspend (String) -> JSONObject,
): DuplicateSearch {
    var pageToken: String? = null
    var pages = 0

    do {
        currentCoroutineContext().ensureActive()
        val page = get(urlFor(pageToken))
        firstEventId(page)?.let { return DuplicateSearch(it) }
        pageToken = nextPageTokenFrom(page)
        pages++
    } while (pageToken != null && pages < maxPages)

    // Reaching the cap means we stopped looking, which must never be read as "no duplicate" —
    // the rule scanCapped already carries for the task scan.
    return DuplicateSearch(existingId = null, scanCapped = pageToken != null)
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

    // FR-601: a recipe step's reminders. Omitted entirely where there are none, which leaves
    // `useDefault` true and the calendar's own reminders in force — the behaviour of every item
    // written before recipes existed. Sending an empty override list instead would mean "no
    // reminders at all", which is a decision this app has never taken for the user.
    if (event.reminderMinutes.isNotEmpty()) {
        val overrides = JSONArray()
        event.reminderMinutes.forEach {
            overrides.put(JSONObject().put("method", "popup").put("minutes", it))
        }
        body.put(
            "reminders",
            JSONObject().put("useDefault", false).put("overrides", overrides),
        )
    }

    // §7.2. Private, never shared: shared properties are visible to every attendee of the
    // event, and this is the user's own provenance data.
    body.put(
        "extendedProperties",
        JSONObject().put("private", JSONObject(event.metadata.toEventProperties() as Map<*, *>)),
    )
    return body
}

private fun eventTimePoint(at: LocalDateTime, allDay: Boolean, timeZone: String?): JSONObject =
    if (allDay) {
        JSONObject().put("date", at.toLocalDate().toString())
    } else {
        // Seconds are written explicitly: ISO_LOCAL_DATE_TIME drops them at zero, and RFC
        // 3339 — which is what the API documents — requires them.
        JSONObject()
            .put("dateTime", at.format(EVENT_DATE_TIME))
            // Omitted where absent rather than guessed at. An event that came back with no
            // zone was using its calendar's default, and leaving it out puts it back on the
            // same footing (FR-804's restore).
            .also { point -> timeZone?.let { point.put("timeZone", it) } }
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

/**
 * Public rather than internal only so the debug-build FR-803 probe can log the exact URL it
 * is about to issue. It is a pure string builder over two arguments and holds no state; the
 * query itself stays behind [CalendarApi]. See `DebugDedupProbe` in `:app`'s debug source set,
 * and the open drain defect it exists to diagnose.
 */
fun eventDedupUrl(calendarId: String, sourceHash: String, pageToken: String? = null): String =
    buildString {
        append("$CALENDAR_V3/calendars/${encodePath(calendarId)}/events")
        append("?privateExtendedProperty=").append(encodeQuery("$KEY_SOURCE_HASH=$sourceHash"))
        // 250, not 1. See findEventBySourceHash: a filtered events.list applies the filter to
        // a page of the scan rather than using it to choose the page, so a one-event page is
        // one arbitrary event tested against the filter. The page size is a throughput
        // choice; the token loop is what makes the answer correct.
        append("&maxResults=250")
        pageToken?.let { append("&pageToken=").append(encodeQuery(it)) }
    }

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

internal fun eventItemUrl(calendarId: String, eventId: String): String =
    "$CALENDAR_V3/calendars/${encodePath(calendarId)}/events/${encodePath(eventId)}"

internal fun taskItemUrl(taskListId: String, taskId: String): String =
    "$TASKS_V1/lists/${encodePath(taskListId)}/tasks/${encodePath(taskId)}"

// ---------------------------------------------------------------------------------------
// FR-804: finding the item a capture reschedules, and moving it.
// ---------------------------------------------------------------------------------------

/**
 * Deliberately **not** `maxResults=1`, which is what the FR-803 query uses.
 *
 * FR-803 asks a yes/no question and can stop at the first hit. FR-804 has to see every event
 * carrying the key, because SRS §5.8 gives the offer to the one with the latest start and
 * "the first Google happened to return" is not that.
 */
internal fun eventItemKeyUrl(calendarId: String, itemKey: String, pageToken: String?): String =
    buildString {
        append("$CALENDAR_V3/calendars/${encodePath(calendarId)}/events")
        append("?privateExtendedProperty=").append(encodeQuery("$KEY_ITEM_KEY=$itemKey"))
        append("&maxResults=250")
        pageToken?.let { append("&pageToken=").append(encodeQuery(it)) }
    }

/**
 * No `dueMin`/`dueMax`, and their absence is the whole difference from [taskDedupUrl].
 *
 * That window works for FR-803 because a duplicate parses to the same due date. A reschedule
 * is a *different* date by definition, so bounding by the new date would search precisely
 * where the item being looked for is not. `showCompleted` and `showHidden` stay on for the
 * same reason as the dedup scan: a ticked-off item still exists and can still be moved.
 */
internal fun taskItemKeyUrl(taskListId: String, pageToken: String?): String =
    buildString {
        append("$TASKS_V1/lists/${encodePath(taskListId)}/tasks")
        append("?maxResults=100&showCompleted=true&showHidden=true")
        pageToken?.let { append("&pageToken=").append(encodeQuery(it)) }
    }

/**
 * The `events.patch` body. Start and end and nothing else — no `summary`, no `description`
 * and above all no `extendedProperties`, so §7.2's metadata survives the move untouched.
 */
internal fun eventDatesBody(dates: ItemDates.Event, description: String? = null): JSONObject =
    JSONObject()
        .put("start", eventTimePoint(dates.start, dates.allDay, dates.timeZone))
        .put("end", eventTimePoint(dates.end, dates.allDay, dates.timeZone))
        // `put(name, null)` removes the key, which is exactly right here: an absent
        // `description` in a patch means "leave as it is", and that is what a caller passing
        // null is asking for.
        .put("description", description)

/**
 * The `tasks.patch` body.
 *
 * **`notes` is sent only when a caller supplies one, and only ever a value composed by
 * `bodyWithMoveNote`.** This used to send `due` alone, and the reason recorded here was that
 * "sending `notes` would rewrite §7.2's metadata, which on this transport lives inside them" —
 * true of a *rewrite*, and the reason FR-804's move note appends above the `[latch]` line
 * instead of replacing anything. Null still means "leave them untouched", which is what every
 * caller but the move note passes.
 *
 * A null due is written as an explicit JSON null rather than omitted, because omission in a
 * patch means "leave as it is" and this has to be able to say "clear it". A null *notes* means
 * the opposite — omit — and the two are deliberately not spelled the same way.
 */
internal fun taskDatesBody(dates: ItemDates.Task, notes: String? = null): JSONObject =
    JSONObject()
        .put(
            "due",
            dates.due?.let { "${it}T00:00:00.000Z" } ?: JSONObject.NULL,
        )
        .put("notes", notes)

internal fun eventMatchesFrom(page: JSONObject): List<RescheduleMatch> {
    val items = page.optJSONArray("items") ?: return emptyList()
    val matches = mutableListOf<RescheduleMatch>()
    for (index in 0 until items.length()) {
        val entry = items.optJSONObject(index) ?: continue
        val id = entry.optString("id").takeIf { it.isNotBlank() } ?: continue
        val dates = eventDatesFrom(entry) ?: continue
        matches += RescheduleMatch(id, dates, entry.optString("summary"), entry.optString("description"))
    }
    return matches
}

/**
 * An event whose dates cannot be read is skipped rather than treated as a match with unknown
 * dates. Undo restores what is captured here, so a match with no readable prior state would
 * be an update that could not be undone.
 */
internal fun eventDatesFrom(event: JSONObject): ItemDates.Event? {
    // A recurring event is refused outright, and this is a rule rather than a side effect of
    // parsing: its start and end parse perfectly well, so nothing else here would have
    // stopped it. `events.list` is not asked for `singleEvents`, so a series comes back as
    // its master, and `events.patch` on a master moves **every** occurrence. FR-804 offers to
    // move one meeting; silently moving a weekly stand-up for the rest of time is not a
    // larger version of that, it is a different act. `recurringEventId` covers the instance
    // form as well, so a change to that query cannot quietly reopen this.
    if ((event.optJSONArray("recurrence")?.length() ?: 0) > 0) return null
    if (event.optString("recurringEventId").isNotBlank()) return null

    val start = event.optJSONObject("start") ?: return null
    val end = event.optJSONObject("end") ?: return null
    val allDay = !start.optString("date").isNullOrBlank()
    return ItemDates.Event(
        start = eventTimePointOf(start) ?: return null,
        end = eventTimePointOf(end) ?: return null,
        allDay = allDay,
        timeZone = start.optString("timeZone").takeIf { it.isNotBlank() },
    )
}

private fun eventTimePointOf(point: JSONObject): LocalDateTime? {
    point.optString("date").takeIf { it.isNotBlank() }?.let { date ->
        return runCatching { LocalDate.parse(date).atStartOfDay() }.getOrNull()
    }
    point.optString("dateTime").takeIf { it.isNotBlank() }?.let { at ->
        // Google answers with an offset, which is a moment; the local wall time is what an
        // event's fields hold, and the zone travels separately.
        return runCatching { OffsetDateTime.parse(at).toLocalDateTime() }.getOrNull()
    }
    return null
}

internal fun taskMatchesFrom(page: JSONObject, itemKey: String): List<RescheduleMatch> {
    val items = page.optJSONArray("items") ?: return emptyList()
    val matches = mutableListOf<RescheduleMatch>()
    for (index in 0 until items.length()) {
        val entry = items.optJSONObject(index) ?: continue
        // As in the dedup scan: notes the user has edited into nonsense parse to null and
        // are simply not a match.
        val metadata = remoteMetadataFromTaskNotes(entry.optString("notes")) ?: continue
        if (metadata.itemKey != itemKey) continue
        val id = entry.optString("id").takeIf { it.isNotBlank() } ?: continue
        matches += RescheduleMatch(
            id,
            ItemDates.Task(taskDueFrom(entry)),
            entry.optString("title"),
            entry.optString("notes"),
        )
    }
    return matches
}

internal fun taskDueFrom(task: JSONObject): LocalDate? =
    task.optString("due").takeIf { it.isNotBlank() }
        ?.let { due -> runCatching { OffsetDateTime.parse(due).toLocalDate() }.getOrNull() }

/**
 * SRS §5.8's tie-break: where several items carry one key, the offer goes to the latest.
 *
 * The others are positions the meeting has already left, and picking among them by whatever
 * order the API returned would make the offer differ between the two transports for no
 * reason a user could see.
 */
fun latestEventMatch(
    current: RescheduleMatch?,
    candidates: List<RescheduleMatch>,
): RescheduleMatch? {
    var best = current
    for (candidate in candidates) {
        val at = (candidate.dates as? ItemDates.Event)?.start ?: continue
        val bestAt = (best?.dates as? ItemDates.Event)?.start
        if (bestAt == null || at.isAfter(bestAt)) best = candidate
    }
    return best
}

/** As [latestEventMatch]. An undated task never outranks a dated one: it has no position. */
internal fun latestTaskMatch(
    current: RescheduleMatch?,
    candidates: List<RescheduleMatch>,
): RescheduleMatch? {
    var best = current
    for (candidate in candidates) {
        val due = (candidate.dates as? ItemDates.Task)?.due
        val bestDue = (best?.dates as? ItemDates.Task)?.due
        when {
            best == null -> best = candidate
            due == null -> Unit
            bestDue == null || due.isAfter(bestDue) -> best = candidate
        }
    }
    return best
}

/**
 * Whether a rejected delete has nonetheless left the account in the state FR-807 asked for.
 *
 * 404 is an id the API no longer knows; 410 is one deleted since. Both mean the item is not
 * there, which is the whole of what undo promised. Anything else — a lost network, an
 * expired grant, a 403 — leaves the item in place and must reach the user, because the
 * recourse is to go and remove it in Google by hand.
 */
fun alreadyGone(rejected: GoogleRejected): Boolean =
    rejected.status == HttpURLConnection.HTTP_NOT_FOUND ||
        rejected.status == HttpURLConnection.HTTP_GONE

/**
 * Whether FR-806 should try this write again, or stop and tell the user (NFR-303).
 *
 * The distinction matters because the two wrong answers fail in opposite directions. Giving
 * up on a recoverable failure loses the capture, which is what NFR-302 forbids. Retrying an
 * unrecoverable one burns the battery on a request that will never succeed, and leaves the
 * queue count sitting on the home screen with nothing the user can do about it — a 403 for
 * an insufficient scope needs them to sign in again, and no amount of backoff supplies that.
 *
 * [GoogleUnreachable] is the offline case and always retries. [GoogleUnreadable] is a 2xx we
 * could not parse: the write may well have happened, so retrying risks a duplicate — but
 * FR-803 runs again before each insert, which is what makes that safe.
 *
 * Public because both callers are in `:app` — the saver, deciding whether a failed write
 * becomes a queue entry or an error, and the worker, deciding whether to come back.
 */
fun isWorthRetrying(failure: Throwable): Boolean = when (failure) {
    is GoogleUnreachable -> true
    is GoogleUnreadable -> true
    // FR-806a: a sign-in fixes this, so the entry waits rather than being given up on. It is
    // the one case where an entry waits on the user rather than on the network.
    is SignInRequiredException -> true
    is GoogleRejected -> failure.status == 429 || failure.status in 500..599
    // Not one of ours — a bug in the mapping rather than an answer from Google. Retrying a
    // bug just repeats it.
    else -> false
}

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

// ---------------------------------------------------------------------------------------
// Diagnostics for the open FR-803 defect (CLAUDE.md, SRS 1.37). Not part of any requirement.
// ---------------------------------------------------------------------------------------

/** One page of a `privateExtendedProperty` query, as the wire returned it. */
data class DebugPage(
    val url: String,
    val status: Int?,
    val itemCount: Int?,
    val nextPageToken: String?,
    val error: String? = null,
)

/**
 * Issues one `events.list` filtered by a private extended property and reports the raw shape
 * of the answer — including whether it carries a `nextPageToken`, which is the fact the
 * paging hypothesis turns on.
 *
 * Exists because `GoogleHttp` is internal to this module and the probe that needs this lives
 * in `:app`'s debug source set. It builds the URL in the same shape as [eventDedupUrl] and
 * [eventItemKeyUrl] but with `maxResults` and the key as parameters, which is the whole point:
 * the two production builders differ in exactly that number, and this is how we find out
 * whether that is what makes one match and the other not.
 */
suspend fun debugProbeProperty(
    tokens: TokenProvider,
    calendarId: String,
    key: String,
    value: String,
    maxResults: Int,
): DebugPage {
    val url = "$CALENDAR_V3/calendars/${encodePath(calendarId)}/events" +
        "?privateExtendedProperty=${encodeQuery("$key=$value")}" +
        "&maxResults=$maxResults"
    return try {
        val page = GoogleHttp(tokens).get(url)
        DebugPage(
            url = url,
            status = 200,
            itemCount = page.optJSONArray("items")?.length() ?: 0,
            nextPageToken = nextPageTokenFrom(page),
        )
    } catch (rejected: GoogleRejected) {
        DebugPage(url, rejected.status, null, null, rejected.message)
    } catch (failure: Exception) {
        DebugPage(url, null, null, null, "${failure::class.simpleName}: ${failure.message}")
    }
}

/**
 * Which [FailureClass] a failed write belongs to, for FR-806's immediate drain.
 *
 * The sibling of [isWorthRetrying] and deliberately a separate function rather than a field on
 * it: whether to retry at all and whether *connectivity returning is news* are two different
 * questions, and collapsing them would make a 429 look like a lost socket. A 429 is retryable
 * and must not be retried the instant the network comes back — the server has just said it is
 * busy, and hammering it is the failure the rate limit exists to describe.
 *
 * Anything unrecognised is [FailureClass.PERMANENT], which is the same answer [isWorthRetrying]
 * gives it and for the same reason: it is a bug in our own mapping rather than an answer from
 * Google, and repeating it repeats the bug.
 */
fun failureClassOf(failure: Throwable): FailureClass = when (failure) {
    is GoogleUnreachable -> FailureClass.TRANSPORT
    // A 2xx we could not parse. The socket worked, so this is not the transport; the write may
    // even have happened, which is why FR-803 runs again before each insert.
    is GoogleUnreadable -> FailureClass.SERVER
    is SignInRequiredException -> FailureClass.SIGN_IN
    is GoogleRejected ->
        if (failure.status == 429 || failure.status in 500..599) FailureClass.SERVER
        else FailureClass.PERMANENT

    else -> FailureClass.PERMANENT
}
