package com.latch.google

import com.latch.core.model.CardDraft
import com.latch.wire.normaliseEmail

/**
 * FR-1231, FR-1232 and FR-1233: FR-804 on contacts.
 *
 * **In `:google` rather than in a client**, beside `contactWriteFor`, for that function's reason:
 * what decides the bytes Google receives is shared, and a comparison that lived in `:app` would be
 * one a second client could not compile. `:desktop` has no contacts pillar today (§5.11.7) and
 * this file costs nothing by being where it would need to be.
 */

/** A field of a contact, as the offer names it. The sentence for each is the client's (NFR-402). */
enum class ContactField { NAME, ORGANISATION, JOB_TITLE, PHONE, EMAIL, ADDRESS, URL }

/**
 * One thing an update would do, with what the contact holds now beside it.
 *
 * **FR-1231: the existing value is quoted from what is stored, not from what was captured.** That
 * is FR-804's own reading and it is the reason [stored] is read back at match time rather than
 * remembered from an earlier capture — the contact may have been edited by hand since, and an
 * offer quoting Latch's memory of it would be an offer about a contact that no longer exists.
 *
 * [stored] is null where the contact has nothing in that field, which is an addition rather than a
 * replacement. FR-1233 needs no special case: employer and job title are visible because every
 * field is.
 */
data class ContactFieldChange(
    val field: ContactField,
    val stored: String?,
    val captured: String,
    /**
     * The label the card gave this value — `MOBILE`, `WORK` — or null where it gave none.
     *
     * **Carried rather than looked up** (SRS 1.159). `mergedContactUpdate` used to find the type
     * by matching the value back against the captured draft, which was fragile before FR-1205's
     * editing reached this path and would have broken *silently* after it: a corrected number
     * matches nothing, so the type would quietly become null.
     */
    val type: String? = null,
    /**
     * What the field already holds, where this change is an **addition** to a list (SRS 1.160).
     *
     * **FR-1231 asks the offer to name "the fields that would change and their current values",
     * and for a multi-valued field [stored] cannot do it.** [stored] is the value a change would
     * *replace*, which for an addition is nothing — so the sheet said "not on the contact yet"
     * about a website the contact demonstrably had. That is false in the direction that hides the
     * consequence: accepting adds a second website rather than correcting the first, and the user
     * had no way to know before pressing.
     *
     * Empty where the field genuinely holds nothing, which is a different sentence and a
     * different decision.
     */
    val existing: List<String> = emptyList(),
    /**
     * Whether accepting this row **replaces** what the field holds rather than adding beside it.
     *
     * **SRS 1.152's "an update adds and never removes" was a reading, not the requirement, and it
     * was too crude** (SRS 1.162). It was justified with telephone numbers — somebody genuinely
     * has an office line and a mobile, and a card printing one is no evidence the other should go
     * — and that argument does not transfer to a website. A company has one; a second string
     * differing by two characters is not a second site but the same site read badly, which is
     * exactly what a device pass produced three times running.
     *
     * So the default is per field and the user can flip it. Nothing is removed without somebody
     * choosing to, which is the part of the old reading worth keeping.
     */
    val replaces: Boolean = false,
)

/**
 * Whether replacing is the likelier intent for this field (SRS 1.162).
 *
 * **A default, and defaults on this sheet are visible and flippable.** A website and a postal
 * address are singular in practice — one company, one office — so a card's version of one the
 * contact already holds is almost always a re-reading. A telephone number and an email address
 * are not: people have several, and replacing would destroy the one the card does not print.
 *
 * **Several existing values always means add**, whatever the field: there is no answer to *which
 * of these three would you replace*, and guessing at one is how an update loses something.
 */
internal fun replaceByDefault(field: ContactField, existing: List<String>): Boolean =
    existing.size == 1 && (field == ContactField.URL || field == ContactField.ADDRESS)

