package com.latch.google

import com.latch.wire.KEY_CARD_IDENTITY
import com.latch.wire.KEY_CARD_SOURCE_HASH
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * FR-1208's scan.
 *
 * **This is the file SRS 1.38 would have wanted to exist.** The event-side duplicate query asked
 * for one result, Google returned an empty page carrying a `nextPageToken`, and duplicates went
 * into a real calendar for five days while a test asserting `maxResults=1` passed throughout. The
 * lesson recorded then was that a test can pin behaviour exactly and pin the wrong behaviour — so
 * what is pinned here is *paging to exhaustion*, and the cases are the ones that produced that
 * defect.
 */
class ContactsApiTest {

    private fun row(name: String, hash: String) =
        ContactRow(name, listOf(KEY_CARD_SOURCE_HASH to hash))

    private fun pages(vararg pages: ContactPage): suspend (String?) -> ContactPage {
        var index = 0
        return { _ -> pages[index++] }
    }

    // ---- the defect SRS 1.38 records, on a new transport ------------------------------------

    @Test
    fun `an empty first page carrying a token is followed, not read as absence`() = runTest {
        // The exact shape of the calendar defect: a page with no matches and a token means the
        // scan is not finished. Stopping here is what wrote duplicates for five days.
        val found = findContactByHashPaged(
            sourceHash = "wanted",
            nextPage = pages(
                ContactPage(emptyList(), nextPageToken = "p2"),
                ContactPage(listOf(row("people/c1", "wanted"))),
            ),
        )
        assertEquals("people/c1", found.existingResourceName)
        assertFalse(found.scanCapped)
    }

    @Test
    fun `a match on the last page is still a match`() = runTest {
        val found = findContactByHashPaged(
            sourceHash = "wanted",
            nextPage = pages(
                ContactPage(listOf(row("people/a", "other")), nextPageToken = "p2"),
                ContactPage(listOf(row("people/b", "another")), nextPageToken = "p3"),
                ContactPage(listOf(row("people/c", "wanted"))),
            ),
        )
        assertEquals("people/c", found.existingResourceName)
    }

    @Test
    fun `a scan that reaches the end with no match is a real absence`() = runTest {
        val found = findContactByHashPaged(
            sourceHash = "wanted",
            nextPage = pages(ContactPage(listOf(row("people/a", "other")))),
        )
        assertNull(found.existingResourceName)
        assertFalse(found.scanCapped, "a completed scan must not claim it gave up")
    }

    @Test
    fun `a scan that runs out of pages says so rather than answering none`() = runTest {
        // SRS 5.8 forbids reading a capped scan as "no duplicate". The distinction only exists
        // if the type carries it and the scan sets it.
        var calls = 0
        val found = findContactByHashPaged("wanted") { _ ->
            calls++
            ContactPage(listOf(row("people/x$calls", "other")), nextPageToken = "more")
        }
        assertTrue(found.scanCapped)
        assertNull(found.existingResourceName)
        assertEquals(CONNECTIONS_PAGE_CAP, calls, "the cap must bound the scan")
    }

    @Test
    fun `a blank token ends the scan rather than fetching for ever`() = runTest {
        // Google returns "" as often as it omits the field, and treating one as "there is more"
        // is an infinite loop on a capture path.
        var calls = 0
        val found = findContactByHashPaged("wanted") { _ ->
            calls++
            ContactPage(emptyList(), nextPageToken = "")
        }
        assertEquals(1, calls)
        assertFalse(found.scanCapped)
    }

    @Test
    fun `the page token is carried into the next request`() = runTest {
        val seen = mutableListOf<String?>()
        findContactByHashPaged("wanted") { token ->
            seen += token
            if (seen.size == 1) ContactPage(emptyList(), nextPageToken = "second")
            else ContactPage(emptyList())
        }
        assertEquals(listOf(null, "second"), seen)
    }

    @Test
    fun `a contact carrying other client data is not a match`() = runTest {
        // Another application's clientData, and Latch's own identity key, both of which sit in
        // the same list as the hash. Matching on the value alone would collide across keys.
        val decoy = ContactRow(
            "people/decoy",
            listOf("someone.else" to "wanted", KEY_CARD_IDENTITY to "wanted"),
        )
        val found = findContactByHashPaged(
            sourceHash = "wanted",
            nextPage = pages(ContactPage(listOf(decoy))),
        )
        assertNull(found.existingResourceName, "matched on the wrong clientData key")
    }

    // ---- the URLs ---------------------------------------------------------------------------

    @Test
    fun `every URL this client builds passes the AC-17 guard`() {
        listOf(
            createContactUrl(),
            deleteContactUrl("people/c123"),
            // FR-1226 is the one place an image leaves the device, so it is the one place where a
            // URL slipping outside the guard would send somebody else's photograph off it. AC-17
            // covers it like everything else, which is why the request is composed here and not
            // in a client of its own.
            updateContactPhotoUrl("people/c123"),
            connectionsUrl(),
            connectionsUrl("token"),
        ).forEach { requireGoogleEndpoint(it) }
    }

    @Test
    fun `the delete URL does not repeat the collection`() {
        // `resourceName` already carries `people/`. A base ending in the collection produces
        // /v1/people/people/c123:deleteContact, which is a 404 against a real account — found
        // by reading the URL before the probe ran, and cheaper there than after.
        assertEquals(
            "https://people.googleapis.com/v1/people/c123:deleteContact",
            deleteContactUrl("people/c123"),
        )
    }

    @Test
    fun `the photo URL does not repeat the collection either`() {
        // The same trap the delete URL fell into: `resourceName` already carries `people/`.
        assertEquals(
            "https://people.googleapis.com/v1/people/c123:updateContactPhoto",
            updateContactPhotoUrl("people/c123"),
        )
    }

    @Test
    fun `the photo URL carries no query parameters at all`() {
        // **Found on a device, not by reading the reference.** `updateContactPhoto` takes
        // `personFields` as a body field, and the first version sent it in the URL as well —
        // Google refused the request outright while the contact beside it saved perfectly, which
        // is exactly the shape FR-1226 says must not read as a failed save. A `?` here is a
        // regression that costs a photograph and says nothing.
        assertTrue('?' !in updateContactPhotoUrl("people/c123"), updateContactPhotoUrl("people/c123"))
    }

    @Test
    fun `the scan asks for the largest page the API allows`() {
        // Not a style choice: the account this was built against holds 3,157 contacts, so a page
        // of 100 is thirty-two round trips and a page of 1,000 is four. SRS 1.38's defect was a
        // page size of one.
        assertTrue(connectionsUrl().contains("pageSize=1000"), connectionsUrl())
        assertTrue(connectionsUrl().contains("personFields=clientData"), connectionsUrl())
    }

    @Test
    fun `a page token appears only when there is one`() {
        assertFalse("pageToken" in connectionsUrl())
        assertFalse("pageToken" in connectionsUrl(""))
        assertTrue("pageToken=abc" in connectionsUrl("abc"))
    }
}
