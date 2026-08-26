package com.latch.android.setup

import com.latch.data.AuthClient
import com.latch.data.CalendarApi
import com.latch.data.GoogleAccount
import com.latch.data.TaskList
import com.latch.data.TasksApi
import com.latch.data.WritableCalendar
import kotlinx.coroutines.delay

/**
 * Scaffolding, not product. These stand in for the Google APIs so that the setup screens,
 * the state machine and the commit sequence can be finished and run end to end before the
 * NFR-501 decision about how to talk to Google is made.
 *
 * They make no network call, which is why the app still holds no `INTERNET` permission and
 * why AC-17 currently holds in the strongest possible form. Replacing these with real
 * clients is the FR-800 series of work; nothing above this file changes when they do.
 */
class StubAuthClient : AuthClient {
    override suspend fun signIn(): GoogleAccount {
        delay(SIMULATED_LATENCY_MS)
        return GoogleAccount(id = "stub-account", email = "you@example.com", displayName = "You")
    }

    override suspend fun signOut(accountId: String) = Unit
}

class StubCalendarApi : CalendarApi {
    private val created = mutableListOf<WritableCalendar>()

    override suspend fun listWritableCalendars(): List<WritableCalendar> {
        delay(SIMULATED_LATENCY_MS)
        return listOf(
            WritableCalendar("primary", "you@example.com", "#4285f4", isPrimary = true, visible = true),
            WritableCalendar("work", "Work", "#0b8043", isPrimary = false, visible = true),
            WritableCalendar("family", "Family", "#8e24aa", isPrimary = false, visible = false),
        ) + created
    }

    override suspend fun createLatchCalendar(summary: String, description: String): WritableCalendar {
        delay(SIMULATED_LATENCY_MS)
        return WritableCalendar(
            id = "latch-${created.size + 1}",
            summary = summary,
            backgroundColor = "#d50000",
            isPrimary = false,
            visible = true,
            latchCreated = true,
        ).also(created::add)
    }

    override suspend fun setColourAndVisibility(calendarId: String, colorId: String, visible: Boolean) = Unit
}

class StubTasksApi : TasksApi {
    override suspend fun listTaskLists(): List<TaskList> {
        delay(SIMULATED_LATENCY_MS)
        return listOf(
            TaskList("default", "My Tasks", isDefault = true),
            TaskList("work", "Work", isDefault = false),
        )
    }
}

/** Enough to make the loading states real rather than theoretical while the flow is driven. */
private const val SIMULATED_LATENCY_MS = 600L
