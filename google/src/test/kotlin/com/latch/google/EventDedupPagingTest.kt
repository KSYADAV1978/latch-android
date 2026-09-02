package com.latch.google

import kotlinx.coroutines.runBlocking
import com.latch.google.json.JSONArray
import com.latch.google.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * FR-803's event query, and the paging behaviour that made it write duplicates into a real
 * calendar between 27 Aug and 1 Sep 2026.
 *
 * A filtered `events.list` applies `privateExtendedProperty` to **a page of the scan** rather
 * than using it to choose the page. So an empty page carrying a `nextPageToken` is the normal
 * case, not an oddity — and the old implementation asked for `maxResults=1`, read the single
 * page, and reported "no duplicate" on the strength of one arbitrary event having failed the
 * filter. Measured on a device: at `maxResults=1` the query returned `items=0` with
 * `nextPageToken=YES`; the identical query at 250 returned `items=2`.
 *
 * These tests exist because nothing could have caught that. The loop was inside a REST client
 * that owns its own HTTP, so no test could serve it a page — the same reachability problem the
 * drain's duplicate check had, one layer down.
 */
class EventDedupPagingTest {

    private fun page(vararg ids: String, nextPageToken: String? = null): JSONObject {
        val items = JSONArray()
        ids.forEach { items.put(JSONObject().put("id", it)) }
        return JSONObject().put("items", items).also {
            if (nextPageToken != null) it.put("nextPageToken", nextPageToken)
        }
    }

    private fun urlFor(token: String?) = eventDedupUrl("cal-1", "a".repeat(64), token)

    @Test
    fun `a match on the second page is found`() = runBlocking {
        // The device case exactly: the first page is empty and says so with a token.
        val served = mutableListOf<String>()
        val result = findEventPaged(::urlFor) { url ->
            served += url
            if ("pageToken" in url) page("event-2") else page(nextPageToken = "PAGE2")
        }

        assertEquals("event-2", result.existingId, "an empty first page ended the search")
        assertFalse(result.scanCapped)
        assertEquals(2, served.size, "the token was not followed")
    }

    @Test
    fun `an empty page with no token is genuinely no duplicate`() = runBlocking {
        val result = findEventPaged(::urlFor) { page() }

        assertEquals(null, result.existingId)
        assertFalse(result.scanCapped, "a complete search must not report itself as capped")
    }

    @Test
    fun `a match on the first page stops there`() = runBlocking {
        var calls = 0
        val result = findEventPaged(::urlFor) { calls++; page("event-1", nextPageToken = "PAGE2") }

        assertEquals("event-1", result.existingId)
        assertEquals(1, calls, "the search kept paging after it had its answer")
    }

    @Test
    fun `running out of pages reports having stopped looking, never no duplicate`() = runBlocking {
        // The rule scanCapped already carries for the task scan: a search that gave up must
        // not be read as one that finished. Without it a large calendar silently duplicates.
        val result = findEventPaged(::urlFor, maxPages = 3) { page(nextPageToken = "MORE") }

        assertEquals(null, result.existingId)
        assertTrue(result.scanCapped, "a capped search claimed to have found nothing")
    }

    @Test
    fun `the url asks for a full page and carries the token`() {
        val first = eventDedupUrl("cal-1", "a".repeat(64), null)
        val next = eventDedupUrl("cal-1", "a".repeat(64), "PAGE2")

        // 250, not 1. The number is a throughput choice; the loop is what makes it correct.
        assertTrue(first.contains("maxResults=250"), first)
        assertFalse(first.contains("maxResults=1&"), first)
        assertFalse(first.contains("pageToken"), "the first page must not ask for a token")
        assertTrue(next.contains("pageToken=PAGE2"), next)
        // The filter itself is unchanged: this was never the part that was wrong.
        assertTrue(first.contains("privateExtendedProperty=latch.source_hash%3D"), first)
    }
}
