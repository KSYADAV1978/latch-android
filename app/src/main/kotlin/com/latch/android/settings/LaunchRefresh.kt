package com.latch.android.settings

import com.latch.data.AccountDefaults
import com.latch.data.WritableCalendar

/**
 * FR-908: "The calendar list shall be refreshed on launch. If a stored destination is missing or
 * has lost write access, the app shall fall back to the primary calendar and inform the user."
 *
 * The decision is pure and lives here; the network call and the storage are the caller's. That
 * split is what makes the interesting cases testable at all — the ones that matter are a
 * calendar that has gone, a calendar that is still there but read-only, and a list that could
 * not be read, and only the last of those is easy to arrange on a device.
 */
sealed interface DestinationCheck {
    /** The stored destination is present and writable. Nothing to do and nothing to say. */
    data object Unchanged : DestinationCheck

    /**
     * The stored name or colour is stale — the user renamed or recoloured the calendar in
     * Google. Corrected silently: FR-904's chip is meant to look like Google, the destination
     * has not moved, and telling someone their own rename went through is noise.
     */
    data class Refreshed(val defaults: AccountDefaults) : DestinationCheck

    /**
     * FR-908's own case. The destination is gone or no longer writable, so captures now go to
     * the primary calendar — and the user is **told**, because their captures are about to
     * start landing somewhere they did not choose.
     */
    data class FellBack(val defaults: AccountDefaults, val previousName: String) : DestinationCheck

    /**
     * The list could not be read, or holds no writable calendar to fall back to.
     *
     * **Nothing changes**, and that is the load-bearing part. `listWritableCalendars` fails when
     * there is no network far more often than when a calendar has been deleted, and falling back
     * to primary because the phone was on a train would move a user's captures for a reason that
     * has nothing to do with their calendars — and would then need a second launch, online, to
     * discover it had been wrong. A stored destination is presumed good until the list says
     * otherwise.
     */
    data object Indeterminate : DestinationCheck
}

/**
 * @param calendars what `calendarList.list` returned, already filtered to `owner` or `writer`
 *   by FR-901 — so a calendar that has lost write access is simply **absent** from it, and
 *   "missing" and "lost write access" are the same test rather than two.
 */
fun checkStoredDestination(
    stored: AccountDefaults,
    calendars: List<WritableCalendar>,
): DestinationCheck {
    if (calendars.isEmpty()) return DestinationCheck.Indeterminate

    val current = calendars.firstOrNull { it.id == stored.destinationCalendarId }
    if (current != null) {
        val moved = current.summary != stored.destinationCalendarName ||
            current.backgroundColor != stored.destinationCalendarColour
        return if (!moved) {
            DestinationCheck.Unchanged
        } else {
            DestinationCheck.Refreshed(
                stored.copy(
                    destinationCalendarName = current.summary,
                    destinationCalendarColour = current.backgroundColor,
                )
            )
        }
    }

    // FR-908 names the primary. Where the account somehow has no primary among its writable
    // calendars, the first writable one is the honest second choice — the alternative is
    // leaving the destination pointing at something that is not there.
    val primary = calendars.firstOrNull { it.isPrimary } ?: calendars.first()
    return DestinationCheck.FellBack(
        defaults = stored.copy(
            destinationCalendarId = primary.id,
            destinationCalendarName = primary.summary,
            destinationCalendarColour = primary.backgroundColor,
        ),
        previousName = stored.destinationCalendarName,
    )
}
