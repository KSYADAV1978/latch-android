package com.latch.data

import android.content.Context
import com.latch.core.model.ItemType
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject

/**
 * One item this device has written to Google, remembered locally.
 *
 * [dates] is what Latch wrote, **not what the item holds now** — the user may have moved it by
 * hand in Google Calendar since. That distinction is what bounds where this index may be used;
 * see [LocalItemIndex].
 */
data class WrittenItem(
    val remoteId: String,
    /** The calendar id for an event, the task list id for a task. Both APIs address by container. */
    val containerId: String,
    val type: ItemType,
    val sourceHash: String,
    val itemKey: String,
    val dates: ItemDates,
    val writtenAt: Instant,
)

/**
 * FR-803's local index of source hashes — the cure both FR-803 and FR-804 record as "deferred
 * because it needs local storage that does not yet exist… revisit when FR-701 lands".
 *
 * **What it cures, and it is the failure that actually bit.** The Tasks API has no content
 * filter at all, so FR-803's task check is a page scan that is allowed to give up, and
 * `DuplicateSearch.scanCapped` is the honest signal that it did. A user with a large task list
 * therefore gets a duplicate the app could see coming and could not prevent. A local hit is
 * exact and costs no request. It also answers **offline**, which nothing else can: FR-806's
 * queue exists because there is no network, and until now that meant no duplicate check either
 * until the drain ran.
 *
 * **A miss is not an answer**, and callers must treat it as one only after asking Google. The
 * index knows what *this device* wrote; §4.1 has three clients writing to one account, so a
 * duplicate created on the PC is invisible here. Consulting it first and Google second is a
 * fast path, never a replacement.
 *
 * **It is deliberately not used to cure FR-804's capped scan**, and that is a decision rather
 * than an omission. FR-804 may only offer an update it can undo, and SRS 1.19 requires the
 * prior state to be what the item held *before* this app changed it, read at match time. A row
 * here records what Latch wrote, so restoring it would write back a value the user was never
 * shown and would silently overwrite a hand edit — the exact defect 1.19 refused. What the
 * index may do for FR-804 is decide whether there is a **question worth asking**
 * ([byItemKey]); the answer is still read from Google.
 */
interface LocalItemIndex {
    /** Called after every successful insert, and never before one. */
    suspend fun remember(item: WrittenItem)

    /** FR-803. Null means "this device did not write it", never "it is not there". */
    suspend fun bySourceHash(sourceHash: String): WrittenItem?

    /** FR-804, offline: is there anything with this identity worth asking about? */
    suspend fun byItemKey(itemKey: String): List<WrittenItem>

    /** FR-807: an undone item was never written after all. */
    suspend fun forget(containerId: String, remoteId: String)

    /** NFR-205. */
    suspend fun clear()
}

/** [LocalItemIndex] over [LatchDatabase]. Digests are stored in the clear; see that class. */
class SqliteItemIndex(context: Context) : LocalItemIndex {

    private val database = LatchDatabase(context)

    override suspend fun remember(item: WrittenItem) {
        withContext(Dispatchers.IO) {
            database.writableDatabase.replaceOrThrow(
                LatchDatabase.TABLE_WRITTEN,
                null,
                contentValuesOf(
                    "remote_id" to item.remoteId,
                    "container_id" to item.containerId,
                    "item_type" to item.type.name,
                    "source_hash" to item.sourceHash,
                    "item_key" to item.itemKey,
                    "dates" to encodeItemDates(item.dates).toString(),
                    "written_at" to item.writtenAt.toEpochMilli(),
                ),
            )
        }
    }

    override suspend fun bySourceHash(sourceHash: String): WrittenItem? =
        withContext(Dispatchers.IO) { query("source_hash = ?", sourceHash).firstOrNull() }

    override suspend fun byItemKey(itemKey: String): List<WrittenItem> =
        withContext(Dispatchers.IO) { query("item_key = ?", itemKey) }

    override suspend fun forget(containerId: String, remoteId: String) {
        withContext(Dispatchers.IO) {
            database.writableDatabase.delete(
                LatchDatabase.TABLE_WRITTEN,
                "container_id = ? AND remote_id = ?",
                arrayOf(containerId, remoteId),
            )
        }
    }

    override suspend fun clear() {
        withContext(Dispatchers.IO) {
            database.writableDatabase.delete(LatchDatabase.TABLE_WRITTEN, null, null)
        }
    }

    private fun query(where: String, argument: String): List<WrittenItem> =
        database.readableDatabase.query(
            LatchDatabase.TABLE_WRITTEN,
            arrayOf("remote_id", "container_id", "item_type", "source_hash", "item_key", "dates", "written_at"),
            where,
            arrayOf(argument),
            null,
            null,
            // Newest first: §5.8's tie-break gives an FR-804 offer to the latest position, and
            // the most recently written row is the closest this index gets to knowing which.
            "written_at DESC",
        ).consume { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    writtenItemFrom(
                        remoteId = cursor.getString(0),
                        containerId = cursor.getString(1),
                        type = cursor.getString(2),
                        sourceHash = cursor.getString(3),
                        itemKey = cursor.getString(4),
                        dates = cursor.getString(5),
                        writtenAt = cursor.getLong(6),
                    )?.let(::add)
                }
            }
        }
}

/**
 * One row, or null where it cannot be read.
 *
 * Internal and pure so the decode is JVM-tested; the SQL around it is not reachable without a
 * device, which is the same split the response mappers in `GoogleRest.kt` keep.
 */
internal fun writtenItemFrom(
    remoteId: String,
    containerId: String,
    type: String,
    sourceHash: String,
    itemKey: String,
    dates: String,
    writtenAt: Long,
): WrittenItem? = try {
    val itemType = ItemType.entries.firstOrNull { it.name == type }
    val parsed = decodeItemDates(JSONObject(dates))
    if (itemType == null || parsed == null) {
        null
    } else {
        WrittenItem(
            remoteId = remoteId,
            containerId = containerId,
            type = itemType,
            sourceHash = sourceHash,
            itemKey = itemKey,
            dates = parsed,
            writtenAt = Instant.ofEpochMilli(writtenAt),
        )
    }
} catch (malformed: JSONException) {
    null
} catch (malformed: java.time.format.DateTimeParseException) {
    null
}
