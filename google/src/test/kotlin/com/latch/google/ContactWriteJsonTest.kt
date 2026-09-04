package com.latch.google

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What a contact write actually puts on the wire.
 *
 * **"Absent" and "empty" are different instructions to the People API**, which is §7.2's
 * `put(name, null)` distinction one API over — and the device pass of 4 Sep 2026 turned up the
 * place where the two meet awkwardly: a job title is a *field inside* an organization, so a title
 * with no company can only be expressed as an organization with no name.
 */
class ContactWriteJsonTest {

    @Test
    fun `a title with no company is an organization with no name, because there is nowhere else`() {
        // SRS 1.102. Not a blank row left behind by carelessness: the People API has no field for
        // a job title outside `organizations`, so this is the only representation available. The
        // device pass expected "no organisation at all" and was asking for something the API
        // cannot say.
        val json = ContactWrite(jobTitle = "Managing Partner").toJson()
        val orgs = json.optJSONArray("organizations")
        assertEquals(1, orgs?.length())
        assertEquals("Managing Partner", orgs?.optJSONObject(0)?.optString("title"))
        assertFalse(orgs!!.optJSONObject(0)!!.has("name"), "an empty name was sent rather than omitted")
    }

    @Test
    fun `clearing both the company and the title omits the organization entirely`() {
        // The claim the device row *should* have made, and the one that is worth holding: with
        // nothing to say about employment, nothing is said.
        val json = ContactWrite(displayName = "Ravi Menon").toJson()
        assertFalse(json.has("organizations"))
    }

    @Test
    fun `absent fields are absent rather than empty`() {
        val json = ContactWrite(displayName = "Someone").toJson()
        listOf("phoneNumbers", "emailAddresses", "addresses", "urls", "biographies", "clientData")
            .forEach { assertFalse(json.has(it), "$it was sent empty") }
    }

    @Test
    fun `a name is sent unstructured as well as structured`() {
        // People derives its own display name; ours goes as the unstructured form so a card whose
        // FN differs from its N components keeps what its author chose.
        val json = ContactWrite(givenName = "Ravi", familyName = "Menon", displayName = "Dr Ravi Menon").toJson()
        val name = json.optJSONArray("names")?.optJSONObject(0)
        assertEquals("Ravi", name?.optString("givenName"))
        assertEquals("Menon", name?.optString("familyName"))
        assertEquals("Dr Ravi Menon", name?.optString("unstructuredName"))
    }

    @Test
    fun `every clientData pair reaches the body`() {
        val json = ContactWrite(
            displayName = "X",
            clientData = listOf("latch.card.version" to "1", "latch.card.identity" to "a", "latch.card.identity" to "b"),
        ).toJson()
        // Duplicate keys are permitted by the API and are how several identities travel.
        assertEquals(3, json.optJSONArray("clientData")?.length())
    }

    @Test
    fun `a phone keeps the type the card gave it`() {
        val json = ContactWrite(phones = listOf("+91 98200 12345" to "CELL")).toJson()
        val phone = json.optJSONArray("phoneNumbers")?.optJSONObject(0)
        assertEquals("+91 98200 12345", phone?.optString("value"))
        assertEquals("CELL", phone?.optString("type"))
        assertTrue(json.has("phoneNumbers"))
    }
}
