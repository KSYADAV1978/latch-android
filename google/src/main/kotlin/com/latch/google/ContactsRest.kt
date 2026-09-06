package com.latch.google

import java.util.Base64
import com.latch.google.json.JSONArray
import com.latch.google.json.JSONObject

/**
 * FR-1206 and FR-1208 over REST, through the same [GoogleHttp] every other request uses.
 *
 * **It goes through the guard like everything else.** `people.googleapis.com` is on
 * `ALLOWED_HOSTS` as §5.11's A5, approved and recorded; what is *not* acceptable is a client that
 * composes its own connection to stay out of the way of AC-17, which is why the webhook is a
 * separate module rather than an exception inside this one.
 */
class ContactsRest(private val http: GoogleHttp) : ContactsApi {

    override suspend fun createContact(person: ContactWrite): String {
        val created = http.post(createContactUrl(), person.toJson())
        return created.optString("resourceName").also {
            if (it.isBlank()) throw GoogleUnreadable("createContact returned no resourceName")
        }
    }

    override suspend fun deleteContact(resourceName: String) {
        try {
            http.delete(deleteContactUrl(resourceName))
        } catch (rejected: GoogleRejected) {
            // Idempotent by contract, as the calendar and tasks deletes are: the caller asked for
            // it not to be there, and it is not there. FR-1210's undo must not report a failure
            // for a contact somebody removed by hand in the meantime.
            if (!alreadyGone(rejected)) throw rejected
        }
    }

    override suspend fun updateContactPhoto(resourceName: String, jpeg: ByteArray) {
        // **Base64 and not multipart**: the People API takes the bytes as a string field on an
        // ordinary JSON body, which is what keeps this request inside `GoogleHttp` and therefore
        // inside AC-17's `ALLOWED_HOSTS` guard like every other request this project composes.
        //
        // The *standard* alphabet, not the URL-safe one. `photoBytes` is a JSON string value and
        // not a path segment, and Google's own clients send standard Base64 here; sending the
        // URL-safe alphabet would substitute `-` and `_` for `+` and `/` and be rejected as
        // malformed for a reason nothing in the response would name.
        val body = JSONObject()
            .put("photoBytes", Base64.getEncoder().encodeToString(jpeg))
            .put("personFields", "photos")
        // **PATCH, and it is not a style choice** (SRS 1.128). `updateContactPhoto` is bound to
        // PATCH in the People API, as `updateContact` is; only `createContact` is a POST. A POST
        // to a PATCH-only binding is not a 405 — Google's front end finds no method at that path
        // and answers **404 with no reason string**, which reads exactly like a contact that does
        // not exist and sent the first device run looking at the resource name.
        http.patch(updateContactPhotoUrl(resourceName), body)
    }

    override suspend fun findContactBySourceHash(
        sourceHash: String,
        personKeys: List<String>,
        identityKeys: List<String>,
    ): ContactDuplicateSearch =
        findContactByHashPaged(sourceHash, personKeys, identityKeys) { token ->
            val page = http.get(connectionsUrl(token))
            ContactPage(
                contacts = page.optJSONArray("connections").rows(),
                nextPageToken = page.optString("nextPageToken"),
            )
        }

    override suspend fun getContact(resourceName: String): ContactRecord =
        contactRecordFrom(resourceName, http.get(getContactUrl(resourceName)))

    override suspend fun updateContact(
        resourceName: String,
        etag: String,
        update: ContactUpdate,
    ): String {
        // **The etag goes in the body, not a header.** The People API carries it as a field of
        // the person being sent, and an update without it is refused with 400 — which is the
        // whole reason `ContactRecord` carries one and `getContact` exists.
        val body = update.write.toJson().put("etag", etag)
        // PATCH, as `updateContactPhoto` is and for SRS 1.128's reason: a POST to a PATCH-only
        // binding answers 404 with no reason string, which reads exactly like a contact that is
        // not there.
        val updated = http.patch(updateContactUrl(resourceName, update.fields), body)
        return updated.optJSONObject("metadata")?.optString("etag").orEmpty()
            .ifBlank { updated.optString("etag") }
    }
}

/**
 * FR-1231: what a contact holds now.
 *
 * **Only the first of each single-valued field.** A contact may carry several organisations; the
 * offer names one employer and one job title because that is what a card carries, and reaching
 * past the first would put Latch in the business of choosing which of somebody's jobs the card
 * refers to.
 */
