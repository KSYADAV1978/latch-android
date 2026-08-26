package com.latch.android.setup

import com.latch.core.model.RoutingMode
import com.latch.data.AccountDefaults
import com.latch.data.AccountDefaultsStore
import com.latch.data.AuthClient
import com.latch.data.CalendarApi
import com.latch.data.LATCH_CALENDAR_MARKER
import com.latch.data.SignInCancelledException
import com.latch.data.TasksApi
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Whether setup ran to completion or was walked away from. Null while it is still running. */
enum class SetupOutcome { COMPLETED, ABANDONED }

/**
 * Performs the effects [reduce] asks for, and nothing else. All decisions about *whether*
 * an effect happens live in the state machine; this class only knows how.
 *
 * It is held for the lifetime of the process and never persisted, which is the mechanism
 * behind FR-105: an abandoned setup, or one interrupted by process death, leaves nothing
 * in the user's Google account and nothing on disk (AC-16). The cost is that setup restarts
 * at step 1 if the process dies mid-flow, which for a sub-minute foreground flow is the
 * right trade — the alternative is a saved-state restore, and a retained ViewModel is a
 * dependency this module does not yet carry.
 */
class SetupCoordinator(
    private val auth: AuthClient,
    private val calendarApi: CalendarApi,
    private val tasksApi: TasksApi,
    private val defaultsStore: AccountDefaultsStore,
    private val scope: CoroutineScope,
    /** NFR-402: the name the user will see on this calendar in Google Calendar. */
    private val latchCalendarSummary: String,
    private val latchCalendarDescription: String,
) {
    private val _state = MutableStateFlow(SetupState())
    val state: StateFlow<SetupState> = _state.asStateFlow()

    private val _outcome = MutableStateFlow<SetupOutcome?>(null)
    val outcome: StateFlow<SetupOutcome?> = _outcome.asStateFlow()

    fun dispatch(event: SetupEvent) {
        val reduction = reduce(_state.value, event)
        _state.value = reduction.state
        reduction.effects.forEach(::perform)
    }

    private fun perform(effect: SetupEffect) {
        when (effect) {
            SetupEffect.SignIn -> scope.launch { signIn() }
            SetupEffect.LoadCalendars -> scope.launch { loadCalendars() }
            SetupEffect.LoadTaskLists -> scope.launch { loadTaskLists() }
            is SetupEffect.Commit -> scope.launch { commit(effect.plan) }
            SetupEffect.Finished -> _outcome.value = SetupOutcome.COMPLETED
            SetupEffect.Abandoned -> _outcome.value = SetupOutcome.ABANDONED
        }
    }

    private suspend fun signIn() {
        try {
            dispatch(SetupEvent.SignInSucceeded(auth.signIn()))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (dismissed: SignInCancelledException) {
            // Caught above the generic branch, and distinct from CancellationException on
            // purpose: dismissing the consent screen is a choice, not a fault, and it has
            // always had its own wording. Nothing could produce it until there was a real
            // consent screen to dismiss.
            dispatch(SetupEvent.SignInFailed(SetupFailure.SIGN_IN_CANCELLED))
        } catch (failure: Exception) {
            dispatch(SetupEvent.SignInFailed(SetupFailure.SIGN_IN_FAILED))
        }
    }

    private suspend fun loadCalendars() {
        try {
            dispatch(SetupEvent.CalendarsLoaded(calendarApi.listWritableCalendars()))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            dispatch(SetupEvent.CalendarsFailed)
        }
    }

    private suspend fun loadTaskLists() {
        try {
            dispatch(SetupEvent.TaskListsLoaded(tasksApi.listTaskLists()))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            dispatch(SetupEvent.TaskListsFailed)
        }
    }

    /**
     * The completion of setup, and the only place in the app where a calendar is created
     * (FR-105). The order matters: the irreversible step goes first, so that a failure
     * anywhere leaves either nothing at all or one adoptable calendar, never a saved
     * configuration pointing at a calendar that was never made.
     */
    private suspend fun commit(plan: CommitPlan) {
        val calendarId = try {
            plan.existingCalendarId ?: calendarApi.createLatchCalendar(
                summary = latchCalendarSummary,
                // The marker, not the prose, is what a later run matches on to adopt this
                // calendar rather than create a second one.
                description = "$latchCalendarDescription\n\n$LATCH_CALENDAR_MARKER",
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            dispatch(SetupEvent.CommitFailed(SetupFailure.CALENDAR_NOT_CREATED))
            return
        }

        if (plan.mode == RoutingMode.LATCH_CALENDAR) {
            // FR-104's "one colour". Failing setup over this would be disproportionate: a
            // calendar returned by calendars.insert is already in the user's list and
            // already visible, so the only thing lost is the colour, and Settings can set
            // it later (FR-1001).
            try {
                calendarApi.setColourAndVisibility(calendarId, LATCH_CALENDAR_COLOR_ID, visible = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (ignored: Exception) {
                // Deliberately swallowed; see above.
            }
        } else if (plan.makeChosenCalendarVisible) {
            // FR-903 / AC-09: the user accepted the offer to tick a calendar of their own
            // that was hidden. Swallowed for the same reason as the colour above — the
            // destination is saved and works either way, and the consequence of not ticking
            // it is the visibility warning the user has already been shown.
            try {
                calendarApi.makeVisible(calendarId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (ignored: Exception) {
                // Deliberately swallowed; see above.
            }
        }

        try {
            defaultsStore.save(
                AccountDefaults(
                    accountId = plan.account.id,
                    email = plan.account.email,
                    routingMode = plan.mode,
                    destinationCalendarId = calendarId,
                    taskListId = plan.taskListId,
                )
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // A calendar may now exist that nothing points at. It carries the marker, so
            // the next attempt adopts it instead of creating another (§4.1 relies on the
            // same mechanism for the desktop client).
            dispatch(SetupEvent.CommitFailed(SetupFailure.DEFAULTS_NOT_SAVED))
            return
        }

        dispatch(SetupEvent.CommitSucceeded)
    }
}

/**
 * A `colorId` from the **calendar** palette, which `calendarList.patch` reads. Calendar and
 * event colours are two separately numbered maps — `colors.get` returns them as separate
 * `calendar` and `event` objects — so an id copied from the event palette names a different
 * colour here, or none. This was "11" with a comment calling it Tomato, which is the event
 * palette's numbering; it was inert only because the stub's patch did nothing.
 *
 * Chosen to be distinct from the default colour of a personal calendar; FR-1001 lets the
 * user change it. Verified on a device on 26 Aug 2026: renders red in Google Calendar.
 */
private const val LATCH_CALENDAR_COLOR_ID = "3"
