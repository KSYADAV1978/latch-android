package com.latch.google

import com.latch.core.model.CardDraft
import com.latch.core.model.CardEmail
import com.latch.core.model.CardPhone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * FR-1231, FR-1232 and FR-1233.
 *
 * **The dangerous direction is a change nobody offered.** An update is a write into somebody's own
 * address book, so most of what is asserted here is what the comparison must *not* produce: a
 * removal, a change to a field the card said nothing about, a mask naming `clientData` or the
 * note, and a restore that reaches further than the update it undoes.
 */
class ContactUpdateTest {

    private val stored = ContactRecord(
        resourceName = "people/c1",
        etag = "%etag1",
        displayName = "Anita Kapoor",
        organisation = "Northwind Textiles",
        jobTitle = "Partner",
        phones = listOf("+91 98765 43210" to "MOBILE", "011 2345 6789" to "WORK"),
        emails = listOf("anita@northwind.in" to "WORK"),
        addresses = listOf("14 Mill Road, Delhi"),
    )

    private val sameCard = CardDraft(
        displayName = "Anita Kapoor",
        organisation = "Northwind Textiles",
        jobTitle = "Partner",
        phones = listOf(CardPhone("+91 98765 43210", "MOBILE")),
        emails = listOf(CardEmail("anita@northwind.in", "WORK")),
        addresses = listOf("14 Mill Road, Delhi"),
    )

    @Test
    fun `a card that says nothing new changes nothing`() {
        assertEquals(emptyList(), contactChanges(stored, sameCard))
    }

    @Test
    fun `a number the contact already holds is not offered again`() {
        // Written without the country code, which is how the same number is printed on half the
        // cards in circulation. `cardPersonKeys` had to decide this too.
        val card = sameCard.copy(phones = listOf(CardPhone("9876543210", "MOBILE")))
        assertEquals(emptyList(), contactChanges(stored, card))
    }

    @Test
    fun `a genuinely new number is an addition`() {
        val card = sameCard.copy(
            phones = sameCard.phones + CardPhone("+91 99887 76655", "MOBILE"),
        )
        val changes = contactChanges(stored, card)
        assertEquals(1, changes.size)
        assertEquals(ContactField.PHONE, changes.single().field)
        // An addition, so there is no current value to quote.
        assertEquals(null, changes.single().stored)
    }

    @Test
    fun `a new job title quotes the one on the contact`() {
        val changes = contactChanges(stored, sameCard.copy(jobTitle = "Managing Partner"))
        assertEquals(1, changes.size)
        // FR-1233: the previous value is visible, and it is visible because every field's is.
        assertEquals(ContactFieldChange(ContactField.JOB_TITLE, "Partner", "Managing Partner"), changes.single())
    }

    @Test
    fun `case and spacing alone are not a change`() {
        // An offer the user has to read and decline, for nothing.
        assertEquals(emptyList(), contactChanges(stored, sameCard.copy(organisation = "NORTHWIND  Textiles")))
    }

    @Test
    fun `a card with no job title does not clear the contact's`() {
        // Design principle 1's reasoning on a field that is not a date: a card that does not
        // print a title is not a card saying the person has none.
        assertEquals(emptyList(), contactChanges(stored, sameCard.copy(jobTitle = null)))
    }

    @Test
    fun `a number the contact has and the card does not is never a change`() {
        // The work number is on the contact and absent from the card. Offering to drop it would
        // make accepting an update a gamble on what the card happened to print.
        val changes = contactChanges(stored, sameCard)
        assertTrue(changes.none { it.field == ContactField.PHONE })
    }

    @Test
    fun `the merged write keeps every stored value and adds the new one`() {
        val card = sameCard.copy(phones = sameCard.phones + CardPhone("+91 99887 76655", "MOBILE"))
        val changes = contactChanges(stored, card)
        val update = mergedContactUpdate(stored, card, changes)

        // **Whole, because the API replaces a field's entire list.** Sending only the new number
        // would delete the two the contact already had, which is exactly the silent removal
        // `contactChanges` refuses to offer.
        assertEquals(3, update.write.phones.size)
        assertTrue(update.write.phones.any { it.first == "+91 98765 43210" })
        assertTrue(update.write.phones.any { it.first == "011 2345 6789" })
        assertTrue(update.write.phones.any { it.first == "+91 99887 76655" })
        assertEquals(listOf("phoneNumbers"), update.fields)
    }

    @Test
    fun `the mask never names clientData or the note`() {
        // FR-1207 is write-once (SRS 1.18) and FR-1233 calls a NOTE a field the user owns.
        // `updatePersonFields` is a licence to overwrite, so neither may ever appear in one.
        val everything = ContactField.entries.toSet()
        val fields = fieldNames(everything)
        assertFalse("clientData" in fields)
        assertFalse("biographies" in fields)
    }

