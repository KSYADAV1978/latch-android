package com.latch.data

import com.latch.core.model.CardDraft
import com.latch.core.model.CardEmail
import com.latch.core.model.CardPhone
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * FR-1212's record.
 *
 * The store itself is `SharedPreferences` and the Keystore, both throwing stubs under JVM unit
 * tests — the property that hid `decodeBitmap` for a slice — so what is tested here is the format,
 * and the storage belongs in the instrumented suite. That split is the one `EncryptedWriteQueueStore`
 * already has.
 */
class HeldCardRecordTest {

    private val held = HeldCard(
        id = "entry-1",
        payload = "BEGIN:VCARD\nVERSION:3.0\nFN:Anita Sharma\nEND:VCARD",
        draft = CardDraft(
            displayName = "Anita Sharma",
            givenName = "Anita",
            familyName = "Sharma",
            organisation = "Northwind Textiles",
            jobTitle = "Head of Sourcing",
            phones = listOf(CardPhone("+91 98200 12345", "CELL"), CardPhone("+91 22 2345 6789", null)),
            emails = listOf(CardEmail("anita@northwind.example", "WORK")),
            addresses = listOf("12 Nehru Road, Pune"),
            urls = listOf("https://northwind.example"),
            note = "met at the fair",
        ),
        layer = "SHARED_IMAGE",
        queuedAt = Instant.parse("2026-09-04T09:15:00Z"),
        attempts = 2,
    )

    @Test
    fun `an entry survives the round trip whole`() {
        assertEquals(held, decodeHeldCard(encodeHeldCard(held)))
    }

    @Test
    fun `the confirmed draft survives, not the parse`() {
        // FR-1205 through FR-1212: a card corrected before saving must drain with the correction.
        // Holding only the payload would lose every edit the moment the network did.
        val corrected = held.copy(draft = held.draft.copy(jobTitle = "Director of Sourcing"))
        assertEquals("Director of Sourcing", decodeHeldCard(encodeHeldCard(corrected))?.draft?.jobTitle)
    }

    @Test
    fun `the payload survives beside the draft, because the hash comes from it`() {
        // Two different questions with two different answers: what to write, and whether it is
        // already there.
        assertEquals(held.payload, decodeHeldCard(encodeHeldCard(held))?.payload)
    }

    @Test
    fun `a phone with no type stays a phone with no type`() {
        val decoded = decodeHeldCard(encodeHeldCard(held))!!
        assertEquals("CELL", decoded.draft.phones[0].type)
        assertNull(decoded.draft.phones[1].type, "an absent type came back as something")
    }

    @Test
    fun `an empty draft round-trips as an empty draft`() {
        val bare = held.copy(draft = CardDraft(displayName = "X"))
        val decoded = decodeHeldCard(encodeHeldCard(bare))!!
        assertEquals(emptyList(), decoded.draft.phones)
        assertEquals(emptyList(), decoded.draft.emails)
        assertNull(decoded.draft.note)
    }

    @Test
    fun `a record from a version this build does not know is refused`() {
        // Not reinterpreted. An entry holds a capture that exists nowhere else, and a build that
        // guessed at a future format could write something the user never confirmed.
        val future = encodeHeldCard(held).replace("\"v\":1", "\"v\":99")
        assertNull(decodeHeldCard(future))
    }

    @Test
    fun `a truncated record is refused rather than half-read`() {
        assertNull(decodeHeldCard("{\"v\":1,\"id\":\"x\"}"))
        assertNull(decodeHeldCard("not json at all"))
    }

    @Test
    fun `text with newlines and quotes survives, which is why this is JSON`() {
        // A vCard payload is full of CRLFs and a note is free user text. The unit-separated
        // format the preferences store uses could not carry either.
        val awkward = held.copy(
            payload = "BEGIN:VCARD\r\nNOTE:He said \"hello\"; then left\r\nEND:VCARD",
            draft = held.draft.copy(note = "line one\nline two\ttabbed"),
        )
        assertEquals(awkward, decodeHeldCard(encodeHeldCard(awkward)))
    }
}
