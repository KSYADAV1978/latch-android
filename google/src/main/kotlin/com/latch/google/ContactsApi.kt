package com.latch.google

import com.latch.wire.KEY_CARD_SOURCE_HASH
import com.latch.wire.KEY_CARD_PERSON_KEY
import com.latch.wire.sharesCardPerson

/**
 * FR-1206 and FR-1208: the People API, and the duplicate check that must exist before anything is
 * written through it.
 *
 * **The order is FR-800's lesson.** An item written into somebody's account before duplicate
 * detection and undo are specified becomes an unmanageable item, and that cost this project five
 * days of duplicates in a real calendar. So this file defines the search before it defines the
 * write.
 */
interface ContactsApi {

    /** FR-1206. Returns the created contact's `resourceName`. */
    suspend fun createContact(person: ContactWrite): String

    /** FR-1210's undo. Idempotent by contract, as [CalendarApi.deleteEvent] is. */
    suspend fun deleteContact(resourceName: String)

    /**
     * FR-1226: the photographed card becomes the contact's photo.
     *
     * **The one place an image leaves the device**, which FR-216 was amended by name to allow. It
     * is a second request after [createContact] and it is allowed to fail without failing the
     * save — a contact with no photo is the whole of what the user came for, and NFR-303 asks
     * that the outcome be *said* rather than that the save be reported as lost.
     *
     * [jpeg] is already square and already scaled; see `letterboxPlacement` for why the card is
     * fitted inside the circle Google will crop it to rather than to the square's own edges.
     */
    suspend fun updateContactPhoto(resourceName: String, jpeg: ByteArray)

    /**
     * FR-1208: is this exact card already in the account?
     *
     * Answers by scanning the user's connections and comparing `latch.card.source_hash`, because
     * **Google cannot be asked this question directly** — see [ContactDuplicateSearch].
     */
    suspend fun findContactBySourceHash(
        sourceHash: String,
        /** FR-1227's inexact key, answered in the same pass. Empty asks FR-1208's question alone. */
        personKeys: List<String> = emptyList(),
    ): ContactDuplicateSearch
}

/**
 * The result of FR-1208's check.
 *
 * **[scanCapped] is not decoration and callers must not read it as "no duplicate".** It is
 * `DuplicateSearch.scanCapped`'s rule on a third transport, and SRS §5.8 already forbids reading a
 * capped scan as an absence — a scan that gave up has answered "I do not know".
 */
data class ContactDuplicateSearch(
    val existingResourceName: String? = null,
    val scanCapped: Boolean = false,
    /**
     * FR-1227: a contact that is **probably** this person, found in the same pass.
     *
     * **Never a reason to refuse anything.** [existingResourceName] means "this exact card is
     * already saved, do not write"; this means "say something, and let them decide". Keeping them
     * as separate fields rather than one nullable answer is what stops a caller conflating them —
     * the whole risk FR-1227 was written to avoid is exactly that conflation.
     */
    val probablePersonResourceName: String? = null,
) {
    val found: Boolean get() = existingResourceName != null
}

/**
 * What a contact write carries. Deliberately not `CardDraft`: this is the wire shape, and a draft
 * is what the user was shown.
 */
data class ContactWrite(
    val givenName: String? = null,
    val familyName: String? = null,
    val displayName: String? = null,
    val organisation: String? = null,
    val jobTitle: String? = null,
    val phones: List<Pair<String, String?>> = emptyList(),
    val emails: List<Pair<String, String?>> = emptyList(),
    val addresses: List<String> = emptyList(),
    val urls: List<String> = emptyList(),
    val note: String? = null,
    /** FR-1207's §7.2 analogue, already encoded as key/value pairs by `:wire`. */
    val clientData: List<Pair<String, String>> = emptyList(),
)

// ---- the URLs, as pure functions a test can reach ------------------------------------------

const val PEOPLE_BASE = "https://people.googleapis.com/v1"

/**
 * FR-1206's write.
 *
 * `personFields` is what comes back, not what goes in. `clientData` is requested so the caller can
 * confirm what Google actually stored rather than assume — the probe of 4 Sep 2026 was looking for
 * silent truncation precisely because an API that stores less than it accepted reports nothing.
 */
fun createContactUrl(): String =
    "$PEOPLE_BASE/people:createContact?personFields=names,emailAddresses,clientData"

fun deleteContactUrl(resourceName: String): String = "$PEOPLE_BASE/$resourceName:deleteContact"

/**
 * FR-1226's write.
 *
 * **No query parameters.** `updateContactPhoto` takes `personFields` as a field of the request
 * *body*, not of the URL, and Google rejects an unknown query parameter outright — the first
 * device run of this path came back refused with the photograph never leaving, while the contact
 * beside it saved perfectly. The first version carried `?personFields=photos` here **and** in the
 * body, which is how a redundant copy became a failure.
 */
fun updateContactPhotoUrl(resourceName: String): String =
    "$PEOPLE_BASE/$resourceName:updateContactPhoto"

