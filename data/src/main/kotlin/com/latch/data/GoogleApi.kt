package com.latch.data

import java.time.LocalDate
import java.time.LocalDateTime

/**
 * The Google surface that first-run setup (FR-101 to FR-110) and the write path (FR-800
 * series) need. Contracts only: no implementation, no HTTP client, no dependency.
 *
 * Whether the implementation is hand-written REST or a Google client library is an open
 * NFR-501 decision recorded in docs/DEPENDENCIES.md. Setup is built against these
 * interfaces so that the screens, the state machine and the FR-105 guarantee can all be
 * finished — and tested on the JVM — before that decision is made.
 */

/** FR-110: defaults are stored per account, so the account identifies them. */
data class GoogleAccount(
    val id: String,
    val email: String,
    val displayName: String?,
)

/**
 * FR-901: only calendars the user can actually write to. Read-only entries (Holidays,
 * Birthdays, subscribed calendars) are filtered out by the implementation, not here, so a
 * caller cannot be handed a destination it may not use.
 */
data class WritableCalendar(
    val id: String,
    val summary: String,
    /** FR-902: shown in the picker so the calendar looks the same here as in Google. */
    val backgroundColor: String,
    val isPrimary: Boolean,
    /**
     * FR-903: the calendar's `selected` property. Writing to an unticked calendar produces
     * an item the user cannot see, which is indistinguishable from a failed write.
     */
    val visible: Boolean,
    /**
     * True where this calendar carries [LATCH_CALENDAR_MARKER]. §4.1 has three clients
     * writing to one account, so the Latch calendar may already exist because another
     * client created it. Setup adopts it rather than creating a second one.
     */
    val latchCreated: Boolean = false,
)

data class TaskList(
    val id: String,
    val title: String,
    /** Google always provides one list; it is pre-selected so FR-108 costs no taps. */
    val isDefault: Boolean,
)

/**
 * Calendars carry no `extendedProperties`, so the Latch calendar cannot be tagged the way
 * FR-802 tags events. The marker goes in the calendar description instead. Matching on the
 * summary alone would adopt any calendar a user happened to name "Latch".
 */
const val LATCH_CALENDAR_MARKER: String = "latch.calendar.v1"

/**
 * An event to create (FR-801). Wire-shaped, like [WritableCalendar] and [TaskList], rather
 * than `:core-model`'s `Item`, which carries local-only concerns — `syncState`, `captureId` —
 * that have no business crossing to Google.
 */
data class EventWrite(
    val summary: String,
    /**
     * The FR-805 body, composed by [sourceBlock] so that FR-805a cannot be forgotten. The
     * §7.2 metadata is **not** part of this; the implementation attaches it separately.
     */
    val description: String,
    val location: String? = null,
    val start: LocalDateTime,
    /**
     * **For an all-day event Google treats the end date as exclusive** — a single-day event
     * on 5 September ends on the 6th. This field is passed through unchanged, so that
     * arithmetic belongs to the caller; guessing at it here would silently move dates.
     */
    val end: LocalDateTime,
    val allDay: Boolean = false,
    /** An IANA zone id. `Item.start` is a `LocalDateTime` and needs one to resolve. */
    val timeZone: String,
    val metadata: RemoteMetadata,
)

/** A task to create (FR-801). Tasks record a due **date** only — §9.1, and the API discards any time. */
data class TaskWrite(
    val title: String,
    /** The FR-805 body. The `[latch]` metadata line is appended by the implementation. */
    val notes: String,
    val due: LocalDate? = null,
    val metadata: RemoteMetadata,
)

/**
 * The result of the FR-803 check that must precede every write.
 *
 * [scanCapped] exists because the two transports are not equally capable. Events are filtered
 * server-side by `privateExtendedProperty`, so the answer is exact. Tasks have no content
 * filter at all, so the search is a bounded scan and can give up — and when it does, the
 * caller is told rather than being handed a "no duplicate" that merely means "did not look
 * far enough".
 */
data class DuplicateSearch(val existingId: String?, val scanCapped: Boolean = false) {
    val found: Boolean get() = existingId != null
}

interface AuthClient {
    /** FR-002: requests the four scopes and no others. */
    suspend fun signIn(): GoogleAccount

