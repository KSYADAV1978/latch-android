package com.latch.android.setup

import com.latch.core.model.RoutingMode
import com.latch.data.GoogleAccount
import com.latch.data.TaskList
import com.latch.data.WritableCalendar

/**
 * NFR-402: failures are named, not phrased. The state machine says which failure occurred
 * and the app maps it to a string resource, the same way `ShiftResult` leaves FR-606's
 * "weekend skipped" wording to the UI.
 */
enum class SetupFailure {
    SIGN_IN_CANCELLED,
    SIGN_IN_FAILED,
    CALENDARS_UNAVAILABLE,
    TASK_LISTS_UNAVAILABLE,
    CALENDAR_NOT_CREATED,
    DEFAULTS_NOT_SAVED,
}

/** FR-101: sign in, choose a destination, choose a task list. */
enum class SetupStep { SIGN_IN, DESTINATION, TASK_LIST }

sealed interface Async<out T> {
    data object Idle : Async<Nothing>
    data object Loading : Async<Nothing>
    data class Ready<T>(val value: T) : Async<T>
    data class Failed(val failure: SetupFailure) : Async<Nothing>
}

/**
 * The whole of first-run setup, held in memory (FR-105).
 *
 * Deliberately free of Android imports and of any suspending call, so setup's behaviour —
 * the FR-105 guarantee included — is exercisable by plain JVM unit tests. `SetupCoordinator`
 * is the thin layer that performs the effects [reduce] asks for.
 *
 * The invariant that makes FR-105 structural rather than a matter of care: this state may
 * hold remote identifiers it has *read*, and may mint none. The only effect that reaches
 * `calendars.insert` is [SetupEffect.Commit], and the only event that produces one is
 * [SetupEvent.FinishRequested]. So there is no path from a step 2 interaction to a created
 * calendar (AC-16), and the calendar is created on finish and not before (AC-15).
 */
data class SetupState(
    val step: SetupStep = SetupStep.SIGN_IN,
    val signIn: Async<GoogleAccount> = Async.Idle,
    /** FR-103: Option B is the pre-selected default. */
    val mode: RoutingMode = RoutingMode.LATCH_CALENDAR,
    val calendars: Async<List<WritableCalendar>> = Async.Idle,
    /** FR-901 Option A choice. Defaults to the primary calendar so FR-108 costs no taps. */
    val chosenCalendarId: String? = null,
    val taskLists: Async<List<TaskList>> = Async.Idle,
    val chosenTaskListId: String? = null,
    val commit: Async<Unit> = Async.Idle,
) {
    val account: GoogleAccount? get() = (signIn as? Async.Ready)?.value

    /**
     * The Latch calendar another client already created (§4.1), where the list holds one.
     * Its presence turns Option B from "a calendar will be created" into "your existing
     * Latch calendar will be used", and makes commit adopt rather than insert.
     */
    val adoptableLatchCalendar: WritableCalendar?
        get() = (calendars as? Async.Ready)?.value?.firstOrNull { it.latchCreated }

    val chosenCalendar: WritableCalendar?
        get() = (calendars as? Async.Ready)?.value?.firstOrNull { it.id == chosenCalendarId }

    /**
     * Option B can always advance: it needs nothing from the calendar list, because under
     * FR-105 it has created nothing yet. Option A must have a calendar the user has seen —
     * FR-906 forbids routing to one they have not.
     */
    val canLeaveDestination: Boolean
        get() = mode == RoutingMode.LATCH_CALENDAR || chosenCalendar != null

    val canFinish: Boolean
        get() = canLeaveDestination && chosenTaskListId != null && commit !is Async.Loading

    /**
     * FR-903: a chosen calendar that is unticked in Google Calendar produces items the user
     * cannot see. Under Option B the new calendar is made visible at commit, so this can
     * only ever concern an Option A choice.
     */
    val chosenCalendarIsHidden: Boolean
        get() = mode == RoutingMode.EXISTING_CALENDARS && chosenCalendar?.visible == false
}

