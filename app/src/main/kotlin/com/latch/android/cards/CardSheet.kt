package com.latch.android.cards

import com.latch.core.model.CardDraft
import com.latch.parser.DatedCandidate
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
     * only where the classifier ran — a photograph, or FR-1230's text selection — because a card
     * whose tagline vanished looks identical to one that never had it.
     */
    val unplaced: List<String> = emptyList(),
    /**
     * FR-1225: how many photographs this capture holds, and how many of them yielded text.
     *
     * Null on every path but the camera's. Drawn only where a photograph gave nothing, which is
     * the one case FR-1225's report can arise from — the cap is enforced by withdrawing the
     * control rather than by discarding a photograph already taken.
     */
    val photos: CardPhotoCoverage? = null,
    /**
     * FR-1226: the user has asked for the card to become the contact's photo.
     *
     * **Off by default and never remembered between captures**, which is the requirement rather
     * than caution: a stored preference would mean the second card's photograph left the device
     * because of a decision taken about the first, and the image is a *third party's*. It lives on
     * the sheet state, so it is keyed on the capture and resets with it, exactly as FR-1004 keeps
     * entering an endpoint and enabling delivery two separate acts.
     *
     * Only ever true on the photographed path: there is no image to attach to a card decoded from
     * a QR payload, which is why the control is not drawn there.
     */
    val attachPhoto: Boolean = false,
    /**
     * FR-1224 and FR-1230: what read this draft.
     *
     * **It changes what the sheet says and never what the save does.** Anything the classifier
     * produced is a guess about somebody else's layout, so the user is told so and asked to
     * check — and the two guessed paths are told apart because the second half of the sentence
     * differs. A photographed card is gone afterwards; a text selection is still in the
     * application the user came from, so it can say where to check.
     *
     * **A three-valued reading rather than a second boolean** (SRS 1.151). `fromPhoto` beside a
     * `fromText` would be two flags that must never both be true, which is the shape that drifts.
     */
    val readBy: CardReading = CardReading.GRAMMAR,
) {
    /** What the sheet displays and what a save would write. */
    val edited: CardDraft get() = edits.applyTo(parsed)

    /** FR-1225 and FR-1226 are photograph-only, and both ask this. */
    val fromPhoto: Boolean get() = readBy == CardReading.PHOTO
}

/**
 * How a draft came to exist, which is what decides how far it should be trusted.
 *
 * FR-1204's grammar is right or wrong; FR-1221's classifier is at best probably right. That is
 * the whole distinction, and it is the reason Phase A settled the write path against a grammar
 * before any classifier existed.
 */
enum class CardReading {
    /** FR-1204: decoded from a vCard or MECARD payload. Field assignments came from the payload. */
    GRAMMAR,

    /** FR-1220: classified from recognised text on a photograph. The card is gone afterwards. */
    PHOTO,

    /** FR-1230: classified from a text selection — an email signature. The text is still there. */
    TEXT,
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

    /**
     * FR-1225: a photograph of this card is still being recognised.
     *
     * **Correctness, not politeness.** A save taken while the back of the card was still being
     * read would write a contact missing everything on it — silently, and against a card that is
     * no longer in the user's hand to check against. It is the same hazard FR-1224 exists for,
     * arriving from timing rather than from OCR.
     */
    READING_A_PHOTO,
}

fun cardSaveBlocker(
    state: CardSheetState,
    /** FR-1225: another photograph is being recognised into this capture right now. */
    readingPhoto: Boolean = false,
): CardSaveBlocker? {
    if (readingPhoto) return CardSaveBlocker.READING_A_PHOTO
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
 * FR-1227: whether to say that this card is probably already saved.
 *
 * **A separate answer from FR-1208's, deliberately.** That one decides whether to write; this one
 * decides whether to *speak*, and the whole risk the requirement was written against is the two
 * being conflated into one. So there is no state here in which Save is unavailable.
 */
enum class CardPersonWarning {
    /** No inexact key, no match, or the scan has not finished. Say nothing. */
    NONE,

    /** A contact carrying the same (name, mobile) is already in the account. Say so; allow Save. */
    PROBABLY_ALREADY_SAVED,
}

/**
 * @param personKeys what this card yields — empty where FR-1227 says the key degenerates, which is
 *   a card with no name or no labelled mobile.
 * @param matched the resource name a scan found, or null.
 * @param strongerAnswerStands a check that outranks this one has already spoken — FR-1208's
 *   "Already saved. Nothing was written again.", or FR-1231's offer naming the very fields that
 *   differ. Either says more about this person than *you may already have them* does, and a
 *   weaker line beside a stronger one only muddles it.
 */
fun cardPersonWarning(
    personKeys: List<String>,
    matched: String?,
    strongerAnswerStands: Boolean = false,
): CardPersonWarning = when {
    strongerAnswerStands -> CardPersonWarning.NONE
    personKeys.isEmpty() -> CardPersonWarning.NONE
    matched.isNullOrBlank() -> CardPersonWarning.NONE
    else -> CardPersonWarning.PROBABLY_ALREADY_SAVED
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

fun cardOffer(
    isImage: Boolean,
    decodedContactPayloads: Int,
    /**
     * FR-1230: this is a text capture and a contact can be read out of it. See
     * `holdsContact` in `:cards` for what that means and for the measurement behind it.
     */
    textHoldsContact: Boolean = false,
): CardOffer = when {
    // **A text capture can never be DETECTED, only AVAILABLE**, and that is not a narrowing: a
    // code is what `DETECTED` reports and there is no code in text. So FR-1230's "the same
    // explicit choice FR-1202 requires" arrives as the quiet button, which is the whole of what
    // this path can honestly offer.
    !isImage -> if (textHoldsContact) CardOffer.AVAILABLE else CardOffer.NONE
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

/**
 * How many of a capture's candidates are actually **dates** somebody has yet to save (SRS 1.147).
 *
 * **A candidate is not the same fact as a date**, and reading `candidates.size` for one was the
 * defect. A capture with no date in it does not yield an empty list: it yields **one**
 * `TASK_UNDATED` carrying `date = null`, which is the parser's way of saying *there is nothing
 * here* — and a business card is that case every time. So every photographed card looked to
 * [cardDismiss] like a capture holding one unsaved date, and SRS 1.104's whole rule inverted.
 *
 * **`date != null` is the test rather than the classification.** FR-510's past-date follow-up is
 * an undated *item* derived from a real date the user wrote, and it is genuinely unsaved — it
 * keeps its date on the candidate and is counted here, which classifying on `TASK_UNDATED` would
 * have got wrong in the opposite direction.
 */
fun unsavedDates(candidates: List<DatedCandidate>): Int = candidates.count { it.date != null }

fun cardDismiss(saved: Boolean, unsavedDateCandidates: Int): CardDismiss = when {
    // Before a save the dates are the reason to go back, and so is a card abandoned by mistake.
    !saved -> CardDismiss.BACK_TO_CAPTURE
    // Saved, but the capture also holds dates nobody has saved yet — those are a second thing
    // worth keeping, and closing over them would lose the user's other half.
    unsavedDateCandidates > 0 -> CardDismiss.BACK_TO_CAPTURE
    else -> CardDismiss.CLOSE_CAPTURE
}
