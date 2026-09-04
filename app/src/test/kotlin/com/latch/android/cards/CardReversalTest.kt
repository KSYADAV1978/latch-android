package com.latch.android.cards

import com.latch.google.ContactDuplicateSearch
import com.latch.google.ContactWrite
import com.latch.google.ContactsApi
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * FR-1210 and FR-1212's reversal.
 *
 * **The case that matters is undoing a *queued* card**, because that is where the two mechanisms
 * meet and where the wrong answer is invisible: a delete sent for a contact that was never
 * created succeeds trivially against a 404, the entry stays on disk, and the card arrives in the
 * account minutes later having been "undone".
 */
class CardReversalTest {

    private class FakeContacts : ContactsApi {
        val deleted = mutableListOf<String>()
        var deleteFails = false
        override suspend fun createContact(person: ContactWrite): String = "people/c1"
        override suspend fun deleteContact(resourceName: String) {
            if (deleteFails) throw RuntimeException("delete failed")
            deleted += resourceName
        }
        override suspend fun findContactBySourceHash(sourceHash: String) = ContactDuplicateSearch()
    }

    private val saved = Instant.parse("2026-09-04T09:15:00Z")

    // ---- the window ---------------------------------------------------------------------------

    @Test
    fun `the offer stands for ten seconds and then does not`() {
        assertTrue(cardUndoOffered(saved, saved))
        assertTrue(cardUndoOffered(saved, saved.plusSeconds(9)))
        assertFalse(cardUndoOffered(saved, saved.plusSeconds(10)))
        assertFalse(cardUndoOffered(saved, saved.plusSeconds(600)))
    }

    @Test
    fun `a clock that has gone backwards does not open the window for ever`() {
        // A manual time change during the ten seconds. Treating a negative elapsed time as "well
        // inside the window" would leave an undo offered indefinitely.
        assertFalse(cardUndoOffered(saved, saved.minusSeconds(30)))
    }

    @Test
    fun `the countdown reaches zero rather than going negative`() {
        assertEquals(10, cardUndoSecondsLeft(saved, saved))
        assertEquals(1, cardUndoSecondsLeft(saved, saved.plusSeconds(9)))
        assertEquals(0, cardUndoSecondsLeft(saved, saved.plusSeconds(10)))
        assertEquals(0, cardUndoSecondsLeft(saved, saved.plusSeconds(99)))
    }

    // ---- what undo does, chosen by what the save did -------------------------------------------

    @Test
    fun `undoing a written contact deletes it`() = runTest {
        val api = FakeContacts()
        val outcome = undoCardCreated(CardCreated.Written("people/c1"), api) { true }
        assertEquals(CardRemoval(1, 1), outcome)
        assertEquals(listOf("people/c1"), api.deleted)
    }

    @Test
    fun `undoing a queued card drops the entry and deletes nothing`() = runTest {
        // The case with teeth. A delete here would ask Google to remove a contact that was never
        // created — succeeding trivially against a 404 while the entry stayed on disk and the
        // card arrived minutes later, having been "undone".
        val api = FakeContacts()
        var droppedId: String? = null
        val outcome = undoCardCreated(CardCreated.Queued("entry-7"), api) { id ->
            droppedId = id
            true
        }
        assertEquals(CardRemoval(1, 1), outcome)
        assertEquals("entry-7", droppedId)
        assertTrue(api.deleted.isEmpty(), "a queued card was undone by deleting from the account")
    }

    @Test
    fun `a delete that fails is reported rather than swallowed`() = runTest {
        val api = FakeContacts().apply { deleteFails = true }
        val outcome = undoCardCreated(CardCreated.Written("people/c1"), api) { true }
        assertEquals(CardRemoval(1, 0), outcome)
        assertFalse(outcome.complete)
    }

    @Test
    fun `an entry that could not be dropped is reported too`() = runTest {
        val outcome = undoCardCreated(CardCreated.Queued("entry-7"), FakeContacts()) { false }
        assertEquals(CardRemoval(1, 0), outcome)
    }

    // ---- FR-1212: the drain must not race the undo ---------------------------------------------

    @Test
    fun `a queued card is not drained inside its undo window`() {
        // `drainable`'s rule on a third transport. A drain landing at nine seconds turns
        // CardCreated.Queued into a lie: the entry is gone, the contact is in the account, and
        // undo removes neither.
        assertFalse(cardDrainable(saved, saved))
        assertFalse(cardDrainable(saved, saved.plusSeconds(9)))
        assertTrue(cardDrainable(saved, saved.plusSeconds(10)))
    }

    @Test
    fun `the window and the drain rule are the same window`() {
        // Two constants would drift, and the gap between them would be a race nobody could see.
        val custom = Duration.ofSeconds(30)
        assertTrue(cardUndoOffered(saved, saved.plusSeconds(20), custom))
        assertFalse(cardDrainable(saved, saved.plusSeconds(20), custom))
    }
}