/**
 * Everything commit needs, resolved from the state at the moment Finish is pressed.
 *
 * A null [existingCalendarId] is the only case that creates anything: Option B where the
 * account holds no Latch calendar yet.
 */
data class CommitPlan(
    val account: GoogleAccount,
    val mode: RoutingMode,
    val existingCalendarId: String?,
    val taskListId: String,
) {
    val createsCalendar: Boolean get() = existingCalendarId == null
}

sealed interface SetupEvent {
    data object SignInRequested : SetupEvent
    data class SignInSucceeded(val account: GoogleAccount) : SetupEvent
    data class SignInFailed(val failure: SetupFailure) : SetupEvent

    data class CalendarsLoaded(val calendars: List<WritableCalendar>) : SetupEvent
    data object CalendarsFailed : SetupEvent
    data class ModeChosen(val mode: RoutingMode) : SetupEvent
    data class CalendarChosen(val calendarId: String) : SetupEvent

    data class TaskListsLoaded(val taskLists: List<TaskList>) : SetupEvent
    data object TaskListsFailed : SetupEvent
    data class TaskListChosen(val taskListId: String) : SetupEvent

    data object NextRequested : SetupEvent
    data object BackRequested : SetupEvent
    data object FinishRequested : SetupEvent
    data object CommitSucceeded : SetupEvent
    data class CommitFailed(val failure: SetupFailure) : SetupEvent
    data object RetryRequested : SetupEvent
}

sealed interface SetupEffect {
    data object SignIn : SetupEffect
    data object LoadCalendars : SetupEffect
    data object LoadTaskLists : SetupEffect

    /** The only effect that may create anything in the user's Google account (FR-105). */
    data class Commit(val plan: CommitPlan) : SetupEffect

    /** Setup is over; the app may proceed to Home. */
    data object Finished : SetupEffect

    /** Backed out of step 1, leaving nothing behind anywhere (AC-16). */
    data object Abandoned : SetupEffect
}

data class Reduction(val state: SetupState, val effects: List<SetupEffect> = emptyList())

private fun SetupState.then(vararg effects: SetupEffect) = Reduction(this, effects.toList())

