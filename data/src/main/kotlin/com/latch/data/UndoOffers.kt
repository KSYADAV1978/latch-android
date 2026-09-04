package com.latch.data

import android.content.Context
import com.latch.core.model.ItemType
import com.latch.google.ItemDates
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * One item a save produced, and the whole of what is needed to take it back again (FR-807).
 *
 * Three shapes, because a save has three possible outcomes and undoing each is a different
 * operation — SRS 1.16 corrected FR-807's wording for exactly this reason. Collapsing them
 * would mean undo guessing, and the wrong guess either leaves an item in the account or
 * destroys one the user had before this app touched it.
 *
 * **It lives in `:data` because it has to be stored.** FR-807's own note recorded that the
 * offer dies with the process and that persisting it "needs local storage this app does not
 * yet have — the same storage FR-701 will bring". FR-701 brought it, so the type moved to
 * where the encoding is; nothing on it is anything but a remote address and a prior state,
 * both of which were already `:data`'s.
 */
sealed interface CreatedItem {
    /**
     * [containerId] is the calendar id for an event and the task list id for a task — both
     * APIs address an item by its container and its own id, and neither can be deleted by id
     * alone.
     */
    data class Written(
        val type: ItemType,
        val containerId: String,
        val remoteId: String,
    ) : CreatedItem

    /**
     * FR-806: still in the queue. The worker will not drain an entry inside its undo window,
     * so this stays droppable for as long as the offer stands.
     */
    data class Queued(val queueId: String) : CreatedItem

    /**
     * FR-804: an item that already existed and was **moved**, not created.
     *
     * Undoing this is a restore and never a delete. The item was the user's before this save
     * touched it, and deleting it would destroy something they already had — the one outcome
     * FR-807 must not produce.
     *
     * [priorDates] is what the item held when the match was made, which is the only place
     * those values still exist once the patch has gone through. SRS 1.19 records the staleness
     * that follows.
     */
    data class Updated(
        val type: ItemType,
        val containerId: String,
        val remoteId: String,
        val priorDates: ItemDates,
        /**
         * The description or notes the item held **before** FR-804's move note was appended.
         *
         * Carried for the reason [priorDates] is, and it became necessary the moment an update
         * started writing a note: an undo that put the dates back and left the note saying the
         * item had moved would leave a statement in the user's calendar that is no longer
         * true. Null where no note was written, in which case undo leaves the body untouched —
         * and null is *not* the same as empty, because sending an empty body would erase a
         * description the user wrote themselves.
         */
        val priorBody: String? = null,
    ) : CreatedItem
}

/**
 * FR-807's offer, as something that outlives the process that made it.
 *
 * The offer used to live only in `CaptureSaver`'s memory, and FR-807's note recorded the
 * consequence plainly: "a process death inside the ten seconds loses it and the save stands."
 * That is not a large loss — ten seconds — but it is a loss of the one control the user has
 * over a write, and the capture window is a floating dialog that the system is entitled to
 * kill.
 *
 * Two of the three limits that note records still stand and are not softened here. **A new
 * capture still ends the offer**, because the countdown belonged to a save the user has moved
 * on from. **Undo from another device is still a different feature** — this store is local,
 * and a cross-device undo would have to go by `latch.chain_id`, which is exact for events and
 * a give-up-able scan for tasks.
 */
data class StoredUndoOffer(
    val chainId: String,
    val expiresAt: Instant,
    val created: List<CreatedItem>,
) {
    fun isOpen(now: Instant): Boolean = now.isBefore(expiresAt)
}

interface UndoOfferStore {
    /** Written as the offer opens, so a process death a moment later still finds it. */
    suspend fun remember(offer: StoredUndoOffer)

    /**
     * The offer still open, if there is one — and lapsed ones swept while looking.
     *
     * At most one is open at a time in practice, a new capture having ended the previous. The
     * newest wins where two somehow overlap, because that is the save the user just made.
     */
    suspend fun open(now: Instant): StoredUndoOffer?

    /** Taken, lapsed, or overtaken by a new capture. */
    suspend fun forget(chainId: String)

    /** NFR-205. */
    suspend fun clear()
}

/** [UndoOfferStore] over [LatchDatabase], the payload encrypted like every other record. */
class SqliteUndoOfferStore(context: Context) : UndoOfferStore {

    private val database = LatchDatabase(context)
    private val cipher = KeystoreCipher(KEY_ALIAS)

    override suspend fun remember(offer: StoredUndoOffer) {
        withContext(Dispatchers.IO) {
            database.writableDatabase.replaceOrThrow(
                LatchDatabase.TABLE_UNDO,
                null,
                contentValuesOf(
                    "chain_id" to offer.chainId,
                    "expires_at" to offer.expiresAt.toEpochMilli(),
                    "payload" to cipher.encrypt(encodeUndoOffer(offer)),
                ),
            )
        }
    }

