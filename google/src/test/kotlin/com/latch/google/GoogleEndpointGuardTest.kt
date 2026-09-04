package com.latch.google

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * **AC-17.** "No outbound request to any non-Google endpoint", in the app's default
 * configuration.
 *
 * Until the app held the INTERNET permission, AC-17 was guaranteed by the platform and no
 * test could weaken it. It is now guaranteed by [requireGoogleEndpoint], so it is tested by
 * name — this file is the thing a reviewer checking AC-17 should be pointed at.
 */
class GoogleEndpointGuardTest {

    @Test
    fun `allows the hosts the app actually calls`() {
        val allowed = listOf(
            "https://www.googleapis.com/calendar/v3/users/me/calendarList",
            "https://www.googleapis.com/calendar/v3/calendars",
            "https://tasks.googleapis.com/tasks/v1/users/@me/lists",
            "https://people.googleapis.com/v1/people:createContact",
        )
        allowed.forEach { assertEquals("https", requireGoogleEndpoint(it).protocol) }
    }

    @Test
    fun `refuses a host that merely starts with a Google one`() {
        // The reason the guard parses the URL instead of matching the string: this passes
        // any startsWith test and is not Google.
        assertFailsWith<GoogleUnreachable> {
            requireGoogleEndpoint("https://www.googleapis.com.example.invalid/calendar/v3/calendars")
        }
    }

    @Test
    fun `refuses a Google host embedded somewhere in the path`() {
        assertFailsWith<GoogleUnreachable> {
            requireGoogleEndpoint("https://example.invalid/https://www.googleapis.com/calendar")
        }
    }

    @Test
    fun `refuses plain HTTP even to a Google host`() {
        assertFailsWith<GoogleUnreachable> {
            requireGoogleEndpoint("http://www.googleapis.com/calendar/v3/calendars")
        }
    }

    @Test
    fun `refuses credentials in the authority`() {
        assertFailsWith<GoogleUnreachable> {
            requireGoogleEndpoint("https://user:pass@www.googleapis.com/calendar/v3/calendars")
        }
    }

    @Test
    fun `refuses a non-default port`() {
        assertFailsWith<GoogleUnreachable> {
            requireGoogleEndpoint("https://www.googleapis.com:8443/calendar/v3/calendars")
        }
    }

    @Test
    fun `refuses something that is not a URL`() {
        assertFailsWith<GoogleUnreachable> { requireGoogleEndpoint("not a url") }
    }

    @Test
    fun `the allowlist stays small and stays Google`() {
        // A guard is worth what the list is worth. If this fails, someone widened it — which may
        // be right, but AC-17 is a sign-off test and the change should be deliberate.
        //
        // **It names the hosts rather than counting them** (SRS 1.90). A count fails just as
        // loudly and says nothing about *which* host arrived, so the diff that widens the guard
        // has to spell out what it is admitting — which is the whole of what makes this an
        // AC-17 decision rather than a refactor.
        assertEquals(
            setOf(
                "www.googleapis.com",
                "tasks.googleapis.com",
                "oauth2.googleapis.com",
                // §5.11's A5, approved with the FR-1200 series.
                "people.googleapis.com",
            ),
            ALLOWED_HOSTS,
        )
        assertTrue(ALLOWED_HOSTS.all { it.endsWith(".googleapis.com") })
    }
}
