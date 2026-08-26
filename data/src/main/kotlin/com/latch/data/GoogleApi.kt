package com.latch.data

/**
 * The Google surface that first-run setup (FR-101 to FR-110) and, later, the write path
 * (FR-800 series) need. Contracts only: no implementation, no HTTP client, no dependency.
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
     */
    suspend fun setColourAndVisibility(calendarId: String, colorId: String, visible: Boolean)

    /**
     * `calendarList.patch`, visibility only (FR-903, AC-09). Separate from
     * [setColourAndVisibility] because this one is applied to a calendar the **user already
     * owns and uses**: taking the opportunity to recolour it would be an edit they did not
     * ask for. Only the tick changes.
     */
    suspend fun makeVisible(calendarId: String)
}

interface TasksApi {
    /** `tasklists.list` (FR-106). */
    suspend fun listTaskLists(): List<TaskList>
}