    override suspend fun open(now: Instant): StoredUndoOffer? = withContext(Dispatchers.IO) {
        // Swept on the way past rather than by anything scheduled: a lapsed offer is dead
        // weight and NFR-104 forbids a background service to tidy it away.
        database.writableDatabase.delete(
            LatchDatabase.TABLE_UNDO,
            "expires_at <= ?",
            arrayOf(now.toEpochMilli().toString()),
        )
        database.readableDatabase.query(
            LatchDatabase.TABLE_UNDO,
            arrayOf("chain_id", "payload"),
            "expires_at > ?",
            arrayOf(now.toEpochMilli().toString()),
            null,
            null,
            "expires_at DESC",
            "1",
        ).consume { cursor ->
            if (!cursor.moveToFirst()) return@consume null
            val offer = cipher.decrypt(cursor.getString(1))?.let(::decodeUndoOffer)
            if (offer == null) {
                // Unreadable now is unreadable for ever, and an offer nobody can act on would
                // sit on the home screen offering an undo that does nothing.
                database.writableDatabase
                    .delete(LatchDatabase.TABLE_UNDO, "chain_id = ?", arrayOf(cursor.getString(0)))
            }
            offer
        }
    }

    override suspend fun forget(chainId: String) {
        withContext(Dispatchers.IO) {
            database.writableDatabase
                .delete(LatchDatabase.TABLE_UNDO, "chain_id = ?", arrayOf(chainId))
        }
    }

    override suspend fun clear() {
        withContext(Dispatchers.IO) {
            database.writableDatabase.delete(LatchDatabase.TABLE_UNDO, null, null)
        }
    }

    private companion object {
        const val KEY_ALIAS = "latch.undo.v1"
    }
}

// ---------------------------------------------------------------------------------------
// The record format. Internal and pure, so it is JVM-tested; the SQL is not.
// ---------------------------------------------------------------------------------------

internal const val UNDO_RECORD_VERSION = 1

internal fun encodeUndoOffer(offer: StoredUndoOffer): String {
    val created = JSONArray()
    offer.created.forEach { created.put(encodeCreatedItem(it)) }
    return JSONObject()
        .put("v", UNDO_RECORD_VERSION)
        .put("chain_id", offer.chainId)
        .put("expires_at", offer.expiresAt.toString())
        .put("created", created)
        .toString()
}

/** `kind` leads, so a reader tells the three apart without guessing from which fields are present. */
internal fun encodeCreatedItem(item: CreatedItem): JSONObject = when (item) {
    is CreatedItem.Written -> JSONObject()
        .put("kind", "WRITTEN")
        .put("type", item.type.name)
        .put("container_id", item.containerId)
        .put("remote_id", item.remoteId)

    is CreatedItem.Queued -> JSONObject()
        .put("kind", "QUEUED")
        .put("queue_id", item.queueId)

    is CreatedItem.Updated -> JSONObject()
        .put("kind", "UPDATED")
        .put("type", item.type.name)
        .put("container_id", item.containerId)
        .put("remote_id", item.remoteId)
        .put("prior", encodeItemDates(item.priorDates))
        // Optional and additive, so the record version does not move: an offer stored by an
        // older build simply has no prior body, which is exactly what its absence means —
        // that update wrote no note, because the build that made it could not.
        .putOpt("prior_body", item.priorBody)
}

internal fun decodeCreatedItem(json: JSONObject): CreatedItem? {
    // Not valueOf: a kind or a type a later version introduced decodes to null rather than
    // throwing, so one unreadable entry cannot take out the offer beside it.
    fun type() = ItemType.entries.firstOrNull { it.name == json.optString("type") }
    return when (json.optString("kind")) {
        "WRITTEN" -> type()?.let {
            CreatedItem.Written(it, json.getString("container_id"), json.getString("remote_id"))
        }

        "QUEUED" -> CreatedItem.Queued(json.getString("queue_id"))

        "UPDATED" -> {
            val prior = json.optJSONObject("prior")?.let(::decodeItemDates)
            val itemType = type()
            if (prior == null || itemType == null) {
                null
            } else {
                CreatedItem.Updated(
                    type = itemType,
                    containerId = json.getString("container_id"),
                    remoteId = json.getString("remote_id"),
                    priorDates = prior,
                    priorBody = json.optString("prior_body").takeIf { it.isNotBlank() },
                )
            }
        }

        else -> null
    }
}

/**
 * Null where anything in the offer is unreadable, rather than a partial offer.
 *
 * A partial one would be worse than none: the user would take an undo that removed three of
 * four items and be told it succeeded, which is the more damaging of the two lies FR-807's
 * note weighs.
 */
internal fun decodeUndoOffer(record: String): StoredUndoOffer? = try {
    val json = JSONObject(record)
    val createdJson = json.getJSONArray("created")
    val created = (0 until createdJson.length()).map { decodeCreatedItem(createdJson.getJSONObject(it)) }
    if (json.optInt("v") != UNDO_RECORD_VERSION || created.isEmpty() || created.any { it == null }) {
        null
    } else {
        StoredUndoOffer(
            chainId = json.getString("chain_id"),
            expiresAt = Instant.parse(json.getString("expires_at")),
            created = created.filterNotNull(),
        )
    }
} catch (malformed: JSONException) {
    null
} catch (malformed: java.time.format.DateTimeParseException) {
    null
}
