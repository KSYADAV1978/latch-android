package com.latch.android.cards

import com.latch.core.model.CardDraft
import com.latch.core.model.CardEmail
import com.latch.google.ContactDuplicateSearch
import com.latch.google.ContactWrite
import com.latch.google.ContactsApi
import com.latch.wire.KEY_CARD_IDENTITY
import com.latch.wire.KEY_CARD_SOURCE_HASH
import com.latch.wire.KEY_CARD_VERSION
import com.latch.wire.cardSourceHashOf
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * FR-1206, FR-1207 and FR-1208 at the seam where they meet.
 *
 * **The fake answers by matching, not by fixture.** CLAUDE.md records what the alternative cost:
 * `findEventBySourceHash` in the date-side fakes ignored its argument and returned a preset id, so
 * it agreed with a broken implementation for as long as that implementation was broken. This one
 * indexes on the hash the write actually carried.
 */
class CardSaverTest {

    private class FakeContacts : ContactsApi {
        /** Indexed by the source hash the created contact carried, which is the whole point. */
        val stored = mutableMapOf<String, ContactWrite>()
        val deleted = mutableListOf<String>()
        var scanCapped = false
        var failCreate = false
        var failSearch = false
        var searches = 0

        override suspend fun createContact(person: ContactWrite): String {
            if (failCreate) throw RuntimeException("create failed")
            val hash = person.clientData.first { it.first == KEY_CARD_SOURCE_HASH }.second
            stored[hash] = person
            return "people/c${stored.size}"
        }

        override suspend fun deleteContact(resourceName: String) {
            deleted += resourceName
        }

        override suspend fun findContactBySourceHash(sourceHash: String): ContactDuplicateSearch {
            searches++
            if (failSearch) throw RuntimeException("search failed")
            if (scanCapped) return ContactDuplicateSearch(scanCapped = true)
            val hit = stored.keys.firstOrNull { it == sourceHash } ?: return ContactDuplicateSearch()
            return ContactDuplicateSearch(existingResourceName = "people/found-$hit")
        }
    }

    private val payload = "BEGIN:VCARD\nVERSION:3.0\nFN:Anita Sharma\nEMAIL:anita@northwind.example\nEND:VCARD"
    private val draft = CardDraft(
        displayName = "Anita Sharma",
        givenName = "Anita",
        familyName = "Sharma",
        emails = listOf(CardEmail("anita@northwind.example", "WORK")),
    )

    private fun saver(api: ContactsApi) =
        CardSaver(api, now = { Instant.parse("2026-09-04T09:15:00Z") })

    // ---- the decision -------------------------------------------------------------------------

    @Test
    fun `a found duplicate stops the write`() {
        val decision = cardWriteDecision(ContactDuplicateSearch(existingResourceName = "people/c1"))
        assertIs<CardWriteDecision.AlreadySaved>(decision)
    }

    @Test
    fun `a capped scan writes, and records that it did not know`() {
        // SRS 5.8 forbids reading a capped scan as "no duplicate", which leaves writing or
        // refusing. Refusing loses the capture; writing costs at worst a duplicate contact,
        // which is visible and deleted in a gesture. The fact that it could not check travels
        // with the decision rather than being swallowed.
        val decision = cardWriteDecision(ContactDuplicateSearch(scanCapped = true))
        assertEquals(CardWriteDecision.Create(checked = false), decision)
    }

    @Test
    fun `a completed scan that found nothing writes, checked`() {
        assertEquals(CardWriteDecision.Create(checked = true), cardWriteDecision(ContactDuplicateSearch()))
    }

    // ---- the order ----------------------------------------------------------------------------

    @Test
    fun `the duplicate check runs before the write, every time`() = runTest {
        val api = FakeContacts()
        saver(api).save(draft, payload, "SHARED_IMAGE")
        assertEquals(1, api.searches)
        assertEquals(1, api.stored.size)
    }

    @Test
    fun `the same card twice writes once`() = runTest {
        // FR-1208. The fake matches on the hash the first write actually carried, so this fails
        // if the saver stores one hash and queries another.
        val api = FakeContacts()
        assertIs<CardSaveResult.Saved>(saver(api).save(draft, payload, "SHARED_IMAGE"))
        assertEquals(CardSaveResult.AlreadySaved, saver(api).save(draft, payload, "SHARED_IMAGE"))
        assertEquals(1, api.stored.size, "a second contact was written for one card")
    }

    @Test
    fun `a different card is not a duplicate`() = runTest {
        val api = FakeContacts()
        saver(api).save(draft, payload, "SHARED_IMAGE")
        val other = payload.replace("Anita Sharma", "Ravi Menon")
        assertIs<CardSaveResult.Saved>(saver(api).save(draft, other, "SHARED_IMAGE"))
        assertEquals(2, api.stored.size)
    }

    @Test
    fun `a search that throws does not become a write`() = runTest {
        // A check that failed is not a check that found nothing. Writing here would be FR-1208
        // skipped on the first flaky network — which is how the calendar's duplicates happened.
        val api = FakeContacts().apply { failSearch = true }
        assertEquals(CardSaveResult.Failed(permanent = false), saver(api).save(draft, payload, "X"))
        assertTrue(api.stored.isEmpty())
    }

    // ---- FR-1207's record ----------------------------------------------------------------------

