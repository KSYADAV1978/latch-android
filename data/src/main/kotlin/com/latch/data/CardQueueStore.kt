package com.latch.data

import android.content.Context
import android.content.SharedPreferences
import com.latch.core.model.CardDraft
import com.latch.core.model.CardEmail
import com.latch.core.model.CardPhone
import com.latch.google.json.JSONArray
import com.latch.google.json.JSONObject
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * FR-1212's storage: card captures held until they can be written.
 *
 * **Its own store rather than a variant of [PendingWrite]**, and that is a reading (SRS 1.99).
 * `PendingWrite` carries a `RemoteMetadata` and a list of dated `Item`s; a contact has neither, so
 * putting one there would mean nullable fields throughout *and* a record version bump — which
 * re-encodes every entry already on disk, each of which holds a capture that exists nowhere else.
 * SRS 1.24 refused precisely that trade when the write queue was asked to move into SQLite. What
 * is shared is FR-806's machinery: the worker, the backoff, the connectivity trigger, Retry now.
 *
 * The mechanism is [EncryptedWriteQueueStore]'s, deliberately — one record per entry, encrypted
 * under an Android Keystore key, in app-private preferences, JSON rather than the
 * unit-separated format because a card's fields are free text. NFR-203's recorded reading says to
 * reuse this rather than introduce a third scheme.
 *
 * **The same consequence has to be stated here as there**: a Keystore key does not survive a
 * device transfer, so a held card is unreadable after a restore. That is outside NFR-302's three
 * named cases and the app disables backup in any event, but it is the one way a held card can
 * disappear.
 */
interface CardQueue {
    /** Returns the entry id, which is what FR-1210's undo needs to drop it again. */
    suspend fun enqueue(entry: HeldCard): String

    suspend fun pending(): List<HeldCard>

    /** FR-1210: undo of a queued card drops the entry; nothing is in the account. */
    suspend fun drop(id: String): Boolean

    /** Retired after a successful write, or after the drain found it already saved. */
    suspend fun retire(id: String): Boolean

    suspend fun markAttempted(id: String): Boolean
}

/**
 * One held card.
 *
 * **The payload is held, not just the draft.** FR-1208's hash is taken from the payload — two
 * encoders order one person's fields differently, while the same physical card scanned twice gives
 * the same bytes — so a drain that had only the draft could not ask the duplicate question the
 * same way the save did.
 */
data class HeldCard(
    val id: String,
    val payload: String,
    /**
     * **What the user agreed to, not what the card said.** FR-1205's edits have to survive the
     * queue, or a card corrected before saving would drain with the correction thrown away. The
     * payload is held beside it for FR-1208's hash, which is a different question.
     */
    val draft: CardDraft,
    val layer: String,
    val queuedAt: Instant,
    val attempts: Int = 0,
)

class EncryptedCardQueueStore(context: Context) : CardQueue {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    private val cipher = KeystoreCipher(KEY_ALIAS)

    override suspend fun enqueue(entry: HeldCard): String = withContext(Dispatchers.IO) {
        val id = entry.id.ifBlank { UUID.randomUUID().toString() }
        val stored = entry.copy(id = id)
        prefs.edit().putString(keyFor(id), cipher.encrypt(encodeHeldCard(stored))).commit()
        id
    }

    override suspend fun pending(): List<HeldCard> = withContext(Dispatchers.IO) {
        prefs.all.keys
            .filter { it.startsWith(ENTRY_PREFIX) }
            .mapNotNull { key -> prefs.getString(key, null)?.let { cipher.decrypt(it) } }
            .mapNotNull(::decodeHeldCard)
            // Oldest first: a queue that drained newest-first would leave the capture somebody
            // has been waiting longest for until last.
            .sortedBy { it.queuedAt }
    }

    override suspend fun drop(id: String): Boolean = remove(id)

    override suspend fun retire(id: String): Boolean = remove(id)

    override suspend fun markAttempted(id: String): Boolean = withContext(Dispatchers.IO) {
        val current = prefs.getString(keyFor(id), null)?.let { cipher.decrypt(it) }
            ?.let(::decodeHeldCard) ?: return@withContext false
        val next = current.copy(attempts = current.attempts + 1)
        prefs.edit().putString(keyFor(id), cipher.encrypt(encodeHeldCard(next))).commit()
        true
    }