    /** NFR-205: one action revokes access; setup uses it for nothing else. */
    suspend fun signOut(accountId: String)
}

/**
 * The user dismissed the consent screen. A distinct type because it is not a failure and
 * must not be reported as one — `SetupFailure.SIGN_IN_CANCELLED` has always had its own
 * wording, and until there was a real consent screen nothing could produce it.
 *
 * Deliberately not a `CancellationException`. `SetupCoordinator` rethrows those to let
 * structured concurrency work, so signalling a dismissal that way would kill the coroutine
 * without dispatching any event, and leave step 1 spinning on a screen that has no Retry
 * and no Back.
 */
class SignInCancelledException : Exception("Sign-in was cancelled by the user")

interface CalendarApi {
    /** `calendarList.list`, filtered to accessRole owner or writer (FR-901). */
    suspend fun listWritableCalendars(): List<WritableCalendar>

    /**
     * `calendars.insert` (FR-105). The one call in setup that creates anything in the
     * user's Google account. It must not be reachable from entry to step 2 — only from
     * the completion of setup. The caller carries that guarantee; this contract only
     * names it so the obligation travels with the method.
     *
     * Returns the new calendar's id, not a [WritableCalendar], because `calendars.insert`
     * answers with a **Calendar** resource — id, summary, description, location, timeZone.
     * `backgroundColor`, `selected`, `primary` and `accessRole` belong to a
     * **CalendarListEntry**, which is a different resource. Returning a `WritableCalendar`
     * here would mean inventing three of its five fields, and FR-902 exists precisely so
     * that the colour shown is the real one.
     */
    suspend fun createLatchCalendar(summary: String, description: String): String

    /**
     * `calendarList.patch`. Used at commit to give the Latch calendar its colour — the
     * "one colour" FR-104 promises — and to ensure it is ticked, per FR-903.
     *
     * Returns the resulting `backgroundColor`, because a calendar Latch has just created is
     * the one destination whose colour nothing else knows: `calendars.insert` answers with a
     * Calendar resource, which has no colour, and the colour is only decided by this call.
     * Null where the response did not carry one — the caller stores it for FR-904's chip and
     * an absent colour renders grey rather than failing.
     */
    suspend fun setColourAndVisibility(calendarId: String, colorId: String, visible: Boolean): String?

    /**
     * `calendarList.patch`, visibility only (FR-903, AC-09). Separate from
     * [setColourAndVisibility] because this one is applied to a calendar the **user already
     * owns and uses**: taking the opportunity to recolour it would be an edit they did not
     * ask for. Only the tick changes.
     */
    suspend fun makeVisible(calendarId: String)

    /** `events.insert` (FR-801, FR-802). Returns the created event's id. */
    suspend fun insertEvent(calendarId: String, event: EventWrite): String

    /**
     * FR-803, the check that must run before [insertEvent].
     *
     * `events.list` filtered by `privateExtendedProperty`, so Google does the matching and
     * the answer is exact — one request regardless of how many events the calendar holds.
     * Deleted events are excluded by the API's own default, which is what we want: an event
     * the user undid under FR-807 must not block them capturing it again.
     */
    suspend fun findEventBySourceHash(calendarId: String, sourceHash: String): DuplicateSearch
}

interface TasksApi {
    /** `tasklists.list` (FR-106). */
    suspend fun listTaskLists(): List<TaskList>

    /** `tasks.insert` (FR-801, FR-802). Returns the created task's id. */
    suspend fun insertTask(taskListId: String, task: TaskWrite): String

    /**
     * FR-803 for tasks, which is a scan rather than a query.
     *
     * The Tasks API has **no filter on content at all** — only due, completion and update
     * dates — and metadata lives in free-text notes, so there is nothing for Google to match
     * on. [due] bounds the scan: a duplicate of the same source text parses to the same due
     * date, and the window is widened by a day either side purely to absorb time-zone
     * boundary differences between two devices, which is where AC-07 would otherwise fail
     * silently. An undated task has nothing to bound by and falls back to a capped scan, so
     * the result may come back with `scanCapped` set.
     */
    suspend fun findTaskBySourceHash(
        taskListId: String,
        sourceHash: String,
        due: LocalDate?,
    ): DuplicateSearch
}
