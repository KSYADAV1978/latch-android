package com.latch.data

import android.content.Context
import com.latch.core.model.CaptureLayer
import com.latch.core.model.CaptureState
import com.latch.core.model.InboxCapture
import com.latch.core.model.InboxReason
import com.latch.core.model.ItemType
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject

/**
 * FR-701 to FR-705: the local Capture Inbox.
 *
 * Nothing here reaches Google. Contents are local until the user confirms them (FR-703), the
 * store is app-private and encrypted at rest (NFR-204), and content from the notification
 * listener never arrives at all (FR-210, NFR-206).
 */
interface CaptureInbox {
    /** FR-701. */
    suspend fun add(capture: InboxCapture)

    /** Every row, oldest first, snoozed ones included. FR-705's review reads this. */
    suspend fun all(): List<InboxCapture>

    /** FR-702's list: what the user is being asked to deal with now. */
    suspend fun due(now: Instant): List<InboxCapture>

    /** FR-704: an unobtrusive count. Excludes snoozed rows, because they are not pending on the user. */
    suspend fun pendingCount(now: Instant): Int

    suspend fun find(id: String): InboxCapture?

    /** FR-702: assign a date, edit, snooze. The whole row is written back. */
    suspend fun update(capture: InboxCapture)

    /** FR-702: discard, and what a save does once the item has reached Google. */
    suspend fun discard(id: String)

    /** NFR-205: one action deletes all local data. */
    suspend fun deleteAll()
}

/**
 * [CaptureInbox] over [LatchDatabase], one encrypted payload per row.
 *
 * The two columns outside the payload — `captured_at` and `snoozed_until` — are there because
 * they are what the store *queries* on, and a value that has to be decrypted to be compared is
 * not a query. Neither is content: one is when a gesture happened and the other is a date the
 * user chose, and both are already visible in the row count the home screen shows.
 *
 * Every method moves to [Dispatchers.IO] itself, for the reason recorded on
 * [EncryptedAccountDefaultsStore]: SQLite and the Keystore both block, and a `suspend` function
 * that blocks its caller reads as safe to call from anywhere.
 */
class SqliteCaptureInbox(context: Context) : CaptureInbox {

    private val database = LatchDatabase(context)
    private val cipher = KeystoreCipher(KEY_ALIAS)

    override suspend fun add(capture: InboxCapture) = withContext(Dispatchers.IO) {
        write(capture)
    }

    override suspend fun update(capture: InboxCapture) = withContext(Dispatchers.IO) {
        write(capture)
    }

    private fun write(capture: InboxCapture) {
        database.writableDatabase.replaceOrThrow(
            LatchDatabase.TABLE_INBOX,
            null,
            contentValuesOf(
                "id" to capture.id,
                "captured_at" to capture.capturedAt.toEpochMilli(),
                "snoozed_until" to capture.snoozedUntil?.toEpochMilli(),
                "state" to capture.state.name,
                "payload" to cipher.encrypt(encodeInboxCapture(capture)),
            ),
        )
    }

    override suspend fun all(): List<InboxCapture> = withContext(Dispatchers.IO) { readAll() }

    override suspend fun due(now: Instant): List<InboxCapture> = withContext(Dispatchers.IO) {
        readAll().filter { it.isDue(now) }
    }

    override suspend fun pendingCount(now: Instant): Int = due(now).size

    override suspend fun find(id: String): InboxCapture? = withContext(Dispatchers.IO) {
        database.readableDatabase.query(
            LatchDatabase.TABLE_INBOX,
            arrayOf("id", "payload"),
            "id = ?",
            arrayOf(id),
            null,
            null,
            null,
        ).consume { cursor -> if (cursor.moveToFirst()) decodeRow(cursor.getString(0), cursor.getString(1)) else null }
    }

    override suspend fun discard(id: String) {
        withContext(Dispatchers.IO) {
            database.writableDatabase.delete(LatchDatabase.TABLE_INBOX, "id = ?", arrayOf(id))
        }
    }

    override suspend fun deleteAll() {
        withContext(Dispatchers.IO) {
            database.writableDatabase.delete(LatchDatabase.TABLE_INBOX, null, null)
        }
    }