    @Test
    fun `an employer change and a job title change name one API field once`() {
        // A job title lives *inside* organizations, which SRS 1.102 already had to record.
        val changes = contactChanges(
            stored,
            sameCard.copy(organisation = "Southern Weaving", jobTitle = "Director"),
        )
        val update = mergedContactUpdate(stored, sameCard.copy(organisation = "Southern Weaving", jobTitle = "Director"), changes)
        assertEquals(2, changes.size)
        assertEquals(listOf("organizations"), update.fields)
        assertEquals("Southern Weaving", update.write.organisation)
        assertEquals("Director", update.write.jobTitle)
    }

    @Test
    fun `a changed name drops the structured pair`() {
        // SRS 1.101: this code cannot know which word of a card's name is the family name, and
        // a stored pair beside a new unstructured name is how a contact displays one name and
        // sorts under another.
        val withPair = stored.copy(givenName = "Anita", familyName = "Kapoor")
        val card = sameCard.copy(displayName = "Anita Kapoor Singh")
        val update = mergedContactUpdate(withPair, card, contactChanges(withPair, card))
        assertEquals("Anita Kapoor Singh", update.write.displayName)
        assertEquals(null, update.write.givenName)
        assertEquals(null, update.write.familyName)
    }

    @Test
    fun `the restore puts back exactly what was stored, over the same fields`() {
        val card = sameCard.copy(jobTitle = "Managing Partner")
        val changes = contactChanges(stored, card)
        val update = mergedContactUpdate(stored, card, changes)
        val restore = restoreContactUpdate(stored, update.fields)

        // FR-1232: the previous values, and the *same* mask — a narrower one would leave half
        // the change standing and a wider one would overwrite a field Latch never touched.
        assertEquals("Partner", restore.write.jobTitle)
        assertEquals("Northwind Textiles", restore.write.organisation)
        assertEquals(update.fields, restore.fields)
        assertEquals(stored.phones, restore.write.phones)
    }

    @Test
    fun `an update sends no clientData at all`() {
        // Belt as well as braces: even were the mask wrong, the body carries no FR-1207 record.
        val card = sameCard.copy(jobTitle = "Managing Partner")
        val update = mergedContactUpdate(stored, card, contactChanges(stored, card))
        assertEquals(emptyList(), update.write.clientData)
        assertEquals(null, update.write.note)
    }
}

/**
 * FR-1231's half of the scan.
 *
 * The fixture that matters is a contact **Latch never created** — no `clientData` at all, which
 * is every contact the user already had and the whole case this requirement exists for.
 */
class ContactIdentityScanTest {

    private fun page(vararg rows: ContactRow) = ContactPage(rows.toList())

    @Test
    fun `a contact Latch never created is found by its own address`() = kotlinx.coroutines.test.runTest {
        val theirs = ContactRow(
            resourceName = "people/theirs",
            clientData = emptyList(),
            emails = listOf("Anita@Northwind.IN"),
        )
        val keys = com.latch.wire.contactIdentityKeys(
            CardDraft(emails = listOf(CardEmail("anita@northwind.in")))
        )
        val search = findContactByHashPaged("no-such-hash", identityKeys = keys) { page(theirs) }
        assertEquals("people/theirs", search.identityResourceName)
        assertFalse(search.found)
    }

    @Test
    fun `a contact with no address matches nobody`() = kotlinx.coroutines.test.runTest {
        val theirs = ContactRow("people/theirs", emptyList(), emptyList())
        val keys = com.latch.wire.contactIdentityKeys(CardDraft(displayName = "Anita Kapoor"))
        // The card has no address either, so both sides are empty — and two empties must never
        // match, or every emailless contact would fold into the first emailless card.
        val search = findContactByHashPaged("no-such-hash", identityKeys = keys) { page(theirs) }
        assertEquals(null, search.identityResourceName)
    }

    @Test
    fun `the exact answer ends the scan and reports no identity`() = kotlinx.coroutines.test.runTest {
        val row = ContactRow(
            resourceName = "people/c1",
            clientData = listOf(com.latch.wire.KEY_CARD_SOURCE_HASH to "hash-1"),
            emails = listOf("anita@northwind.in"),
        )
        val keys = com.latch.wire.contactIdentityKeys(
            CardDraft(emails = listOf(CardEmail("anita@northwind.in")))
        )
        val search = findContactByHashPaged("hash-1", identityKeys = keys) { page(row) }
        // FR-1208 outranks FR-1231: an offer to update a card that is already saved is an offer
        // about a change that is not there.
        assertEquals("people/c1", search.existingResourceName)
        assertEquals(null, search.identityResourceName)
    }
}

