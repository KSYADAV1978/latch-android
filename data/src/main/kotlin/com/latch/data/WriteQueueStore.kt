package com.latch.data

import android.content.Context
import android.content.SharedPreferences
import com.latch.core.model.Item
import com.latch.core.model.ItemType
import com.latch.core.model.SyncState
import com.latch.google.ALLOWED_HOSTS
import com.latch.google.CalendarApi
import com.latch.google.FailureClass
import com.latch.google.ItemDates
import com.latch.google.TasksApi
import com.latch.google.requireGoogleEndpoint
import com.latch.wire.RemoteMetadata
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * FR-806's queue, on disk (NFR-302).
 *
 * **The payload is here and not in WorkManager.** WorkManager keeps its own SQLite database
 * and it is not encrypted; its input `Data` is also capped at roughly 10 KB, and a capture
 * can exceed that. A queue entry contains the user's own text, so it is stored here — one
 * record per entry, encrypted under an Android Keystore key, in app-private preferences —
 * and `WriteQueueWorker` carries no payload at all. It drains this.
 *
 * That is the same mechanism as [EncryptedAccountDefaultsStore] and the one NFR-203's
 * recorded reading says to reuse rather than introduce a second scheme. It also keeps Room
 * deferred: nothing here needs a query language, only "read them all, oldest first".
 *
 * **One consequence has to be stated rather than discovered.** A Keystore key does not
 * survive transfer to another device, so a queued capture is unreadable after a restore and
 * is dropped as absent — which for this store means the capture is lost. NFR-302 names
 * network failure, app termination and device restart, and the key survives all three; a
 * device transfer is not in that list, and the app disables backup and device transfer in
 * any case. So this is inside the requirement, but it is the one way a queued capture can
 * disappear.
 *
 * **Webhooks cannot travel through here** (FR-1004b), and structurally rather than by
 * remembering to check: draining goes through `CalendarApi` and `TasksApi`, whose every
 * request passes `requireGoogleEndpoint` and is refused for any host outside
 * [ALLOWED_HOSTS]. A webhook delivery could not traverse this queue even if a future change
 * tried to put one in it.
 *
 * Every method moves to [Dispatchers.IO] itself, for the reason given on
 * [EncryptedAccountDefaultsStore]: preferences and the Keystore both block.
 */
class EncryptedWriteQueueStore(context: Context) : WriteQueue {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    private val cipher = KeystoreCipher(KEY_ALIAS)

    override suspend fun enqueue(
        write: PendingWrite,
        operation: WriteOperation,
    ): String = withContext(Dispatchers.IO) {
        require(operation != WriteOperation.UPDATE || write.targetRemoteId != null) {
            "An UPDATE entry needs the id of the item it updates"
        }
        val entry = QueuedWrite(
            id = UUID.randomUUID().toString(),
            write = write,
            operation = operation,
            attempts = 0,
            lastError = null,
            needsSignIn = false,
            queuedAt = Instant.now(),
        )
        // `commit`, not `apply`: the caller is about to tell the user their capture is safe,
        // and `apply` would return before it was. A queue that reports success without
        // having written is the exact failure NFR-302 exists to prevent.
        val written = prefs.edit()
            .putString(keyFor(entry.id), cipher.encrypt(encodeQueuedWrite(entry)))
            .commit()
        check(written) { "Queue entry ${entry.id} was not written to disk." }
        entry.id
    }

    override suspend fun pending(): List<QueuedWrite> = withContext(Dispatchers.IO) {
        prefs.all.keys
            .filter { it.startsWith(ENTRY_PREFIX) }
            .mapNotNull(::read)
            // Oldest first, so a drain writes captures in the order they were made.
            .sortedBy { it.queuedAt }
    }