    /** Oldest first: FR-705 wants an aged capture surfacing rather than sinking under new ones. */
    private fun readAll(): List<InboxCapture> =
        database.readableDatabase.query(
            LatchDatabase.TABLE_INBOX,
            arrayOf("id", "payload"),
            null,
            null,
            null,
            null,
            "captured_at ASC",
        ).consume { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    decodeRow(cursor.getString(0), cursor.getString(1))?.let(::add)
                }
            }
        }

    /**
     * A row that cannot be read is dropped, one row at a time.
     *
     * The same rule the queue store follows, and for the weaker version of the same reason: a
     * record from a later version or one encrypted under a key that did not survive a restore
     * is unreadable now and unreadable for ever, and keeping it would mean a count that never
     * goes down over a row that can never be opened. It costs a capture, which is why the
     * record carries a leading version so that a *readable* older layout is read rather than
     * discarded.
     */
    private fun decodeRow(id: String, payload: String): InboxCapture? {
        val capture = cipher.decrypt(payload)?.let(::decodeInboxCapture)
        if (capture == null) {
            database.writableDatabase.delete(LatchDatabase.TABLE_INBOX, "id = ?", arrayOf(id))
        }
        return capture
    }

    private companion object {
        /**
         * Its own alias, as the queue has its own. NFR-205's revoke clears both, but they have
         * different lifetimes — a queue entry is short-lived and an Inbox row may sit for a
         * fortnight (FR-705) — and one key would mean neither could be rotated alone.
         */
        const val KEY_ALIAS = "latch.inbox.v1"
    }
}

// ---------------------------------------------------------------------------------------
// The record format. Internal rather than private so the whole of it is exercised by JVM
// tests — the arrangement the account-defaults record and the queue record already have, and
// the only part of this file a test without a device can reach.
// ---------------------------------------------------------------------------------------

/**
 * JSON, and a leading version, for exactly the reasons the queue record gives: an Inbox row
 * carries free user text with newlines in it, which a separator format would have to escape,
 * and `org.json` ships inside `android.jar` so it costs no dependency.
 */
internal const val INBOX_RECORD_VERSION = 1

internal fun encodeInboxCapture(capture: InboxCapture): String = JSONObject()
    .put("v", INBOX_RECORD_VERSION)
    .put("id", capture.id)
    .put("text", capture.rawText)
    .put("layer", capture.layer.name)
    .putOpt("app_id", capture.appId)
    .putOpt("preferred_title", capture.preferredTitle)
    .put("ocr_used", capture.ocrUsed)
    .put("captured_at", capture.capturedAt.toString())
    .put("captured_local", capture.capturedLocal.toString())
    .put("zone", capture.zone)
    .put("confidence", capture.confidence)
    .put("reason", capture.reason.name)
    .putOpt("assigned_date", capture.assignedDate?.toString())
    .putOpt("edited_title", capture.editedTitle)
    // Optional and additive, so the record version does not move: an older row simply has no
    // override, which is exactly what its absence means.
    .putOpt("type_override", capture.typeOverride?.name)
    .putOpt("snoozed_until", capture.snoozedUntil?.toString())
    .put("state", capture.state.name)
    .toString()

internal fun decodeInboxCapture(record: String): InboxCapture? = try {
    val json = JSONObject(record)
    val version = json.optInt("v")
    // Not valueOf anywhere below: a value written by a later version must decode to null
    // rather than throw, so one unreadable row cannot take out the whole list.
    val layer = CaptureLayer.entries.firstOrNull { it.name == json.getString("layer") }
    val reason = InboxReason.entries.firstOrNull { it.name == json.getString("reason") }
    val state = CaptureState.entries.firstOrNull { it.name == json.getString("state") }
    if (version != INBOX_RECORD_VERSION || layer == null || reason == null || state == null) {
        null
    } else {
        InboxCapture(
            id = json.getString("id"),
            rawText = json.getString("text"),
            layer = layer,
            appId = json.optString("app_id").takeIf { it.isNotBlank() },
            preferredTitle = json.optString("preferred_title").takeIf { it.isNotBlank() },
            ocrUsed = json.optBoolean("ocr_used"),
            capturedAt = Instant.parse(json.getString("captured_at")),
            capturedLocal = LocalDateTime.parse(json.getString("captured_local")),
            zone = json.getString("zone"),
            confidence = json.getDouble("confidence"),
            reason = reason,
            assignedDate = json.optString("assigned_date").takeIf { it.isNotBlank() }?.let(LocalDate::parse),
            editedTitle = json.optString("edited_title").takeIf { it.isNotBlank() },
            typeOverride = ItemType.entries
                .firstOrNull { it.name == json.optString("type_override") },
            snoozedUntil = json.optString("snoozed_until").takeIf { it.isNotBlank() }?.let(Instant::parse),
            state = state,
        )
    }
} catch (malformed: JSONException) {
    null
} catch (malformed: java.time.format.DateTimeParseException) {
    null
}
