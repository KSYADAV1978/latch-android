package com.latch.desktop.inbox

import com.latch.core.model.CaptureLayer
import com.latch.core.model.CaptureState
import com.latch.core.model.InboxCapture
import com.latch.core.model.InboxReason
import com.latch.core.model.ItemType
import com.latch.google.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * FR-701's row, as JSON, for the same reasons the queue record gives: an Inbox row carries free
 * user text with newlines in it, which a separator format would have to escape, and the JSON
 * here is `:google`'s hand-written one so it costs no dependency.
 *
 * **The field names are the Android record's, deliberately.** Nothing reads across the two
 * clients today — an Inbox is local by FR-703 and neither machine can see the other's — so this
 * is not a compatibility guarantee. It is so that a person reading one file against the other
 * is reading the same names, and so a future export between machines is a transport question
 * rather than a translation one.
 *
 * A leading version, and a record from a version this build does not know decodes to null. What
 * the *store* then does with that null is where this client and the phone part company, and
 * [DesktopInbox] is where that reading is written down.
 */
const val INBOX_RECORD_VERSION: Int = 1

fun encodeInboxCapture(capture: InboxCapture): String = JSONObject()
    .put("v", INBOX_RECORD_VERSION)
    .put("id", capture.id)
    .put("text", capture.rawText)
    .put("layer", capture.layer.name)
    .put("app_id", capture.appId)
    .put("preferred_title", capture.preferredTitle)
    .put("ocr_used", capture.ocrUsed)
    .put("captured_at", capture.capturedAt.toString())
    .put("captured_local", capture.capturedLocal.toString())
    .put("zone", capture.zone)
    .put("confidence", capture.confidence)
    .put("reason", capture.reason.name)
    .put("assigned_date", capture.assignedDate?.toString())
    .put("edited_title", capture.editedTitle)
    .put("type_override", capture.typeOverride?.name)
    .put("snoozed_until", capture.snoozedUntil?.toString())
    .put("state", capture.state.name)
    .toString()

/**
 * Null where the record is from another version, malformed, or missing something the row needs.
 *
 * Not `valueOf` anywhere below: a layer or a reason written by a later version has to decode to
 * null rather than throw, so one row this build does not understand cannot take out the list.
 */
fun decodeInboxCapture(record: String): InboxCapture? {
    val json = runCatching { JSONObject(record) }.getOrNull() ?: return null
    if (json.optInt("v", -1) != INBOX_RECORD_VERSION) return null

    val layer = CaptureLayer.entries.firstOrNull { it.name == json.optString("layer") } ?: return null
    val reason = InboxReason.entries.firstOrNull { it.name == json.optString("reason") } ?: return null
    val state = CaptureState.entries.firstOrNull { it.name == json.optString("state") } ?: return null
    val id = json.optString("id").ifBlank { return null }
    val capturedAt = json.optString("captured_at").toInstantOrNull() ?: return null
    val capturedLocal = json.optString("captured_local").toLocalDateTimeOrNull() ?: return null

    return InboxCapture(
        id = id,
        rawText = json.optString("text"),
        layer = layer,
        appId = json.optString("app_id").takeIf { it.isNotBlank() },
        preferredTitle = json.optString("preferred_title").takeIf { it.isNotBlank() },
        ocrUsed = json.optBoolean("ocr_used", false),
        capturedAt = capturedAt,
        capturedLocal = capturedLocal,
        zone = json.optString("zone").ifBlank { "UTC" },
        confidence = json.optDouble("confidence", 0.0),
        reason = reason,
        assignedDate = json.optString("assigned_date").toLocalDateOrNull(),
        editedTitle = json.optString("edited_title").takeIf { it.isNotBlank() },
        typeOverride = ItemType.entries.firstOrNull { it.name == json.optString("type_override") },
        snoozedUntil = json.optString("snoozed_until").toInstantOrNull(),
        state = state,
    )
}

private fun String.toInstantOrNull(): Instant? =
    takeIf { it.isNotBlank() }?.let { runCatching { Instant.parse(it) }.getOrNull() }

private fun String.toLocalDateTimeOrNull(): LocalDateTime? =
    takeIf { it.isNotBlank() }?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }

private fun String.toLocalDateOrNull(): LocalDate? =
    takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