    override suspend fun status(): QueueStatus {
        val entries = pending()
        return QueueStatus(
            waiting = entries.count { !it.givenUp },
            givenUp = entries.count { it.givenUp },
            // FR-806a. Only a waiting entry can be unblocked by signing in; one already given
            // up on is not going to move whatever the user does.
            needsSignIn = entries.any { !it.givenUp && it.needsSignIn },
        )
    }

    override suspend fun markWritten(queueId: String, remoteId: String) {
        // The entry's whole purpose was to reach Google, and it has. Nothing keeps a record
        // of a completed write: the item in the account is the record.
        withContext(Dispatchers.IO) { prefs.edit().remove(keyFor(queueId)).commit() }
    }

    /**
     * SRS 1.24's marker, written after each insert of a chain rather than after the last.
     *
     * `commit`, like every other write here: the whole value of a marker is that it survives
     * the process dying between two inserts, which is precisely the case `apply` would lose.
     */
    override suspend fun markItemWritten(queueId: String, itemId: String, remoteId: String) {
        withContext(Dispatchers.IO) {
            val entry = read(keyFor(queueId)) ?: return@withContext
            val updated = entry.copy(writtenItemIds = entry.writtenItemIds + itemId)
            prefs.edit()
                .putString(keyFor(queueId), cipher.encrypt(encodeQueuedWrite(updated)))
                .commit()
        }
    }

    override suspend fun reviveGivenUp(): Int = withContext(Dispatchers.IO) {
        val revived = pending().filter { it.givenUp }
        val editor = prefs.edit()
        revived.forEach { entry ->
            // The error text is kept. The user asked to try again, not to be told the last
            // attempt never happened, and NFR-303 wants the reason available if it fails again.
            editor.putString(
                keyFor(entry.id),
                cipher.encrypt(encodeQueuedWrite(entry.copy(givenUp = false, attempts = 0))),
            )
        }
        editor.commit()
        revived.size
    }

    override suspend fun markFailed(
        queueId: String,
        error: String,
        permanent: Boolean,
        needsSignIn: Boolean,
        failureClass: FailureClass,
    ) {
        withContext(Dispatchers.IO) {
            val entry = read(keyFor(queueId)) ?: return@withContext
            val updated = entry.copy(
                attempts = entry.attempts + 1,
                lastError = error,
                givenUp = permanent,
                needsSignIn = needsSignIn,
                failureClass = failureClass,
            )
            prefs.edit()
                .putString(keyFor(queueId), cipher.encrypt(encodeQueuedWrite(updated)))
                .commit()
        }
    }

    override suspend fun drop(queueId: String): Boolean = withContext(Dispatchers.IO) {
        val key = keyFor(queueId)
        if (!prefs.contains(key)) return@withContext false
        prefs.edit().remove(key).commit()
    }

    private fun read(key: String): QueuedWrite? {
        val stored = prefs.getString(key, null) ?: return null
        val entry = cipher.decrypt(stored)?.let(::decodeQueuedWrite)
        if (entry == null) {
            // Unreadable now is unreadable forever — a record from a later version, or one
            // encrypted under a key that did not survive a restore. Keeping it would mean a
            // pending count that never goes down and a drain that retries it every time.
            prefs.edit().remove(key).apply()
        }
        return entry
    }

    private fun keyFor(queueId: String) = ENTRY_PREFIX + queueId

    private companion object {
        const val PREFS_FILE = "write_queue"

        /**
         * A separate alias from the defaults store's. The two have different lifetimes —
         * NFR-205's revoke clears the queue, and a queue entry is expected to be short-lived
         * — and sharing one key would mean neither could be rotated without the other.
         */
        const val KEY_ALIAS = "latch.queue.v1"
        const val ENTRY_PREFIX = "write."
    }
}

// ---------------------------------------------------------------------------------------
// The record format. Internal rather than private so the whole of it is exercised by JVM
// tests, the same arrangement as the account-defaults record and the response mappers.
// ---------------------------------------------------------------------------------------

