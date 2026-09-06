package com.latch.android.cards

import com.latch.core.model.CardDraft
import com.latch.core.model.CardEmail
import com.latch.core.model.CardPhone
import com.latch.google.ContactDuplicateSearch
import com.latch.google.ContactRecord
import com.latch.google.ContactUpdate
import com.latch.google.ContactWrite
import com.latch.wire.KEY_CARD_SOURCE_HASH
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * FR-1231 and FR-1232 as decisions.
 *
 * **The two things that must not happen are what most of this file is about**: a contact patched
 * without an offer, and an undo that deletes. Both are asserted structurally where the type system
 * can be made to carry them and by test where it cannot.
 */
class CardUpdateDecisionTest {

    private val stored = ContactRecord(
        resourceName = "people/c1",
        etag = "%etag1",
        displayName = "Anita Kapoor",
        organisation = "Northwind Textiles",
        jobTitle = "Partner",
        emails = listOf("anita@northwind.in" to "WORK"),
    )

    private val draft = CardDraft(
        displayName = "Anita Kapoor",
        organisation = "Northwind Textiles",
        jobTitle = "Managing Partner",
        emails = listOf(CardEmail("anita@northwind.in", "WORK")),
    )

    @Test
    fun `the exact hash outranks the identity`() {
        // FR-1208's answer is the strong one and it ends the question. An offer to change a card
        // that is already saved would be an offer about a difference that is not there.
        val decision = cardWriteDecision(
            ContactDuplicateSearch(existingResourceName = "people/exact"),
            draft,
            stored,
        )
        val already = assertIs<CardWriteDecision.AlreadySaved>(decision)
        assertEquals(CardWriteBasis.SOURCE_HASH, already.basis)
    }

    @Test
    fun `an identity match with a difference is offered, never taken`() {
        val decision = cardWriteDecision(
            ContactDuplicateSearch(identityResourceName = "people/c1"),
            draft,
            stored,
        )
        val offer = assertIs<CardWriteDecision.UpdateOffered>(decision)
        assertEquals(1, offer.changes.size)
        assertEquals("Partner", offer.changes.single().stored)
    }

    @Test
    fun `an identity match with nothing to change is already saved, and says which row`() {
        val decision = cardWriteDecision(
            ContactDuplicateSearch(identityResourceName = "people/c1"),
            draft.copy(jobTitle = "Partner"),
            stored,
        )
        val already = assertIs<CardWriteDecision.AlreadySaved>(decision)
        // SRS 1.72's lesson: two rows reach one sentence on the screen, and from outside the
        // phone they were indistinguishable on the date pillar for two device sessions.
        assertEquals(CardWriteBasis.IDENTITY_NO_CHANGE, already.basis)
    }

    @Test
    fun `an identity found on a capped scan is still an identity`() {
        // A scan that gave up has not answered about the pages it did not reach. It has answered
        // perfectly well about the ones it did.
        val decision = cardWriteDecision(
            ContactDuplicateSearch(scanCapped = true, identityResourceName = "people/c1"),
            draft,
            stored,
        )
        assertIs<CardWriteDecision.UpdateOffered>(decision)
    }

    @Test
    fun `a capped scan with no identity writes and says it could not check`() {
        val decision = cardWriteDecision(ContactDuplicateSearch(scanCapped = true), draft)
        assertFalse(assertIs<CardWriteDecision.Create>(decision).checked)
    }

    @Test
    fun `no match at all is an ordinary create`() {
        val decision = cardWriteDecision(ContactDuplicateSearch(), draft)
        assertTrue(assertIs<CardWriteDecision.Create>(decision).checked)
    }
}

/** FR-1231's write and FR-1232's reversal, against a fake that records rather than answers. */
class CardUpdateSaverTest {

    @Test
    fun `an identity match offers and writes nothing`() = runTest {
        val contacts = UpdatingContacts()
        val result = CardSaver(contacts).save(draft, payload, "SHARE_SHEET")

        assertIs<CardSaveResult.UpdateOffered>(result)
        // The requirement's own promise: nothing is written while the offer stands.
        assertEquals(0, contacts.created)
        assertEquals(0, contacts.updates.size)
    }

    @Test
    fun `accepting the offer patches and carries the prior record`() = runTest {
        val contacts = UpdatingContacts()
        val saver = CardSaver(contacts)
        val offer = assertIs<CardSaveResult.UpdateOffered>(saver.save(draft, payload, "SHARE_SHEET"))

        val updated = assertIs<CardSaveResult.Updated>(
            saver.update(offer.stored, draft, offer.changes)
        )
        assertEquals(1, contacts.updates.size)
        assertEquals(0, contacts.created)
        // FR-1232: after the patch these values exist nowhere else, so the result carries them.
        assertEquals("Partner", updated.prior.jobTitle)
        // And the etag the *patch* returned, because the stored one is now stale and an update
        // without a current etag is refused with a 400 that names nothing.
        assertEquals("%etag2", updated.etag)
    }

