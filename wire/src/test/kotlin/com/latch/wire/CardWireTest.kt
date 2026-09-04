package com.latch.wire

import com.latch.core.model.CardDraft
import com.latch.core.model.CardEmail
import com.latch.core.model.CardPhone
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FR-1207 and FR-1209.
 *
 * **The load-bearing test in this file is the switchboard one**, and it is written first for that
 * reason. Every other case here fails visibly; that one fails by merging two people's contact
 * details together, which the user discovers by telephoning the wrong person.
 */
class CardWireTest {

    private fun card(vararg emails: String, phone: String? = null) = CardDraft(
        displayName = "Someone",
        emails = emails.map { CardEmail(it) },
        phones = listOfNotNull(phone).map { CardPhone(it) },
    )

    // ---- FR-1209: identity ------------------------------------------------------------------

    @Test
    fun `two colleagues sharing a switchboard number are two people`() {
        // The failure this requirement exists to prevent. Identity by telephone would fold these
        // into one contact, silently overwriting one person's details with the other's.
        val anita = card("anita@northwind.example", phone = "+91 22 2345 6789")
        val ravi = card("ravi@northwind.example", phone = "+91 22 2345 6789")

        assertFalse(
            sharesContactIdentity(contactIdentityKeys(anita), contactIdentityKeys(ravi)),
            "a shared switchboard number must not make two colleagues one contact",
        )
    }

    @Test
    fun `a telephone number contributes nothing to identity at all`() {
        // Not merely "not sufficient": absent. Two cards for the same person, one carrying a
        // number and one not, must key identically.
        val withPhone = card("meera@example.com", phone = "+91 90000 11111")
        val without = card("meera@example.com")
        assertEquals(contactIdentityKeys(withPhone), contactIdentityKeys(without))
    }

    @Test
    fun `case and surrounding whitespace do not make a second person`() {
        assertEquals(
            contactIdentityKeys(card("Anita.Sharma@Example.COM")),
            contactIdentityKeys(card("  anita.sharma@example.com  ")),
        )
    }

    @Test
    fun `provider-specific rules are deliberately not applied`() {
        // Dot-stripping is right for Gmail and wrong for every mail server that treats dots as
        // significant — and applying it there merges two colleagues at that company, which is
        // exactly the harm FR-1209 is written against.
        assertNotEquals(
            contactIdentityKeys(card("a.b@northwind.example")),
            contactIdentityKeys(card("ab@northwind.example")),
        )
        assertNotEquals(
            contactIdentityKeys(card("ravi+cards@example.com")),
            contactIdentityKeys(card("ravi@example.com")),
        )
    }

    @Test
    fun `a card with no email has no identity, and two such cards do not match`() {
        // The safe direction: no identity means always written as new, so the worst outcome is a
        // duplicate. Treating "neither has an email" as agreement would merge every emailless
        // card in the account into one contact.
        val one = card(phone = "+91 22 2345 6789")
        val two = card(phone = "+1 555 0100")
        assertTrue(contactIdentityKeys(one).isEmpty())
        assertFalse(sharesContactIdentity(contactIdentityKeys(one), contactIdentityKeys(two)))
        assertFalse(sharesContactIdentity(contactIdentityKeys(one), contactIdentityKeys(one)))
    }

    @Test
    fun `a card with several addresses matches on any of them`() {
        // People change employer and keep a personal address; a card carrying both should match
        // an existing contact known by either.
        val both = card("ravi@northwind.example", "ravi.menon@personal.example")
        val personalOnly = card("ravi.menon@personal.example")
        assertEquals(2, contactIdentityKeys(both).size)
        assertTrue(sharesContactIdentity(contactIdentityKeys(both), contactIdentityKeys(personalOnly)))
    }

    @Test
    fun `a repeated address is one identity, not two`() {
        assertEquals(1, contactIdentityKeys(card("x@example.com", "X@EXAMPLE.COM")).size)
    }

    @Test
    fun `an identity key is a digest and never the address`() {
        // An email is a third party's personal data. FR-1211's reasoning applies to the
        // bookkeeping as much as to the image.
        val key = contactIdentityKeys(card("anita@northwind.example")).single()
        assertFalse("anita" in key, key)
        assertFalse("northwind" in key, key)
        assertEquals(64, key.length)
    }

    // ---- FR-1208: the source hash ------------------------------------------------------------

    @Test
    fun `the same payload hashes the same way twice`() {
        val payload = "BEGIN:VCARD\nVERSION:3.0\nFN:Anita Sharma\nEND:VCARD"
        assertEquals(cardSourceHashOf(payload), cardSourceHashOf(payload))
    }

    @Test
    fun `line endings and trailing whitespace do not change the hash`() {
        // A QR decoder is entitled to give CRLF where another gives LF.
        assertEquals(
            cardSourceHashOf("BEGIN:VCARD\nFN:X\nEND:VCARD"),
            cardSourceHashOf("BEGIN:VCARD\r\nFN:X\r\nEND:VCARD  "),
        )
    }

    @Test
    fun `a different card is a different hash`() {
        assertNotEquals(
            cardSourceHashOf("BEGIN:VCARD\nFN:Anita\nEND:VCARD"),
            cardSourceHashOf("BEGIN:VCARD\nFN:Ravi\nEND:VCARD"),
        )
    }

    // ---- FR-1207: the record ------------------------------------------------------------------

    private val metadata = CardMetadata(
        sourceHash = "a".repeat(64),
        identityKeys = listOf("b".repeat(64), "c".repeat(64)),
        capturedAt = Instant.parse("2026-09-04T09:15:00Z"),
        captureLayer = "SHARED_IMAGE",
    )

    @Test
    fun `the record round-trips through clientData`() {
        assertEquals(metadata, cardMetadataFromClientData(metadata.toClientData()))
    }

    @Test
    fun `several identities become several entries, which the API permits`() {
        val entries = metadata.toClientData().filter { it.first == KEY_CARD_IDENTITY }
        assertEquals(2, entries.size)
    }

    @Test
    fun `a record with no identity round-trips as one with no identity`() {
        val none = metadata.copy(identityKeys = emptyList())
        assertEquals(none, cardMetadataFromClientData(none.toClientData()))
    }

    @Test
    fun `the captured instant is UTC seconds, so two clients cannot disagree`() {
        val stamp = metadata.toClientData().single { it.first == KEY_CARD_CAPTURED_AT }.second
        assertEquals("2026-09-04T09:15:00Z", stamp)
    }

    @Test
    fun `a version this build does not know is left alone rather than reinterpreted`() {
        // A contact is in somebody's account and cannot be re-written on a guess — which is why
        // this returns null instead of doing its best with the fields it recognises.
        val future = metadata.toClientData().map {
            if (it.first == KEY_CARD_VERSION) it.first to "99" else it
        }
        assertNull(cardMetadataFromClientData(future))
    }

    @Test
    fun `a record with no version at all is not ours`() {
        // Somebody else's clientData, on a contact Latch never wrote.
        assertNull(cardMetadataFromClientData(listOf("other.app.key" to "value")))
    }

    @Test
    fun `a record missing its hash is refused rather than half-read`() {
        val broken = metadata.toClientData().filterNot { it.first == KEY_CARD_SOURCE_HASH }
        assertNull(cardMetadataFromClientData(broken))
    }
}