fun reduce(state: SetupState, event: SetupEvent): Reduction = when (event) {

    SetupEvent.SignInRequested ->
        // Already signed in: this is the Continue button on a step 1 the user walked back
        // to, and re-authenticating would put a pointless second chooser in their way.
        if (state.account != null) state.copy(step = SetupStep.DESTINATION).then()
        else state.copy(signIn = Async.Loading).then(SetupEffect.SignIn)

    is SetupEvent.SignInSucceeded ->
        // Both lists are fetched the moment auth lands, while the user reads step 2. Step 3
        // is then instant, which is most of what keeps AC-15 inside 60 seconds.
        state.copy(
            step = SetupStep.DESTINATION,
            signIn = Async.Ready(event.account),
            calendars = Async.Loading,
            taskLists = Async.Loading,
        ).then(SetupEffect.LoadCalendars, SetupEffect.LoadTaskLists)

    is SetupEvent.SignInFailed ->
        state.copy(signIn = Async.Failed(event.failure)).then()

    is SetupEvent.CalendarsLoaded ->
        state.copy(
            calendars = Async.Ready(event.calendars),
            chosenCalendarId = state.chosenCalendarId
                ?: event.calendars.firstOrNull { it.isPrimary }?.id
                ?: event.calendars.firstOrNull()?.id,
        ).then()

    SetupEvent.CalendarsFailed ->
        state.copy(calendars = Async.Failed(SetupFailure.CALENDARS_UNAVAILABLE)).then()

    is SetupEvent.ModeChosen ->
        // FR-1002 in miniature: choosing a mode never discards the other mode's choice.
        state.copy(mode = event.mode).then()

    is SetupEvent.CalendarChosen ->
        state.copy(chosenCalendarId = event.calendarId).then()

    is SetupEvent.TaskListsLoaded ->
        state.copy(
            taskLists = Async.Ready(event.taskLists),
            chosenTaskListId = state.chosenTaskListId
                ?: event.taskLists.firstOrNull { it.isDefault }?.id
                ?: event.taskLists.firstOrNull()?.id,
        ).then()

    SetupEvent.TaskListsFailed ->
        state.copy(taskLists = Async.Failed(SetupFailure.TASK_LISTS_UNAVAILABLE)).then()

    is SetupEvent.TaskListChosen ->
        state.copy(chosenTaskListId = event.taskListId).then()

    SetupEvent.NextRequested -> when (state.step) {
        SetupStep.SIGN_IN ->
            if (state.account != null) state.copy(step = SetupStep.DESTINATION).then()
            else state.then()

        SetupStep.DESTINATION ->
            if (state.canLeaveDestination) state.copy(step = SetupStep.TASK_LIST).then()
            else state.then()

        // Step 3 finishes; it does not advance. FinishRequested is the only way out.
        SetupStep.TASK_LIST -> state.then()
    }

    SetupEvent.BackRequested -> when (state.step) {
        // Nothing has been written anywhere, so abandoning costs the user nothing and
        // leaves their Google account untouched (AC-16).
        SetupStep.SIGN_IN -> state.then(SetupEffect.Abandoned)
        SetupStep.DESTINATION -> state.copy(step = SetupStep.SIGN_IN).then()
        SetupStep.TASK_LIST -> state.copy(step = SetupStep.DESTINATION).then()
    }

    SetupEvent.FinishRequested -> {
        val plan = state.commitPlan()
        if (plan == null) state.then()
        else state.copy(commit = Async.Loading).then(SetupEffect.Commit(plan))
    }

    SetupEvent.CommitSucceeded ->
        state.copy(commit = Async.Ready(Unit)).then(SetupEffect.Finished)

    is SetupEvent.CommitFailed ->
        // The draft survives: the user stays on step 3 and may press Finish again. Nothing
        // was persisted, so the retry starts from exactly where this attempt did (NFR-303).
        state.copy(commit = Async.Failed(event.failure)).then()

    SetupEvent.RetryRequested -> when (state.step) {
        SetupStep.SIGN_IN -> state.copy(signIn = Async.Idle).then()

        SetupStep.DESTINATION ->
            if (state.calendars is Async.Failed) {
                state.copy(calendars = Async.Loading).then(SetupEffect.LoadCalendars)
            } else {
                state.then()
            }

        SetupStep.TASK_LIST ->
            if (state.taskLists is Async.Failed) {
                state.copy(taskLists = Async.Loading).then(SetupEffect.LoadTaskLists)
            } else {
                // A failed commit is retried with Finish, not here; this only clears the
                // error so the button reads as available again.
                state.copy(commit = Async.Idle).then()
            }
    }
}

/**
 * Null unless setup is genuinely complete. Being the only way to build a [CommitPlan], and
 * being called only from [SetupEvent.FinishRequested], is what stops any earlier interaction
 * reaching `calendars.insert`.
 */
private fun SetupState.commitPlan(): CommitPlan? {
    if (step != SetupStep.TASK_LIST || !canFinish) return null
    val account = account ?: return null
    val taskListId = chosenTaskListId ?: return null

    val existingCalendarId = when (mode) {
        // Option B adopts a Latch calendar another client already made, and is otherwise
        // null — the single case in all of setup that creates a calendar.
        RoutingMode.LATCH_CALENDAR -> adoptableLatchCalendar?.id
        RoutingMode.EXISTING_CALENDARS -> chosenCalendar?.id ?: return null
    }
    return CommitPlan(account, mode, existingCalendarId, taskListId)
}
