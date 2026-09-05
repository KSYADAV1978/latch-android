package com.latch.wire

import com.latch.core.model.CardDraft
import com.latch.core.model.CardEmail
import com.latch.core.model.CardPhone
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * FR-1227's inexact key.
 *
 * **What is being tested is mostly where it refuses to exist**, because that is where the harm
 * lives. A key that is present when it should not be produces a warning about the wrong person;
 * a key that is absent produces silence, which is the requirement's own answer for the cases it
 * cannot judge.
 */
class CardPersonKeyTest {

    private fun card(
        name: String? = "Arvind Bhandari",
        phones: List<CardPhone> = listOf(CardPhone("+91 70009 00582", "MOBILE")),
    ) = CardDraft(displayName = name, phones = phones)

    // ---- where it exists ------------------------------------------------------------------------

    @Test
    fun `the same name and mobile give the same key`() {
        assertEquals(cardPersonKeys(card()), cardPersonKeys(card()))
        assertTrue(cardPersonKeys(card()).isNotEmpty())
    }

    @Test
    fun `the number is compared on its digits`() {
        // The real difference between two readings of one card on 5 September: the `+` was lost.
        // A key over the raw string would have missed it, which is the whole point of the digits.
        assertEquals(
            cardPersonKeys(card(phones = listOf(CardPhone("+91 7000900582", "MOBILE")))),
            cardPersonKeys(card(phones = listOf(CardPhone("91 70009 00582", "MOBILE")))),
        )
    }

    @Test
    fun `the name is compared case and whitespace insensitively`() {
        assertEquals(cardPersonKeys(card()), cardPersonKeys(card(name = "  ARVIND   BHANDARI ")))
    }

    @Test
    fun `a second side adding a number does not lose the first key`() {
        // FR-1225 is why this is a list. The front alone yields one mobile; both sides may yield
        // two. A single compound over the set would differ between two captures of one card —
        // exactly the instability this key exists to see through.
        val front = card()
        val bothSides = card(
            phones = listOf(
                CardPhone("+91 70009 00582", "MOBILE"),
                CardPhone("+91 99999 11111", "MOBILE"),
            ),
        )
        assertTrue(sharesCardPerson(cardPersonKeys(front), cardPersonKeys(bothSides)))
    }

    // ---- where it refuses to exist, which is the half that matters ---------------------------------

    @Test
    fun `a card with no labelled mobile has no key`() {
        // 3 of the 11 corpus cards. `phoneTypeNear` never guesses a type, so a switchboard stays
        // WORK — and the compound would otherwise degenerate to a bare name.
        assertEquals(emptyList(), cardPersonKeys(card(phones = listOf(CardPhone("011 4000 8600", "WORK")))))
        assertEquals(emptyList(), cardPersonKeys(card(phones = listOf(CardPhone("011 4000 8600", null)))))
        assertEquals(emptyList(), cardPersonKeys(card(phones = emptyList())))
    }

    @Test
    fun `a card with no name has no key`() {
        // It would reduce to a bare telephone number, which FR-1209 forbids as identity and which
        // a shared switchboard makes actively dangerous.
        assertEquals(emptyList(), cardPersonKeys(card(name = null)))
        assertEquals(emptyList(), cardPersonKeys(card(name = "   ")))
    }

    @Test
    fun `a fragment of a number is not a number`() {
        assertEquals(emptyList(), cardPersonKeys(card(phones = listOf(CardPhone("4000", "MOBILE")))))
    }

    @Test
    fun `two cards with no key never match each other`() {
        // `sharesContactIdentity`'s rule one key over, and for the same reason: treating "neither
        // has one" as agreement would warn about every keyless card against every other.
        val keyless = cardPersonKeys(card(name = null))
        assertFalse(sharesCardPerson(keyless, keyless))
    }

    @Test
    fun `two different people sharing one mobile do not match`() {
        // The switchboard hazard, arriving through a mobile. Binding the number to the name is
        // what keeps a shared handset from folding two colleagues together.
        assertFalse(
            sharesCardPerson(
                cardPersonKeys(card(name = "Arvind Bhandari")),
                cardPersonKeys(card(name = "Priya Nair")),
            )
        )
    }

    // ---- the wire record ---------------------------------------------------------------------------

    @Test
    fun `the key round-trips through clientData`() {
        val metadata = CardMetadata(
            sourceHash = "hash",
            identityKeys = listOf("id1"),
            capturedAt = Instant.parse("2026-09-06T09:00:00Z"),
            captureLayer = "CAMERA",
            personKeys = listOf("p1", "p2"),
        )
        assertEquals(metadata, cardMetadataFromClientData(metadata.toClientData()))
    }

    @Test
    fun `a contact written before FR-1227 still decodes, with no keys`() {
        // **The reason the version was not bumped.** `cardMetadataFromClientData` refuses a
        // version it does not know, so a bump would make every contact already in the account
        // decode to null and duplicate once. A new key is additive in both directions.
        val old = CardMetadata(
            sourceHash = "hash",
            identityKeys = emptyList(),
            capturedAt = Instant.parse("2026-09-04T09:00:00Z"),
            captureLayer = "SHARE_SHEET",
        ).toClientData()
        assertTrue(old.none { it.first == KEY_CARD_PERSON_KEY })

        val decoded = checkNotNull(cardMetadataFromClientData(old))
        assertEquals(emptyList(), decoded.personKeys)
        assertEquals("hash", decoded.sourceHash, "an old contact stopped being matchable by hash")
    }

    @Test
    fun `an unrelated draft with an email is unaffected`() {
        // The two keys are independent: FR-1209's identity is email-based and FR-1227's is not,
        // and a card can perfectly well have one and not the other.
        val emailOnly = CardDraft(
            displayName = "Arvind Bhandari",
            emails = listOf(CardEmail("a@b.example")),
        )
        assertTrue(contactIdentityKeys(emailOnly).isNotEmpty())
        assertEquals(emptyList(), cardPersonKeys(emailOnly))
    }
}
