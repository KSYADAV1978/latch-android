package com.latch.android.settings

import com.latch.core.model.RoutingMode
import com.latch.data.AccountDefaults
import com.latch.google.WritableCalendar
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * FR-908: the calendar list, refreshed on launch.
 *
 * The interesting cases are all ones a device pass would find hard to arrange — a calendar that
 * has been deleted, one that has lost write access, a list that could not be read — which is
 * exactly why the decision is a pure function and this exists.
 */
class LaunchRefreshTest {

    private val stored = AccountDefaults(
        accountId = "acct",
        email = "you@example.com",
        routingMode = RoutingMode.LATCH_CALENDAR,
        destinationCalendarId = "latch-cal",
        destinationCalendarName = "Latch",
        destinationCalendarColour = "#d50000",
        taskListId = "list-1",
    )

    private fun calendar(
        id: String,
        summary: String = id,
        colour: String = "#d50000",
        primary: Boolean = false,
    ) = WritableCalendar(
        id = id,
        summary = summary,
        backgroundColor = colour,
        isPrimary = primary,
        visible = true,
    )

    @Test
    fun `a destination that is still there and unchanged is left alone`() {
        val check = checkStoredDestination(
            stored,
            listOf(calendar("primary", primary = true), calendar("latch-cal", "Latch")),
        )
        assertEquals(DestinationCheck.Unchanged, check)
    }

    @Test
    fun `a renamed or recoloured destination is corrected silently`() {
        // The destination has not moved. Telling someone their own rename went through is
        // noise, and FR-904's chip is meant to look like Google.
        val check = assertIs<DestinationCheck.Refreshed>(
            checkStoredDestination(stored, listOf(calendar("latch-cal", "Latch dates", "#0b8043")))
        )
        assertEquals("Latch dates", check.defaults.destinationCalendarName)
        assertEquals("#0b8043", check.defaults.destinationCalendarColour)
        assertEquals("latch-cal", check.defaults.destinationCalendarId)
    }

    @Test
    fun `a destination that has gone falls back to the primary, and says which it left`() {
        val check = assertIs<DestinationCheck.FellBack>(
            checkStoredDestination(
                stored,
                listOf(calendar("primary", "you@example.com", "#3f51b5", primary = true)),
            )
        )
        assertEquals("primary", check.defaults.destinationCalendarId)
        assertEquals("you@example.com", check.defaults.destinationCalendarName)
        // FR-908's "inform the user" needs the name they will recognise, which is the one
        // that has just stopped existing rather than the one taking its place.
        assertEquals("Latch", check.previousName)
    }

    @Test
    fun `a destination that has lost write access is the same case as one that has gone`() {
        // FR-901 filters `listWritableCalendars` to owner and writer, so a calendar the user
        // can no longer write to is simply **absent** — which makes "missing" and "lost write
        // access" one test rather than two, and is why this test names the requirement's other
        // half explicitly.
        val readOnlyNowAbsent = listOf(calendar("primary", primary = true))
        assertIs<DestinationCheck.FellBack>(checkStoredDestination(stored, readOnlyNowAbsent))
    }

    @Test
    fun `an unreadable list changes nothing at all`() {
        // The load-bearing case. `listWritableCalendars` fails for want of a network far more
        // often than because a calendar was deleted, and falling back to primary because the
        // phone was on a train would move a user's captures for a reason that has nothing to do
        // with their calendars — and would need a second launch, online, to discover it was
        // wrong.
        assertEquals(DestinationCheck.Indeterminate, checkStoredDestination(stored, emptyList()))
    }

    @Test
    fun `an account with no primary falls back to the first writable calendar`() {
        // The honest second choice: the alternative is leaving the destination pointing at
        // something that is not there.
        val check = assertIs<DestinationCheck.FellBack>(
            checkStoredDestination(stored, listOf(calendar("work", "Work"), calendar("family", "Family")))
        )
        assertEquals("work", check.defaults.destinationCalendarId)
    }

    @Test
    fun `the task list is never touched by any of this`() {
        // FR-908 is about calendars. A task list that has gone is a different failure and this
        // must not quietly change one while correcting the other.
        val check = assertIs<DestinationCheck.FellBack>(
            checkStoredDestination(stored, listOf(calendar("primary", primary = true)))
        )
        assertEquals("list-1", check.defaults.taskListId)
        assertEquals(stored.accountId, check.defaults.accountId)
        assertEquals(stored.routingMode, check.defaults.routingMode)
    }
}
