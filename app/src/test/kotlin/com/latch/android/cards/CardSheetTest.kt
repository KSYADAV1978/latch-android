package com.latch.android.cards

import com.latch.core.model.CardDraft
import com.latch.core.model.CardEmail
import com.latch.core.model.CardPhone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FR-1202 and FR-1205's decisions.
 *
 * The Compose that draws this is unreachable from here, which is why everything it *decides*
 * lives in `CardSheet.kt`. On the Windows client the same argument was made five times before it
 * was believed.
 */
class CardSheetTest {

    private val card = CardDraft(
        displayName = "Anita Sharma",
        givenName = "Anita",
        familyName = "Sharma",
        organisation = "Northwind Textiles",
        jobTitle = "Head of Sourcing",
        phones = listOf(CardPhone("+91 98200 12345", "CELL"), CardPhone("+91 22 2345 6789", "WORK")),
        emails = listOf(CardEmail("anita@northwind.example", "WORK")),
    )

    // ---- FR-1205: every field editable ------------------------------------------------------

    @Test
    fun `an untouched sheet shows exactly what was parsed`() {
        assertEquals(card, CardSheetState(card).edited)
    }

    @Test
    fun `editing a field changes what would be written and not what was parsed`() {
        // The correction has to travel beside the parse rather than over it: FR-509b's shape,
        // where a typo fixed must not make an item unmatchable.
        val state = CardSheetState(card, edits = CardEdits(jobTitle = "Director of Sourcing"))
        assertEquals("Director of Sourcing", state.edited.jobTitle)
        assertEquals("Head of Sourcing", state.parsed.jobTitle, "the parse was overwritten")
    }

    @Test
    fun `a phone can be corrected in place`() {
        val state = CardSheetState(card, edits = CardEdits(phones = mapOf(0 to "+91 98200 99999")))
        assertEquals(listOf("+91 98200 99999", "+91 22 2345 6789"), state.edited.phones.map { it.number })
        // The type survives a correction to the number: a mobile edited is still a mobile.
        assertEquals("CELL", state.edited.phones.first().type)
    }

    @Test
    fun `blanking a phone removes it rather than writing an empty one`() {
        // A card often carries a fax nobody wants. Clearing the field is how the user says so,
        // and an empty phoneNumbers entry would put a blank row on their contact.
        val state = CardSheetState(card, edits = CardEdits(phones = mapOf(1 to "  ")))
        assertEquals(listOf("+91 98200 12345"), state.edited.phones.map { it.number })
    }

    @Test
    fun `blanking an email removes it, and with it the identity it carried`() {
        // FR-1209 keys on email, so this is not only a display change: a card saved with its
        // address cleared has no automatic identity and will be written as new.
        val state = CardSheetState(card, edits = CardEdits(emails = mapOf(0 to "")))
        assertTrue(state.edited.emails.isEmpty())
    }

    // ---- the save blocker --------------------------------------------------------------------

    @Test
    fun `an ordinary card can be saved`() {
        assertNull(cardSaveBlocker(CardSheetState(card)))
    }

    @Test
    fun `a card with nothing but a note cannot be saved`() {
        // It would produce an entry the user can never find again.
        val empty = CardDraft(note = "met at the fair")
        assertEquals(CardSaveBlocker.NOTHING_TO_SAVE, cardSaveBlocker(CardSheetState(empty)))
    }

    @Test
    fun `an organisation and a number is somebody's switchboard, and is worth keeping`() {
        // Deliberately not "must have a name": a card for a shop, with no person on it, is a
        // contact people really do want.
        val shop = CardDraft(organisation = "Northwind Textiles", phones = listOf(CardPhone("+91 22 2345 6789")))
        assertNull(cardSaveBlocker(CardSheetState(shop)))
    }

    @Test
    fun `emptying the last field blocks a save that was allowed a moment ago`() {
        // The blocker is re-asked on every edit. Computed once when the sheet opened, it would
        // be FR-608's frozen ticks one screen over.
        val one = CardDraft(phones = listOf(CardPhone("+91 22 2345 6789")))
        assertNull(cardSaveBlocker(CardSheetState(one)))
        assertEquals(
            CardSaveBlocker.NOTHING_TO_SAVE,
            cardSaveBlocker(CardSheetState(one, edits = CardEdits(phones = mapOf(0 to "")))),
        )
    }

    // ---- FR-1202: detection changes what is offered, never what happens -----------------------

    @Test
    fun `a text capture is never offered the card path`() {
        assertEquals(CardOffer.NONE, cardOffer(isImage = false, decodedContactPayloads = 0))
        assertEquals(CardOffer.NONE, cardOffer(isImage = false, decodedContactPayloads = 3))
    }

    @Test
    fun `a detected card is promoted but not taken`() {
        // The requirement's own sentence: detection may change what is offered and may never
        // change what happens. DETECTED is an offer; there is no value here meaning "switched".
        assertEquals(CardOffer.DETECTED, cardOffer(isImage = true, decodedContactPayloads = 1))
    }

    @Test
    fun `an image with no code still offers the card path`() {
        // The user may know it is a card when the decoder does not — a photographed card rather
        // than a QR. Withholding the action would make Phase B unreachable from the same screen.
        assertEquals(CardOffer.AVAILABLE, cardOffer(isImage = true, decodedContactPayloads = 0))
    }

    // ---- FR-1203: several codes ---------------------------------------------------------------

    @Test
    fun `one payload opens, several ask`() {
        assertEquals("MECARD:N:Doe,John;;", soleContactPayload(listOf("MECARD:N:Doe,John;;")))
        assertNull(
            soleContactPayload(listOf("MECARD:N:Doe,John;;", "MECARD:N:Roe,Jane;;")),
            "the app must not choose which of two cards the user meant",
        )
        assertNull(soleContactPayload(emptyList()))
    }
}
