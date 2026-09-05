package com.latch.android.cards

import com.latch.core.model.CardDraft
import com.latch.core.model.CardEmail
import com.latch.core.model.CardPhone

/**
 * FR-1202 and FR-1205: what the card preview shows, and what the user may change.
 *
 * **Pure, and applied in exactly one place**, for the reason `SheetEdits` is on the date side: a
 * screen that worked the answers out for itself would eventually show one thing and save another.
 * This project has watched that happen five times on the Windows client.
 */
data class CardSheetState(
    /** What the grammar read. Never shown directly — [edited] is. */
    val parsed: CardDraft,
    /**
     * The decoded payload this was parsed from.
     *
     * **Carried because FR-1208's hash is taken from the payload and not from the draft.** Two
     * encoders can order one person's fields differently; the same physical card scanned twice
     * gives the same payload, and §7.2 already records what a hash that can wobble costs.
     */
    val payload: String = "",
    /** FR-1205: every field is editable, so every field can differ from what was parsed. */
    val edits: CardEdits = CardEdits(),
    /**
     * FR-1223: lines the classifier could not place, shown rather than dropped.
     *
     * Empty for a QR card, where the grammar says which field each value belongs to. Non-empty
     * only on the photographed path, where a card whose tagline vanished looks identical to one
     * that never had it.
     */
    val unplaced: List<String> = emptyList(),
    /**
     * FR-1224: this draft was read off a photograph, not decoded from a grammar.
     *
     * **It changes what the sheet says and never what the save does.** A photographed card is a
     * guess about somebody else's layout; the user is told so, and asked to check, because the
     * card is gone afterwards and a plausible wrong name is not checkable from the contact.
     */
    val fromPhoto: Boolean = false,
) {
    /** What the sheet displays and what a save would write. */
    val edited: CardDraft get() = edits.applyTo(parsed)
}

/**
 * The user's changes, held apart from what was parsed.
 *
 * **Separate rather than a mutated draft**, so "what the card said" survives alongside "what the
 * user corrected". FR-509b took the same shape on the date side and SRS 1.71 records why it
 * mattered: a correction has to travel outside the identity, or fixing a typo would make an item
 * unmatchable.
 */
data class CardEdits(
    val displayName: String? = null,
    val organisation: String? = null,
    val jobTitle: String? = null,
    val note: String? = null,
    /** Indexed by position in the parsed list; a blank value removes the entry. */
    val phones: Map<Int, String> = emptyMap(),
    val emails: Map<Int, String> = emptyMap(),
    /**
     * FR-1205 for the address, which was read-only until SRS 1.110.
     *
     * The classifier joins an address from several recognised lines and gets the joining wrong as
     * readily as the reading — so this is the field most likely to need correcting, and it was the
     * one field with no box to correct it in.
     */
    val addresses: Map<Int, String> = emptyMap(),
) {
    fun applyTo(draft: CardDraft): CardDraft = draft.copy(
        displayName = displayName ?: draft.displayName,
        // **An edited name replaces the structured pair** (SRS 1.101). The sheet shows *one*
        // Name field while a draft carries three, so leaving `givenName` and `familyName` alone
        // meant blanking the visible field cleared nothing: the blocker still saw a name, Save
        // stayed enabled, and the contact Google received carried the name the user had just
        // removed. That is the screen showing one thing and the account receiving another.
        //
        // Dropping the pair rather than trying to re-split the typed text is the honest answer:
        // this code cannot know which word is the family name in an arbitrary correction, and
        // the People API derives its own structure from an unstructured name.
        givenName = if (displayName != null) null else draft.givenName,
        familyName = if (displayName != null) null else draft.familyName,
        organisation = organisation ?: draft.organisation,
        jobTitle = jobTitle ?: draft.jobTitle,
        note = note ?: draft.note,
        phones = draft.phones.mapIndexed { index, phone ->
            CardPhone(phones[index] ?: phone.number, phone.type)
        }.filter { it.number.isNotBlank() },
        emails = draft.emails.mapIndexed { index, email ->
            CardEmail(emails[index] ?: email.address, email.type)
        }.filter { it.address.isNotBlank() },
        addresses = draft.addresses.mapIndexed { index, address ->
            addresses[index] ?: address
        }.filter { it.isNotBlank() },
    )
}

