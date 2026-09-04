package com.latch.core.model

/**
 * A contact as it was read off a card, before anybody has confirmed it (SRS §5.11, FR-1204).
 *
 * **A draft, not a contact.** Nothing here has been agreed to by the user: FR-1205 requires every
 * field to be editable before any write, so this type is what the preview shows and never what
 * the preview *is*. That distinction is the same one `ParseResult` draws on the date side.
 *
 * **Every field is optional and none is invented.** Design principle 1 is about dates, but its
 * reasoning is about guessing: a card with no job title produces a draft with no job title, never
 * one derived from the company name or the email address. A wrong telephone number is the same
 * class of harm as an invented date and is harder to notice, because nothing about it looks wrong
 * until somebody dials it.
 */
data class CardDraft(
    /** `FN` in vCard, or the assembled `N` where no `FN` is present. */
    val displayName: String? = null,
    val givenName: String? = null,
    val familyName: String? = null,
    val organisation: String? = null,
    val jobTitle: String? = null,
    val phones: List<CardPhone> = emptyList(),
    val emails: List<CardEmail> = emptyList(),
    val addresses: List<String> = emptyList(),
    val urls: List<String> = emptyList(),
    val note: String? = null,
) {
    /**
     * Whether there is anything worth showing.
     *
     * A payload that parses to nothing at all is reported unreadable rather than opened as an
     * empty sheet: FR-1204 says a payload that does not parse is reported and not partially
     * accepted, and a draft with no name, no number and no address is that outcome by another
     * route.
     */
    val isEmpty: Boolean
        get() = displayName == null && givenName == null && familyName == null &&
            organisation == null && jobTitle == null && phones.isEmpty() &&
            emails.isEmpty() && addresses.isEmpty() && urls.isEmpty()
}

/**
 * A telephone number and what the card said it was for.
 *
 * [type] is carried verbatim from the payload rather than mapped to an enumeration here. Cards in
 * the wild use `CELL`, `MOBILE`, `WORK,VOICE`, `main` and much else, and collapsing that into a
 * fixed set at the parser is a decision about *display* taken in the wrong place — FR-1205's
 * preview and the People API's own vocabulary are where it belongs.
 */
data class CardPhone(val number: String, val type: String? = null)

data class CardEmail(val address: String, val type: String? = null)
