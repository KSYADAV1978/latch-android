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

interface CalendarApi {
    /** `calendarList.list`, filtered to accessRole owner or writer (FR-901). */
    suspend fun listWritableCalendars(): List<WritableCalendar>

    /**
     * `calendars.insert` (FR-105). The one call in setup that creates anything in the
     * user's Google account. It must not be reachable from entry to step 2 — only from
     * the completion of setup. The caller carries that guarantee; this contract only
     * names it so the obligation travels with the method.
     */
    suspend fun createLatchCalendar(summary: String, description: String): WritableCalendar

    /**
     * `calendarList.patch`. Used at commit to give the Latch calendar its colour — the
     * "one colour" FR-104 promises — and to ensure it is ticked, per FR-903.
     */
    suspend fun setColourAndVisibility(calendarId: String, colorId: String, visible: Boolean)
}

interface TasksApi {
    /** `tasklists.list` (FR-106). */
    suspend fun listTaskLists(): List<TaskList>
}