/**
 * What is stored on a contact right now.
 *
 * **The etag is not decoration.** The People API refuses `updateContact` without a current one,
 * and FR-1232's restore is a second update — so the etag the patch returns has to travel with the
 * undo or the undo cannot run at all.
 */
data class ContactRecord(
    val resourceName: String,
    val etag: String,
    val displayName: String? = null,
    val givenName: String? = null,
    val familyName: String? = null,
    val organisation: String? = null,
    val jobTitle: String? = null,
    val phones: List<Pair<String, String?>> = emptyList(),
    val emails: List<Pair<String, String?>> = emptyList(),
    val addresses: List<String> = emptyList(),
    val urls: List<String> = emptyList(),
)

/**
 * FR-1231: what would change, field by field.
 *
 * **An update adds and replaces. It never removes.** A card carries a *subset* of what a contact
 * holds — one of somebody's three numbers, the address of the office they were at that day — so a
 * value on the contact and not on the card is not evidence of anything, and offering to drop it
 * would make accepting an update a gamble on what the card happened to print. Multi-valued fields
 * therefore yield one change per captured value that is **not already there**, and never a change
 * that takes one away.
 *
 * **The note is not here at all** (SRS 1.152). FR-1233's own reasoning is that a NOTE is a field
 * the user owns; FR-1223 fills it on a *new* contact, where nothing is displaced, and putting
 * unplaced card lines over somebody's own biography is the same harm one field along.
 *
 * Comparison is case-folded and trimmed for text, digits-only for a number — +91 98765 43210 and
 * 9876543210 are one number, which `cardPersonKeys` already had to decide — and FR-1209's
 * normalisation for an address, so this file and the identity cannot disagree about what an email
 * *is*.
 */
fun contactChanges(stored: ContactRecord, captured: CardDraft): List<ContactFieldChange> = buildList {
    val name = captured.displayName?.takeIf { it.isNotBlank() }
        ?: listOfNotNull(captured.givenName, captured.familyName)
            .joinToString(" ").takeIf { it.isNotBlank() }
    val storedName = stored.displayName?.takeIf { it.isNotBlank() }
        ?: listOfNotNull(stored.givenName, stored.familyName)
            .joinToString(" ").takeIf { it.isNotBlank() }
    replacement(ContactField.NAME, storedName, name)?.let { add(it) }
    replacement(ContactField.ORGANISATION, stored.organisation, captured.organisation)?.let { add(it) }
    replacement(ContactField.JOB_TITLE, stored.jobTitle, captured.jobTitle)?.let { add(it) }

    captured.phones.filter { it.number.isNotBlank() }.distinctBy { it.number }.forEach { phone ->
        if (stored.phones.none { sameNumber(it.first, phone.number) }) {
            add(ContactFieldChange(ContactField.PHONE, null, phone.number, phone.type, existing = stored.phones.map { it.first }))
        }
    }
    captured.emails.filter { it.address.isNotBlank() }.distinctBy { it.address }.forEach { email ->
        if (stored.emails.none { normaliseEmail(it.first) == normaliseEmail(email.address) }) {
            add(ContactFieldChange(ContactField.EMAIL, null, email.address, email.type, existing = stored.emails.map { it.first }))
        }
    }
    captured.addresses.filter { it.isNotBlank() }.distinct().forEach { address ->
        if (stored.addresses.none { folded(it) == folded(address) }) {
            add(ContactFieldChange(ContactField.ADDRESS, null, address, existing = stored.addresses,
            replaces = replaceByDefault(ContactField.ADDRESS, stored.addresses)))
        }
    }
    captured.urls.filter { it.isNotBlank() }.distinct().forEach { url ->
        if (stored.urls.none { folded(it) == folded(url) }) {
            add(ContactFieldChange(ContactField.URL, null, url, existing = stored.urls,
            replaces = replaceByDefault(ContactField.URL, stored.urls)))
        }
    }
}

/**
 * A single-valued field the card would replace, or null where it would not.
 *
 * **A blank capture changes nothing**, which is design principle 1's reasoning on a field that is
 * not a date: a card that does not print a job title is not a card saying the person has none.
 */