    @Test
    fun `an update never sends the FR-1207 record`() = runTest {
        val contacts = UpdatingContacts()
        val saver = CardSaver(contacts)
        val offer = assertIs<CardSaveResult.UpdateOffered>(saver.save(draft, payload, "SHARE_SHEET"))
        saver.update(offer.stored, draft, offer.changes)

        val sent = contacts.updates.single()
        assertEquals(emptyList(), sent.write.clientData)
        assertFalse("clientData" in sent.fields)
    }

    @Test
    fun `declining the offer creates, without asking again`() = runTest {
        val contacts = UpdatingContacts()
        val saver = CardSaver(contacts)
        saver.save(draft, payload, "SHARE_SHEET")
        val searchesAfterOffer = contacts.searches

        assertIs<CardSaveResult.Saved>(saver.createAnyway(draft, payload, "SHARE_SHEET"))
        assertEquals(1, contacts.created)
        // Asking twice would either repeat the offer the user has just rejected or produce a
        // different one from a scan that raced an edit.
        assertEquals(searchesAfterOffer, contacts.searches)
    }

    @Test
    fun `undo of an update restores and deletes nothing`() = runTest {
        val contacts = UpdatingContacts()
        val saver = CardSaver(contacts)
        val offer = assertIs<CardSaveResult.UpdateOffered>(saver.save(draft, payload, "SHARE_SHEET"))
        val updated = assertIs<CardSaveResult.Updated>(saver.update(offer.stored, draft, offer.changes))

        val removal = undoCardCreated(
            created = CardCreated.Updated(
                updated.resourceName, updated.prior, updated.etag, updated.fields,
            ),
            contacts = contacts,
        ) { false }

        assertTrue(removal.complete)
        // FR-1232's own sentence: it shall never delete the contact.
        assertEquals(emptyList(), contacts.deleted)
        // Two writes: the patch, then the restore putting the old title back.
        assertEquals(2, contacts.updates.size)
        assertEquals("Partner", contacts.updates.last().write.jobTitle)
        assertEquals(offer.stored.etag, contacts.updates.first().etag)
        assertEquals("%etag2", contacts.updates.last().etag)
    }

    @Test
    fun `the undo sentence distinguishes a restore from a removal`() {
        assertTrue(cardUndoRestores(CardCreated.Updated("people/c1", record, "%e", listOf("organizations"))))
        assertFalse(cardUndoRestores(CardCreated.Written("people/c1")))
        assertFalse(cardUndoRestores(CardCreated.Queued("entry-1")))
    }

    private val record = ContactRecord(resourceName = "people/c1", etag = "%etag1")

    private val payload =
        "BEGIN:VCARD\nVERSION:3.0\nFN:Anita Kapoor\nEMAIL:anita@northwind.in\nEND:VCARD"

    private val draft = CardDraft(
        displayName = "Anita Kapoor",
        organisation = "Northwind Textiles",
        jobTitle = "Managing Partner",
        emails = listOf(CardEmail("anita@northwind.in", "WORK")),
        phones = listOf(CardPhone("+91 98765 43210", "MOBILE")),
    )

    /**
     * A fake that answers by **matching**, not by fixture.
     *
     * CLAUDE.md records why that distinction is load-bearing: `findEventBySourceHash` in the
     * fakes ignored its `sourceHash` argument and returned a preset id, so it could not have
     * caught the matching bug it sat beside for five days.
     */
    private class UpdatingContacts : ContactsApiFake() {
        var created = 0
        var searches = 0
        val deleted = mutableListOf<String>()
        val updates = mutableListOf<SentUpdate>()

        private var record = ContactRecord(
            resourceName = "people/c1",
            etag = "%etag1",
            displayName = "Anita Kapoor",
            organisation = "Northwind Textiles",
            jobTitle = "Partner",
            emails = listOf("anita@northwind.in" to "WORK"),
        )

        override suspend fun createContact(person: ContactWrite): String {
            created++
            // Asserted on elsewhere; kept so a create is distinguishable from an update.
            person.clientData.first { it.first == KEY_CARD_SOURCE_HASH }
            return "people/new"
        }

        override suspend fun deleteContact(resourceName: String) {
            deleted += resourceName
        }

        override suspend fun updateContactPhoto(resourceName: String, jpeg: ByteArray) = Unit

        override suspend fun findContactBySourceHash(
            sourceHash: String,
            personKeys: List<String>,
            identityKeys: List<String>,
        ): ContactDuplicateSearch {
            searches++
            // Derived from the contact's own addresses, exactly as the real scan derives it
            // for a contact Latch never created — which is FR-1231's whole case.
            val mine = record.emails.map {
                com.latch.wire.identityKeyOf(com.latch.wire.normaliseEmail(it.first))
            }
            val identity = if (identityKeys.any { it in mine }) record.resourceName else null
            return ContactDuplicateSearch(identityResourceName = identity)
        }

        override suspend fun getContact(resourceName: String): ContactRecord = record

        override suspend fun updateContact(
            resourceName: String,
            etag: String,
            update: ContactUpdate,
        ): String {
            updates += SentUpdate(resourceName, etag, update.write, update.fields)
            record = record.copy(etag = "%etag2", jobTitle = update.write.jobTitle)
            return "%etag2"
        }
    }

    private data class SentUpdate(
        val resourceName: String,
        val etag: String,
        val write: ContactWrite,
        val fields: List<String>,
    )
}
