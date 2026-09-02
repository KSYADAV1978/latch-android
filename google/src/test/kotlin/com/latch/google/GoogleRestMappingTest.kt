package com.latch.google

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.latch.google.json.JSONObject

/**
 * The response mappers, against captured Google response bodies.
 *
 * This is where a defect would be silent: a mapper that misreads a field does not throw, it
 * offers the wrong calendar. Every requirement decided in `GoogleRest.kt` — FR-901's role
 * filter, FR-902's colour, FR-903's `selected` — is decided here and nowhere else, so it is
 * all reachable on the JVM with no device and no account.
 */
class GoogleRestMappingTest {

    private fun fixture(name: String): JSONObject {
        val text = checkNotNull(javaClass.getResourceAsStream("/google/$name")) {
            "Missing fixture: $name"
        }.bufferedReader().use { it.readText() }
        return JSONObject(text)
    }

    private val page1 get() = writableCalendarsFrom(fixture("calendarList_page1.json"))
    private val page2 get() = writableCalendarsFrom(fixture("calendarList_page2.json"))

    // ----- FR-901: only calendars the user can actually write to -----

    @Test
    fun `keeps owner and writer only`() {
        assertEquals(
            listOf("you@example.com", "work@example.com", "family@example.com"),
            page1.map { it.id },
        )
    }

    @Test
    fun `drops a role that merely looks writable`() {
        // writerWithoutPrivateAccess is in the accessRole enum and is not one of the two
        // roles FR-901 names. A server-side minAccessRole filter would have let it through.
        assertTrue(page1.none { it.id == "restricted@example.com" })
    }

    @Test
    fun `drops a deleted entry`() {
        assertTrue(page1.none { it.id == "removed@example.com" })
    }

    // ----- FR-902 and the display fields -----

    @Test
    fun `prefers the name the user gave the calendar in their own list`() {
        val work = page1.single { it.id == "work@example.com" }
        assertEquals("Work", work.summary)
    }

    @Test
    fun `carries the calendar's own colour`() {
        assertEquals("#4285f4", page1.single { it.id == "you@example.com" }.backgroundColor)
    }

    @Test
    fun `a calendar with no colour maps to blank rather than an invented one`() {
        // The UI renders an unparseable colour as grey, which is honest. Inventing a
        // plausible hex here would put a wrong colour in the picker FR-902 exists for.
        val entry = JSONObject("""{"items":[{"id":"a","summary":"A","accessRole":"owner"}]}""")
        assertEquals("", writableCalendarsFrom(entry).single().backgroundColor)
    }

    @Test
    fun `marks the primary calendar`() {
        assertTrue(page1.single { it.id == "you@example.com" }.isPrimary)
        assertFalse(page1.single { it.id == "work@example.com" }.isPrimary)
    }

    // ----- FR-903: `selected` is omitted entirely when false -----

    @Test
    fun `an absent selected field means hidden, not visible`() {
        val family = page1.single { it.id == "family@example.com" }
        assertFalse(family.visible)
        assertTrue(page1.single { it.id == "work@example.com" }.visible)
    }

    // ----- Adoption (§4.1): the marker, not the name -----

    @Test
    fun `adopts only the calendar carrying the marker`() {
        assertTrue(page2.single { it.id == "latch123@group.calendar.google.com" }.latchCreated)
    }

    @Test
    fun `does not adopt a calendar someone merely named Latch`() {
        assertFalse(page2.single { it.id == "impostor@group.calendar.google.com" }.latchCreated)
    }

    // ----- Pagination -----

    @Test
    fun `reads the next page token and knows when there is none`() {
        assertEquals("PAGE2", nextPageTokenFrom(fixture("calendarList_page1.json")))
        assertNull(nextPageTokenFrom(fixture("calendarList_page2.json")))
    }

    @Test
    fun `a body with no items is empty rather than an error`() {
        assertEquals(emptyList(), writableCalendarsFrom(JSONObject("""{"kind":"x"}""")))
    }

    // ----- FR-106 and FR-108: the default task list -----

    @Test
    fun `marks the default task list by id`() {
        val lists = taskListsFrom(fixture("taskLists.json"), defaultListId = "QUJDRUZHSElKS0xN")
        assertEquals(listOf("Work", "My Tasks"), lists.map { it.title })
        assertFalse(lists.first().isDefault)
        assertTrue(lists.last().isDefault)
    }

    @Test
    fun `marks nothing default when the default list could not be read`() {
        // The client falls back to the first entry; the mapper must not guess on its own,
        // and must never match on the title, which is localized.
        assertTrue(taskListsFrom(fixture("taskLists.json"), defaultListId = null).none { it.isDefault })
    }

    // ----- The account key -----

    @Test
    fun `account id is a stable opaque digest, not the address`() {
        val id = accountIdFor("You@Example.com")
        assertEquals(accountIdFor("you@example.com"), id)
        assertEquals(64, id.length)
        assertFalse(id.contains("example"))
    }

    // ----- Google's error envelope -----

    @Test
    fun `reads the machine-readable reason out of an error body`() {
        val body = """
            {"error":{"code":403,"message":"Request had insufficient authentication scopes.",
            "status":"PERMISSION_DENIED","details":[{"reason":"ACCESS_TOKEN_SCOPE_INSUFFICIENT"}]}}
        """.trimIndent()
        val rejected = rejection(403, body)
        assertEquals(403, rejected.status)
        assertEquals("PERMISSION_DENIED", rejected.reason)
        assertTrue(rejected.message!!.contains("insufficient"))
    }

    @Test
    fun `keeps the status code when the error body is not JSON at all`() {
        // A proxy or captive portal answers with HTML. Losing the status to a parse failure
        // would be worse than losing the reason.
        val rejected = rejection(502, "<html><body>Bad Gateway</body></html>")
        assertEquals(502, rejected.status)
        assertNull(rejected.reason)
        assertEquals("HTTP 502", rejected.message)
    }
}