private fun replacement(
    field: ContactField,
    stored: String?,
    captured: String?,
): ContactFieldChange? {
    val new = captured?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val old = stored?.trim()?.takeIf { it.isNotBlank() }
    // Case and spacing alone are not a change worth asking about: "Acme Ltd" against "ACME Ltd"
    // is an offer the user has to read and decline, for nothing.
    if (old != null && folded(old) == folded(new)) return null
    return ContactFieldChange(field, old, new)
}

private fun folded(value: String): String = value.trim().lowercase().replace(SPACES, " ")

private val SPACES = Regex("\\s+")

/** A number with a country code and the same number without it are one number. */
private fun sameNumber(left: String, right: String): Boolean {
    val a = left.filter(Char::isDigit)
    val b = right.filter(Char::isDigit)
    if (a.isEmpty() || b.isEmpty()) return left.trim() == right.trim()
    // The shorter has to be a *suffix* rather than merely contained, so 43210 does not match
    // 9876543210 by accident. A card prints a number both ways within one company.
    return a == b || (a.length > b.length && a.endsWith(b)) || (b.length > a.length && b.endsWith(a))
}

/**
 * FR-1231's patch: what to send, and which fields to let it touch.
 *
 * **`updatePersonFields` is a licence to overwrite**, so it names only the fields [changes]
 * actually mention. Two things can therefore never be in it, structurally rather than by care:
 * `clientData`, because FR-1207's record is write-once (SRS 1.18), and `biographies`, because the
 * note is not a field this update may reach.
 *
 * Each named field is sent **whole**, the API replacing a field's entire list rather than merging
 * — so a phone addition sends the stored numbers *and* the new one, and dropping the stored ones
 * here would be the silent removal [contactChanges] refuses to offer.
 */
fun mergedContactUpdate(
    stored: ContactRecord,
    captured: CardDraft,
    changes: List<ContactFieldChange>,
): ContactUpdate {
    val touched = changes.map { it.field }.toSet()
    val write = ContactWrite(
        // **The structured pair is dropped when the name changes**, for SRS 1.101's reason: this
        // code cannot know which word of a card's name is the family name, and leaving the stored
        // pair beside a new unstructured name is how a contact comes to display one name and sort
        // under another.
        givenName = if (ContactField.NAME in touched) null else stored.givenName,
        familyName = if (ContactField.NAME in touched) null else stored.familyName,
        displayName = changed(changes, ContactField.NAME) ?: stored.displayName,
        organisation = changed(changes, ContactField.ORGANISATION) ?: stored.organisation,
        jobTitle = changed(changes, ContactField.JOB_TITLE) ?: stored.jobTitle,
        // **A replaced field sends only what the user accepted; an added one sends both**
        // (SRS 1.162). Each named field goes whole, so what is left out of the list is what is
        // removed — which is why this decision has to be read off the change rather than assumed.
        phones = merged(stored.phones, changes, ContactField.PHONE),
        emails = merged(stored.emails, changes, ContactField.EMAIL),
        addresses = merged(stored.addresses.map { it to null }, changes, ContactField.ADDRESS)
            .map { it.first },
        urls = merged(stored.urls.map { it to null }, changes, ContactField.URL).map { it.first },
    )
    return ContactUpdate(write = write, fields = fieldNames(touched))
}

/** The body and the `updatePersonFields` mask that governs what it is allowed to change. */
data class ContactUpdate(val write: ContactWrite, val fields: List<String>)

/**
 * FR-1232: put the contact back exactly as it was.
 *
 * The same mask, so the restore reaches every field the update reached and no others — an undo
 * with a narrower mask would leave half the change standing, and a wider one would overwrite a
 * field Latch never touched.
 */
