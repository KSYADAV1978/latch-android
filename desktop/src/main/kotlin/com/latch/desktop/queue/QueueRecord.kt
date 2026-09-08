package com.latch.desktop.queue

import com.latch.core.model.Item
import com.latch.core.model.ItemType
import com.latch.core.model.SyncState
import com.latch.google.FailureClass
import com.latch.google.json.JSONArray
import com.latch.google.json.JSONObject
import com.latch.wire.RemoteMetadata
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * One item of a queued chain, and whether it has already been written.
 *
 * SRS 1.24's per-item marker. A chain whose first insert succeeded and whose second failed must
 * resume rather than restart, or the retry writes the first item a second time — which is
 * AC-07 failing inside FR-806, in the one place FR-803's check cannot see it because the
 * duplicate is being made by the retry of the very entry that made the original.
 */
data class QueuedItem(val item: Item, val writtenId: String? = null)

/**
 * A capture waiting to be written.
 *
 * **It carries the finished write, not the capture.** The metadata, the composed FR-805
 * description and the drafted items are all computed at enqueue time and stored, so the drain
 * writes exactly the bytes the save would have. Storing the raw text and re-deriving at drain
 * would run the parser against a later clock, and FR-515 makes a parse a function of the
 * instant it was made at — "kal" would move a day every time the queue retried.
 */
data class QueuedWrite(
    val id: String,
    val metadata: RemoteMetadata,
    /** FR-805's body, composed once at enqueue. */
    val body: String,
    val calendarId: String,
    val taskListId: String,
    val timeZone: String,
    val items: List<QueuedItem>,
    val queuedAt: Instant,
    val attempts: Int = 0,
    val failureClass: FailureClass? = null,
    val nextAttemptAt: Instant = queuedAt,
    /**
     * Retries have stopped. The entry **stays**, and that is design principle 1 rather than
     * politeness: it holds a capture that exists nowhere else, so giving up means stopping and
     * saying so, never discarding. "Retry now" revives it.
     */
    val givenUp: Boolean = false,
    /**
     * FR-806a: this entry is waiting on a **sign-in**, not on a network (SRS 1.192).
     *
     * Recorded on the entry rather than derived from [failureClass] because it has to survive
     * a restart: the whole point of the state is that Latch can be closed, reopened and still
     * say why nothing is moving. It is the desktop's half of Android's `QueueStatus.needsSignIn`.
     */
    val needsSignIn: Boolean = false,
) {
    /** What is still owed to Google. SRS 1.24: a resumed chain writes only what it has not. */
    val remaining: List<QueuedItem> get() = items.filter { it.writtenId == null }
}

const val QUEUE_RECORD_VERSION: Int = 1

/**
 * The record, as JSON.
 *
 * Hand-encoded field by field rather than serialised from the type, for the reason FR-1004a's
 * payload is: a record built by reflecting over `Item` would grow silently the next time `Item`
 * did, and what would grow is a file holding the user's captures. Adding a field here is a
 * visible change rather than an invisible one.
 *
 * **What that sentence used to say, and why it is corrected (SRS 1.192): "with a version bump
 * beside it".** On *this* record a bump is not a formality — `decodeQueuedWrite` refuses any
 * version but the current one, and a refused record is a **dropped queue entry**, which is a
 * lost capture and the one thing NFR-302 forbids outright. So the version marks a change that
 * would make an old record *wrong*, never the mere presence of a new field. A field an older
 * record can be read without — a flag defaulting to false, as `given_up` and `failure_class`
 * already are — is added without one, and the encoder's conservatism is what keeps that safe.
 */
fun QueuedWrite.encode(): String = JSONObject()
    .put("v", QUEUE_RECORD_VERSION)
    .put("id", id)
    .put("body", body)
    .put("calendar_id", calendarId)
    .put("task_list_id", taskListId)
    .put("time_zone", timeZone)
    .put("queued_at", queuedAt.toString())
    .put("attempts", attempts)
    .put("failure_class", failureClass?.name)
    .put("next_attempt_at", nextAttemptAt.toString())
    .put("given_up", givenUp)
    .put("needs_sign_in", needsSignIn)
    .put("metadata", metadata.encodeForQueue())
    .put("items", JSONArray().also { array -> items.forEach { array.put(it.encodeForQueue()) } })
    .toString()

/**
 * Null where the record is from another version, is malformed, or is missing something a write
 * needs.
 *
 * Dropped rather than repaired, as every record format here is — but this one is the expensive
 * case and the caller must know it: dropping a queue entry **loses a capture**, which is the
 * one thing NFR-302 forbids. So the encoder above is deliberately conservative, the version is
 * bumped rather than fields quietly added, and `WriteQueue` reports what it dropped rather than
 * discarding it silently.
 */