internal fun contactRecordFrom(resourceName: String, person: JSONObject): ContactRecord {
    val name = person.optJSONArray("names")?.firstObject()
    val organisation = person.optJSONArray("organizations")?.firstObject()
    return ContactRecord(
        resourceName = person.optString("resourceName").ifBlank { resourceName },
        // The etag is on the person itself. Read from `metadata` too, because the People API has
        // put it in both places across versions and an update refused for a missing etag reports
        // nothing that names it.
        etag = person.optString("etag").ifBlank {
            person.optJSONObject("metadata")?.optString("etag").orEmpty()
        },
        displayName = name?.optString("displayName")?.takeIf { it.isNotBlank() },
        givenName = name?.optString("givenName")?.takeIf { it.isNotBlank() },
        familyName = name?.optString("familyName")?.takeIf { it.isNotBlank() },
        organisation = organisation?.optString("name")?.takeIf { it.isNotBlank() },
        jobTitle = organisation?.optString("title")?.takeIf { it.isNotBlank() },
        phones = person.optJSONArray("phoneNumbers").valuesWithType("value", "type"),
        emails = person.optJSONArray("emailAddresses").valuesWithType("value", "type"),
        addresses = person.optJSONArray("addresses").values("formattedValue"),
        urls = person.optJSONArray("urls").values("value"),
    )
}

private fun JSONArray.firstObject(): JSONObject? =
    if (length() == 0) null else optJSONObject(0)

private fun JSONArray?.values(key: String): List<String> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { optJSONObject(it)?.optString(key)?.takeIf { v -> v.isNotBlank() } }
}

private fun JSONArray?.valuesWithType(key: String, typeKey: String): List<Pair<String, String?>> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { index ->
        val entry = optJSONObject(index) ?: return@mapNotNull null
        val value = entry.optString(key).takeIf { it.isNotBlank() } ?: return@mapNotNull null
        value to entry.optString(typeKey).takeIf { it.isNotBlank() }
    }
}

private fun JSONArray?.rows(): List<ContactRow> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { index ->
        val person = optJSONObject(index) ?: return@mapNotNull null
        val name = person.optString("resourceName")
        if (name.isBlank()) return@mapNotNull null
        // FR-1231: the addresses travel with the row so an identity can be derived for a
        // contact Latch never created. Digested at the comparison, never stored.
        ContactRow(
            resourceName = name,
            clientData = person.optJSONArray("clientData").pairs(),
            emails = person.optJSONArray("emailAddresses").addresses(),
        )
    }
}

private fun JSONArray?.addresses(): List<String> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull {
        optJSONObject(it)?.optString("value")?.takeIf { value -> value.isNotBlank() }
    }
}

private fun JSONArray?.pairs(): List<Pair<String, String>> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { index ->
        val entry = optJSONObject(index) ?: return@mapNotNull null
        val key = entry.optString("key")
        if (key.isBlank()) null else key to entry.optString("value")
    }
}

/**
 * The request body.
 *
 * **Every field is omitted where it is absent rather than sent empty.** An empty `organizations`
 * entry is a real instruction to the People API — it creates a blank organisation on the contact —
 * where an absent one says nothing. That is the same null-against-empty distinction §7.2's
 * `put(name, null)` rests on, one API over.
 */
internal fun ContactWrite.toJson(): JSONObject {
    val body = JSONObject()

    if (givenName != null || familyName != null || displayName != null) {
        body.put(
            "names",
            JSONArray().put(
                JSONObject()
                    .put("givenName", givenName)
                    .put("familyName", familyName)
                    // People derives its own display name; ours is sent as the unstructured form
                    // so a card whose FN differs from its N components keeps what it chose.
                    .put("unstructuredName", displayName),
            ),
        )
    }

    if (organisation != null || jobTitle != null) {
        body.put(
            "organizations",
            JSONArray().put(JSONObject().put("name", organisation).put("title", jobTitle)),
        )
    }

    if (phones.isNotEmpty()) {
        body.put(
            "phoneNumbers",
            JSONArray().also { array ->
                phones.forEach { (number, type) ->
                    array.put(JSONObject().put("value", number).put("type", type))
                }
            },
        )
    }

    if (emails.isNotEmpty()) {
        body.put(
            "emailAddresses",
            JSONArray().also { array ->
                emails.forEach { (address, type) ->
                    array.put(JSONObject().put("value", address).put("type", type))
                }
            },
        )
    }

    if (addresses.isNotEmpty()) {
        body.put(
            "addresses",
            JSONArray().also { array ->
                addresses.forEach { array.put(JSONObject().put("formattedValue", it)) }
            },
        )
    }

    if (urls.isNotEmpty()) {
        body.put(
            "urls",
            JSONArray().also { array -> urls.forEach { array.put(JSONObject().put("value", it)) } },
        )
    }

    if (note != null) {
        body.put("biographies", JSONArray().put(JSONObject().put("value", note)))
    }

    if (clientData.isNotEmpty()) {
        body.put(
            "clientData",
            JSONArray().also { array ->
                clientData.forEach { (key, value) ->
                    array.put(JSONObject().put("key", key).put("value", value))
                }
            },
        )
    }

    return body
}
