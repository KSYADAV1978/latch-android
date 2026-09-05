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
    fun `blanking the name clears the structured name with it`() {
        // SRS 1.101, and the fixture is the point. The old test for this used a draft carrying
        // *only* phones, so `givenName` and `familyName` were already null and it could not have
        // failed. This card has all three, which is what every real vCard has — and blanking the
        // one field the sheet shows must clear all of them, or the account receives a name the
        // user removed.
        val state = CardSheetState(card, edits = CardEdits(displayName = ""))
        assertNull(state.edited.givenName)
        assertNull(state.edited.familyName)
        assertEquals("", state.edited.displayName)
    }

    @Test
    fun `a blanked name on a card with nothing else blocks the save`() {
        // The device row that failed: name, company, both phones and the email cleared, and Save
        // stayed live because two invisible name fields still held the parse.
        val stripped = CardEdits(
            displayName = "",
            organisation = "",
            phones = mapOf(0 to "", 1 to ""),
            emails = mapOf(0 to ""),
        )
        assertEquals(
            CardSaveBlocker.NOTHING_TO_SAVE,
            cardSaveBlocker(CardSheetState(card, edits = stripped)),
            "Save stayed enabled with every visible field cleared",
        )
    }

    @Test
    fun `a corrected name replaces the parse rather than joining it`() {
        val state = CardSheetState(card, edits = CardEdits(displayName = "A. Sharma"))
        assertEquals("A. Sharma", state.edited.displayName)
        // The pair goes, because this code cannot know which word is the family name in an
        // arbitrary correction and guessing would file somebody under the wrong one.
        assertNull(state.edited.givenName)
        assertNull(state.edited.familyName)
    }

    @Test
    fun `an untouched name keeps its structure`() {
        val state = CardSheetState(card, edits = CardEdits(jobTitle = "Director"))
        assertEquals("Anita", state.edited.givenName)
        assertEquals("Sharma", state.edited.familyName)
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

/** SRS 1.104: what dismissing the card sheet does, found in use rather than by a test. */
class CardDismissTest {

    @Test
    fun `before a save, backing out returns to the dates`() {
        // Changing your mind about the card must not throw away the capture it came from.
        assertEquals(CardDismiss.BACK_TO_CAPTURE, cardDismiss(saved = false, unsavedDateCandidates = 0))
        assertEquals(CardDismiss.BACK_TO_CAPTURE, cardDismiss(saved = false, unsavedDateCandidates = 3))
    }

    @Test
    fun `after a save with nothing else in the capture, the whole thing closes`() {
        // The case that was wrong: a QR on a white sheet has no dates, so the user was returned
        // to an empty capture sheet after finishing.
        assertEquals(CardDismiss.CLOSE_CAPTURE, cardDismiss(saved = true, unsavedDateCandidates = 0))
    }

    @Test
    fun `after a save with dates still unsaved, the dates are still offered`() {
        // A photographed flyer can carry a contact code and a date. Saving the contact says
        // nothing about the date, and closing over it would lose the user's other half.
        assertEquals(CardDismiss.BACK_TO_CAPTURE, cardDismiss(saved = true, unsavedDateCandidates = 2))
    }
}

/** SRS 1.113: the lines the classifier could not place are carried, not merely shown. */
class CardNotesTest {

    @Test
    fun `an edited note is what would be written`() {
        val card = CardDraft(displayName = "Anita Sharma", note = "Weaving since 1974")
        val state = CardSheetState(card, edits = CardEdits(note = "met at the fair"))
        assertEquals("met at the fair", state.edited.note)
    }

    @Test
    fun `clearing the note clears it, rather than falling back to the parse`() {
        // The user trimming boilerplate out of a photographed card must actually remove it, or
        // the ISO certifications and straplines reach their address book anyway.
        val card = CardDraft(displayName = "Anita Sharma", note = "A Mini Ratna Company")
        val state = CardSheetState(card, edits = CardEdits(note = ""))
        assertEquals("", state.edited.note)
    }

    @Test
    fun `a card with nothing but a note still cannot be saved`() {
        // Carrying unplaced text into the notes must not turn a cardful of boilerplate into a
        // saveable contact: the blocker asks for a name, a number or an address, and a note is
        // none of those.
        val onlyNote = CardDraft(note = "IS/S0 9001 & IS/S0 14001")
        assertEquals(CardSaveBlocker.NOTHING_TO_SAVE, cardSaveBlocker(CardSheetState(onlyNote)))
    }
}
