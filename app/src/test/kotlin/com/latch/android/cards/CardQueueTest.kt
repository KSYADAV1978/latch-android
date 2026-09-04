package com.latch.android.cards

import com.latch.core.model.CardDraft
import com.latch.core.model.CardEmail
import com.latch.data.CardQueue
import com.latch.data.HeldCard
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

/** FR-1212's loop: what a drain over several held cards does. */
class HeldCardDrainTest {

    private class FakeQueue(initial: List<HeldCard>) : CardQueue {
        val entries = initial.toMutableList()
        override suspend fun enqueue(entry: HeldCard): String {
            entries += entry; return entry.id
        }
        override suspend fun pending(): List<HeldCard> = entries.sortedBy { it.queuedAt }
        override suspend fun drop(id: String) = entries.removeIf { it.id == id }
        override suspend fun retire(id: String) = entries.removeIf { it.id == id }
        override suspend fun markAttempted(id: String): Boolean {
            val i = entries.indexOfFirst { it.id == id }
            if (i < 0) return false
            entries[i] = entries[i].copy(attempts = entries[i].attempts + 1)
            return true
        }
    }

    private class Contacts(val failFor: Set<String> = emptySet()) : ContactsApi {
        val written = mutableListOf<String>()
        override suspend fun createContact(person: ContactWrite): String {
            val name = person.displayName.orEmpty()
            if (name in failFor) throw RuntimeException("no")
            written += name
            return "people/${written.size}"
        }
        override suspend fun deleteContact(resourceName: String) = Unit
        override suspend fun findContactBySourceHash(sourceHash: String) = ContactDuplicateSearch()
    }

    private val long_ago = Instant.parse("2026-09-04T09:00:00Z")
    private val now = Instant.parse("2026-09-04T10:00:00Z")

    private fun card(id: String, name: String, at: Instant = long_ago, attempts: Int = 0) = HeldCard(
        id = id,
        payload = "BEGIN:VCARD\nVERSION:3.0\nFN:$name\nEND:VCARD",
        draft = CardDraft(displayName = name),
        layer = "SHARED_IMAGE",
        queuedAt = at,
        attempts = attempts,
    )

    @Test
    fun `every ready entry is written and retired`() = runTest {
        val queue = FakeQueue(listOf(card("a", "Anita"), card("b", "Ravi")))
        val contacts = Contacts()
        val report = drainHeldCards(queue, contacts, now)
        assertEquals(CardDrainReport(written = 2, retired = 0, kept = 0), report)
        assertTrue(queue.entries.isEmpty())
        assertEquals(listOf("Anita", "Ravi"), contacts.written)
    }

    @Test
    fun `one failure does not hold up the entries behind it`() = runTest {
        // A queue that stopped at the first failure would let one bad entry block everything,
        // which is the shape SRS 1.24's per-item marker prevents on the date side.
        val queue = FakeQueue(listOf(card("a", "Anita"), card("b", "Ravi"), card("c", "Meera")))
        val contacts = Contacts(failFor = setOf("Ravi"))
        val report = drainHeldCards(queue, contacts, now)
        assertEquals(2, report.written)
        assertEquals(1, report.kept)
        assertEquals(listOf("Anita", "Meera"), contacts.written)
        assertEquals(listOf("b"), queue.entries.map { it.id }, "the failed entry was lost")
    }

    @Test
    fun `a kept entry has its attempt counted`() = runTest {
        val queue = FakeQueue(listOf(card("a", "Anita")))
        drainHeldCards(queue, Contacts(failFor = setOf("Anita")), now)
        assertEquals(1, queue.entries.single().attempts)
    }

    @Test
    fun `an entry inside its undo window is left alone and not counted as an attempt`() = runTest {
        // FR-1210 before FR-1212. Counting an attempt here would burn the limit on entries that
        // were never tried.
        val queue = FakeQueue(listOf(card("a", "Anita", at = now)))
        val report = drainHeldCards(queue, Contacts(), now.plusSeconds(2))
        assertEquals(1, report.kept)
        assertEquals(1, queue.entries.single().attempts, "an untried entry should not consume the limit")
    }

    @Test
    fun `an entry that has run out of attempts is kept, never deleted`() = runTest {
        // It holds a capture that exists nowhere else. The write queue's own rule is that a
        // given-up entry stays and can be revived.
        val queue = FakeQueue(listOf(card("a", "Anita", attempts = CARD_ATTEMPT_LIMIT)))
        val contacts = Contacts()
        val report = drainHeldCards(queue, contacts, now)
        assertEquals(1, report.kept)
        assertTrue(contacts.written.isEmpty(), "a given-up entry was written anyway")
        assertEquals(1, queue.entries.size, "a given-up entry was deleted")
    }

    @Test
    fun `an empty queue is not an error`() = runTest {
        assertEquals(CardDrainReport(0, 0, 0), drainHeldCards(FakeQueue(emptyList()), Contacts(), now))
    }
}