    private suspend fun remove(id: String): Boolean = withContext(Dispatchers.IO) {
        if (!prefs.contains(keyFor(id))) return@withContext false
        prefs.edit().remove(keyFor(id)).commit()
        true
    }

    private fun keyFor(id: String) = ENTRY_PREFIX + id

    private companion object {
        const val PREFS_FILE = "card_queue"
        const val ENTRY_PREFIX = "card."

        /** Its own alias: a store with its own file has its own key. */
        const val KEY_ALIAS = "latch.cardqueue.v1"
    }
}

// ---- the record ----------------------------------------------------------------------------

/**
 * Version 1, leading, as every record format in this project does.
 *
 * **An unreadable entry is kept, never dropped** — the rule the write queue already carries and
 * SRS 1.70 applied to the Inbox: dropping an entry loses a capture that exists nowhere else, and
 * an entry a future build can read is worth more than a tidy store.
 */
internal const val HELD_CARD_VERSION = 1

internal fun encodeHeldCard(card: HeldCard): String = JSONObject()
    .put("v", HELD_CARD_VERSION)
    .put("id", card.id)
    .put("payload", card.payload)
    .put("draft", encodeCardDraft(card.draft))
    .put("layer", card.layer)
    .put("queued_at", card.queuedAt.toString())
    .put("attempts", card.attempts)
    .toString()

internal fun decodeHeldCard(text: String): HeldCard? {
    val json = runCatching { JSONObject(text) }.getOrNull() ?: return null
    if (json.optInt("v", 0) != HELD_CARD_VERSION) return null
    val id = json.optString("id").takeIf { it.isNotBlank() } ?: return null
    val payload = json.optString("payload").takeIf { it.isNotBlank() } ?: return null
    val queuedAt = runCatching { Instant.parse(json.optString("queued_at")) }.getOrNull()
        ?: return null
    return HeldCard(
        id = id,
        payload = payload,
        draft = decodeCardDraft(json.optJSONObject("draft")) ?: return null,
        layer = json.optString("layer"),
        queuedAt = queuedAt,
        attempts = json.optInt("attempts", 0),
    )
}

private fun encodeCardDraft(draft: CardDraft): JSONObject = JSONObject()
    .put("display", draft.displayName)
    .put("given", draft.givenName)
    .put("family", draft.familyName)
    .put("org", draft.organisation)
    .put("title", draft.jobTitle)
    .put("note", draft.note)
    .put("phones", JSONArray().also { a -> draft.phones.forEach { a.put(pair(it.number, it.type)) } })
    .put("emails", JSONArray().also { a -> draft.emails.forEach { a.put(pair(it.address, it.type)) } })
    .put("addresses", JSONArray().also { a -> draft.addresses.forEach { a.put(it) } })
    .put("urls", JSONArray().also { a -> draft.urls.forEach { a.put(it) } })

private fun pair(value: String, type: String?): JSONObject =
    JSONObject().put("v", value).put("t", type)

private fun decodeCardDraft(json: JSONObject?): CardDraft? {
    if (json == null) return null
    fun strings(name: String): List<String> {
        val array = json.optJSONArray(name) ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optString(it).takeIf { s -> s.isNotBlank() } }
    }
    fun pairs(name: String): List<Pair<String, String?>> {
        val array = json.optJSONArray(name) ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val entry = array.optJSONObject(index) ?: return@mapNotNull null
            val value = entry.optString("v").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            value to entry.optString("t").takeIf { it.isNotBlank() }
        }
    }
    return CardDraft(
        displayName = json.optString("display").takeIf { it.isNotBlank() },
        givenName = json.optString("given").takeIf { it.isNotBlank() },
        familyName = json.optString("family").takeIf { it.isNotBlank() },
        organisation = json.optString("org").takeIf { it.isNotBlank() },
        jobTitle = json.optString("title").takeIf { it.isNotBlank() },
        note = json.optString("note").takeIf { it.isNotBlank() },
        phones = pairs("phones").map { CardPhone(it.first, it.second) },
        emails = pairs("emails").map { CardEmail(it.first, it.second) },
        addresses = strings("addresses"),
        urls = strings("urls"),
    )
}