/**
 * Why a card cannot be saved yet, or null where it can.
 *
 * A card with nothing on it but a note is not a contact, and saving one produces an entry the
 * user cannot find again. This is `saveBlocker`'s job on the date side.
 */
enum class CardSaveBlocker {
    /** Nothing that identifies a person: no name, no number, no address. */
    NOTHING_TO_SAVE,
}

fun cardSaveBlocker(state: CardSheetState): CardSaveBlocker? {
    val draft = state.edited
    val hasName = !draft.displayName.isNullOrBlank() ||
        !draft.givenName.isNullOrBlank() || !draft.familyName.isNullOrBlank()
    val hasContact = draft.phones.isNotEmpty() || draft.emails.isNotEmpty()
    // An organisation alone is a company, not a person — but with a number it is somebody's
    // switchboard and worth keeping, which is why this is not simply "must have a name".
    val hasSomething = hasName || hasContact || !draft.organisation.isNullOrBlank()
    return if (hasSomething) null else CardSaveBlocker.NOTHING_TO_SAVE
}

/**
 * FR-1202: whether the card path is *offered* for a capture, and why.
 *
 * **Detection may change what is offered. It may never change what happens.** Where an image
 * carries a QR code that decodes as a contact grammar, the sheet says so and gives the card
 * action more prominence — it does not switch modes. That is FR-804's rule, one surface earlier,
 * and it is the reason this returns an *offer* rather than a decision.
 */
enum class CardOffer {
    /** No image, or an image with no contact code in it: the action is not shown at all. */
    NONE,

    /** A contact grammar was found. Say so, and put the action first. */
    DETECTED,

    /** An image capture with no code found. The action is available but not promoted: the user
     *  may know it is a card when the decoder does not — a photograph rather than a QR. */
    AVAILABLE,
}

fun cardOffer(isImage: Boolean, decodedContactPayloads: Int): CardOffer = when {
    !isImage -> CardOffer.NONE
    decodedContactPayloads > 0 -> CardOffer.DETECTED
    else -> CardOffer.AVAILABLE
}

/**
 * FR-1203: which payload to open, where an image carries several.
 *
 * Returns null when the user must choose. **The app does not pick one**, and that is the
 * requirement rather than caution: a poster carrying two cards, or a card beside a Wi-Fi code,
 * is a question only the person holding the phone can answer.
 */
fun soleContactPayload(payloads: List<String>): String? = payloads.singleOrNull()

/**
 * What dismissing the card sheet should do (SRS 1.104).
 *
 * **Found in use, not by a test.** Backing out of the card sheet always returned to the capture
 * sheet — which is right *before* a save, because the dates are still unsaved and changing your
 * mind about the card should not throw them away. It is wrong *after* one: the user has finished,
 * and a dates sheet reappearing reads either as nothing having happened or as a second unsaved
 * thing. It was worst on the fixture that found it, a QR on a white sheet with no dates in it at
 * all, where the user was returned to an empty capture.
 *
 * The distinction is what the capture still *holds*, not what it is: a photographed flyer can
 * carry both a contact code and a date, and saving the contact says nothing about the date.
 */
enum class CardDismiss {
    /** Nothing has been written, or dates remain unsaved. Go back to them. */
    BACK_TO_CAPTURE,

    /** The contact is saved and there is nothing else here. Close the whole capture. */
    CLOSE_CAPTURE,
}

fun cardDismiss(saved: Boolean, unsavedDateCandidates: Int): CardDismiss = when {
    // Before a save the dates are the reason to go back, and so is a card abandoned by mistake.
    !saved -> CardDismiss.BACK_TO_CAPTURE
    // Saved, but the capture also holds dates nobody has saved yet — those are a second thing
    // worth keeping, and closing over them would lose the user's other half.
    unsavedDateCandidates > 0 -> CardDismiss.BACK_TO_CAPTURE
    else -> CardDismiss.CLOSE_CAPTURE
}
