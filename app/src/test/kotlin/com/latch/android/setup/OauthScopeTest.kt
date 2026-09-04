package com.latch.android.setup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * FR-002 and FR-003, asserted rather than trusted.
 *
 * FR-002 names an exact scope set and FR-003 forbids widening it without re-verification. Until
 * SRS 1.86 nothing checked either: the list was private to a class that touches Play services,
 * so no JVM test could see it. A scope is not a detail — every one of these is Sensitive, each
 * appears on the consent screen the user reads, and §8.6 budgets weeks of review against the
 * set. This test exists so a sixth cannot arrive quietly.
 */
class OauthScopeTest {

    @Test
    fun `the application requests exactly the scopes FR-002 names`() {
        assertEquals(
            listOf(
                "https://www.googleapis.com/auth/calendar.events",
                "https://www.googleapis.com/auth/calendar.calendarlist",
                "https://www.googleapis.com/auth/calendar.calendars",
                "https://www.googleapis.com/auth/tasks",
                // SRS 1.84's A2 amendment. FR-003 forbade this scope until §5.11 was approved.
                "https://www.googleapis.com/auth/contacts",
            ),
            LATCH_OAUTH_SCOPES,
        )
    }

    @Test
    fun `no scope reaches a service FR-003 still forbids`() {
        // FR-003 lost its Contacts clause at v1.84 and kept the rest. Gmail and Drive would each
        // move the application into a tier requiring an independent security assessment.
        listOf("gmail", "drive", "photoslibrary", "documents", "spreadsheets").forEach { service ->
            assertTrue(
                LATCH_OAUTH_SCOPES.none { service in it },
                "a scope reaching $service is forbidden by FR-003",
            )
        }
    }

    @Test
    fun `every scope is distinct and fully qualified`() {
        // A duplicate would be harmless and a relative one would fail at the grant, which is a
        // long way from here.
        assertEquals(LATCH_OAUTH_SCOPES.size, LATCH_OAUTH_SCOPES.toSet().size)
        assertTrue(LATCH_OAUTH_SCOPES.all { it.startsWith("https://www.googleapis.com/auth/") })
    }
}