/**
 * JSON, where the account-defaults record next door is unit-separated fields.
 *
 * That file chose separators to avoid "either a serialization dependency or hand-rolled
 * escaping", and it was right for what it stores: short, fixed, separator-free values. A
 * queue entry is not that. It carries a title and an FR-805 body of free user text, with
 * newlines in it and no character that can be reserved, so a separator format would need
 * escaping — the very thing that was being avoided. `org.json` ships inside `android.jar`
 * and already builds every request in `GoogleRest.kt`, so JSON costs no dependency here.
 *
 * The version leads for the same reason it does there: a record from a later version decodes
 * to null and is dropped rather than mis-parsed into a write that would reach the user's
 * account wrong.
 *
 * **Every earlier layout is still read**, which is the opposite of the account-defaults
 * store's answer — there a v1 record is dropped and setup simply runs again. The difference is
 * what is lost: dropping a queue entry loses a capture that exists nowhere else, which is the
 * one thing NFR-302 forbids. Nothing has to be inferred to read an old one either, because
 * every field added since has an honest default for a record that predates it.
 *
 *  - **v2** added FR-804's `target` and `prior` (SRS 1.16). An `UPDATE` was never issued at v1,
 *    so every v1 record is a `CREATE` and a `CREATE` carries neither.
 *  - **v3** holds a chain of items where 1 and 2 held one (SRS §7.1 at v1.23). An older record
 *    decodes to a chain of one, which is exactly what it was.
 *  - **v4** adds SRS 1.24's per-item written marker and the failure class FR-806's immediate
 *    drain reads. An older record has written nothing yet, and is presumed to have stopped on
 *    the transport — which is why the queue exists, and is the reading that retries soonest.
 *  - **v5** adds FR-510's per-item note, which carries the past date a follow-up was created
 *    for. Absent on every earlier record, and correctly so: no earlier record could have held a
 *    follow-up, because the requirement was not built.
 *  - **v6** adds FR-601's reminder minutes, for the same reason and with the same defence: no
 *    earlier record could have held a recipe expansion, and an absent list means Google's own
 *    calendar defaults, which is what every earlier item got.
 *
 * **The queue deliberately did not move into FR-701's SQLite database.** SRS 1.24 named the
 * marker as needing that storage, and it does not: this record can carry it. What the move
 * would have cost is a migration of entries holding captures that exist nowhere else,
 * undertaken to tidy a storage layer — exactly the trade NFR-302 forbids. FR-803's hash index
 * went into the database instead, because that is a new index and loses nothing if it starts
 * empty.
 */
internal const val QUEUE_RECORD_VERSION = 6

/** The oldest record layout still readable. See [QUEUE_RECORD_VERSION]. */
internal const val QUEUE_RECORD_MIN_VERSION = 1

internal fun encodeItem(item: Item): JSONObject = JSONObject()
    .put("id", item.id)
    .put("capture_id", item.captureId)
    .putOpt("chain_id", item.chainId)
    .put("type", item.type.name)
    .put("title", item.title)
    .putOpt("start", item.start?.toString())
    .putOpt("end", item.end?.toString())
    .put("all_day", item.allDay)
    .putOpt("due_date", item.dueDate?.toString())
    .putOpt("location", item.location)
    .putOpt("notes", item.notes)
    .put("reminders", JSONArray(item.reminderMinutes))
    .putOpt("calendar_id", item.calendarId)
    .putOpt("task_list_id", item.taskListId)