/**
 * FR-1205 on the update path (SRS 1.159).
 *
 * **What this must never do is write something nobody agreed to.** An unticked row, a blanked
 * value and a value edited back to what is already stored are three ways of saying no, and each
 * has to reach the same place: out of the patch entirely, so `updatePersonFields` never names its
 * field. A field named in that mask whose value is absent is a field **emptied**.
 */
class AcceptedChangesTest {

    private val changes = listOf(
        ContactFieldChange(ContactField.JOB_TITLE, "Manager", "Advisor"),
        ContactFieldChange(ContactField.URL, null, "www.mec.co.test"),
        ContactFieldChange(ContactField.ADDRESS, null, "Some Street, Delhi"),
    )

    @Test
    fun `all ticked keeps every line`() {
        assertEquals(3, acceptedChanges(changes, setOf(0, 1, 2)).size)
    }

    @Test
    fun `an unticked line is dropped`() {
        // The developer's own case: take the job title, decline the address and the website.
        val kept = acceptedChanges(changes, setOf(0))
        assertEquals(1, kept.size)
        assertEquals(ContactField.JOB_TITLE, kept.single().field)
    }

    @Test
    fun `nothing ticked writes nothing`() {
        assertEquals(emptyList(), acceptedChanges(changes, emptySet()))
    }

    @Test
    fun `a corrected value is what is kept`() {
        // The reason editing exists: the recogniser read the domain wrong, twice, differently.
        val kept = acceptedChanges(changes, setOf(1), mapOf(1 to "www.mecl.co.test"))
        assertEquals("www.mecl.co.test", kept.single().captured)
    }

    @Test
    fun `a blanked value declines the line`() {
        // The second way to say no. It must not become a write of an empty field.
        assertEquals(emptyList(), acceptedChanges(changes, setOf(0), mapOf(0 to "   ")))
    }

    @Test
    fun `a value edited back to what is stored is dropped`() {
        // A change that changes nothing still names its field in `updatePersonFields`, and a
        // field named there is a field the request is licensed to overwrite.
        assertEquals(emptyList(), acceptedChanges(changes, setOf(0), mapOf(0 to "manager")))
    }

    @Test
    fun `the type survives an edit`() {
        // SRS 1.159: carried on the change rather than looked up by matching the value back
        // against the draft, which an edit breaks silently.
        val phone = listOf(ContactFieldChange(ContactField.PHONE, null, "+91 90000 00001", "MOBILE"))
        val kept = acceptedChanges(phone, setOf(0), mapOf(0 to "+91 90000 00002"))
        assertEquals("MOBILE", kept.single().type)
    }

    @Test
    fun `the merged write carries the corrected value and its type`() {
        val stored = ContactRecord(resourceName = "people/c1", etag = "%e")
        val phone = listOf(ContactFieldChange(ContactField.PHONE, null, "+91 90000 00001", "MOBILE"))
        val kept = acceptedChanges(phone, setOf(0), mapOf(0 to "+91 90000 00002"))
        val update = mergedContactUpdate(stored, com.latch.core.model.CardDraft(), kept)
        assertEquals("+91 90000 00002" to "MOBILE", update.write.phones.single())
    }
}

/**
 * FR-1231's "and their current values", for a field that holds several (SRS 1.160).
 *
 * **The failure this pins is a caption that lies in the reassuring direction.** An addition to a
 * field that already holds something was described as *not on the contact yet*, which hid the one
 * consequence the user needed before pressing: accepting adds a second value rather than
 * correcting the first.
 */
class ExistingValuesTest {

    private val stored = ContactRecord(
        resourceName = "people/c1",
        etag = "%e",
        urls = listOf("www.wrong.test"),
        addresses = listOf("Old Road, Delhi"),
    )

    @Test
    fun `an addition names what the field already holds`() {
        val card = CardDraft(urls = listOf("www.right.test"))
        val change = contactChanges(stored, card).single { it.field == ContactField.URL }
        assertEquals(null, change.stored, "an addition replaces nothing")
        assertEquals(listOf("www.wrong.test"), change.existing, "but the field is not empty")
    }

    @Test
    fun `an addition to a genuinely empty field names nothing`() {
        val empty = ContactRecord(resourceName = "people/c1", etag = "%e")
        val card = CardDraft(urls = listOf("www.right.test"))
        val change = contactChanges(empty, card).single()
        assertEquals(emptyList(), change.existing)
    }