    @Test
    fun `the written contact carries the source hash the check will look for`() = runTest {
        val api = FakeContacts()
        saver(api).save(draft, payload, "SHARED_IMAGE")
        val written = api.stored.values.single()
        assertEquals(
            cardSourceHashOf(payload),
            written.clientData.single { it.first == KEY_CARD_SOURCE_HASH }.second,
        )
        assertEquals("1", written.clientData.single { it.first == KEY_CARD_VERSION }.second)
    }

    @Test
    fun `the identity comes from the draft as confirmed, not from the payload`() = runTest {
        // FR-1209 with FR-1205: an address the user corrected is the identity, and one they
        // cleared is no identity at all. Taking it from the payload would key on what the card
        // said rather than on what the user agreed to.
        val api = FakeContacts()
        saver(api).save(draft.copy(emails = emptyList()), payload, "SHARED_IMAGE")
        val written = api.stored.values.single()
        assertTrue(
            written.clientData.none { it.first == KEY_CARD_IDENTITY },
            "an identity was written for a card whose address the user cleared",
        )
    }

    @Test
    fun `a card the user corrected writes the correction and not the parse`() = runTest {
        val api = FakeContacts()
        saver(api).save(draft.copy(jobTitle = "Director"), payload, "SHARED_IMAGE")
        assertEquals("Director", api.stored.values.single().jobTitle)
    }

    @Test
    fun `a blank field is omitted rather than sent empty`() = runTest {
        // An empty organizations entry puts a blank organisation on somebody's contact.
        val api = FakeContacts()
        saver(api).save(draft.copy(organisation = "   "), payload, "SHARED_IMAGE")
        assertEquals(null, api.stored.values.single().organisation)
    }

    // ---- failures -------------------------------------------------------------------------------

    @Test
    fun `a create that throws is reported and writes nothing`() = runTest {
        val api = FakeContacts().apply { failCreate = true }
        assertEquals(CardSaveResult.Failed(permanent = false), saver(api).save(draft, payload, "X"))
        assertTrue(api.stored.isEmpty())
    }

    @Test
    fun `a capped scan still writes, and the result says it was unchecked`() = runTest {
        val api = FakeContacts().apply { scanCapped = true }
        val result = saver(api).save(draft, payload, "SHARED_IMAGE")
        assertIs<CardSaveResult.Saved>(result)
        assertTrue(!result.checked, "a write made without an answer must say so")
        assertEquals(1, api.stored.size)
    }
}

/** FR-1212's half of the saver: a card that cannot be written is held, not lost. */
class CardSaverHoldTest {

    private class FailingContacts(private val alsoFailCreate: Boolean = false) : ContactsApi {
        var created = 0
        override suspend fun createContact(person: ContactWrite): String {
            if (alsoFailCreate) throw RuntimeException("create failed")
            created++
            return "people/c1"
        }
        override suspend fun deleteContact(resourceName: String) = Unit
        override suspend fun findContactBySourceHash(sourceHash: String): ContactDuplicateSearch =
            throw RuntimeException("offline")
    }

    private val draft = CardDraft(displayName = "Anita Sharma")
    private val payload = "BEGIN:VCARD\nVERSION:3.0\nFN:Anita Sharma\nEND:VCARD"

    @Test
    fun `a search that cannot run holds the card rather than losing it`() = runTest {
        // The offline case. Before FR-1212's store the same condition reported a failure and the
        // capture went with the sheet.
        var heldPayload: String? = null
        val saver = CardSaver(
            FailingContacts(),
            now = { Instant.parse("2026-09-04T09:15:00Z") },
            hold = { _, p, _, _ -> heldPayload = p; "entry-1" },
        )
        assertEquals(CardSaveResult.Held("entry-1"), saver.save(draft, payload, "SHARED_IMAGE"))
        assertEquals(payload, heldPayload, "the payload must be held, since FR-1208 hashes it")
    }

    @Test
    fun `a write that fails after a clean check is held too`() = runTest {
        // The network can go between the check and the insert. Reporting a failure here would
        // lose a capture that had already been confirmed.
        val contacts = object : ContactsApi {
            override suspend fun createContact(person: ContactWrite): String =
                throw RuntimeException("network went")
            override suspend fun deleteContact(resourceName: String) = Unit
            override suspend fun findContactBySourceHash(sourceHash: String) = ContactDuplicateSearch()
        }
        val saver = CardSaver(contacts, hold = { _, _, _, _ -> "entry-2" })
        assertEquals(CardSaveResult.Held("entry-2"), saver.save(draft, payload, "SHARED_IMAGE"))
    }

    @Test
    fun `a saver with no queue reports a failure rather than pretending to hold`() = runTest {
        // Accepting a card and never writing it is worse than declining it, so a saver without a
        // queue must not claim one.
        val saver = CardSaver(FailingContacts())
        assertEquals(CardSaveResult.Failed(permanent = false), saver.save(draft, payload, "X"))
    }

    @Test
    fun `a queue that itself fails is reported, not silently swallowed`() = runTest {
        val saver = CardSaver(FailingContacts(), hold = { _, _, _, _ -> throw RuntimeException("disk full") })
        assertEquals(CardSaveResult.Failed(permanent = false), saver.save(draft, payload, "X"))
    }

    @Test
    fun `holding writes nothing to the account`() = runTest {
        val contacts = FailingContacts()
        CardSaver(contacts, hold = { _, _, _, _ -> "entry-3" }).save(draft, payload, "X")
        assertEquals(0, contacts.created)
    }
}