/**
 * FR-1208's scan.
 *
 * **A thousand at a time, and the reason is a measurement rather than a preference.** The account
 * this was built against holds 3,157 contacts, so a page of 100 would be thirty-two round trips
 * and a page of 1,000 is four. `eventDedupUrl` asking for one result is the defect SRS 1.38
 * records; this asks for the maximum the API allows and follows the token, so correctness does
 * not depend on how many contacts somebody has.
 */
fun connectionsUrl(pageToken: String? = null): String =
    "$PEOPLE_BASE/people/me/connections?personFields=clientData&pageSize=$CONNECTIONS_PAGE_SIZE" +
        (pageToken?.takeIf { it.isNotBlank() }?.let { "&pageToken=$it" } ?: "")

const val CONNECTIONS_PAGE_SIZE = 1000

/**
 * How many pages a scan will read before giving up and saying so.
 *
 * Ten pages is ten thousand contacts, which is far beyond the account this was built against and
 * still bounded — the same shape as the Tasks scan's cap, and for the same reason: an unbounded
 * scan on a capture path is a hang, and a silent truncation is a false negative.
 */
const val CONNECTIONS_PAGE_CAP = 10

/**
 * Decide FR-1208 from pages of connections, without owning the fetching.
 *
 * **Extracted so a JVM test can call it**, which is this project's standing rule and was learnt
 * twice the expensive way: the drain's FR-803 check and `findEventPaged` were both wrong inside
 * code no test could reach. [nextPage] takes a page token and returns that page.
 */
suspend fun findContactByHashPaged(
    sourceHash: String,
    /**
     * FR-1227's inexact key, or empty to ask only FR-1208's question.
     *
     * **Answered in the same pass, which is the whole reason it is a parameter here rather than a
     * second scan.** The account this was built against holds 3,157 contacts; asking twice would
     * double a page loop the save path already waits on, to compare a second string per contact.
     */
    personKeys: List<String> = emptyList(),
    nextPage: suspend (String?) -> ContactPage,
): ContactDuplicateSearch {
    var token: String? = null
    var pages = 0
    var person: String? = null
    while (pages < CONNECTIONS_PAGE_CAP) {
        val page = nextPage(token)
        pages++
        page.contacts.firstOrNull { contact ->
            // **A blank hash matches nothing, and that is a supported call rather than an
            // accident.** FR-1227's probe runs before a save and has no capture hash to ask about
            // yet — it wants the inexact answer alone. Without this guard it would match any
            // contact whose source hash was somehow empty, which is the kind of sentinel that
            // works until the day it does not.
            sourceHash.isNotBlank() &&
                contact.clientData.any { it.first == KEY_CARD_SOURCE_HASH && it.second == sourceHash }
        }?.let {
            // The exact answer ends the scan. FR-1227's line would be redundant beside "already
            // saved" and the caller has a stronger thing to say.
            return ContactDuplicateSearch(existingResourceName = it.resourceName)
        }

        // First match wins and the scan continues, because the exact key outranks this one and
        // may still be on a later page.
        if (person == null) {
            person = page.contacts.firstOrNull { contact ->
                sharesCardPerson(
                    personKeys,
                    contact.clientData.filter { it.first == KEY_CARD_PERSON_KEY }.map { it.second },
                )
            }?.resourceName
        }

        token = page.nextPageToken?.takeIf { it.isNotBlank() }
            ?: return ContactDuplicateSearch(probablePersonResourceName = person)
    }
    // Out of pages with a token still outstanding: this is "I do not know", not "not found".
    return ContactDuplicateSearch(scanCapped = true, probablePersonResourceName = person)
}

data class ContactPage(val contacts: List<ContactRow>, val nextPageToken: String? = null)

data class ContactRow(val resourceName: String, val clientData: List<Pair<String, String>>)

/**
 * FR-1206: a confirmed draft and its FR-1207 record, as the People API wants them.
 *
 * **In `:google` because it needs both sides** — `CardDraft` through `:wire`'s dependency on
 * `:core-model`, and `ContactWrite` from here. Putting it in a client would be the mapping that
 * decides what Google receives living somewhere a second client could not compile.
 */
fun contactWriteFor(
    draft: com.latch.core.model.CardDraft,
    clientData: List<Pair<String, String>>,
): ContactWrite = ContactWrite(
    givenName = draft.givenName?.takeIf { it.isNotBlank() },
    familyName = draft.familyName?.takeIf { it.isNotBlank() },
    displayName = draft.displayName?.takeIf { it.isNotBlank() },
    organisation = draft.organisation?.takeIf { it.isNotBlank() },
    jobTitle = draft.jobTitle?.takeIf { it.isNotBlank() },
    phones = draft.phones.map { it.number to it.type },
    emails = draft.emails.map { it.address to it.type },
    addresses = draft.addresses,
    urls = draft.urls,
    note = draft.note?.takeIf { it.isNotBlank() },
    clientData = clientData,
)
