package com.latch.android.setup

import com.latch.core.model.RoutingMode
import com.latch.data.GoogleAccount
import com.latch.data.TaskList
import com.latch.data.WritableCalendar
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The setup state machine has no Android types and reads no clock, so it runs here in
 * milliseconds, the same way the parser corpus does. AC-15 and AC-16 are the two tests that
 * matter most: between them they say that the Latch calendar is created on completion of
 * setup and at no other moment.
 */
class SetupStateTest {

    private val account = GoogleAccount("acct-1", "user@example.com", "User")

    private val calendars = listOf(
        WritableCalendar("primary", "user@example.com", "#4285f4", isPrimary = true, visible = true),
        WritableCalendar("work", "Work", "#0b8043", isPrimary = false, visible = false),
    )

    private val taskLists = listOf(
        TaskList("default", "My Tasks", isDefault = true),
        TaskList("work", "Work", isDefault = false),
    )

    /** Drives a list of events from the initial state, collecting every effect emitted. */
    private fun run(vararg events: SetupEvent): Pair<SetupState, List<SetupEffect>> {
        var state = SetupState()
        val effects = mutableListOf<SetupEffect>()
        events.forEach { event ->
            val reduction = reduce(state, event)
            state = reduction.state
            effects += reduction.effects
        }
        return state to effects
    }

    private val signedInAndLoaded = arrayOf(
        SetupEvent.SignInRequested,
        SetupEvent.SignInSucceeded(account),
        SetupEvent.CalendarsLoaded(calendars),
        SetupEvent.TaskListsLoaded(taskLists),
    )

    // ----- FR-103: the two options and their defaults -----

    @Test
    fun `option B is pre-selected`() {
        assertEquals(RoutingMode.LATCH_CALENDAR, SetupState().mode)
    }

    @Test
    fun `signing in advances to step 2 and fetches both lists`() {
        val (state, effects) = run(SetupEvent.SignInRequested, SetupEvent.SignInSucceeded(account))

        assertEquals(SetupStep.DESTINATION, state.step)
        assertEquals(account, state.account)
        // Both fetched at once, while the user reads step 2 — most of FR-108's 60 seconds.
        assertTrue(SetupEffect.LoadCalendars in effects)
        assertTrue(SetupEffect.LoadTaskLists in effects)
    }

    @Test
    fun `defaults need no taps - primary calendar and default task list are pre-selected`() {
        val (state, _) = run(*signedInAndLoaded)

        assertEquals("primary", state.chosenCalendarId)
        assertEquals("default", state.chosenTaskListId)
    }

    // ----- FR-105, AC-15, AC-16: when the calendar is created -----

    @Test
    fun `no commit effect is emitted anywhere before Finish`() {
        val (_, effects) = run(
            *signedInAndLoaded,
            SetupEvent.ModeChosen(RoutingMode.LATCH_CALENDAR),
            SetupEvent.ModeChosen(RoutingMode.EXISTING_CALENDARS),
            SetupEvent.CalendarChosen("work"),
            SetupEvent.ModeChosen(RoutingMode.LATCH_CALENDAR),
            SetupEvent.NextRequested,
            SetupEvent.TaskListChosen("work"),
            SetupEvent.BackRequested,
            SetupEvent.NextRequested,
        )

        assertTrue(effects.none { it is SetupEffect.Commit })
    }

    @Test
    fun `AC-16 - abandoning at step 2 emits nothing that touches the account`() {
        val (_, effects) = run(
            *signedInAndLoaded,
            SetupEvent.ModeChosen(RoutingMode.LATCH_CALENDAR),
            SetupEvent.BackRequested,
            SetupEvent.BackRequested,
        )

        assertTrue(effects.none { it is SetupEffect.Commit })
        assertTrue(SetupEffect.Abandoned in effects)
    }

    @Test
    fun `AC-15 - Finish emits exactly one commit, and it creates the calendar`() {
        val (_, effects) = run(*signedInAndLoaded, SetupEvent.NextRequested, SetupEvent.FinishRequested)

        val commits = effects.filterIsInstance<SetupEffect.Commit>()
        assertEquals(1, commits.size)
        val plan = commits.single().plan
        assertTrue(plan.createsCalendar)
        assertEquals(RoutingMode.LATCH_CALENDAR, plan.mode)
        assertEquals("default", plan.taskListId)
    }

    @Test
    fun `option B adopts a Latch calendar another client already created`() {
        val existing = WritableCalendar(
            "latch-1", "Latch", "#d50000", isPrimary = false, visible = true, latchCreated = true,
        )
        val (state, effects) = run(
            SetupEvent.SignInRequested,
            SetupEvent.SignInSucceeded(account),
            SetupEvent.CalendarsLoaded(calendars + existing),
            SetupEvent.TaskListsLoaded(taskLists),
            SetupEvent.NextRequested,
            SetupEvent.FinishRequested,
        )

        assertEquals(existing, state.adoptableLatchCalendar)
        val plan = effects.filterIsInstance<SetupEffect.Commit>().single().plan
        assertFalse(plan.createsCalendar)
        assertEquals("latch-1", plan.existingCalendarId)
    }