    @Test
    fun `the address carries its existing values too`() {
        val card = CardDraft(addresses = listOf("New Road, Delhi"))
        val change = contactChanges(stored, card).single { it.field == ContactField.ADDRESS }
        assertEquals(listOf("Old Road, Delhi"), change.existing)
    }

    @Test
    fun `existing values are for the sentence on screen, not the write`() {
        // Asserted on a phone, because SRS 1.162 makes a *website* replace by default and this
        // test is about `existing` being display-only rather than about the mode. It failed when
        // that default changed, which is the test doing its job.
        val withPhone = stored.copy(phones = listOf("011 2000 0000" to "WORK"))
        val card = CardDraft(phones = listOf(CardPhone("+91 90000 00001", "MOBILE")))
        val change = contactChanges(withPhone, card).single()
        assertEquals(listOf("011 2000 0000"), change.existing, "named on screen")
        val update = mergedContactUpdate(withPhone, card, listOf(change))
        assertEquals(2, update.write.phones.size, "and the stored one still goes to Google")
    }
}

/**
 * SRS 1.162: replace or add, per field, flippable.
 *
 * **The two dangerous directions are opposite ones**, which is why a blanket rule was wrong.
 * Replacing a phone number destroys the office line a card does not print; adding a website
 * leaves two, one of them a misreading. Both are asserted here.
 */
class ReplaceOrAddTest {

    private val stored = ContactRecord(
        resourceName = "people/c1",
        etag = "%e",
        phones = listOf("011 2000 0000" to "WORK"),
        emails = listOf("old@acme.test" to "WORK"),
        addresses = listOf("Old Road, Delhi"),
        urls = listOf("www.wrong.test"),
    )

    @Test
    fun `a website the contact already has is replaced by default`() {
        val card = CardDraft(urls = listOf("www.right.test"))
        val change = contactChanges(stored, card).single()
        assertTrue(change.replaces, "one website is one website")
        val update = mergedContactUpdate(stored, card, acceptedChanges(listOf(change), setOf(0)))
        assertEquals(listOf("www.right.test"), update.write.urls, "the wrong one is gone")
    }

    @Test
    fun `an address the contact already has is replaced by default`() {
        val card = CardDraft(addresses = listOf("New Road, Delhi"))
        val change = contactChanges(stored, card).single()
        assertTrue(change.replaces)
    }

    @Test
    fun `a phone number is added, never replaced by default`() {
        // The case the old blanket rule was right about: replacing would destroy the office
        // line the card does not print.
        val card = CardDraft(phones = listOf(CardPhone("+91 90000 00001", "MOBILE")))
        val change = contactChanges(stored, card).single()
        assertFalse(change.replaces)
        val update = mergedContactUpdate(stored, card, acceptedChanges(listOf(change), setOf(0)))
        assertEquals(2, update.write.phones.size, "both numbers survive")
    }

    @Test
    fun `an email is added, never replaced by default`() {
        val card = CardDraft(emails = listOf(CardEmail("new@acme.test", "WORK")))
        assertFalse(contactChanges(stored, card).single().replaces)
    }

    @Test
    fun `several existing values always means add`() {
        // There is no answer to "which of these three would you replace", and guessing at one is
        // how an update loses something.
        val two = stored.copy(urls = listOf("www.a.test", "www.b.test"))
        val card = CardDraft(urls = listOf("www.c.test"))
        assertFalse(contactChanges(two, card).single().replaces)
    }

    @Test
    fun `the user can flip a default either way`() {
        val card = CardDraft(urls = listOf("www.right.test"))
        val change = contactChanges(stored, card).single()
        // Flipped off replace: both survive.
        val added = mergedContactUpdate(
            stored, card, acceptedChanges(listOf(change), setOf(0), flipped = setOf(0)),
        )
        assertEquals(listOf("www.wrong.test", "www.right.test"), added.write.urls)

        // And a phone flipped *to* replace drops the stored one, because the user said so.
        val phoneCard = CardDraft(phones = listOf(CardPhone("+91 90000 00001", "MOBILE")))
        val phoneChange = contactChanges(stored, phoneCard).single()
        val replaced = mergedContactUpdate(
            stored, phoneCard, acceptedChanges(listOf(phoneChange), setOf(0), flipped = setOf(0)),
        )
        assertEquals(1, replaced.write.phones.size)
    }

    @Test
    fun `a field with no accepted row is left exactly as it was`() {
        // The row was unticked, so the field is not in the mask and not in the body — and even
        // if it were, nothing about it may change.
        val card = CardDraft(urls = listOf("www.right.test"))
        val update = mergedContactUpdate(stored, card, emptyList())
        assertEquals(listOf("www.wrong.test"), update.write.urls)
        assertEquals(emptyList(), update.fields)
    }
}
