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

/**
 * The date fields of an item, and the **only** fields an FR-804 update may modify (SRS
 * §5.8, v1.16). One type serves two purposes: the new values a `patch` writes, and the
 * previous values FR-807's undo writes back.
 *
 * That it is one type is the point. There is no field here for a title, a description or
 * notes, so an update cannot express a change to them and no caller has to remember not to
 * — the dates-only rule holds structurally, the way FR-105 holds. It matters most on tasks,
 * where §7.2's metadata lives *in* the notes: an update that rewrote them would breach the
 * write-once invariant, which is why the rule is uniform across both transports rather than
 * relaxed on events where the metadata sits elsewhere.
 */
sealed interface ItemDates {
    data class Event(
        val start: LocalDateTime,
        val end: LocalDateTime,
        val allDay: Boolean = false,
        /**
         * Null where Google returned none, which happens when the event simply uses its
         * calendar's default zone. Omitting it on the way back reproduces exactly that, so
         * null is carried rather than filled in with a guess.
         */
        val timeZone: String? = null,
    ) : ItemDates

    data class Task(val due: LocalDate?) : ItemDates
}

/**
 * An existing item that FR-804 may be about to update: what to patch, and what to restore
 * if the user undoes it.
 *
 * [dates] is read at match time rather than at undo time deliberately. The values undo must
 * write back are the ones that were there *before* this app changed them, and after the
 * patch they are gone from the account — the only place they exist is here.
 */
data class RescheduleMatch(
    val remoteId: String,
    val dates: ItemDates,
    /**
     * The matched item's **own** title, as it stands in the account — an event's `summary`,
     * a task's `title`.
     *
     * Read here because FR-804's offer asks about the item that already exists, and the only
     * title the app would otherwise have is the one derived from the capture doing the
     * moving. For a short capture FR-509 makes that the whole text, so the question came out
     * as «"Project sync on Thursday, March 7, 2030 at 21:30" is already saved for Tue 5 Mar»,
     * which reads as a contradiction. It matters most after a "create new": the two items
     * genuinely have different titles from then on, and only this one names the right item.
     *
     * Empty where the item carries no title. The app decides what to show for that; a blank
     * is a fact about the item, not a failure to read it.
     */
    val title: String,
)

/**
 * The result of the FR-804 search by `latch.item_key`.
 *
 * [scanCapped] carries the same warning as [DuplicateSearch.scanCapped] and one more
 * besides. On tasks this search cannot be bounded by date at all — FR-803 bounds its scan to
 * D±1 because a duplicate shares its date, whereas a reschedule differs by definition — so
 * it is a capped scan of the whole list in every case, not only for undated tasks. A capped
 * result with no match therefore means "did not look everywhere" and must never be reported
 * as "not a reschedule"; a capped result *with* a match means [match] is the latest among
 * those read, which is not certainly the latest that exists.
 */
data class RescheduleSearch(val match: RescheduleMatch? = null, val scanCapped: Boolean = false) {
    val found: Boolean get() = match != null
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

    /**
     * `events.delete` (FR-807).
     *
     * **Idempotent by contract**: an event that is already gone is a success, not a failure.
     * The caller asked for it not to be in the account and it is not. Reporting a failure
     * there would tell the user their item survived an undo when it did not, which is the
     * more damaging of the two possible lies.
     */
    suspend fun deleteEvent(calendarId: String, eventId: String)

    /**
     * FR-804, the reschedule search. Exact, for the same reason [findEventBySourceHash] is:
     * `events.list` filtered by `privateExtendedProperty` has Google do the matching.
     *
     * Unlike the FR-803 query this one does not stop at the first hit. Several events can
     * share a key once the user has answered an earlier offer with "create new", and SRS
     * §5.8 says the offer goes to the one with the **latest** start — the position a person
     * means by "the meeting", the others being ones it has already left.
     *
     * Derives the key exactly once, by the current §7.2 derivation. It must not fall back to
     * a superseded derivation to catch pre-v1.14 items: §7.2 declares those unmatched, and
     * querying for an old-style key would quietly restore a wire contract that has moved.
     */
    suspend fun findEventByItemKey(calendarId: String, itemKey: String): RescheduleSearch

    /**
     * `events.patch` (FR-804). Dates only — [ItemDates.Event] cannot express anything else.
     *
     * The request body carries no `extendedProperties`, and that is required rather than
     * incidental: §7.2's metadata is written once at insert and no later operation modifies
     * it, so an updated item keeps the `source_hash` of the capture that created it.
     */
    suspend fun patchEventDates(calendarId: String, eventId: String, dates: ItemDates.Event)
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

    /** `tasks.delete` (FR-807), idempotent in the same way as [CalendarApi.deleteEvent]. */
    suspend fun deleteTask(taskListId: String, taskId: String)

    /**
     * FR-804 for tasks, and the weakest path in the write layer — worth knowing before
     * relying on it.
     *
     * [findTaskBySourceHash] can bound its scan to D±1 because a duplicate parses to the
     * same due date. **That bound does not carry over here**: a reschedule has a different
     * date by definition, which is the whole premise of FR-804, so there is nothing to bound
     * by and this reads the list until it runs out or hits the page cap — for dated and
     * undated tasks alike.
     *
     * A capped result is therefore ordinary rather than exceptional on a large list, and
     * [RescheduleSearch.scanCapped] must not be collapsed into "not a reschedule": the
     * caller falls through to an ordinary create, which is the safe answer, and the user is
     * offered a create where an update was available. The cure is the local index deferred
     * under FR-803, and it should arrive with FR-701's storage.
     */
    suspend fun findTaskByItemKey(taskListId: String, itemKey: String): RescheduleSearch

    /**
     * `tasks.patch` (FR-804). Due date only.
     *
     * Carries no `notes`, and on this transport that is what keeps §7.2's write-once
     * invariant: task metadata lives *in* the notes, so a patch that sent them would rewrite
     * the item's `source_hash` and `item_key` as a side effect of moving its date.
     */
    suspend fun patchTaskDates(taskListId: String, taskId: String, dates: ItemDates.Task)
}