internal fun decodeItem(itemJson: JSONObject): Item? {
    // Not valueOf: a type written by a later version must decode to null rather than throw,
    // so one unreadable entry cannot take out a drain.
    val type = ItemType.entries.firstOrNull { it.name == itemJson.getString("type") } ?: return null
    return Item(
        id = itemJson.getString("id"),
        captureId = itemJson.getString("capture_id"),
        chainId = itemJson.optString("chain_id").takeIf { it.isNotBlank() },
        type = type,
        title = itemJson.getString("title"),
        start = itemJson.optString("start").takeIf { it.isNotBlank() }?.let(LocalDateTime::parse),
        end = itemJson.optString("end").takeIf { it.isNotBlank() }?.let(LocalDateTime::parse),
        allDay = itemJson.optBoolean("all_day"),
        dueDate = itemJson.optString("due_date").takeIf { it.isNotBlank() }?.let(LocalDate::parse),
        location = itemJson.optString("location").takeIf { it.isNotBlank() },
        // FR-510's past-date note, added at record version 5. Absent on every earlier record,
        // which is correct: no earlier record could have carried a follow-up.
        notes = itemJson.optString("notes").takeIf { it.isNotBlank() },
        // FR-601's reminders, added at record version 6. Absent on every earlier record, which
        // is right: no earlier record could have carried a recipe expansion.
        reminderMinutes = itemJson.optJSONArray("reminders")
            ?.let { array -> (0 until array.length()).map(array::getInt) }
            .orEmpty(),
        calendarId = itemJson.optString("calendar_id").takeIf { it.isNotBlank() },
        taskListId = itemJson.optString("task_list_id").takeIf { it.isNotBlank() },
        // Read back as queued, not as the draft it was: this record exists because the
        // write is outstanding.
        syncState = SyncState.QUEUED,
    )
}

internal fun encodeQueuedWrite(entry: QueuedWrite): String {
    val metadata = entry.write.metadata

    val json = JSONObject()
        .put("v", QUEUE_RECORD_VERSION)
        .put("id", entry.id)
        .put("op", entry.operation.name)
        .put("attempts", entry.attempts)
        .put("given_up", entry.givenUp)
        .put("needs_sign_in", entry.needsSignIn)
        .put("failure_class", entry.failureClass.name)
        .put("queued_at", entry.queuedAt.toString())
        .put("zone", entry.write.timeZone)
        .put("body", entry.write.body)

    entry.lastError?.let { json.put("last_error", it) }

    // Both are UPDATE-only. Omitted rather than written null for a CREATE, the rule §7.2
    // applies to its own optional keys: an empty value cannot be told from a value that is
    // genuinely empty.
    entry.write.targetRemoteId?.let { json.put("target", it) }
    entry.write.priorState?.let { json.put("prior", encodeItemDates(it)) }

    val items = JSONArray()
    for (one in entry.write.items) items.put(encodeItem(one))
    json.put("items", items)

    // SRS 1.24. Omitted where empty rather than written as an empty array — the rule §7.2
    // applies to its own optional keys, and it keeps a v4 record of an unattempted entry the
    // same shape as the v3 record it replaces.
    if (entry.writtenItemIds.isNotEmpty()) {
        val written = JSONArray()
        entry.writtenItemIds.sorted().forEach(written::put)
        json.put("written_items", written)
    }

    json.put(
        "metadata",
        JSONObject()
            .put("version", metadata.version)
            .put("source_hash", metadata.sourceHash)
            .put("item_key", metadata.itemKey)
            .put("chain_id", metadata.chainId)
            .put("captured_at", metadata.capturedAt.toString())
            .putOpt("source_app", metadata.sourceApp)
            .putOpt("recipe_id", metadata.recipeId),
    )

    return json.toString()
}

/**
 * Encodes the FR-804 prior state. `kind` leads so a reader can tell the two apart without
 * guessing from which fields happen to be present.
 */
internal fun encodeItemDates(dates: ItemDates): JSONObject = when (dates) {
    is ItemDates.Event -> JSONObject()
        .put("kind", "EVENT")
        .put("start", dates.start.toString())
        .put("end", dates.end.toString())
        .put("all_day", dates.allDay)
        .putOpt("time_zone", dates.timeZone)

    is ItemDates.Task -> JSONObject()
        .put("kind", "TASK")
        .putOpt("due", dates.due?.toString())
}

