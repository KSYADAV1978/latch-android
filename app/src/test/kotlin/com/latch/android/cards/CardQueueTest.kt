package com.latch.android.cards

import com.latch.core.model.CardDraft
import com.latch.core.model.CardEmail
import com.latch.google.ContactDuplicateSearch
import com.latch.google.ContactWrite
import com.latch.google.ContactsApi
import com.latch.wire.KEY_CARD_CAPTURED_AT
import com.latch.wire.KEY_CARD_SOURCE_HASH
import com.latch.wire.cardSourceHashOf
import com.latch.wire.contactIdentityKeys
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * FR-1212's drain.
 *
 * **This file exists because the calendar's equivalent did not.** SRS 1.37: the drain's FR-803
 * check was private to a `CoroutineWorker` needing a `Context`, so all five drain tests covered
 * the pure scheduling function beside it and none covered the check itself — which was the
 * load-bearing one, and which wrote duplicates into a real calendar for five days. `drainCard`
 * takes its dependencies as parameters so that this file can exist at all.
 */
class CardQueueTest {

    private class FakeContacts : ContactsApi {
        val stored = mutableMapOf<String, ContactWrite>()
        var searchFails = false
        var createFails = false
        var searches = 0

        override suspend fun createContact(person: ContactWrite): String {
            if (createFails) throw RuntimeException("create failed")
            val hash = person.clientData.first { it.first == KEY_CARD_SOURCE_HASH }.second
            stored[hash] = person
            return "people/c${stored.size}"
        }

        override suspend fun deleteContact(resourceName: String) = Unit

        override suspend fun findContactBySourceHash(sourceHash: String): ContactDuplicateSearch {
            searches++
            if (searchFails) throw RuntimeException("search failed")
            return if (stored.containsKey(sourceHash)) {
                ContactDuplicateSearch(existingResourceName = "people/found")
            } else {
                ContactDuplicateSearch()
            }
        }
    }

    private val payload = "BEGIN:VCARD\nVERSION:3.0\nFN:Anita Sharma\nEMAIL:anita@northwind.example\nEND:VCARD"
    private val queuedAt = Instant.parse("2026-09-04T09:15:00Z")
    private val entry = QueuedCard(
        id = "entry-1",
        draft = CardDraft(displayName = "Anita Sharma", emails = listOf(CardEmail("anita@northwind.example"))),
        payload = payload,
        layer = "SHARED_IMAGE",
        queuedAt = queuedAt,
    )

    private suspend fun drain(api: ContactsApi, now: Instant) = drainCard(
        entry = entry,
        now = now,
        contacts = api,
        sourceHashOf = ::cardSourceHashOf,
        identityKeysOf = ::contactIdentityKeys,
    )

    @Test
    fun `an entry inside its undo window is not drained`() = runTest {
        // FR-1210 before FR-1212: writing here would turn CardCreated.Queued into a lie.
        val api = FakeContacts()
        assertEquals(CardDrainOutcome.KEPT, drain(api, queuedAt.plusSeconds(5)))
        assertEquals(0, api.searches, "the check ran for an entry that must not be written yet")
        assertTrue(api.stored.isEmpty())
    }

    @Test
    fun `an entry past its window is written`() = runTest {
        val api = FakeContacts()
        assertEquals(CardDrainOutcome.WRITTEN, drain(api, queuedAt.plusSeconds(30)))
        assertEquals(1, api.stored.size)
    }

    @Test
    fun `the check runs again at drain, and retires an entry already saved`() = runTest {
        // Without this, two offline captures of one card become two contacts — AC-07 failing
        // inside AC-10, on a third transport.
        val api = FakeContacts()
        drain(api, queuedAt.plusSeconds(30))
        assertEquals(CardDrainOutcome.RETIRED, drain(api, queuedAt.plusSeconds(60)))
        assertEquals(1, api.stored.size, "the drain wrote a second copy of one card")
    }

    @Test
    fun `a search that throws keeps the entry rather than writing blind`() = runTest {
        val api = FakeContacts().apply { searchFails = true }
        assertEquals(CardDrainOutcome.KEPT, drain(api, queuedAt.plusSeconds(30)))
        assertTrue(api.stored.isEmpty())
    }

    @Test
    fun `a create that throws keeps the entry, so the capture is not lost`() = runTest {
        // NFR-302: a capture that exists nowhere else must survive a failed attempt.
        val api = FakeContacts().apply { createFails = true }
        assertEquals(CardDrainOutcome.KEPT, drain(api, queuedAt.plusSeconds(30)))
    }

    @Test
    fun `the record carries the instant the card was captured, not the instant it drained`() = runTest {
        // FR-1207's record is provenance: "when did I get this card" is the question it answers,
        // and a drain two days later must not rewrite the answer to the day of the drain.
        val api = FakeContacts()
        drain(api, queuedAt.plusSeconds(200_000))
        val written = api.stored.values.single()
        assertEquals(
            "2026-09-04T09:15:00Z",
            written.clientData.single { it.first == KEY_CARD_CAPTURED_AT }.second,
        )
    }

    @Test
    fun `the drained contact carries the hash the check will look for`() = runTest {
        val api = FakeContacts()
        drain(api, queuedAt.plusSeconds(30))
        assertEquals(
            cardSourceHashOf(payload),
            api.stored.values.single().clientData.single { it.first == KEY_CARD_SOURCE_HASH }.second,
        )
    }
}
