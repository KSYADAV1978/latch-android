package com.latch.desktop.save

import com.latch.desktop.store.DesktopDefaults
import com.latch.google.CalendarApi
import com.latch.google.TasksApi
import com.latch.google.WritableCalendar

/** What choosing a destination produced. */
sealed interface SetupResult {
    data class Ready(val defaults: DesktopDefaults) : SetupResult
    data class Failed(val reason: SetupFailure, val detail: String = "") : SetupResult
}

enum class SetupFailure { NO_WRITABLE_CALENDAR, NO_TASK_LIST, UNREACHABLE }

/**
 * Which of the account's calendars a capture from this machine goes to.
 *
 * **This is not FR-100's setup, and does not pretend to be.** The phone's wizard asks the user
 * which mode they want and which calendar, and FR-105 is load-bearing about a calendar being
 * created only when they finish. What happens here is narrower and is the Option B default the
 * SRS already describes: reuse the account's Latch calendar if it exists, otherwise make one.
 * The user is never asked, because there is no screen to ask on yet — the FR-900 destination
 * picker is owed on this client and is in the backlog.
 *
 * **Reusing the existing Latch calendar is what makes two clients agree.** AC-07 needs a
 * capture made on the phone and the same capture made here to find each other, and FR-803's
 * event query is scoped to a calendar id — so a desktop that made its own second calendar
 * called "Latch" would look, to itself, like an account with no duplicates in it.
 */
class DesktopSetup(
    private val calendar: CalendarApi,
    private val tasks: TasksApi,
) {
    suspend fun chooseDestination(email: String): SetupResult {
        val calendars = runCatching { calendar.listWritableCalendars() }
            .getOrElse { return SetupResult.Failed(SetupFailure.UNREACHABLE, it.message.orEmpty()) }

        val lists = runCatching { tasks.listTaskLists() }
            .getOrElse { return SetupResult.Failed(SetupFailure.UNREACHABLE, it.message.orEmpty()) }

        val taskList = lists.firstOrNull { it.isDefault } ?: lists.firstOrNull()
            ?: return SetupResult.Failed(SetupFailure.NO_TASK_LIST)

        val existing = existingLatchCalendar(calendars)
        if (existing != null) {
            return SetupResult.Ready(
                DesktopDefaults(email, existing.id, existing.summary, taskList.id)
            )
        }

        if (calendars.isEmpty()) return SetupResult.Failed(SetupFailure.NO_WRITABLE_CALENDAR)

        val created = runCatching { calendar.createLatchCalendar(LATCH_CALENDAR_NAME, LATCH_CALENDAR_DESCRIPTION) }
            .getOrElse { return SetupResult.Failed(SetupFailure.UNREACHABLE, it.message.orEmpty()) }

        // FR-104's one colour. Failure here is cosmetic — a calendar in the wrong colour still
        // takes writes — so it is not allowed to fail the setup that has already succeeded.
        runCatching { calendar.setColourAndVisibility(created, LATCH_CALENDAR_COLOUR, visible = true) }

        return SetupResult.Ready(DesktopDefaults(email, created, LATCH_CALENDAR_NAME, taskList.id))
    }

    companion object {
        const val LATCH_CALENDAR_NAME: String = "Latch"
        const val LATCH_CALENDAR_DESCRIPTION: String = "Dates and deadlines captured with Latch."

        /**
         * FR-104's colour id, from the **calendar** palette rather than the event one.
         *
         * The two are separately numbered, and the Android client shipped `"11"` — an id from
         * the event palette — for long enough to reach a device pass, where it turned out to be
         * inert only because the stub's patch did nothing. Same value, same reason, one client
         * over.
         */
        const val LATCH_CALENDAR_COLOUR: String = "3"
    }
}

/**
 * The account's own Latch calendar, if it has one.
 *
 * **Matched on the summary, and the primary calendar is excluded.** Matching by name is
 * imprecise and is the best available: a calendar carries no marker this client can read
 * before it has written to it. Excluding the primary is what stops a user who happens to be
 * called Latch, or whose main calendar is named for their company, having every capture land
 * in their personal calendar — the one place FR-906 is emphatic captures must not silently go.
 */
internal fun existingLatchCalendar(calendars: List<WritableCalendar>): WritableCalendar? =
    calendars.firstOrNull {
        !it.isPrimary && it.summary.trim().equals(DesktopSetup.LATCH_CALENDAR_NAME, ignoreCase = true)
    }