fun decodeQueuedWrite(json: String): QueuedWrite? {
    val record = runCatching { JSONObject(json) }.getOrNull() ?: return null
    if (record.optInt("v", -1) != QUEUE_RECORD_VERSION) return null

    val id = record.optString("id").ifBlank { return null }
    val calendarId = record.optString("calendar_id").ifBlank { return null }
    val taskListId = record.optString("task_list_id").ifBlank { return null }
    val metadata = record.optJSONObject("metadata")?.let(::decodeMetadataForQueue) ?: return null
    val queuedAt = record.optString("queued_at").toInstantOrNull() ?: return null

    val items = record.optJSONArray("items") ?: return null
    val decoded = (0 until items.length()).map { index ->
        items.optJSONObject(index)?.let(::decodeQueuedItem) ?: return null
    }
    if (decoded.isEmpty()) return null

    return QueuedWrite(
        id = id,
        metadata = metadata,
        body = record.optString("body"),
        calendarId = calendarId,
        taskListId = taskListId,
        timeZone = record.optString("time_zone").ifBlank { "UTC" },
        items = decoded,
        queuedAt = queuedAt,
        attempts = record.optInt("attempts", 0),
        failureClass = record.optString("failure_class").takeIf { it.isNotBlank() }
            ?.let { name -> runCatching { FailureClass.valueOf(name) }.getOrNull() },
        nextAttemptAt = record.optString("next_attempt_at").toInstantOrNull() ?: queuedAt,
        givenUp = record.optBoolean("given_up", false),
        // Absent in a record written before SRS 1.192, and false is right for one: an entry
        // queued by that build was never held for a sign-in, because that build could not
        // hold one at all — it reported the capture and dropped it.
        needsSignIn = record.optBoolean("needs_sign_in", false),
    )
}

// ---------------------------------------------------------------------------- the parts

private fun RemoteMetadata.encodeForQueue(): JSONObject = JSONObject()
    .put("source_hash", sourceHash)
    .put("item_key", itemKey)
    .put("chain_id", chainId)
    .put("captured_at", capturedAt.toString())
    .put("source_app", sourceApp)
    .put("recipe", recipeId)

private fun decodeMetadataForQueue(json: JSONObject): RemoteMetadata? {
    val sourceHash = json.optString("source_hash").ifBlank { return null }
    val itemKey = json.optString("item_key").ifBlank { return null }
    val chainId = json.optString("chain_id").ifBlank { return null }
    val capturedAt = json.optString("captured_at").toInstantOrNull() ?: return null
    return RemoteMetadata(
        sourceHash = sourceHash,
        itemKey = itemKey,
        chainId = chainId,
        capturedAt = capturedAt,
        // Absent rather than empty, which §7.2 is strict about: `latch.recipe` must not exist
        // on an item that had no recipe, and an empty string is a value.
        sourceApp = json.optString("source_app").takeIf { it.isNotBlank() },
        recipeId = json.optString("recipe").takeIf { it.isNotBlank() },
    )
}

private fun QueuedItem.encodeForQueue(): JSONObject = JSONObject()
    .put("written_id", writtenId)
    .put("id", item.id)
    .put("capture_id", item.captureId)
    .put("chain_id", item.chainId)
    .put("type", item.type.name)
    .put("title", item.title)
    .put("start", item.start?.toString())
    .put("end", item.end?.toString())
    .put("all_day", item.allDay)
    .put("due_date", item.dueDate?.toString())
    .put("location", item.location)
    .put("notes", item.notes)
    .put("calendar_id", item.calendarId)
    .put("task_list_id", item.taskListId)
    .put(
        "reminder_minutes",
        JSONArray().also { array -> item.reminderMinutes.forEach { array.put(it) } },
    )

private fun decodeQueuedItem(json: JSONObject): QueuedItem? {
    val type = runCatching { ItemType.valueOf(json.optString("type")) }.getOrNull() ?: return null
    val reminders = json.optJSONArray("reminder_minutes")
    return QueuedItem(
        item = Item(
            id = json.optString("id").ifBlank { return null },
            captureId = json.optString("capture_id"),
            chainId = json.optString("chain_id").takeIf { it.isNotBlank() },
            type = type,
            title = json.optString("title"),
            start = json.optString("start").toLocalDateTimeOrNull(),
            end = json.optString("end").toLocalDateTimeOrNull(),
            allDay = json.optBoolean("all_day", false),
            dueDate = json.optString("due_date").toLocalDateOrNull(),
            location = json.optString("location").takeIf { it.isNotBlank() },
            notes = json.optString("notes").takeIf { it.isNotBlank() },
            calendarId = json.optString("calendar_id").takeIf { it.isNotBlank() },
            taskListId = json.optString("task_list_id").takeIf { it.isNotBlank() },
            reminderMinutes = (0 until (reminders?.length() ?: 0)).mapNotNull {
                reminders?.optString(it)?.toIntOrNull()
            },
            // A queued item is by definition not written yet; the marker for one that is
            // lives on QueuedItem, not here.
            syncState = SyncState.DRAFT,
        ),
        writtenId = json.optString("written_id").takeIf { it.isNotBlank() },
    )
}

private fun String.toInstantOrNull(): Instant? =
    takeIf { it.isNotBlank() }?.let { runCatching { Instant.parse(it) }.getOrNull() }

private fun String.toLocalDateTimeOrNull(): LocalDateTime? =
    takeIf { it.isNotBlank() }?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }

private fun String.toLocalDateOrNull(): LocalDate? =
    takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