fun restoreContactUpdate(stored: ContactRecord, fields: List<String>): ContactUpdate = ContactUpdate(
    write = ContactWrite(
        givenName = stored.givenName,
        familyName = stored.familyName,
        displayName = stored.displayName,
        organisation = stored.organisation,
        jobTitle = stored.jobTitle,
        phones = stored.phones,
        emails = stored.emails,
        addresses = stored.addresses,
        urls = stored.urls,
    ),
    fields = fields,
)

/**
 * The new list for one multi-valued field: the stored values kept or dropped, plus the accepted
 * ones (SRS 1.162).
 *
 * Replacing drops **only what this field held**, never another field's, and only where a row
 * actually asked for it.
 */
private fun merged(
    stored: List<Pair<String, String?>>,
    changes: List<ContactFieldChange>,
    field: ContactField,
): List<Pair<String, String?>> {
    val mine = changes.filter { it.field == field }
    if (mine.isEmpty()) return stored
    val keep = if (mine.any { it.replaces }) emptyList() else stored
    return keep + mine.map { it.captured to it.type }
}

private fun changed(changes: List<ContactFieldChange>, field: ContactField): String? =
    changes.firstOrNull { it.field == field }?.captured

private fun additions(changes: List<ContactFieldChange>, field: ContactField): List<String> =
    changes.filter { it.field == field }.map { it.captured }

/**
 * The People API's own names for the fields, which are not this enum's.
 *
 * **A field named here that the body does not carry is a field emptied.** `updatePersonFields`
 * means "replace these with what I sent", and what was not sent is nothing — so this is derived
 * from the changes rather than written out as a constant list, which is how a mask comes to name
 * a field a later body stopped filling.
 */
internal fun fieldNames(touched: Set<ContactField>): List<String> = buildList {
    if (ContactField.NAME in touched) add("names")
    if (ContactField.ORGANISATION in touched || ContactField.JOB_TITLE in touched) {
        // One People API field carries both: a job title lives *inside* `organizations`, which
        // SRS 1.102 already had to record when a cleared company left an organisation with an
        // empty name. Naming it once for either change is that same fact.
        add("organizations")
    }
    if (ContactField.PHONE in touched) add("phoneNumbers")
    if (ContactField.EMAIL in touched) add("emailAddresses")
    if (ContactField.ADDRESS in touched) add("addresses")
    if (ContactField.URL in touched) add("urls")
}

/**
 * FR-1205 on the update path: what the user actually agreed to (SRS 1.159).
 *
 * **Each line of an offer is a row with a tick and an editable value**, which is FR-511's per-date
 * checkbox and FR-608's per-step tick a third time. This applies both answers and is the single
 * place that decides them, so the sheet and the write cannot disagree — the reason `SheetEdits`
 * exists on the date side.
 *
 * **Two ways to decline and neither writes an empty field**: untick the row, or blank its value.
 * A line edited back to what is already stored is dropped too — it would be a change that changes
 * nothing, and naming a field in `updatePersonFields` for that is how a field gets emptied.
 */
fun acceptedChanges(
    changes: List<ContactFieldChange>,
    /** Indices of the rows still ticked. */
    accepted: Set<Int>,
    /** Corrections, by row index. Absent means the captured value stands. */
    values: Map<Int, String> = emptyMap(),
    /**
     * Rows whose replace-or-add the user flipped away from the default (SRS 1.162).
     *
     * A set of *overrides* rather than a set of "replacing" rows, so a row nobody touched keeps
     * whatever `replaceByDefault` decided and the default stays in one place.
     */
    flipped: Set<Int> = emptySet(),
): List<ContactFieldChange> = changes.mapIndexedNotNull { index, change ->
    if (index !in accepted) return@mapIndexedNotNull null
    val value = (values[index] ?: change.captured).trim()
    if (value.isBlank()) return@mapIndexedNotNull null
    if (change.stored != null && value.equals(change.stored, ignoreCase = true)) {
        return@mapIndexedNotNull null
    }
    change.copy(captured = value, replaces = change.replaces != (index in flipped))
}