/** Null for a kind a later version introduced, so one entry cannot take out a drain. */
internal fun decodeItemDates(json: JSONObject): ItemDates? = when (json.optString("kind")) {
    "EVENT" -> ItemDates.Event(
        start = LocalDateTime.parse(json.getString("start")),
        end = LocalDateTime.parse(json.getString("end")),
        allDay = json.optBoolean("all_day"),
        timeZone = json.optString("time_zone").takeIf { it.isNotBlank() },
    )

    "TASK" -> ItemDates.Task(
        due = json.optString("due").takeIf { it.isNotBlank() }?.let(LocalDate::parse),
    )

    else -> null
}

internal fun decodeQueuedWrite(record: String): QueuedWrite? = try {
    val json = JSONObject(record)
    val version = json.optInt("v")
    if (version < QUEUE_RECORD_MIN_VERSION || version > QUEUE_RECORD_VERSION) {
        null
    } else {
        val metadataJson = json.getJSONObject("metadata")

        // A v3 record holds a chain; v1 and v2 held exactly one item, which is a chain of
        // one and decodes as such — nothing has to be inferred to read them.
        val itemsJson = json.optJSONArray("items")
        val items = if (itemsJson != null) {
            (0 until itemsJson.length()).map { itemsJson.getJSONObject(it) }
        } else {
            listOf(json.getJSONObject("item"))
        }.map(::decodeItem)

        // Not valueOf: an operation written by a later version must decode to null rather
        // than throw, so one unreadable entry cannot take out a drain.
        val operation = WriteOperation.entries.firstOrNull { it.name == json.getString("op") }

        if (items.isEmpty() || items.any { it == null } || operation == null) {
            null
        } else {
            QueuedWrite(
                id = json.getString("id"),
                operation = operation,
                attempts = json.optInt("attempts"),
                lastError = json.optString("last_error").takeIf { it.isNotBlank() },
                givenUp = json.optBoolean("given_up"),
                needsSignIn = json.optBoolean("needs_sign_in"),
                queuedAt = Instant.parse(json.getString("queued_at")),
                // Not valueOf: a class written by a later version decodes to the default
                // rather than throwing. TRANSPORT is the honest default for a record that
                // predates the field — it is why the queue exists, and it is the reading that
                // retries soonest, which is the safe direction for a capture.
                failureClass = FailureClass.entries
                    .firstOrNull { it.name == json.optString("failure_class") }
                    ?: FailureClass.TRANSPORT,
                writtenItemIds = json.optJSONArray("written_items")
                    ?.let { array -> (0 until array.length()).map(array::getString).toSet() }
                    .orEmpty(),
                write = PendingWrite(
                    items = items.filterNotNull(),
                    metadata = RemoteMetadata(
                        sourceHash = metadataJson.getString("source_hash"),
                        itemKey = metadataJson.getString("item_key"),
                        chainId = metadataJson.getString("chain_id"),
                        capturedAt = Instant.parse(metadataJson.getString("captured_at")),
                        sourceApp = metadataJson.optString("source_app").takeIf { it.isNotBlank() },
                        recipeId = metadataJson.optString("recipe_id").takeIf { it.isNotBlank() },
                        version = metadataJson.getString("version"),
                    ),
                    body = json.getString("body"),
                    timeZone = json.getString("zone"),
                    // Absent on every v1 record and on every CREATE, which is the same set.
                    targetRemoteId = json.optString("target").takeIf { it.isNotBlank() },
                    priorState = json.optJSONObject("prior")?.let(::decodeItemDates),
                ),
            )
        }
    }
} catch (malformed: JSONException) {
    null
} catch (malformed: java.time.format.DateTimeParseException) {
    null
} catch (rejected: IllegalArgumentException) {
    // Item's own init refuses a task carrying a start (§8.1). A record that violates it was
    // never writable in the first place and must not take out the drain.
    null
}