    @Test
    fun `option A commits the chosen calendar and creates nothing`() {
        val (_, effects) = run(
            *signedInAndLoaded,
            SetupEvent.ModeChosen(RoutingMode.EXISTING_CALENDARS),
            SetupEvent.CalendarChosen("work"),
            SetupEvent.NextRequested,
            SetupEvent.FinishRequested,
        )

        val plan = effects.filterIsInstance<SetupEffect.Commit>().single().plan
        assertFalse(plan.createsCalendar)
        assertEquals("work", plan.existingCalendarId)
        assertEquals(RoutingMode.EXISTING_CALENDARS, plan.mode)
    }

    @Test
    fun `a second Finish while committing does not commit twice`() {
        val (_, effects) = run(
            *signedInAndLoaded,
            SetupEvent.NextRequested,
            SetupEvent.FinishRequested,
            SetupEvent.FinishRequested,
        )

        assertEquals(1, effects.filterIsInstance<SetupEffect.Commit>().size)
    }

    // ----- FR-906: Option A cannot leave step 2 without a calendar the user has seen -----

    @Test
    fun `option A cannot advance until the calendar list has loaded`() {
        val (state, _) = run(
            SetupEvent.SignInRequested,
            SetupEvent.SignInSucceeded(account),
            SetupEvent.ModeChosen(RoutingMode.EXISTING_CALENDARS),
            SetupEvent.NextRequested,
        )

        assertFalse(state.canLeaveDestination)
        assertEquals(SetupStep.DESTINATION, state.step)
    }

    @Test
    fun `option B can advance without the calendar list, because it creates nothing yet`() {
        val (state, _) = run(
            SetupEvent.SignInRequested,
            SetupEvent.SignInSucceeded(account),
            SetupEvent.NextRequested,
        )

        assertTrue(state.canLeaveDestination)
        assertEquals(SetupStep.TASK_LIST, state.step)
    }

    // ----- FR-903: an unticked destination -----

    @Test
    fun `a hidden calendar chosen under option A is flagged`() {
        val (state, _) = run(
            *signedInAndLoaded,
            SetupEvent.ModeChosen(RoutingMode.EXISTING_CALENDARS),
            SetupEvent.CalendarChosen("work"),
        )

        assertTrue(state.chosenCalendarIsHidden)
    }

    // ----- FR-1002 in miniature, and going backwards -----

    @Test
    fun `switching mode back and forth keeps the option A choice`() {
        val (state, _) = run(
            *signedInAndLoaded,
            SetupEvent.ModeChosen(RoutingMode.EXISTING_CALENDARS),
            SetupEvent.CalendarChosen("work"),
            SetupEvent.ModeChosen(RoutingMode.LATCH_CALENDAR),
            SetupEvent.ModeChosen(RoutingMode.EXISTING_CALENDARS),
        )

        assertEquals("work", state.chosenCalendarId)
    }

    @Test
    fun `back from step 2 returns to sign-in with the account still held`() {
        val (state, effects) = run(*signedInAndLoaded, SetupEvent.BackRequested)

        assertEquals(SetupStep.SIGN_IN, state.step)
        assertEquals(account, state.account)
        assertTrue(effects.none { it == SetupEffect.Abandoned })
    }

    @Test
    fun `continuing from a revisited sign-in does not authenticate again`() {
        val (state, effects) = run(
            *signedInAndLoaded,
            SetupEvent.BackRequested,
            SetupEvent.SignInRequested,
        )

        assertEquals(SetupStep.DESTINATION, state.step)
        // One sign-in, from the first request; the second was a Continue button.
        assertEquals(1, effects.count { it == SetupEffect.SignIn })
    }

    // ----- NFR-303: failures are recoverable and say nothing was saved -----

    @Test
    fun `a failed commit keeps the draft so Finish can be pressed again`() {
        val (state, _) = run(
            *signedInAndLoaded,
            SetupEvent.ModeChosen(RoutingMode.EXISTING_CALENDARS),
            SetupEvent.CalendarChosen("work"),
            SetupEvent.NextRequested,
            SetupEvent.FinishRequested,
            SetupEvent.CommitFailed(SetupFailure.CALENDAR_NOT_CREATED),
        )

        assertEquals(SetupStep.TASK_LIST, state.step)
        assertEquals("work", state.chosenCalendarId)
        assertEquals("default", state.chosenTaskListId)
        assertTrue(state.canFinish)
    }

    @Test
    fun `retrying a failed calendar list reloads it`() {
        val (state, effects) = run(
            SetupEvent.SignInRequested,
            SetupEvent.SignInSucceeded(account),
            SetupEvent.CalendarsFailed,
            SetupEvent.RetryRequested,
        )

        assertEquals(Async.Loading, state.calendars)
        assertEquals(2, effects.count { it == SetupEffect.LoadCalendars })
    }

    @Test
    fun `commit succeeding finishes setup`() {
        val (_, effects) = run(
            *signedInAndLoaded,
            SetupEvent.NextRequested,
            SetupEvent.FinishRequested,
            SetupEvent.CommitSucceeded,
        )

        assertTrue(SetupEffect.Finished in effects)
    }

    @Test
    fun `finish is refused while the task lists are still loading`() {
        val (state, effects) = run(
            SetupEvent.SignInRequested,
            SetupEvent.SignInSucceeded(account),
            SetupEvent.NextRequested,
            SetupEvent.FinishRequested,
        )

        assertNull(state.chosenTaskListId)
        assertFalse(state.canFinish)
        assertTrue(effects.none { it is SetupEffect.Commit })
    }
}
